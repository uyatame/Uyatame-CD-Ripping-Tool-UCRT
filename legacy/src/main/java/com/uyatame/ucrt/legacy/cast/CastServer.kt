package com.uyatame.ucrt.legacy.cast

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.uyatame.ucrt.player.BuiltinCodecs
import com.uyatame.ucrt.player.PcmDecoder
import com.uyatame.ucrt.player.RandomInput
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** キャストする曲(本家の CastItem の代わり) */
internal class CastItem(
    val id: Long, val uri: String, val title: String, val artist: String, val album: String,
    val durationMs: Long, val startMs: Long, val endMs: Long, val art: com.uyatame.ucrt.legacy.ArtRef?,
)

/**
 * Chromecast に曲を渡すための、端末の中の小さな HTTP サーバー(本家の CastServer と同じ。古い Android 向けに、
 * Socket を use で閉じない(Android 4.4 より前は Socket が Closeable でないため))。
 * Chromecast は曲を URL から読むので、端末のファイルを同じネットワークに公開する(登録した曲だけ、推測できない URL で)。
 *
 * - Chromecast がそのまま再生できる形式(FLAC・MP3・AAC・Ogg・WAV など)は、ファイルをそのまま送る
 * - ALAC・AIFF・WavPack・APE と、CUE シートで分けた曲は、アプリの中でデコードして WAV で送る
 *   (96 kHz を超える音源は、Chromecast が再生できるよう半分の周波数にする)
 * どちらも Range(途中から読む)に対応し、Chromecast の早送り・巻き戻しが効くようにする
 */
internal object CastServer {
    /** Chromecast に送れないとき(DSD・CD の直接再生など) */
    class Unsupported(msg: String) : IOException(msg)

    private class Entry(
        val item: CastItem,
        /** 実際のファイル(CUE シートで分けた曲は元のファイル) */
        val file: Uri,
        /** null ならファイルをそのまま送る。そうでなければデコードする形式(BuiltinCodecs の種類か "wav") */
        val decode: String?,
        val mime: String,
        val art: ByteArray?,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private var server: ServerSocket? = null
    private lateinit var app: Context

    /** 曲を公開して、Chromecast に渡す URL を返す(曲の URL, ジャケットの URL, MIME) */
    fun publish(ctx: Context, item: CastItem, near: String): Triple<String, String?, String> {
        app = ctx.applicationContext
        if (item.uri.startsWith("cd:")) throw Unsupported("cd")
        val uri = Uri.parse(item.uri)
        val cue = com.uyatame.ucrt.library.Cue.isCue(uri.toString())
        val file = if (cue) Uri.parse(com.uyatame.ucrt.library.Cue.fileUri(uri.toString())) else uri
        val name = Uri.decode(file.toString()).substringAfterLast('/')
        if (com.uyatame.ucrt.player.dsd.DsdFile.isDsd(name)) throw Unsupported("dsd")
        val kind = BuiltinCodecs.kindOf(name)
            ?: BuiltinCodecs.openInput(app, file)?.use { BuiltinCodecs.sniff(it) }
        val type = runCatching { app.contentResolver.getType(file) }.getOrNull().orEmpty()
        val wav = name.lowercase().endsWith(".wav") || type.contains("wav")
        val decode: String?
        val mime: String
        when {
            kind != null && kind != "flac" -> { decode = kind; mime = "audio/wav" }
            kind == "flac" -> {
                // 96 kHz までの FLAC で、CUE シートで分けていなければそのまま送る
                val rate = BuiltinCodecs.openInput(app, file)?.use { BuiltinCodecs.info("flac", it)?.rate } ?: 0
                if (!cue && rate in 1..96000) { decode = null; mime = "audio/flac" } else { decode = "flac"; mime = "audio/wav" }
            }
            cue && wav -> { decode = "wav"; mime = "audio/wav" }
            cue -> throw Unsupported("cue")
            wav -> { decode = null; mime = "audio/wav" }
            type.startsWith("audio/") -> { decode = null; mime = type }
            else -> {
                val ext = name.substringAfterLast('.', "").lowercase()
                mime = when (ext) {
                    "mp3" -> "audio/mpeg"
                    "m4a", "mp4", "aac" -> "audio/mp4"
                    "ogg", "oga", "opus" -> "audio/ogg"
                    else -> throw Unsupported("format")
                }
                decode = null
            }
        }
        val art = runCatching { artBytes(item) }.getOrNull()
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        // 古い登録は消す(同時に送るのは 1 曲だけ)
        entries.clear()
        entries[token] = Entry(item, file, decode, mime, art)
        val base = "http://${hostFor(near)}:${ensureServer()}"
        val ext = when (mime) { "audio/flac" -> "flac"; "audio/wav" -> "wav"; "audio/mpeg" -> "mp3"; "audio/ogg" -> "ogg"; else -> "m4a" }
        return Triple("$base/a/$token.$ext", if (art != null) "$base/art/$token.jpg" else null, mime)
    }

    fun clear() = entries.clear()

    private fun artBytes(item: CastItem): ByteArray? {
        val r = item.art ?: return null
        val raw = com.uyatame.ucrt.library.ArtSource.bytes(app, r.trackUri, r.coverUri)
            ?: r.fallbackUri?.let { com.uyatame.ucrt.library.ArtSource.bytes(app, it, null) } ?: return null
        val img = com.uyatame.ucrt.library.ArtSource.decode(raw, 600, false) ?: return null
        val out = java.io.ByteArrayOutputStream()
        img.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    /** Chromecast から届く、この端末の IPv4 アドレス(Chromecast と同じネットワークのものを選ぶ) */
    private fun hostFor(near: String): String {
        val target = runCatching { InetAddress.getByName(near).address }.getOrNull()
        val addrs = NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address }
        if (target != null) {
            for (ia in addrs) {
                val a = ia.address.address
                val bits = ia.networkPrefixLength.toInt().coerceIn(0, 32)
                val mask = if (bits == 0) 0 else (-1 shl (32 - bits))
                fun toInt(b: ByteArray) = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                    ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
                if ((toInt(a) and mask) == (toInt(target) and mask)) return ia.address.hostAddress ?: continue
            }
        }
        return addrs.firstOrNull { it.address.isSiteLocalAddress }?.address?.hostAddress
            ?: addrs.firstOrNull()?.address?.hostAddress
            ?: throw IOException("no network address")
    }

    @Synchronized
    private fun ensureServer(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0)
        server = s
        Thread({
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                Thread({ handle(c) }, "cast-http").apply { isDaemon = true; start() }
            }
        }, "cast-http-accept").apply { isDaemon = true; start() }
        com.uyatame.ucrt.legacy.AppLog.log("cast: http server on port ${s.localPort}")
        return s.localPort
    }

    @Synchronized
    fun stopServer() {
        entries.clear()
        runCatching { server?.close() }
        server = null
    }

    private fun handle(s: Socket) {
        try {
            runCatching {
                s.soTimeout = 30_000
                val inp = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val line = inp.readLine() ?: return
                val parts = line.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                var range: String? = null
                while (true) {
                    val h = inp.readLine() ?: break
                    if (h.isEmpty()) break
                    val k = h.substringBefore(':').trim().lowercase()
                    if (k == "range") range = h.substringAfter(':').trim()
                }
                val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                val head = method == "HEAD"
                val token = path.substringAfterLast('/').substringBefore('.')
                val e = entries[token]
                when {
                    e == null -> status(out, 404, "Not Found")
                    path.startsWith("/art/") -> {
                        val a = e.art
                        if (a == null) status(out, 404, "Not Found") else {
                            out.write(header(200, "OK", "image/jpeg", a.size.toLong(), null).toByteArray())
                            if (!head) out.write(a)
                        }
                    }
                    path.startsWith("/a/") -> {
                        if (e.decode == null) sendFile(out, e, range, head) else sendWav(out, e, range, head)
                    }
                    else -> status(out, 404, "Not Found")
                }
                out.flush()
            }
        } finally {
            runCatching { s.close() }
        }
    }

    private fun status(out: OutputStream, code: Int, msg: String) {
        out.write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    private fun header(code: Int, msg: String, mime: String, length: Long, contentRange: String?): String = buildString {
        append("HTTP/1.1 $code $msg\r\n")
        append("Content-Type: $mime\r\n")
        append("Content-Length: $length\r\n")
        append("Accept-Ranges: bytes\r\n")
        // Chromecast の受信アプリはウェブページなので、ほかの場所から読めるようにする
        append("Access-Control-Allow-Origin: *\r\n")
        if (contentRange != null) append("Content-Range: $contentRange\r\n")
        append("Connection: close\r\n\r\n")
    }

    /** "bytes=a-b" を (始め, 終わり) にする(ファイルの大きさ total に収める)。無ければ null */
    private fun parseRange(range: String?, total: Long): LongRange? {
        val r = range?.removePrefix("bytes=")?.substringBefore(',')?.trim() ?: return null
        val a = r.substringBefore('-').trim()
        val b = r.substringAfter('-').trim()
        return when {
            a.isEmpty() && b.isNotEmpty() -> (total - b.toLong()).coerceAtLeast(0)..(total - 1)
            a.isNotEmpty() -> a.toLong()..(if (b.isEmpty()) total - 1 else minOf(b.toLong(), total - 1))
            else -> null
        }
    }

    private fun sendFile(out: OutputStream, e: Entry, range: String?, head: Boolean) {
        val inp = BuiltinCodecs.openInput(app, e.file) ?: return status(out, 404, "Not Found")
        inp.use { src ->
            val total = src.size
            val r = parseRange(range, total)
            val from = r?.first ?: 0L
            val to = r?.last ?: (total - 1)
            if (from > to || from >= total) return status(out, 416, "Range Not Satisfiable")
            val len = to - from + 1
            out.write(
                (if (r != null) header(206, "Partial Content", e.mime, len, "bytes $from-$to/$total")
                else header(200, "OK", e.mime, len, null)).toByteArray(),
            )
            if (head) return
            val buf = ByteArray(64 * 1024)
            var pos = from
            while (pos <= to) {
                val n = src.read(pos, buf, 0, minOf(buf.size.toLong(), to - pos + 1).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                pos += n
            }
        }
    }

    /** デコードして WAV で送る(CUE シートで分けた曲は、その部分だけ) */
    private fun sendWav(out: OutputStream, e: Entry, range: String?, head: Boolean) {
        val inp = BuiltinCodecs.openInput(app, e.file) ?: return status(out, 404, "Not Found")
        inp.use { src -> writeWav(out, src, e.decode!!, e.item.startMs, e.item.endMs, range, head) }
    }

    /** src を kind の形式としてデコードし、WAV にして out へ書く(HTTP の応答の頭も書く) */
    internal fun writeWav(out: OutputStream, src: RandomInput, kind: String, startMs: Long, endMs: Long, range: String?, head: Boolean) {
        run {
            val d: PcmDecoder = if (kind == "wav") WavDecoder(src) else BuiltinCodecs.create(kind, src)
            d.open()
            val rate = d.sampleRate
            val ch = d.channels.coerceAtMost(2)
            // 96 kHz を超えるときは、2 で割れるあいだ半分にする(Chromecast が再生できる周波数にする)
            var div = 1
            while (rate / div > 96000 && (rate / div) % 2 == 0) div *= 2
            val outRate = rate / div
            val outBits = if (d.bitsPerSample <= 16) 16 else 24
            val bytesPer = outBits / 8
            val frameBytes = ch * bytesPer
            val start = startMs * rate / 1000
            val end = if (endMs > 0) minOf(endMs * rate / 1000, d.totalSamples) else d.totalSamples
            val outFrames = ((end - start).coerceAtLeast(0)) / div
            val dataLen = outFrames * frameBytes
            val total = 44 + dataLen
            val r = parseRange(range, total)
            val from = r?.first ?: 0L
            val to = r?.last ?: (total - 1)
            if (from > to || from >= total) return status(out, 416, "Range Not Satisfiable")
            out.write(
                (if (r != null) header(206, "Partial Content", "audio/wav", to - from + 1, "bytes $from-$to/$total")
                else header(200, "OK", "audio/wav", to - from + 1, null)).toByteArray(),
            )
            if (head) return
            var pos = from
            // WAV のヘッダー(44 バイト)
            if (pos < 44) {
                val h = wavHeader(outRate, ch, outBits, dataLen)
                val until = minOf(to, 43L)
                out.write(h, pos.toInt(), (until - pos + 1).toInt())
                pos = until + 1
            }
            if (pos > to) return
            // 音声: 何フレーム目から送るか(途中のバイトから始まるときは、そのフレームの途中を捨てる)
            val firstOut = (pos - 44) / frameBytes
            var skipBytes = ((pos - 44) % frameBytes).toInt()
            var remaining = to - pos + 1
            d.seekSample(start + firstOut * div)
            var next = start + firstOut * div
            val buf = ByteArray(64 * 1024)
            var o = 0
            loop@ while (remaining > 0 && d.decodeNext()) {
                val blk = d.frameSample
                val shift = 32 - d.bitsPerSample
                for (i in 0 until d.blockSize) {
                    val sample = blk + i
                    if (sample < next) continue
                    if (sample >= end) break@loop
                    if ((sample - start) % div != 0L) continue
                    for (c in 0 until ch) {
                        // 32bit(上位詰め)にそろえてから、出力のビット数に切る(半分にするときは隣と平均する)
                        var v = d.samples[c][i].toLong() shl shift
                        if (div > 1) {
                            var sum = 0L
                            var n = 0
                            for (k in 0 until div) {
                                val j = i + k
                                if (j < d.blockSize) { sum += d.samples[c][j].toLong() shl shift; n++ }
                            }
                            v = sum / n
                        }
                        val s32 = v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                        for (k in 0 until bytesPer) {
                            val byte = (s32 ushr (32 - outBits + 8 * k)).toByte()
                            if (skipBytes > 0) { skipBytes--; continue }
                            buf[o++] = byte
                            if (--remaining <= 0L) {
                                out.write(buf, 0, o)
                                return
                            }
                            if (o == buf.size) { out.write(buf, 0, o); o = 0 }
                        }
                    }
                }
                next = blk + d.blockSize
            }
            if (o > 0) out.write(buf, 0, o)
        }
    }

    private fun wavHeader(rate: Int, ch: Int, bits: Int, dataLen: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val riff = (36 + dataLen).coerceAtMost(0xFFFFFFFFL)
        b.put("RIFF".toByteArray()).putInt(riff.toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort()).putInt(rate)
            .putInt(rate * ch * bits / 8).putShort((ch * bits / 8).toShort()).putShort(bits.toShort())
        b.put("data".toByteArray()).putInt(dataLen.coerceAtMost(0xFFFFFFFFL).toInt())
        return b.array()
    }
}

/** WAV(整数 PCM と 32bit float)を読む。CUE シートで分けた WAV の曲を Chromecast に送るときに使う */
internal class WavDecoder(private val src: RandomInput) : PcmDecoder {
    override var sampleRate = 0; private set
    override var channels = 0; private set
    override var bitsPerSample = 0; private set
    override var totalSamples = 0L; private set
    override var samples: Array<IntArray> = emptyArray(); private set
    override var blockSize = 0; private set
    override var frameSample = 0L; private set
    private var dataStart = 0L
    private var bytes = 0
    private var float = false
    private var next = 0L
    private var buf = ByteArray(0)
    private val block = 4096

    private fun readAt(pos: Long, n: Int): ByteArray {
        val b = ByteArray(n)
        var o = 0
        while (o < n) {
            val r = src.read(pos + o, b, o, n - o)
            if (r <= 0) throw IOException("WAV: unexpected end")
            o += r
        }
        return b
    }

    private fun le32(b: ByteArray, o: Int) = (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
        ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    override fun open() {
        val h = readAt(0, 12)
        if (String(h, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(h, 8, 4, Charsets.ISO_8859_1) != "WAVE") throw IOException("not WAV")
        var pos = 12L
        var dataLen = -1L
        while (pos + 8 <= src.size) {
            val c = readAt(pos, 8)
            val id = String(c, 0, 4, Charsets.ISO_8859_1)
            val size = le32(c, 4)
            when (id) {
                "fmt " -> {
                    val f = readAt(pos + 8, minOf(size, 40L).toInt())
                    var tag = le16(f, 0)
                    channels = le16(f, 2)
                    sampleRate = le32(f, 4).toInt()
                    bitsPerSample = le16(f, 14)
                    if (tag == 0xFFFE && f.size >= 26) tag = le16(f, 24)
                    float = tag == 3
                    if (tag != 1 && tag != 3) throw IOException("WAV: unsupported format $tag")
                }
                "data" -> { dataStart = pos + 8; dataLen = size; break }
            }
            pos += 8 + size + (size and 1)
        }
        if (dataStart == 0L || channels <= 0 || sampleRate <= 0) throw IOException("WAV: bad header")
        bytes = (bitsPerSample + 7) / 8
        val avail = src.size - dataStart
        val len = if (dataLen <= 0 || dataLen > avail) avail else dataLen
        totalSamples = len / (bytes * channels)
        if (float) bitsPerSample = 24
    }

    override fun decodeNext(): Boolean {
        val n = minOf(block.toLong(), totalSamples - next).toInt()
        if (n <= 0) return false
        val fb = bytes * channels
        if (buf.size < n * fb) buf = ByteArray(n * fb)
        var o = 0
        while (o < n * fb) {
            val r = src.read(dataStart + next * fb + o, buf, o, n * fb - o)
            if (r <= 0) break
            o += r
        }
        val got = o / fb
        if (got <= 0) return false
        if (samples.size != channels || samples[0].size < block) samples = Array(channels) { IntArray(block) }
        var p = 0
        for (i in 0 until got) for (c in 0 until channels) {
            var v = 0
            for (k in bytes - 1 downTo 0) v = (v shl 8) or (buf[p + k].toInt() and 0xFF)
            p += bytes
            samples[c][i] = when {
                float -> (java.lang.Float.intBitsToFloat(v).coerceIn(-1f, 1f) * 8388607f).toInt()
                bytes == 1 -> (v and 0xFF) - 128
                else -> (v shl (32 - bytes * 8)) shr (32 - bytes * 8)
            }
        }
        frameSample = next
        blockSize = got
        next += got
        return true
    }

    override fun seekSample(target: Long) {
        next = target.coerceIn(0L, totalSamples)
    }
}
