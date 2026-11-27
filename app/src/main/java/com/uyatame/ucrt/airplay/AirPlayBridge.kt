package com.uyatame.ucrt.airplay

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uyatame.ucrt.player.PlayItem
import com.uyatame.ucrt.player.PlayerController
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * アプリ側と core の AirPlay 受信機をつなぐ配線。
 * UcrtApp.onCreate から受信機 (RTSP + mDNS) を起動し、MainViewModel が
 * PlayerController を教えてくれたら、受信した音声を通常の再生として流す。
 * 音量 (SET_PARAMETER volume:) はデコーダーのゲインへ、DACP メタデータは
 * 再生中の曲の表示へ反映する。
 */
object AirPlayBridge {

    private const val TAG = "UCRT-AirPlay"

    /** 受信機名の初期値 (設定で変更できる)。 */
    const val DEFAULT_NAME = "UCRT"

    /** 設定で決まった受信機名。 */
    @Volatile
    private var desiredName = DEFAULT_NAME

    /** 受信の状態 (詳細画面の表示用)。 */
    enum class Status { OFF, WAITING, CONNECTED, PLAYING }

    var status by mutableStateOf(Status.OFF)
        private set

    /** 接続元の表示 (IP アドレス)。 */
    var clientLabel by mutableStateOf("")
        private set

    /** 受信中の曲の表示 (詳細画面用)。 */
    var nowTitle by mutableStateOf("")
        private set

    var nowArtist by mutableStateOf("")
        private set

    /** AirPlay の PlayItem を見分けるための URI。 */
    private val AIRPLAY_URI: Uri = Uri.parse("ucrt-airplay://live")

    private val mainHandler = Handler(Looper.getMainLooper())

    private var receiver: AirPlayReceiver? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var appContext: Context? = null

    // 最後に受けたカバー画像 (ArtLoader のキーと、保存先ファイルの URI)。
    @Volatile
    private var artKey: String? = null

    @Volatile
    private var artUri: String? = null

    @Volatile
    private var player: PlayerController? = null

    @Volatile
    private var activeDecoder: AirPlayPcmDecoder? = null

    @Volatile
    private var playbackActive = false

    /** 最後に受けた音量の倍率 (デコーダーがまだ無いときのために覚えておく)。 */
    @Volatile
    private var lastGain = 1.0

    // 最後に受けたメタデータ (再生開始前でも届くことがある)。
    @Volatile
    private var metaTitle: String? = null

    @Volatile
    private var metaArtist: String? = null

    @Volatile
    private var metaAlbum: String? = null

    /** MainViewModel が PlayerController を作ったら呼ぶ。 */
    fun attachPlayer(p: PlayerController) {
        player = p
    }

    /**
     * PlayerController の AirPlaySource が再生を始めるときに呼ぶ。
     * 受信中の PCM キューにつながる新しいデコーダーを返す。
     */
    @Synchronized
    internal fun newPcmDecoder(): AirPlayPcmDecoder? {
        val r = receiver ?: return null
        val dec = AirPlayPcmDecoder(r.stream.pcmFrames)
        dec.gain = lastGain
        activeDecoder = dec
        return dec
    }

    @Synchronized
    fun start(context: Context) {
        if (receiver != null) return
        try {
            val app = context.applicationContext
            appContext = app
            val identity = AirPlayIdentity.loadOrCreate(File(app.filesDir, "airplay_key.bin"))
            // Android は本当の MAC を隠すので、GoAirPlay (Termux) と同じく
            // identity から安定したデバイス ID を導く。
            val deviceId = AirPlayReceiver.deviceIdFor(identity, null)
            val listener = object : AirPlayListener {
                override fun onVolumeDb(db: Double) {
                    val g = if (db <= -144.0) 0.0 else Math.pow(10.0, db / 20.0)
                    lastGain = g
                    activeDecoder?.gain = g
                    Log.d(TAG, "volume $db dB -> gain $g")
                }

                override fun onTeardown(session: AirPlaySession) {
                    Log.d(TAG, "TEARDOWN from ${session.remoteAddress}")
                    if (receiver != null) status = Status.WAITING
                    clientLabel = ""
                    nowTitle = ""
                    nowArtist = ""
                    mainHandler.post { stopPlayback() }
                }

                override fun onStreamKeyReady(session: AirPlaySession) {
                    Log.d(TAG, "stream key ready")
                }

                override fun onAudioStreamConfigured(session: AirPlaySession) {
                    Log.d(TAG, "audio stream ct=${session.streamCt} spf=${session.streamSpf}")
                    status = Status.PLAYING
                    mainHandler.post { startPlayback() }
                }

                override fun onMetadataPlist(session: AirPlaySession, body: ByteArray) {
                    val tags = DmapParser.parse(body)
                    val t = DmapParser.title(tags)
                    if (t != null) {
                        metaTitle = t
                        metaArtist = DmapParser.artist(tags) ?: metaArtist
                        metaAlbum = DmapParser.album(tags) ?: metaAlbum
                        nowTitle = t
                        nowArtist = metaArtist ?: ""
                    } else {
                        Log.d(TAG, "metadata without title: ${body.size}B tags=${tags.keys}")
                    }
                    Log.d(TAG, "metadata: $metaTitle / $metaArtist / $metaAlbum")
                    mainHandler.post { applyMetadata() }
                }

                override fun onArtwork(session: AirPlaySession, contentType: String, body: ByteArray) {
                    val app = appContext ?: return
                    if (body.isEmpty()) return
                    try {
                        val key = "airplay-" + java.security.MessageDigest.getInstance("SHA-1")
                            .digest(body).joinToString("") { "%02x".format(it) }.substring(0, 16)
                        val dir = File(app.cacheDir, "airplay").also { it.mkdirs() }
                        val f = File(dir, "$key.jpg")
                        if (!f.exists()) f.writeBytes(body)
                        artKey = key
                        artUri = Uri.fromFile(f).toString()
                        Log.d(TAG, "artwork: $contentType ${body.size}B key=$key")
                        mainHandler.post {
                            val p = player
                            if (p != null && isAirPlayCurrent(p)) p.updateCurrentArt(key, artUri!!)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "artwork save failed: ${e.message}")
                    }
                }

                override fun onSetParameterOther(session: AirPlaySession, contentType: String?, body: ByteArray) {
                    // progress (text/parameters) は毎秒届くので記録しない
                    if (body.isEmpty() || contentType == "text/parameters") return
                    Log.d(TAG, "other SET_PARAMETER: ct=$contentType size=${body.size}")
                }

                override fun onClientVerified(session: AirPlaySession) {
                    Log.i(TAG, "client verified: ${session.remoteAddress}")
                    status = Status.CONNECTED
                    clientLabel = session.remoteAddress.substringAfterLast('/').substringBefore(':')
                }

                override fun onPauseRequest(session: AirPlaySession) {
                    mainHandler.post {
                        val p = player
                        if (p != null && isAirPlayCurrent(p)) p.pause()
                    }
                }

                override fun onRecordRequest(session: AirPlaySession) {
                    mainHandler.post {
                        val p = player
                        if (p != null && isAirPlayCurrent(p)) p.resume()
                    }
                }

                override fun onFlushRequest(session: AirPlaySession) {
                    Log.d(TAG, "FLUSH (queue cleared by receiver)")
                }
            }
            val r = AirPlayReceiver(identity, desiredName, deviceId, listener) { Log.d(TAG, it) }

            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("ucrt-airplay").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }

            r.start()
            val ip = pickLanIp()
            if (ip != null) {
                r.startAdvertising(ip)
            } else {
                Log.w(TAG, "no LAN IPv4 found; RTSP only, not discoverable")
            }
            receiver = r
            status = Status.WAITING
            Log.i(TAG, "AirPlay receiver started (name=$desiredName, deviceId=$deviceId, ip=${ip?.hostAddress})")
        } catch (e: Exception) {
            status = Status.OFF
            Log.w(TAG, "AirPlay receiver failed to start: ${e.message}")
        }
    }

    /** 受信機を止める (再生中なら再生も止める)。 */
    @Synchronized
    fun stop() {
        val r = receiver
        receiver = null
        if (r != null) {
            try {
                r.stop()
            } catch (e: Exception) {
                Log.w(TAG, "receiver stop failed: ${e.message}")
            }
        }
        multicastLock?.let { lock ->
            try {
                if (lock.isHeld) lock.release()
            } catch (e: Exception) {
                Log.w(TAG, "multicast lock release failed: ${e.message}")
            }
        }
        multicastLock = null
        status = Status.OFF
        clientLabel = ""
        nowTitle = ""
        nowArtist = ""
        mainHandler.post { stopPlayback() }
        Log.i(TAG, "AirPlay receiver stopped")
    }

    /**
     * 設定 (使うか・受信機名) を受信機へ反映する。設定が流れてくるたびに呼ばれるので、
     * 変わったときだけ起動・停止・再起動する。
     */
    @Synchronized
    fun applySettings(context: Context, enabled: Boolean, name: String) {
        desiredName = name.trim().ifEmpty { DEFAULT_NAME }
        if (!enabled) {
            if (receiver != null) stop()
            return
        }
        val r = receiver
        if (r == null) {
            start(context)
        } else if (r.receiverName != desiredName) {
            stop()
            start(context)
        }
    }

    private fun isAirPlayCurrent(p: PlayerController): Boolean =
        p.current?.uri?.scheme == "ucrt-airplay"

    /** SETUP が済んだら、AirPlay を 1 曲の再生として始める (メインスレッドで呼ぶ)。 */
    private fun startPlayback() {
        val p = player ?: run {
            Log.w(TAG, "player not attached yet; cannot start playback")
            return
        }
        if (playbackActive && isAirPlayCurrent(p)) return
        playbackActive = true
        val item = PlayItem(
            title = metaTitle ?: "AirPlay",
            artist = metaArtist ?: "",
            album = metaAlbum ?: "",
            durationMs = 0,
            uri = AIRPLAY_URI,
            artKey = artKey,
            artCoverUri = artUri,
        )
        p.playFiles(listOf(item), 0)
        Log.i(TAG, "AirPlay playback started")
    }

    /** TEARDOWN が来たら再生を止める (メインスレッドで呼ぶ)。 */
    private fun stopPlayback() {
        playbackActive = false
        val p = player
        if (p != null && isAirPlayCurrent(p)) {
            p.stop()
        }
        activeDecoder?.close()
        activeDecoder = null
        metaTitle = null
        metaArtist = null
        metaAlbum = null
        artKey = null
        artUri = null
    }

    /** メタデータを再生中の曲の表示へ反映する (メインスレッドで呼ぶ)。 */
    private fun applyMetadata() {
        val p = player ?: return
        if (!isAirPlayCurrent(p)) return
        p.updateCurrentMetadata(metaTitle ?: "AirPlay", metaArtist ?: "", metaAlbum ?: "")
    }

    /** GoAirPlay の pickLANInterface と同じ優先度で LAN の IPv4 を選ぶ。 */
    private fun pickLanIp(): InetAddress? {
        var best: InetAddress? = null
        var bestScore = -1
        var anyV4: InetAddress? = null
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nif in ifaces) {
            if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
            for (addr in nif.inetAddresses) {
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                if (anyV4 == null) anyV4 = addr
                val b = addr.address
                val score = when {
                    (b[0].toInt() and 0xFF) == 192 && (b[1].toInt() and 0xFF) == 168 -> 3
                    (b[0].toInt() and 0xFF) == 10 -> 2
                    (b[0].toInt() and 0xFF) == 172 && (b[1].toInt() and 0xFF) in 16..31 -> 1
                    else -> -1
                }
                if (score > bestScore) {
                    bestScore = score
                    best = addr
                }
            }
        }
        return best ?: anyV4
    }
}
