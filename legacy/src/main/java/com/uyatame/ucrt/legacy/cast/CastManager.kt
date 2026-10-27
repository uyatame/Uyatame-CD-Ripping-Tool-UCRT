package com.uyatame.ucrt.legacy.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Chromecast(Google Cast 対応の機器)への再生(試験的)。
 *
 * 同じネットワークの機器を探し(mDNS)、標準の受信アプリ(Default Media Receiver)を起動して、
 * 端末の中の HTTP サーバー(CastServer)から曲を読ませる。再生の操作(再生・一時停止・移動・音量)はこの接続で送る。
 * 音は Chromecast の側でデコードされるので、このアプリの USB DAC 直接出力・イコライザー・DAR は通らない。
 * 本家(app)の player/cast/CastManager.kt と同じ。機器を探すのは Android 4.1 から(Api16CastNsd)
 */
object CastManager {
    class Device(val id: String, val name: String, val model: String, val host: String, val port: Int)

    /** 見つかった機器 */
    @Volatile var devices: List<Device> = emptyList()
        private set(v) { field = v; changed() }
    /** つないでいる機器(つないでいなければ null) */
    @Volatile var connected: Device? = null
        private set(v) { field = v; changed() }
    /** つないでいる途中の機器 */
    @Volatile var connecting: Device? = null
        private set(v) { field = v; changed() }
    /** Chromecast の音量(0〜1) */
    @Volatile var volume = 1f
        private set

    private fun changed() = main.post { com.uyatame.ucrt.legacy.Bus.emit(com.uyatame.ucrt.legacy.Bus.PLAYER) }

    /** 曲が終わったとき(メインスレッドで呼ぶ) */
    @Volatile var onFinished: (() -> Unit)? = null
    /** つないだ・切れたとき(メインスレッドで呼ぶ) */
    @Volatile var onConnectionChanged: ((Boolean) -> Unit)? = null
    /** 曲を送れなかったとき(理由。メインスレッドで呼ぶ) */
    @Volatile var onError: ((String) -> Unit)? = null

    val active: Boolean get() = connected != null

    private const val APP_ID = "CC1AD845" // Default Media Receiver
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val reqId = AtomicInteger(1)

    private var channel: CastChannel? = null
    @Volatile private var transportId: String? = null
    @Volatile private var mediaSessionId = -1
    /** 曲を読み込ませた番号(古い曲の「終わった」を無視するため) */
    @Volatile private var loadedFor: Long = -1
    @Volatile private var playing = false
    @Volatile private var posMs = 0L
    @Volatile private var posAt = 0L
    @Volatile private var finishedSent = false

    // ---------------- 機器を探す ----------------

    /** 同じネットワークの機器とのやり取りの許可(Android 17 から。それより前は INTERNET で足りる) */
    const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    fun hasLocalNetwork(ctx: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 37 || ctx.checkCallingOrSelfPermission(LOCAL_NETWORK) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 機器を探せるか(Android 4.1 から) */
    val canDiscover: Boolean get() = android.os.Build.VERSION.SDK_INT >= 16

    fun startDiscovery(ctx: Context) {
        if (!canDiscover) return
        Api16CastNsd.start(ctx) { d, lost -> main.post { devices = if (lost) devices.filter { it.id != d.id && it.name != d.name } else devices.filter { it.id != d.id } + d } }
    }

    fun stopDiscovery() {
        if (canDiscover) Api16CastNsd.stop()
    }

    // ---------------- つなぐ・切る ----------------

    fun connect(d: Device) {
        if (connected?.id == d.id || connecting != null) return
        disconnect()
        connecting = d
        io.execute {
            try {
                val ch = CastChannel(d.host, d.port, ::onMessage) { main.post { lost() } }
                channel = ch
                ch.send(CastChannel.NS_CONNECTION, CastChannel.RECEIVER, JSONObject().put("type", "CONNECT"))
                ch.send(CastChannel.NS_RECEIVER, CastChannel.RECEIVER, JSONObject().put("type", "LAUNCH").put("appId", APP_ID).put("requestId", reqId.getAndIncrement()))
                startHeartbeat()
                // 受信アプリが起動して RECEIVER_STATUS が届いたら、connected になる(onMessage)
                main.postDelayed({
                    if (connecting === d && connected == null) {
                        com.uyatame.ucrt.legacy.AppLog.log("cast: launch timed out")
                        disconnect()
                        onError?.invoke("timeout")
                    }
                }, 15_000)
            } catch (e: Exception) {
                com.uyatame.ucrt.legacy.AppLog.log("cast: connect failed: ${e.javaClass.simpleName}: ${e.message}")
                main.post { connecting = null; onError?.invoke("connect") }
            }
        }
    }

    /** 接続を切る(受信アプリも止める) */
    fun disconnect() {
        val ch = channel
        val t = transportId
        channel = null
        transportId = null
        mediaSessionId = -1
        loadedFor = -1
        playing = false
        val was = connected != null
        connected = null
        connecting = null
        CastServer.clear()
        if (ch != null) io.execute {
            runCatching {
                if (t != null) ch.send(CastChannel.NS_RECEIVER, CastChannel.RECEIVER, JSONObject().put("type", "STOP").put("requestId", reqId.getAndIncrement()))
                ch.send(CastChannel.NS_CONNECTION, CastChannel.RECEIVER, JSONObject().put("type", "CLOSE"))
            }
            ch.close()
        }
        if (was) onConnectionChanged?.invoke(false)
    }

    private fun lost() {
        if (channel == null && connected == null) return
        com.uyatame.ucrt.legacy.AppLog.log("cast: connection lost")
        val was = connected != null
        channel = null
        transportId = null
        connected = null
        connecting = null
        playing = false
        if (was) onConnectionChanged?.invoke(false)
    }

    private var heartbeat: Thread? = null

    private fun startHeartbeat() {
        heartbeat?.interrupt()
        heartbeat = Thread({
            try {
                var n = 0
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(1000)
                    val ch = channel ?: break
                    if (++n % 5 == 0) runCatching { ch.send(CastChannel.NS_HEARTBEAT, CastChannel.RECEIVER, JSONObject().put("type", "PING")) }
                    // 再生位置をこまめに聞く(曲の終わりにも早く気づける)
                    val t = transportId
                    if (t != null && mediaSessionId >= 0) {
                        runCatching { ch.send(CastChannel.NS_MEDIA, t, JSONObject().put("type", "GET_STATUS").put("requestId", reqId.getAndIncrement())) }
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "cast-heartbeat").apply { isDaemon = true; start() }
    }

    // ---------------- 受け取ったメッセージ ----------------

    private fun onMessage(ns: String, source: String, m: JSONObject) {
        val ch = channel ?: return
        when (ns) {
            CastChannel.NS_HEARTBEAT -> if (m.optString("type") == "PING") {
                runCatching { ch.send(CastChannel.NS_HEARTBEAT, source, JSONObject().put("type", "PONG")) }
            }
            CastChannel.NS_RECEIVER -> if (m.optString("type") == "RECEIVER_STATUS") {
                val st = m.optJSONObject("status") ?: return
                st.optJSONObject("volume")?.let { v -> val lv = v.optDouble("level", 1.0).toFloat(); main.post { volume = lv } }
                val apps = st.optJSONArray("applications") ?: JSONArray()
                val app = (0 until apps.length()).map { apps.getJSONObject(it) }.firstOrNull { it.optString("appId") == APP_ID }
                if (app == null) {
                    // ほかの送り手が別の受信アプリを起動した: こちらの接続は終わり
                    if (transportId != null) main.post { lost() }
                    return
                }
                val t = app.optString("transportId")
                if (t.isNotEmpty() && t != transportId) {
                    transportId = t
                    runCatching { ch.send(CastChannel.NS_CONNECTION, t, JSONObject().put("type", "CONNECT")) }
                    main.post {
                        val d = connecting
                        if (d != null) {
                            connected = d
                            connecting = null
                            com.uyatame.ucrt.legacy.AppLog.log("cast: connected to ${d.name} (${d.model})")
                            onConnectionChanged?.invoke(true)
                        }
                    }
                }
            }
            CastChannel.NS_MEDIA -> when (m.optString("type")) {
                "MEDIA_STATUS" -> {
                    val arr = m.optJSONArray("status") ?: return
                    if (arr.length() == 0) return
                    val s = arr.getJSONObject(0)
                    mediaSessionId = s.optInt("mediaSessionId", mediaSessionId)
                    val state = s.optString("playerState")
                    if (s.has("currentTime")) {
                        posMs = (s.optDouble("currentTime") * 1000).toLong()
                        posAt = SystemClock.elapsedRealtime()
                    }
                    playing = state == "PLAYING" || state == "BUFFERING"
                    if (state == "IDLE" && s.optString("idleReason") == "FINISHED" && !finishedSent && loadedFor >= 0) {
                        finishedSent = true
                        main.post { onFinished?.invoke() }
                    }
                }
                "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST" -> {
                    com.uyatame.ucrt.legacy.AppLog.log("cast: ${m.optString("type")} ${m.optString("reason")}")
                    main.post { onError?.invoke("load") }
                }
            }
        }
    }

    // ---------------- 再生の操作 ----------------

    private fun media(json: JSONObject) {
        val ch = channel ?: return
        val t = transportId ?: return
        io.execute { runCatching { ch.send(CastChannel.NS_MEDIA, t, json.put("requestId", reqId.getAndIncrement())) } }
    }

    /** 曲を Chromecast に読み込ませる。送れない形式なら false(理由は onError) */
    internal fun load(ctx: Context, item: CastItem, fromMs: Long, play: Boolean) {
        val d = connected ?: return
        val app = ctx.applicationContext
        loadedFor = item.id
        finishedSent = false
        playing = play
        posMs = fromMs
        posAt = SystemClock.elapsedRealtime()
        io.execute {
            val (url, art, mime) = try {
                CastServer.publish(app, item, d.host)
            } catch (e: CastServer.Unsupported) {
                main.post { onError?.invoke(e.message ?: "format") }
                return@execute
            } catch (e: Exception) {
                com.uyatame.ucrt.legacy.AppLog.log("cast: publish failed: ${e.javaClass.simpleName}: ${e.message}")
                main.post { onError?.invoke("publish") }
                return@execute
            }
            val meta = JSONObject().put("metadataType", 3).put("title", item.title).put("artist", item.artist).put("albumName", item.album)
            if (art != null) meta.put("images", JSONArray().put(JSONObject().put("url", art)))
            val media = JSONObject().put("contentId", url).put("contentType", mime).put("streamType", "BUFFERED").put("metadata", meta)
            if (item.durationMs > 0) media.put("duration", item.durationMs / 1000.0)
            com.uyatame.ucrt.legacy.AppLog.log("cast: load $mime $url")
            media(JSONObject().put("type", "LOAD").put("media", media).put("autoplay", play).put("currentTime", fromMs / 1000.0))
        }
    }

    fun play() {
        if (mediaSessionId < 0) return
        playing = true
        posAt = SystemClock.elapsedRealtime()
        media(JSONObject().put("type", "PLAY").put("mediaSessionId", mediaSessionId))
    }

    fun pause() {
        if (mediaSessionId < 0) return
        posMs = positionMs()
        playing = false
        media(JSONObject().put("type", "PAUSE").put("mediaSessionId", mediaSessionId))
    }

    fun stopMedia() {
        if (mediaSessionId < 0) return
        loadedFor = -1
        playing = false
        media(JSONObject().put("type", "STOP").put("mediaSessionId", mediaSessionId))
    }

    fun changeVolume(v: Float) {
        val ch = channel ?: return
        val lv = v.coerceIn(0f, 1f)
        volume = lv
        changed()
        io.execute {
            runCatching {
                ch.send(
                    CastChannel.NS_RECEIVER, CastChannel.RECEIVER,
                    JSONObject().put("type", "SET_VOLUME").put("volume", JSONObject().put("level", lv.toDouble())).put("requestId", reqId.getAndIncrement()),
                )
            }
        }
    }

    /** この曲(PlayItem.id)を読み込ませているか */
    fun isLoaded(id: Long): Boolean = loadedFor == id && mediaSessionId >= 0

    /** 今の再生位置(最後に聞いた位置から、再生中なら時間の分だけ進める) */
    fun positionMs(): Long = if (playing) posMs + (SystemClock.elapsedRealtime() - posAt) else posMs
}
