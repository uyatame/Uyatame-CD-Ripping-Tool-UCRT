package com.uyatame.ucrt.legacy.cast

// 本家(app)の player/cast/CastChannel.kt と同じ。Android 4.1〜4.4 では TLS 1.2 を入れる(初めは切ってあるため)

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Chromecast との接続(Cast v2 プロトコル)。TLS の上で、長さ(4 バイト)+ protobuf の CastMessage をやり取りする。
 * Google の Cast SDK(オープンソースではない)を使わずに、必要な分だけをこのアプリで実装している。
 *
 * CastMessage のうち使うのは、文字列の本文(JSON)を運ぶ形だけ:
 *   1: protocol_version(0) / 2: source_id / 3: destination_id / 4: namespace / 5: payload_type(0 = 文字列) / 6: payload_utf8
 */
internal class CastChannel(
    host: String,
    port: Int,
    /** 受け取った JSON(namespace, 送り元, 本文)。読み取りのスレッドから呼ぶ */
    private val onMessage: (String, String, JSONObject) -> Unit,
    /** 接続が切れたとき(読み取りのスレッドから呼ぶ) */
    private val onClosed: () -> Unit,
) {
    private val socket: SSLSocket
    @Volatile private var closed = false

    init {
        // Chromecast の証明書は機器ごとの自己署名なので、証明書の検証はしない(同じネットワークの機器との通信)
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        val s = ctx.socketFactory.createSocket() as SSLSocket
        if (android.os.Build.VERSION.SDK_INT < 20) {
            val want = s.supportedProtocols.filter { it == "TLSv1" || it == "TLSv1.1" || it == "TLSv1.2" }
            if (want.isNotEmpty()) s.enabledProtocols = want.toTypedArray()
        }
        s.connect(InetSocketAddress(host, port), 5000)
        s.soTimeout = 0
        s.startHandshake()
        socket = s
        Thread({ readLoop() }, "cast-read").apply { isDaemon = true; start() }
    }

    fun send(namespace: String, destination: String, payload: JSONObject, source: String = SENDER) {
        val body = encode(source, destination, namespace, payload.toString())
        val out = ByteArray(4 + body.size)
        out[0] = (body.size ushr 24).toByte(); out[1] = (body.size ushr 16).toByte()
        out[2] = (body.size ushr 8).toByte(); out[3] = body.size.toByte()
        System.arraycopy(body, 0, out, 4, body.size)
        synchronized(this) {
            if (closed) throw IOException("closed")
            socket.outputStream.write(out)
            socket.outputStream.flush()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    private fun readLoop() {
        try {
            val inp = DataInputStream(socket.inputStream)
            while (!closed) {
                val len = inp.readInt()
                if (len < 0 || len > 1 shl 20) throw IOException("bad length $len")
                val b = ByteArray(len)
                inp.readFully(b)
                val m = decode(b) ?: continue
                val json = runCatching { JSONObject(m.payload) }.getOrNull() ?: continue
                runCatching { onMessage(m.namespace, m.source, json) }
            }
        } catch (_: Exception) {
        } finally {
            val was = closed
            close()
            if (!was) onClosed()
        }
    }

    internal class Message(val source: String, val destination: String, val namespace: String, val payload: String)

    companion object {
        const val SENDER = "sender-0"
        const val RECEIVER = "receiver-0"
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

        private fun varint(out: ByteArrayOutputStream, v0: Long) {
            var v = v0
            while (v >= 0x80) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }

        private fun str(out: ByteArrayOutputStream, field: Int, s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write((field shl 3) or 2)
            varint(out, b.size.toLong())
            out.write(b)
        }

        fun encode(source: String, destination: String, namespace: String, payload: String): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0x08); out.write(0) // protocol_version = CASTV2_1_0
            str(out, 2, source)
            str(out, 3, destination)
            str(out, 4, namespace)
            out.write(0x28); out.write(0) // payload_type = STRING
            str(out, 6, payload)
            return out.toByteArray()
        }

        /** 文字列の本文を持つメッセージだけを読む(バイナリの本文などは null) */
        fun decode(b: ByteArray): Message? {
            var p = 0
            fun readVarint(): Long {
                var r = 0L
                var shift = 0
                while (true) {
                    if (p >= b.size) throw IOException("truncated")
                    val c = b[p++].toInt() and 0xFF
                    r = r or ((c and 0x7F).toLong() shl shift)
                    if (c < 0x80) return r
                    shift += 7
                    if (shift > 63) throw IOException("bad varint")
                }
            }
            var src = ""; var dst = ""; var ns = ""; var payload: String? = null
            return runCatching {
                while (p < b.size) {
                    val tag = readVarint().toInt()
                    val field = tag ushr 3
                    when (tag and 7) {
                        0 -> readVarint()
                        2 -> {
                            val n = readVarint().toInt()
                            if (n < 0 || p + n > b.size) throw IOException("truncated")
                            val s = String(b, p, n, Charsets.UTF_8)
                            p += n
                            when (field) {
                                2 -> src = s
                                3 -> dst = s
                                4 -> ns = s
                                6 -> payload = s
                            }
                        }
                        1 -> p += 8
                        5 -> p += 4
                        else -> throw IOException("bad wire type")
                    }
                }
                payload?.let { Message(src, dst, ns, it) }
            }.getOrNull()
        }
    }
}
