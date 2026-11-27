package com.uyatame.ucrt.airplay

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * AirPlay 送信クライアント (RAOP)。GoAirPlay の sender.go (S2〜S4 で実機検証
 * 済み) の Kotlin 移植。
 *
 * ハンドシェイク: OPTIONS → GET /info → pair-setup → pair-verify (両段、
 * 署名検証あり) → fp-setup (PlayfairSend) → SETUP 1/2 → SET_PARAMETER →
 * RECORD。音声の実ストリーミングは AirPlayStreamer が担う。
 *
 * すべてのメソッドはブロッキング IO なので、呼び出し側はワーカースレッドで
 * 使うこと。ログは log コールバックへ 1 行ずつ出す (アプリ側の表示用)。
 */
internal class AirPlaySender(
    private val host: String,
    private val port: Int,
    private val mode: Int = 0,
    private val log: (String) -> Unit = {},
) {
    private val random = SecureRandom()
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private var cseq = 0

    val rtspUrl: String = "rtsp://$host:$port/stream"

    var serverName: String = ""
        private set
    private var serverEdPub: ByteArray? = null
    var serverTimingPort: Int = 0
        private set
    var dataPort: Int = 0
        private set
    var controlPort: Int = 0
        private set

    private lateinit var identity: AirPlayIdentity
    private lateinit var ecdhSecret: ByteArray
    private lateinit var keymsg: ByteArray

    /** ストリーム暗号鍵 (SETUP 完了後に有効)。 */
    lateinit var aesKey: ByteArray
        private set
    lateinit var aesIV: ByteArray
        private set

    /** 自分のタイミング用 UDP ソケット (SETUP 1 で束縛し、相手へ通知する)。 */
    var timingSocket: DatagramSocket? = null
        private set

    private class Response(val statusLine: String, val headers: Map<String, String>, val body: ByteArray) {
        val isOk: Boolean get() = statusLine.contains("200")
    }

    private fun step(msg: String) {
        log("[airplay-send] $msg")
    }

    // ---------- RTSP トランスポート ----------

    private fun readLine(): String {
        val inp = input ?: throw IOException("not connected")
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) throw IOException("connection closed by receiver")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private val requestLock = Any()

    private fun request(method: String, uri: String, contentType: String?, body: ByteArray?): Response =
        synchronized(requestLock) { requestLocked(method, uri, contentType, body) }

    private fun requestLocked(method: String, uri: String, contentType: String?, body: ByteArray?): Response {
        val sock = socket ?: throw IOException("not connected")
        val out = output ?: throw IOException("not connected")
        sock.soTimeout = 5000
        cseq++
        val head = StringBuilder()
        head.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n")
        head.append("CSeq: ").append(cseq).append("\r\n")
        head.append("User-Agent: GoAirPlay-Sender/0.1\r\n")
        if (contentType != null) head.append("Content-Type: ").append(contentType).append("\r\n")
        if (body != null) head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        if (body != null) out.write(body)
        out.flush()

        val statusLine = readLine()
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        var respBody = ByteArray(0)
        val cl = headers["Content-Length"]?.toIntOrNull() ?: 0
        if (cl > 0) {
            respBody = ByteArray(cl)
            var off = 0
            val inp = input!!
            while (off < cl) {
                val n = inp.read(respBody, off, cl - off)
                if (n < 0) throw IOException("connection closed reading body")
                off += n
            }
        }
        return Response(statusLine, headers, respBody)
    }

    private fun requireOk(step: String, resp: Response) {
        if (!resp.isOk) throw IOException("$step rejected: ${resp.statusLine}")
    }

    // ---------- ハンドシェイク ----------

    /** 接続して RECORD まで完了する。失敗時は IOException (手順名つき)。 */
    fun connect() {
        identity = AirPlayIdentity.ephemeral()
        val sock = Socket()
        sock.connect(InetSocketAddress(host, port), 10_000)
        socket = sock
        input = BufferedInputStream(sock.getInputStream())
        output = BufferedOutputStream(sock.getOutputStream())
        step("connected to $host:$port")

        // OPTIONS
        val opt = request("OPTIONS", "*", null, null)
        step("OPTIONS -> ${opt.statusLine}")

        // GET /info
        val infoResp = request("GET", "/info", null, null)
        val info = Bplist.read(infoResp.body) as? Map<*, *>
            ?: throw IOException("/info: not a bplist dict")
        serverName = info["name"] as? String ?: ""
        serverEdPub = info["pk"] as? ByteArray
        step("GET /info -> ${infoResp.statusLine}: name=\"$serverName\" pk=${serverEdPub?.size ?: 0} bytes")
        if (serverEdPub?.size != 32) throw IOException("/info pk missing")

        // pair-setup
        val ps = request("POST", "/pair-setup", "application/octet-stream", identity.edPublicBytes)
        if (ps.body.size != 32) throw IOException("pair-setup: response ${ps.body.size} bytes, want 32")
        serverEdPub = ps.body
        step("pair-setup -> ${ps.statusLine}: server Ed25519 pk received")

        // pair-verify step 1
        val x = PairCrypto.generateX25519()
        val myEcdhPub = x.pubBytes
        val step1 = ByteArray(4 + 32 + 32)
        step1[0] = 1
        myEcdhPub.copyInto(step1, 4)
        identity.edPublicBytes.copyInto(step1, 36)
        val pv1 = request("POST", "/pair-verify", "application/octet-stream", step1)
        if (pv1.body.size != 96) throw IOException("pair-verify step1: response ${pv1.body.size} bytes, want 96")
        val serverEcdhPub = pv1.body.copyOfRange(0, 32)
        val encSig = pv1.body.copyOfRange(32, 96)
        ecdhSecret = PairCrypto.x25519Secret(x.priv, serverEcdhPub)
        val sig = PairCrypto.aesCtrWithSecret(ecdhSecret, encSig)
        if (!AirPlayIdentity.verify(serverEdPub!!, serverEcdhPub + myEcdhPub, sig)) {
            throw IOException("pair-verify step1: server signature INVALID")
        }
        step("pair-verify step1 -> ${pv1.statusLine}: server signature verified")

        // pair-verify step 2 (受信側は CTR を 1 ブロック進めてから復号する)
        val mySig = identity.sign(myEcdhPub + serverEcdhPub)
        val (ctrKey, ctrIv) = PairCrypto.deriveKeyIV(ecdhSecret)
        val burned = PairCrypto.aesCtr(ctrKey, ctrIv, ByteArray(64) + mySig)
        val encMySig = burned.copyOfRange(64, 128)
        val step2 = ByteArray(4 + 64)
        encMySig.copyInto(step2, 4)
        val pv2 = request("POST", "/pair-verify", "application/octet-stream", step2)
        requireOk("pair-verify step2", pv2)
        step("pair-verify step2 -> ${pv2.statusLine}: receiver accepted our signature")

        // fp-setup phase 1
        val fp1 = ByteArray(16)
        fp1[4] = 0x03
        fp1[14] = mode.toByte()
        val f1 = request("POST", "/fp-setup", "application/octet-stream", fp1)
        requireOk("fp-setup phase1", f1)
        step("fp-setup phase1 -> ${f1.statusLine}: canned reply ${f1.body.size} bytes (mode $mode)")

        // fp-setup phase 2
        val plain = ByteArray(128).also { random.nextBytes(it) }
        keymsg = PlayfairSend.buildKeymsg(plain, mode, random)
        val f2 = request("POST", "/fp-setup", "application/octet-stream", keymsg)
        requireOk("fp-setup phase2", f2)
        val echoOk = f2.body.size == 32 &&
            f2.body.copyOfRange(12, 32).contentEquals(keymsg.copyOfRange(144, 164))
        step("fp-setup phase2 -> ${f2.statusLine}: reply ${f2.body.size} bytes, keymsg trailer echo ok: $echoOk")
        if (!echoOk) throw IOException("fp-setup phase2: keymsg trailer echo mismatch")

        // SETUP 1
        val pfKey = ByteArray(16).also { random.nextBytes(it) }
        aesKey = PairCrypto.sha512(pfKey + ecdhSecret).copyOfRange(0, 16)
        aesIV = ByteArray(16).also { random.nextBytes(it) }
        val ekey = PlayfairSend.playfairEncrypt(keymsg, pfKey, random)
        val ts = DatagramSocket(null)
        ts.bind(InetSocketAddress(0))
        timingSocket = ts
        val setup1 = mapOf<String, Any?>(
            "ekey" to ekey,
            "eiv" to aesIV,
            "timingPort" to ts.localPort.toLong(),
        )
        val s1 = request("SETUP", rtspUrl, "application/x-apple-binary-plist", Bplist.write(setup1))
        requireOk("SETUP 1", s1)
        val resp1 = Bplist.read(s1.body) as? Map<*, *>
        serverTimingPort = (resp1?.get("timingPort") as? Long)?.toInt() ?: 0
        step("SETUP 1 -> ${s1.statusLine}: server timingPort=$serverTimingPort")

        // SETUP 2
        val setup2 = mapOf<String, Any?>(
            "streams" to listOf(
                mapOf<String, Any?>(
                    "type" to 96L,
                    "ct" to 2L,
                    "spf" to 352L,
                )
            )
        )
        val s2 = request("SETUP", rtspUrl, "application/x-apple-binary-plist", Bplist.write(setup2))
        requireOk("SETUP 2", s2)
        val resp2 = Bplist.read(s2.body) as? Map<*, *>
        val streams = resp2?.get("streams") as? List<*>
        val stream0 = streams?.firstOrNull() as? Map<*, *>
        dataPort = (stream0?.get("dataPort") as? Long)?.toInt() ?: 0
        controlPort = (stream0?.get("controlPort") as? Long)?.toInt() ?: 0
        step("SETUP 2 -> ${s2.statusLine}: dataPort=$dataPort controlPort=$controlPort")
        if (dataPort == 0) throw IOException("SETUP 2: no dataPort in response")

        // 音量 + RECORD
        setVolume(-9.0)
        val rec = request("RECORD", rtspUrl, null, null)
        requireOk("RECORD", rec)
        step("RECORD -> ${rec.statusLine}: session established with \"$serverName\"")
    }

    // ---------- セッションパラメータ ----------

    fun setVolume(db: Double) {
        val resp = request("SET_PARAMETER", rtspUrl, "text/parameters", "volume: $db\r\n".toByteArray(Charsets.US_ASCII))
        step("SET_PARAMETER volume $db dB -> ${resp.statusLine}")
    }

    fun sendProgress(start: Long, current: Long, end: Long) {
        val resp = request(
            "SET_PARAMETER", rtspUrl, "text/parameters",
            "progress: $start/$current/$end\r\n".toByteArray(Charsets.US_ASCII)
        )
        step("SET_PARAMETER progress -> ${resp.statusLine}")
    }

    fun sendMetadata(title: String, artist: String, album: String, genre: String) {
        val resp = request("SET_PARAMETER", rtspUrl, "application/x-dmap-tagged", dmapBody(title, artist, album, genre))
        step("SET_PARAMETER metadata (title=\"$title\") -> ${resp.statusLine}")
    }

    fun sendArtwork(bytes: ByteArray, mime: String) {
        val resp = request("SET_PARAMETER", rtspUrl, mime, bytes)
        step("SET_PARAMETER artwork ($mime, ${bytes.size} bytes) -> ${resp.statusLine}")
    }

    /** キープアライブ (Go 版 S4 の教訓: 制御接続を眠らせると受信側が切る)。 */
    /** 一時停止 (受信側の再生を止める)。 */
    fun pause() {
        request("PAUSE", rtspUrl, null, null)
    }

    /** 再開。 */
    fun resume() {
        request("RECORD", rtspUrl, null, null)
    }

    /** 曲の切り替え前に、受信側のバッファを捨てさせる。 */
    fun flush() {
        request("FLUSH", rtspUrl, null, null)
    }

    fun keepalive() {
        try {
            request("OPTIONS", "*", null, null)
        } catch (e: Exception) {
            step("keepalive failed: ${e.message}")
        }
    }

    fun teardown() {
        try {
            request("TEARDOWN", rtspUrl, null, null)
            step("TEARDOWN: done")
        } catch (e: Exception) {
            step("TEARDOWN failed: ${e.message}")
        }
        close()
    }

    fun close() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // 閉鎖時の例外は無視する。
        }
        socket = null
        timingSocket?.close()
        timingSocket = null
    }

    companion object {
        /** DMAP タグ付きメタデータ本体 (sender_media.go の dmapBody の移植)。 */
        fun dmapBody(title: String, artist: String, album: String, genre: String): ByteArray {
            val out = ArrayList<Byte>()
            fun field(tag: String, value: String) {
                if (value.isEmpty()) return
                val v = value.toByteArray(Charsets.UTF_8)
                for (c in tag) out.add(c.code.toByte())
                out.add((v.size ushr 24).toByte())
                out.add((v.size ushr 16).toByte())
                out.add((v.size ushr 8).toByte())
                out.add(v.size.toByte())
                for (b in v) out.add(b)
            }
            field("minm", title)
            field("asar", artist)
            field("asal", album)
            field("asgn", genre)
            return ByteArray(out.size) { out[it] }
        }
    }
}
