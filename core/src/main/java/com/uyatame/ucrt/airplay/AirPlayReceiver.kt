package com.uyatame.ucrt.airplay

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** RTSP の待ち受けポート (7000 が AirPlay の発見に必須だった、という GoAirPlay の実証)。 */
internal const val RTSP_PORT = 7000

/** UDP ポート (GoAirPlay と同じ割り当て)。 */
internal const val UDP_TIMING_PORT = 6002
internal const val UDP_CONTROL_PORT = 6001
internal const val UDP_DATA_PORT = 6000

/** 接続 1 本分のセッション状態 (GoAirPlay の session + ストリーム状態)。 */
internal class AirPlaySession {
    var remoteAddress: String = ""
    var clientEdPub: ByteArray? = null
    var ecdhPriv: X25519PrivateKeyParameters? = null
    var ourEcdhPub: ByteArray? = null
    var ecdhSecret: ByteArray? = null
    var peerEcdh: ByteArray? = null
    var handshake = false
    var verified = false
    var fpVersion = 0
    var fpKeymsg: ByteArray? = null

    // SETUP で決まるストリームの鍵と形式 (Phase 3 の受信サーバーが使う)。
    var aesKey: ByteArray? = null
    var aesIV: ByteArray? = null
    var streamCt: Long = 0
    var streamSpf: Long = 0
}

/** 受信側の出来事をアプリへ伝える窓口。 */
internal interface AirPlayListener {
    /** SET_PARAMETER の volume: (dB、0 が最大、-144 以下はミュート相当)。 */
    fun onVolumeDb(db: Double)
    /** TEARDOWN が来た。 */
    fun onTeardown(session: AirPlaySession)
    /** SETUP 1 でストリーム鍵が決まった (タイミングサーバーの起動どき)。 */
    fun onStreamKeyReady(session: AirPlaySession)
    /** SETUP 2 で音声ストリームの形式が決まった (データ/コントロール受信の起動どき)。 */
    fun onAudioStreamConfigured(session: AirPlaySession)
    /** DACP メタデータなどの plist が SET_PARAMETER で届いた (Phase 4 で解釈する)。 */
    fun onMetadataPlist(session: AirPlaySession, body: ByteArray)
    /** pair-verify が完了した。 */
    fun onClientVerified(session: AirPlaySession)
    /** クライアントが PAUSE を送ってきた。 */
    fun onPauseRequest(session: AirPlaySession) {}
    /** クライアントが RECORD (再生開始/再開) を送ってきた。 */
    fun onRecordRequest(session: AirPlaySession) {}
    /** クライアントが FLUSH を送ってきた (溜まった音は stream 側で捨て済み)。 */
    fun onFlushRequest(session: AirPlaySession) {}
    /** 音量でもメタデータでもない SET_PARAMETER (progress など)。 */
    fun onSetParameterOther(session: AirPlaySession, contentType: String?, body: ByteArray) {}
    /** カバー画像が SET_PARAMETER で届いた (image/jpeg など)。 */
    fun onArtwork(session: AirPlaySession, contentType: String, body: ByteArray) {}
}

/**
 * AirPlay 受信機の RTSP サーバーとプロトコル処理。GoAirPlay の main.go / setup.go の
 * ハンドラ部分の移植。発見 (mDNS) は Phase 2、UDP 受信と再生は Phase 3/4 が担い、
 * ここでは TXT レコードの定義と SETUP までの状態遷移を提供する。
 */
internal class AirPlayReceiver(
    val identity: AirPlayIdentity,
    @Volatile var receiverName: String,
    val deviceId: String,
    private val listener: AirPlayListener,
    private val log: (String) -> Unit = {},
) {
    @Volatile
    var running: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null
    private var responder: MdnsResponder? = null
    private var rtspPort: Int = RTSP_PORT

    /** UDP 受信 (タイミング / データ / 制御) と ALAC デコード (Phase 3)。 */
    internal val stream = AirPlayStream(log)

    fun raopTxt(): List<String> = listOf(
        "txtvers=1", "ch=2", "cn=0,1,2,3", "da=true", "et=0,3,5", "md=0,1,2",
        "pw=false", "sr=44100", "ss=16", "tp=UDP", "vn=65537", "vs=220.68",
        "am=AppleTV3,2", "sf=0x4",
    )

    fun airplayTxt(): List<String> = listOf(
        "deviceid=$deviceId", "features=0x5A7FFFF7,0x1E", "flags=0x4",
        "model=AppleTV3,2", "pw=false", "srcvers=220.68", "vv=2",
    )

    /** RAOP の mDNS インスタンス名: "<MAC (コロンなし小文字)>@<受信機名>"。 */
    fun raopInstance(): String = deviceId.replace(":", "") + "@" + receiverName

    /**
     * mDNS で受信機を広告する (Phase 2)。RTSP の start() とは独立に呼び、
     * 止めるときは stop() が両方を止める。
     */
    fun startAdvertising(localIp: java.net.InetAddress) {
        if (responder != null) return
        val services = listOf(
            MdnsResponder.Service("_raop._tcp.local", raopInstance() + "._raop._tcp.local", raopTxt()),
            MdnsResponder.Service("_airplay._tcp.local", receiverName + "._airplay._tcp.local", airplayTxt()),
        )
        val r = MdnsResponder(localIp, rtspPort, MdnsResponder.sanitizeHostName(receiverName), services, log)
        responder = r
        r.start()
    }

    fun start(port: Int = RTSP_PORT) {
        if (running) return
        rtspPort = port
        val ss = ServerSocket(port)
        serverSocket = ss
        running = true
        thread(isDaemon = true, name = "airplay-rtsp-accept") {
            while (running) {
                val conn = try {
                    ss.accept()
                } catch (e: Exception) {
                    break
                }
                thread(isDaemon = true, name = "airplay-rtsp-conn") { handleConn(conn) }
            }
        }
        log("[rtsp] listening on :$port")
    }

    fun stop() {
        running = false
        stream.stopAll()
        responder?.stop()
        responder = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // 終了時の例外は無視する。
        }
        serverSocket = null
    }

    // ---------- 接続処理 ----------

    private class Response(
        val status: String,
        val extraHeaders: List<String> = emptyList(),
        val body: ByteArray? = null,
        val contentType: String = "",
    )

    private fun handleConn(conn: Socket) {
        conn.use {
            val remote = conn.remoteSocketAddress?.toString() ?: "?"
            val session = AirPlaySession()
            session.remoteAddress = remote
            log("[conn] from $remote")
            val input = BufferedInputStream(conn.getInputStream())
            val output = BufferedOutputStream(conn.getOutputStream())
            conn.soTimeout = 120_000
            while (running) {
                val reqLine = readLine(input) ?: return
                if (reqLine.isEmpty()) continue
                val parts = reqLine.split(" ", limit = 3)
                if (parts.size < 3) {
                    log("[conn] malformed request line: $reqLine")
                    return
                }
                val method = parts[0]
                val uri = parts[1]

                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    }
                }
                var body = ByteArray(0)
                val cl = headers["content-length"]?.toIntOrNull()
                if (cl != null && cl > 0 && cl <= 8 shl 20) {
                    body = ByteArray(cl)
                    var read = 0
                    while (read < cl) {
                        val n = input.read(body, read, cl - read)
                        if (n < 0) return
                        read += n
                    }
                }

                log("[conn] $method $uri CSeq=${headers["cseq"]} len=${body.size}")
                val resp = dispatch(session, method, uri, headers, body)

                val sb = StringBuilder()
                sb.append("RTSP/1.0 ").append(resp.status).append("\r\n")
                headers["cseq"]?.let { sb.append("CSeq: ").append(it).append("\r\n") }
                sb.append("Server: UCRT-AirPlay/1.0\r\n")
                for (h in resp.extraHeaders) sb.append(h).append("\r\n")
                if (resp.contentType.isNotEmpty()) sb.append("Content-Type: ").append(resp.contentType).append("\r\n")
                if (resp.body != null) sb.append("Content-Length: ").append(resp.body.size).append("\r\n")
                sb.append("\r\n")
                output.write(sb.toString().toByteArray(Charsets.US_ASCII))
                if (resp.body != null) output.write(resp.body)
                output.flush()
            }
        }
    }

    /** CRLF / LF どちらでも 1 行読む。EOF では null。 */
    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString().trim()
            if (c == '\n'.code) break
            sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    private fun dispatch(
        session: AirPlaySession,
        method: String,
        uri: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): Response {
        return when {
            method == "GET" && uri == "/info" -> {
                val b = buildInfoResponse(body)
                log("[info] responding ${b.size} bytes")
                Response("200 OK", body = b, contentType = "application/x-apple-binary-plist")
            }
            method == "POST" && uri == "/pair-setup" -> {
                if (body.size != 32) {
                    log("[pair-setup] unexpected body length ${body.size}")
                    Response("400 Bad Request")
                } else {
                    session.clientEdPub = body.copyOf()
                    log("[pair-setup] got client Ed25519 pk, returning ours")
                    Response("200 OK", body = identity.edPublicBytes, contentType = "application/octet-stream")
                }
            }
            method == "POST" && uri == "/pair-verify" -> {
                val b = pairVerify(session, body)
                if (b == null) Response("400 Bad Request")
                else Response("200 OK", body = b, contentType = "application/octet-stream")
            }
            method == "POST" && uri == "/fp-setup" -> {
                val b = FairPlay.handle(session, body)
                if (b == null) Response("400 Bad Request")
                else Response("200 OK", body = b, contentType = "application/octet-stream")
            }
            method == "SETUP" -> {
                val (b, status) = handleSetup(session, body)
                if (b == null) Response(status)
                else Response(status, body = b, contentType = "application/x-apple-binary-plist")
            }
            method == "OPTIONS" -> Response(
                "200 OK",
                extraHeaders = listOf("Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"),
            )
            method == "SET_PARAMETER" && body.toString(Charsets.US_ASCII).startsWith("volume:") -> {
                val v = body.toString(Charsets.US_ASCII).removePrefix("volume:").trim()
                v.toDoubleOrNull()?.let {
                    listener.onVolumeDb(it)
                    log("[conn] volume set to $it dB")
                }
                Response("200 OK")
            }
            method == "SET_PARAMETER" && headers["content-type"] == "application/x-dmap-tagged" -> {
                listener.onMetadataPlist(session, body)
                Response("200 OK")
            }
            method == "SET_PARAMETER" && headers["content-type"]?.startsWith("image/") == true -> {
                listener.onArtwork(session, headers["content-type"]!!, body)
                Response("200 OK")
            }
            method == "SET_PARAMETER" -> {
                listener.onSetParameterOther(session, headers["content-type"], body)
                Response("200 OK")
            }
            method == "PAUSE" -> {
                listener.onPauseRequest(session)
                log("[conn] PAUSE")
                Response("200 OK")
            }
            method == "RECORD" -> {
                listener.onRecordRequest(session)
                log("[conn] RECORD")
                Response("200 OK")
            }
            method == "FLUSH" -> {
                stream.reset()
                listener.onFlushRequest(session)
                log("[conn] FLUSH")
                Response("200 OK")
            }
            method == "TEARDOWN" -> {
                stream.reset()
                listener.onTeardown(session)
                log("[conn] TEARDOWN")
                Response("200 OK")
            }
            else -> {
                log("[conn] unhandled $method $uri - answering 200 empty")
                Response("200 OK")
            }
        }
    }

    // ---------- pair-verify (GoAirPlay の pairVerify の移植) ----------

    private fun pairVerify(session: AirPlaySession, body: ByteArray): ByteArray? {
        if (body.size < 4) {
            log("[pair-verify] body too short: ${body.size}")
            return null
        }
        when (body[0].toInt()) {
            1 -> {
                if (body.size != 4 + 32 + 32) {
                    log("[pair-verify] step1 bad length ${body.size}")
                    return null
                }
                val theirEcdh = body.copyOfRange(4, 4 + 32)
                val theirEd = body.copyOfRange(4 + 32, 4 + 64)
                session.peerEcdh = theirEcdh
                if (session.clientEdPub == null) session.clientEdPub = theirEd
                val keys = PairCrypto.generateX25519()
                session.ecdhPriv = keys.priv
                session.ourEcdhPub = keys.pubBytes
                val secret = try {
                    PairCrypto.x25519Secret(keys.priv, theirEcdh)
                } catch (e: Exception) {
                    log("[pair-verify] ECDH failed: ${e.message}")
                    return null
                }
                session.ecdhSecret = secret
                session.handshake = true

                // 署名 = Ed25519(自 ECDH 公開鍵 || 相手 ECDH 公開鍵)、AES-CTR で暗号化。
                val sig = identity.sign(keys.pubBytes + theirEcdh)
                val enc = PairCrypto.aesCtrWithSecret(secret, sig)
                log("[pair-verify] step1 OK")
                return keys.pubBytes + enc
            }
            0 -> {
                if (body.size != 4 + 64) {
                    log("[pair-verify] step2 bad length ${body.size}")
                    return null
                }
                if (!session.handshake) {
                    log("[pair-verify] step2 without step1")
                    return null
                }
                val secret = session.ecdhSecret ?: return null
                val priv = session.ecdhPriv ?: return null
                val ourPub = session.ourEcdhPub ?: return null
                val clientEd = session.clientEdPub ?: return null
                val theirEcdh = session.peerEcdh ?: return null
                // クライアントは CTR ブロックを 1 つ (64 バイト) 消費済み。
                // CTR のキーストリームは位置で決まるので、先行 64 バイトとまとめて
                // 処理して後半を取ることで、進んだ位置からの復号を再現する。
                val (key, iv) = PairCrypto.deriveKeyIV(secret)
                val combined = PairCrypto.aesCtr(key, iv, ByteArray(64) + body.copyOfRange(4, 4 + 64))
                val sig = combined.copyOfRange(64, 128)
                val msg = theirEcdh + ourPub
                if (!AirPlayIdentity.verify(clientEd, msg, sig)) {
                    log("[pair-verify] step2 signature INVALID")
                    return null
                }
                session.verified = true
                listener.onClientVerified(session)
                log("[pair-verify] step2 OK - client verified")
                return ByteArray(0)
            }
            else -> {
                log("[pair-verify] unknown step byte ${body[0]}")
                return null
            }
        }
    }

    // ---------- /info ----------

    private fun txtWire(txt: List<String>): ByteArray {
        var out = ByteArray(0)
        for (e in txt) {
            val b = e.toByteArray(Charsets.US_ASCII)
            out += byteArrayOf(b.size.toByte()) + b
        }
        return out
    }

    private fun buildInfoResponse(reqBody: ByteArray): ByteArray {
        // qualifier 要求か {"qualifier": ["txtAirPlay"|"txtRAOP"]}
        if (reqBody.isNotEmpty()) {
            try {
                val req = Bplist.read(reqBody) as? Map<*, *>
                val q = (req?.get("qualifier") as? List<*>)?.firstOrNull() as? String
                when (q) {
                    "txtAirPlay" -> return Bplist.write(mapOf("txtAirPlay" to txtWire(airplayTxt())))
                    "txtRAOP" -> return Bplist.write(mapOf("txtRAOP" to txtWire(raopTxt())))
                }
            } catch (e: Exception) {
                // qualifier でなければ通常の /info 応答に落ちる。
            }
        }
        val features = 0x5A7FFFF7L or (0x1EL shl 32)
        val info = linkedMapOf<String, Any?>(
            "deviceID" to deviceId,
            "macAddress" to deviceId,
            "pk" to identity.edPublicBytes,
            "features" to features,
            "name" to receiverName,
            "pi" to "2f1a4d2c-0000-4000-8000-000000000000",
            "vv" to 2L,
            "statusFlags" to 68L,
            "keepAliveLowPower" to true,
            "sourceVersion" to "220.68",
            "keepAliveSendStatsAsBody" to true,
            "model" to "AppleTV3,2",
            "initialVolume" to 0.0,
            "audioLatencies" to listOf(
                linkedMapOf<String, Any?>("type" to 100L, "audioType" to "default", "inputLatencyMicros" to 0L, "outputLatencyMicros" to false),
                linkedMapOf<String, Any?>("type" to 101L, "audioType" to "default", "inputLatencyMicros" to 0L, "outputLatencyMicros" to false),
            ),
            "audioFormats" to listOf(
                linkedMapOf<String, Any?>("type" to 100L, "audioInputFormats" to 0x3fffffcL, "audioOutputFormats" to 0x3fffffcL),
                linkedMapOf<String, Any?>("type" to 101L, "audioInputFormats" to 0x3fffffcL, "audioOutputFormats" to 0x3fffffcL),
            ),
            "displays" to listOf(
                linkedMapOf<String, Any?>(
                    "uuid" to "e0ff8a27-6738-3d56-8a16-cc53aacee925",
                    "width" to 1920L, "height" to 1080L,
                    "widthPixels" to 1920L, "heightPixels" to 1080L,
                    "widthPhysical" to 0L, "heightPhysical" to 0L,
                    "rotation" to false,
                    "refreshRate" to 1.0 / 60.0,
                    "maxFPS" to 30L,
                    "overscanned" to false,
                    "features" to 14L,
                ),
            ),
        )
        return Bplist.write(info)
    }

    // ---------- SETUP (GoAirPlay の handleSETUP の移植。UDP 受信は Phase 3) ----------

    private fun handleSetup(session: AirPlaySession, body: ByteArray): Pair<ByteArray?, String> {
        val req = try {
            Bplist.read(body) as? Map<*, *>
        } catch (e: Exception) {
            log("[setup] plist parse failed: ${e.message}")
            return null to "400 Bad Request"
        } ?: return null to "400 Bad Request"

        val resp = LinkedHashMap<String, Any?>()

        // ---- SETUP 1: 鍵とタイミング ----
        val ekeyRaw = req["ekey"] as? ByteArray
        if (ekeyRaw != null) {
            val eivRaw = req["eiv"] as? ByteArray
            if (ekeyRaw.size != 72 || eivRaw == null || eivRaw.size != 16) {
                log("[setup] bad ekey/eiv lengths ${ekeyRaw.size}/${eivRaw?.size}")
                return null to "400 Bad Request"
            }
            val keymsg = session.fpKeymsg
            if (keymsg == null || keymsg.size != 164) {
                log("[setup] no FairPlay key message stored")
                return null to "400 Bad Request"
            }
            val pfKey = Playfair.decrypt(keymsg, ekeyRaw)
            val secret = session.ecdhSecret
            session.aesKey = if (secret != null && secret.size == 32) {
                // AirPlay 2 レガシー: 実際の鍵は SHA-512(playfair 鍵 || ECDH 秘密) の先頭 16 バイト。
                PairCrypto.sha512(pfKey + secret).copyOfRange(0, 16)
            } else {
                pfKey
            }
            log("[setup] stream key ready")
            session.aesIV = eivRaw.copyOf()
            resp["timingPort"] = UDP_TIMING_PORT.toLong()
            resp["eventPort"] = 0L
            listener.onStreamKeyReady(session)
            stream.setKey(session.aesKey!!, session.aesIV!!)
            try {
                stream.startTiming()
            } catch (e: Exception) {
                log("[ntp] start failed: ${e.message}")
            }
        }

        // ---- SETUP 2: ストリーム ----
        val streamsRaw = req["streams"] as? List<*>
        if (streamsRaw != null) {
            val resStreams = ArrayList<Any?>()
            for (si in streamsRaw) {
                val sm = si as? Map<*, *> ?: continue
                val typ = (sm["type"] as? Long) ?: 0L
                log("[setup] stream type=$typ ct=${sm["ct"]} spf=${sm["spf"]} audioFormat=${sm["audioFormat"]}")
                when (typ) {
                    96L -> { // 音声
                        session.streamCt = (sm["ct"] as? Long) ?: 0L
                        session.streamSpf = (sm["spf"] as? Long) ?: 0L
                        resStreams.add(
                            linkedMapOf<String, Any?>(
                                "type" to 96L,
                                "dataPort" to UDP_DATA_PORT.toLong(),
                                "controlPort" to UDP_CONTROL_PORT.toLong(),
                            ),
                        )
                        listener.onAudioStreamConfigured(session)
                        stream.configure(session.streamCt.toInt(), session.streamSpf.toInt())
                        try {
                            stream.startDataControl()
                        } catch (e: Exception) {
                            log("[rtp] start failed: ${e.message}")
                        }
                    }
                    110L -> { // ミラーリング (受け付けるが復号はしない)
                        resStreams.add(
                            linkedMapOf<String, Any?>(
                                "type" to 110L,
                                "dataPort" to (UDP_DATA_PORT + 10).toLong(),
                            ),
                        )
                    }
                }
            }
            if (resStreams.isNotEmpty()) resp["streams"] = resStreams
        }

        val out = try {
            Bplist.write(resp)
        } catch (e: Exception) {
            log("[setup] response marshal failed: ${e.message}")
            return null to "500 Internal Server Error"
        }
        return out to "200 OK"
    }

    companion object {
        /**
         * デバイス ID を決める。使える MAC があればそれを、なければ Ed25519 の
         * 公開鍵から安定したローカル管理アドレスを導く (GoAirPlay と同じ規則)。
         */
        fun deviceIdFor(identity: AirPlayIdentity, mac: String?): String {
            if (!mac.isNullOrEmpty() && mac != "02:00:00:00:00:00" && mac != "00:00:00:00:00:00") {
                return mac.lowercase()
            }
            val b = identity.edPublicBytes.copyOfRange(0, 6)
            b[0] = ((b[0].toInt() and 0xFE) or 0x02).toByte()
            return b.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
