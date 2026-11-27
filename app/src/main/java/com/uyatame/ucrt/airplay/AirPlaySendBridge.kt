package com.uyatame.ucrt.airplay

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uyatame.ucrt.player.Decoders
import com.uyatame.ucrt.player.PcmDecoder
import com.uyatame.ucrt.player.PlayItem
import com.uyatame.ucrt.player.PlayerController
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * アプリ側と core の AirPlay 送信 (AirPlaySender / AirPlayStreamer) の配線。
 * キャストシートから _raop._tcp を NSD で探し、選ばれた受信機へ再生中の曲を
 * デコードして送る。発見の作法は CastManager (Chromecast) と揃えてある。
 */
object AirPlaySendBridge {

    private const val TAG = "UCRT-AirPlaySend"
    private const val SERVICE_TYPE = "_raop._tcp"

    class Device(val id: String, val name: String, val model: String, val host: String, val port: Int)

    var devices by mutableStateOf<List<Device>>(emptyList())
        private set

    var connected by mutableStateOf<Device?>(null)
        private set

    var connecting by mutableStateOf<Device?>(null)
        private set

    /** 送信音量 (0..1)。dB へは -30..0 に線形変換する (0.7 で -9 dB)。 */
    var volume by mutableStateOf(0.7f)
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    val active: Boolean get() = connected != null

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "airplay-send").apply { isDaemon = true }
    }

    @Volatile
    private var player: PlayerController? = null

    @Volatile
    private var sender: AirPlaySender? = null

    @Volatile
    private var streamer: AirPlayStreamer? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /** 送信中は Wi-Fi を眠らせない (省電力で UDP が止まるのを防ぐ)。 */
    private fun acquireWifiLock(ctx: Context) {
        runCatching {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val lock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ucrt-airplay-send")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    fun attachPlayer(p: PlayerController) {
        player = p
    }

    // ---------- 発見 (NSD) ----------

    private var nsd: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    /** この端末自身の IPv4 アドレス (自分の受信機を候補から外すため)。 */
    @Volatile
    private var ownAddrs: Set<String> = emptySet()

    private fun computeOwnAddrs(): Set<String> {
        val out = HashSet<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val addrs = ifaces.nextElement().inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address) out.add(addr.hostAddress)
                }
            }
        } catch (e: Exception) {
            // 取れなければフィルタなしで続ける。
        }
        return out
    }

    fun startDiscovery(ctx: Context) {
        if (discoveryListener != null) return
        ownAddrs = computeOwnAddrs()
        val manager = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = manager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "discovery failed ($errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { resolveQueue.addLast(info); resolveNext() }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { devices = devices.filter { it.id != info.serviceName } }
            }
        }
        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices failed: ${e.message}")
            discoveryListener = null
        }
    }

    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            nsd?.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            // 停止時の例外は無視する。
        }
        resolveQueue.clear()
        resolving = false
    }

    private fun resolveNext() {
        val manager = nsd ?: return
        if (resolving) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        try {
            @Suppress("DEPRECATION")
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo?, errorCode: Int) {
                    main.post { resolving = false; resolveNext() }
                }

                override fun onServiceResolved(si: NsdServiceInfo) {
                    val host = si.host?.hostAddress
                    // 自分自身の受信機は送信先にしない (ループになるため)。
                    if (host != null && host in ownAddrs) {
                        main.post { resolving = false; resolveNext() }
                        return
                    }
                    if (host != null) {
                        val fullName = si.serviceName ?: host
                        val display = fullName.substringAfter('@', fullName)
                        val model = si.attributes["am"]?.let { String(it, Charsets.UTF_8) } ?: ""
                        val d = Device(fullName, display, model, host, si.port)
                        main.post {
                            devices = (devices.filter { it.id != d.id } + d).sortedBy { it.name }
                        }
                    }
                    main.post { resolving = false; resolveNext() }
                }
            })
        } catch (e: Exception) {
            resolving = false
            resolveNext()
        }
    }

    // ---------- 送信セッション ----------

    @Volatile
    private var sessionOn = false
    @Volatile
    private var streamThread: Thread? = null
    private val loadGen = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    private var loadedId: Long = Long.MIN_VALUE
    @Volatile
    private var loadedPcm: ShortArray? = null
    @Volatile
    private var loadedFromMs: Long = 0L
    @Volatile
    private var appCtx: Context? = null

    fun connect(ctx: Context, device: Device) {
        if (connecting != null || connected != null) return
        connecting = device
        lastError = null
        val app = ctx.applicationContext
        appCtx = app
        executor.execute {
            var snd: AirPlaySender? = null
            val step = "接続 (${device.host}:${device.port})"
            try {
                snd = AirPlaySender(device.host, device.port) { Log.d(TAG, it) }
                snd.connect()
                val strm = AirPlayStreamer(snd, device.host) { Log.d(TAG, it) }
                sender = snd
                streamer = strm
                sessionOn = true
                acquireWifiLock(app)
                snd.setVolume(volume * 30.0 - 30.0)
                main.post {
                    connected = device
                    connecting = null
                    // プレイヤーの出力先を AirPlay に切り替え、今の曲を同じ位置から送らせる。
                    player?.onAirPlayConnectionChanged(true)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "connect failed at step $step", e)
                try {
                    snd?.teardown()
                } catch (ignored: Exception) {
                    // 後片付けの失敗は無視する。
                }
                sender = null
                streamer = null
                sessionOn = false
                main.post {
                    connecting = null
                    lastError = "$step: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
    }

    /**
     * PlayerController の startCurrent から呼ばれる: この曲を fromMs から送る。
     * 実際のデコードと送信は executor / 送信スレッドで行う (ここは予約だけ)。
     */
    fun load(item: PlayItem, fromMs: Long, play: Boolean) {
        val snd = sender ?: return
        val strm = streamer ?: return
        val app = appCtx ?: return
        val gen = loadGen.incrementAndGet()
        executor.execute {
            var step = "デコード"
            try {
                if (!sessionOn) return@execute
                val uri = item.uri ?: throw IllegalStateException("この曲はAirPlay送信できません")
                var pcm = loadedPcm
                if (loadedId != item.id || pcm == null) {
                    // 先にデコードする (その間、前の曲は流れ続ける)。
                    // 止めてからデコードすると無音の待ち時間が丸ごと出る。
                    step = "デコード (${uri.scheme})"
                    pcm = decodeToPcm44k(app, uri, uri.lastPathSegment ?: "")
                    loadedPcm = pcm
                    loadedId = item.id
                }
                if (gen != loadGen.get() || !sessionOn) return@execute
                stopStreamRun()
                step = "送信"
                runCatching { snd.flush() }
                val total = (pcm.size / 2).toLong()
                val fromFrames = fromMs * 44100L / 1000L
                val base = strm.currentTimestamp()
                snd.sendProgress(base, base + fromFrames, base + total)
                snd.sendMetadata(item.title, item.artist, item.album, "")
                artworkOf(app, item)?.let { (bytes, mime) -> snd.sendArtwork(bytes, mime) }
                snd.setVolume(volume * 30.0 - 30.0)
                runCatching { AirPlaySendService.start(app, item.title, connected?.name ?: "") }
                loadedFromMs = fromMs
                strm.paused = !play
                val th = Thread {
                    try {
                        strm.stream(pcm, fromFrames.toInt())
                        if (strm.finishedNaturally && gen == loadGen.get() && sessionOn) {
                            // 送り切ったらプレイヤーに次の曲へ進んでもらう。
                            main.post { player?.next(auto = true) }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "stream failed", e)
                        main.post { lastError = "送信: ${e.javaClass.simpleName}: ${e.message}" }
                    }
                }
                th.isDaemon = true
                th.name = "airplay-send-stream"
                streamThread = th
                th.start()
            } catch (e: Throwable) {
                Log.w(TAG, "load failed at step $step", e)
                main.post { lastError = "$step: ${e.javaClass.simpleName}: ${e.message}" }
            }
        }
    }

    private fun stopStreamRun() {
        streamer?.stop()
        val th = streamThread
        streamThread = null
        if (th != null && th !== Thread.currentThread()) {
            try {
                th.join(2000)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** PlayerController の pause から: 送出を止める。 */
    fun pause() {
        streamer?.paused = true
        val snd = sender ?: return
        executor.execute { runCatching { snd.pause() } }
    }

    /** PlayerController の resume から: 送出を再開する。 */
    fun play() {
        streamer?.paused = false
        val snd = sender ?: return
        executor.execute { runCatching { snd.resume() } }
    }

    /** PlayerController の stop から: 今の曲だけ止めてセッションは残す。 */
    fun stopMedia() {
        loadGen.incrementAndGet()
        executor.execute { stopStreamRun() }
    }

    fun isLoaded(id: Long): Boolean = sessionOn && loadedId == id

    /** プレイヤーの位置表示用: 今の曲の送出位置。 */
    fun positionMs(): Long {
        val strm = streamer ?: return loadedFromMs
        return loadedFromMs + strm.sentFrames * 1000L / 44100L
    }

    /** 送信セッションを終える (シートの「この端末」などから)。 */
    fun disconnect() {
        if (!sessionOn && connected == null) return
        sessionOn = false
        loadGen.incrementAndGet()
        val snd = sender
        val app = appCtx
        executor.execute {
            stopStreamRun()
            try {
                snd?.teardown()
            } catch (ignored: Exception) {
                // 後片付けの失敗は無視する。
            }
            sender = null
            streamer = null
            loadedPcm = null
            loadedId = Long.MIN_VALUE
            if (app != null) runCatching { AirPlaySendService.stop(app) }
            releaseWifiLock()
            main.post {
                connected = null
                connecting = null
                // 出力先を端末に戻す (同じ位置で一時停止した状態)。
                player?.onAirPlayConnectionChanged(false)
            }
        }
    }

    fun changeVolume(v: Float) {
        volume = v
        val snd = sender ?: return
        executor.execute {
            try {
                snd.setVolume(v * 30.0 - 30.0)
            } catch (e: Exception) {
                Log.w(TAG, "setVolume failed: ${e.message}")
            }
        }
    }

    private fun artworkOf(ctx: Context, item: com.uyatame.ucrt.player.PlayItem): Pair<ByteArray, String>? {
        // 表示と同じ経路 (埋め込みアート・フォルダカバー・明示カバー) で取る
        return try {
            val bytes = com.uyatame.ucrt.library.ArtSource.bytes(ctx, item.artTrackUri, item.artCoverUri) ?: return null
            if (bytes.size < 4) return null
            val mime = if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) "image/png" else "image/jpeg"
            bytes to mime
        } catch (e: Exception) {
            null
        }
    }

    // ---------- デコード (44.1kHz / 16bit / ステレオへ) ----------

    /**
     * 曲ファイルを UCRT 内蔵デコーダー (Decoders) でデコードし、
     * 44.1kHz ステレオ PCM にする。端末の OS デコーダーは ALAC 等で
     * 動かないことがあるため使わない (再生と同じ経路)。
     */
    private fun decodeToPcm44k(ctx: Context, uri: Uri, name: String): ShortArray {
        val resampler = StreamingResampler()
        Decoders.open(ctx, uri, name).use { opened ->
            val dec = opened.decoder
            resampler.configure(dec.sampleRate, dec.channels)
            while (dec.decodeNext()) {
                resampler.feedBlock(dec)
            }
        }
        return resampler.finish()
    }

    /**
     * デコード済み PCM を 44.1kHz ステレオへ逐次変換する線形リサンプラ。
     * チャンク境界をまたぐ補間のため、消費済みサンプルは少し残して捨てる。
     */
    private class StreamingResampler {
        private var srcRate = 44100
        private var channels = 2
        private var pending = ShortArray(0)
        private var pendingFrames = 0
        private var pos = 0.0 // pending 内の読み位置 (フレーム単位)
        // 出力はボクシングなしの素の配列で貯める (ArrayList<Short> は
        // 1 サンプルごとにオブジェクト化して数百 MB 食い、OOM で落ちる)。
        private var outBuf = ShortArray(1 shl 20)
        private var outSize = 0

        private fun addOut(v: Short) {
            if (outSize == outBuf.size) outBuf = outBuf.copyOf(outBuf.size * 2)
            outBuf[outSize++] = v
        }

        fun configure(sampleRate: Int, ch: Int) {
            srcRate = sampleRate
            channels = ch.coerceIn(1, 8)
        }

        /** デコーダーの 1 ブロック (チャンネル別 Int サンプル) を取り込む。 */
        fun feedBlock(dec: PcmDecoder) {
            val frames = dec.blockSize
            if (frames == 0) return
            val shift = dec.bitsPerSample - 16
            val shorts = ShortArray(frames * channels)
            for (i in 0 until frames) {
                for (c in 0 until channels) {
                    var v = dec.samples[c][i]
                    v = if (shift > 0) v shr shift else if (shift < 0) v shl -shift else v
                    shorts[i * channels + c] = v.coerceIn(-32768, 32767).toShort()
                }
            }
            val merged = ShortArray(pendingFrames * channels + shorts.size)
            pending.copyInto(merged, 0, 0, pendingFrames * channels)
            shorts.copyInto(merged, pendingFrames * channels)
            pending = merged
            pendingFrames += frames
            emit(final = false)
        }

        private fun frameAt(frame: Int, ch: Int): Double {
            val c = if (channels == 1) 0 else minOf(ch, channels - 1)
            return pending[frame * channels + c].toDouble()
        }

        private fun emit(final: Boolean) {
            val step = srcRate.toDouble() / 44100.0
            while (true) {
                val i0 = pos.toInt()
                val lastNeeded = if (final) i0 else i0 + 1
                if (lastNeeded > pendingFrames - 1) break
                val frac = pos - i0
                val i1 = minOf(i0 + 1, pendingFrames - 1)
                for (ch in 0 until 2) {
                    val s = frameAt(i0, ch) * (1 - frac) + frameAt(i1, ch) * frac
                    addOut(s.toInt().coerceIn(-32768, 32767).toShort())
                }
                pos += step
            }
            // 消費済みの先頭を捨てる (pos はずらして追随)。
            // pos は補間の進みでバッファ末尾を超えることがある (ハイレゾの
            // step > 1 のとき) ので、捨てる量はバッファ内までに丸める。
            val consumed = (pos.toInt() - 1).coerceIn(0, pendingFrames)
            if (consumed > 8192) {
                val keepFrom = consumed * channels
                val keep = pendingFrames * channels - keepFrom
                val next = ShortArray(keep)
                pending.copyInto(next, 0, keepFrom, keepFrom + keep)
                pending = next
                pendingFrames -= consumed
                pos -= consumed
            }
        }

        fun finish(): ShortArray {
            emit(final = true)
            return outBuf.copyOf(outSize)
        }
    }
}
