package com.uyatame.ucrt.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.net.Socket

/**
 * RTSP プロトコル全体の通しテスト。実ソケット越しにクライアント役を演じ、
 * /info → pair-setup → pair-verify (双方向の署名検証) → fp-setup → SETUP 1/2 →
 * 音量 → TEARDOWN までを GoAirPlay で実証済みの手順どおりに検証する。
 */
class AirPlayProtocolTest {

    private class RecListener : AirPlayListener {
        var volumeDb: Double? = null
        var teardown = false
        var keySession: AirPlaySession? = null
        var streamSession: AirPlaySession? = null
        var verifiedSession: AirPlaySession? = null
        override fun onVolumeDb(db: Double) { volumeDb = db }
        override fun onTeardown(session: AirPlaySession) { teardown = true }
        override fun onStreamKeyReady(session: AirPlaySession) { keySession = session }
        override fun onAudioStreamConfigured(session: AirPlaySession) { streamSession = session }
        override fun onMetadataPlist(session: AirPlaySession, body: ByteArray) {}
        override fun onClientVerified(session: AirPlaySession) { verifiedSession = session }
    }

    private class Resp(val status: String, val headers: Map<String, String>, val body: ByteArray)

    private class Client(port: Int) {
        private val socket = Socket("127.0.0.1", port)
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = socket.getOutputStream()
        private var cseq = 0

        init {
            socket.soTimeout = 10_000
        }

        fun request(method: String, uri: String, body: ByteArray = ByteArray(0), contentType: String? = null): Resp {
            cseq++
            val sb = StringBuilder()
            sb.append("$method $uri RTSP/1.0\r\n")
            sb.append("CSeq: $cseq\r\n")
            sb.append("User-Agent: AirPlayProtocolTest\r\n")
            if (contentType != null) sb.append("Content-Type: $contentType\r\n")
            if (body.isNotEmpty()) sb.append("Content-Length: ${body.size}\r\n")
            sb.append("\r\n")
            output.write(sb.toString().toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()

            val status = readLine() ?: error("no response")
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine() ?: error("eof in headers")
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val respBody = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = input.read(respBody, read, len - read)
                if (n < 0) error("eof in body")
                read += n
            }
            return Resp(status, headers, respBody)
        }

        private fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString().trim()
                if (c == '\n'.code) break
                sb.append(c.toChar())
            }
            return sb.toString().trim()
        }

        fun close() = socket.close()
    }

    @Test
    fun fullHandshake() {
        val dir = File(System.getProperty("java.io.tmpdir"), "airplay-test-${System.nanoTime()}")
        dir.mkdirs()
        val identity = AirPlayIdentity.loadOrCreate(File(dir, "key.bin"))
        val clientIdentity = AirPlayIdentity.loadOrCreate(File(dir, "client-key.bin"))
        val listener = RecListener()
        val deviceId = AirPlayReceiver.deviceIdFor(identity, null)
        val receiver = AirPlayReceiver(identity, "UCRT-Test", deviceId, listener)
        val port = 17000
        receiver.start(port)
        try {
            val client = Client(port)

            // ---- /info ----
            val infoResp = client.request("GET", "/info")
            assertTrue(infoResp.status.contains("200"))
            val info = Bplist.read(infoResp.body) as Map<*, *>
            assertEquals("UCRT-Test", info["name"])
            assertEquals(deviceId, info["deviceID"])
            assertTrue((info["pk"] as ByteArray).contentEquals(identity.edPublicBytes))

            // ---- /info (qualifier) ----
            val qBody = Bplist.write(mapOf("qualifier" to listOf("txtRAOP")))
            val qResp = client.request("GET", "/info", qBody, "application/x-apple-binary-plist")
            val qInfo = Bplist.read(qResp.body) as Map<*, *>
            val txt = qInfo["txtRAOP"] as ByteArray
            assertEquals("txtvers=1".length.toByte(), txt[0])
            assertEquals("txtvers=1", String(txt, 1, 9, Charsets.US_ASCII))

            // ---- pair-setup ----
            val psResp = client.request("POST", "/pair-setup", clientIdentity.edPublicBytes, "application/octet-stream")
            assertTrue(psResp.status.contains("200"))
            assertTrue(psResp.body.contentEquals(identity.edPublicBytes))

            // ---- pair-verify step 1 ----
            val clientX = PairCrypto.generateX25519()
            val step1Body = byteArrayOf(1, 0, 0, 0) + clientX.pubBytes + clientIdentity.edPublicBytes
            val pv1 = client.request("POST", "/pair-verify", step1Body, "application/octet-stream")
            assertTrue(pv1.status.contains("200"))
            assertEquals(96, pv1.body.size)
            val serverEcdhPub = pv1.body.copyOfRange(0, 32)
            val encSig = pv1.body.copyOfRange(32, 96)
            val secret = PairCrypto.x25519Secret(clientX.priv, serverEcdhPub)
            val (pvKey, pvIv) = PairCrypto.deriveKeyIV(secret)
            val sig = PairCrypto.aesCtr(pvKey, pvIv, encSig)
            assertTrue(
                "receiver signature must verify",
                AirPlayIdentity.verify(identity.edPublicBytes, serverEcdhPub + clientX.pubBytes, sig),
            )

            // ---- pair-verify step 2 ----
            val clientSig = clientIdentity.sign(clientX.pubBytes + serverEcdhPub)
            val step2Enc = PairCrypto.aesCtr(pvKey, pvIv, ByteArray(64) + clientSig).copyOfRange(64, 128)
            val pv2 = client.request("POST", "/pair-verify", byteArrayOf(0, 0, 0, 0) + step2Enc, "application/octet-stream")
            assertTrue(pv2.status.contains("200"))
            assertNotNull(listener.verifiedSession)

            // ---- fp-setup ----
            val fp16 = ByteArray(16)
            fp16[4] = 0x03
            fp16[14] = 0x02
            val fpResp = client.request("POST", "/fp-setup", fp16, "application/octet-stream")
            assertEquals(142, fpResp.body.size)
            assertEquals("FPLY", String(fpResp.body, 0, 4, Charsets.US_ASCII))

            val fp164 = ByteArray(164) { (it * 5 + 1).toByte() }
            fp164[4] = 0x03
            fp164[12] = 0x01
            val fpResp2 = client.request("POST", "/fp-setup", fp164, "application/octet-stream")
            assertEquals(32, fpResp2.body.size)
            assertTrue(fpResp2.body.copyOfRange(12, 32).contentEquals(fp164.copyOfRange(144, 164)))

            val fpBad = ByteArray(16)
            fpBad[4] = 0x03
            fpBad[14] = 0x09
            val fpBadResp = client.request("POST", "/fp-setup", fpBad, "application/octet-stream")
            assertTrue(fpBadResp.status.contains("400"))

            // ---- SETUP 1 ----
            val setup1 = Bplist.write(
                mapOf<String, Any?>(
                    "ekey" to ByteArray(72) { (it * 7).toByte() },
                    "eiv" to ByteArray(16) { (it + 3).toByte() },
                ),
            )
            val s1 = client.request("SETUP", "rtsp://127.0.0.1/stream", setup1, "application/x-apple-binary-plist")
            assertTrue(s1.status.contains("200"))
            val s1Resp = Bplist.read(s1.body) as Map<*, *>
            assertEquals(6002L, s1Resp["timingPort"])
            assertNotNull(listener.keySession)
            assertEquals(16, listener.keySession!!.aesKey!!.size)

            // ---- SETUP 2 ----
            val setup2 = Bplist.write(
                mapOf<String, Any?>(
                    "streams" to listOf(
                        mapOf<String, Any?>("type" to 96L, "ct" to 2L, "spf" to 352L, "audioFormat" to 262144L),
                    ),
                ),
            )
            val s2 = client.request("SETUP", "rtsp://127.0.0.1/stream", setup2, "application/x-apple-binary-plist")
            assertTrue(s2.status.contains("200"))
            val s2Resp = Bplist.read(s2.body) as Map<*, *>
            val streams = s2Resp["streams"] as List<*>
            val stream0 = streams[0] as Map<*, *>
            assertEquals(6000L, stream0["dataPort"])
            assertEquals(6001L, stream0["controlPort"])
            assertNotNull(listener.streamSession)
            assertEquals(2L, listener.streamSession!!.streamCt)
            assertEquals(352L, listener.streamSession!!.streamSpf)

            // ---- OPTIONS / volume / TEARDOWN ----
            val opt = client.request("OPTIONS", "*")
            assertTrue(opt.headers["public"]?.contains("SETUP") == true)

            client.request("SET_PARAMETER", "rtsp://127.0.0.1/stream", "volume: -9.5".toByteArray(Charsets.US_ASCII), "text/parameters")
            assertEquals(-9.5, listener.volumeDb!!, 0.001)

            client.request("TEARDOWN", "rtsp://127.0.0.1/stream")
            assertTrue(listener.teardown)

            client.close()
        } finally {
            receiver.stop()
            dir.deleteRecursively()
        }
    }
}
