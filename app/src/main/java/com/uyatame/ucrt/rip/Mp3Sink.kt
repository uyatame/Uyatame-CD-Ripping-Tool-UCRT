package com.uyatame.ucrt.rip

import com.uyatame.ucrt.T

import com.uyatame.ucrt.meta.Cover
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Method

class Id3Tags(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val year: String,
    val track: Int,
    val trackTotal: Int,
    val disc: Int,
    val discTotal: Int,
)

/** ID3v2.3 タグ(UTF-16で日本語対応、ジャケットはAPIC)を作る */
object Id3v2 {
    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    fun build(t: Id3Tags, cover: Cover?, rawApic: List<ByteArray> = emptyList()): ByteArray {
        val frames = ByteArrayOutputStream()
        fun frame(id: String, body: ByteArray) {
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            frames.write(be32(body.size))
            frames.write(byteArrayOf(0, 0))
            frames.write(body)
        }
        fun text(id: String, v: String) {
            if (v.isEmpty()) return
            frame(
                id,
                byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + v.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0),
            )
        }
        text("TIT2", t.title)
        text("TPE1", t.artist)
        text("TALB", t.album)
        text("TPE2", t.albumArtist)
        text("TYER", t.year.take(4))
        text("TRCK", "${t.track}/${t.trackTotal}")
        text("TPOS", "${t.disc}/${t.discTotal}")
        if (cover != null) {
            frame(
                "APIC",
                byteArrayOf(0) + cover.mime.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + cover.data,
            )
        }
        rawApic.forEach { frame("APIC", it) }
        val body = frames.toByteArray()
        val s = body.size
        val head = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((s shr 21) and 0x7F).toByte(), ((s shr 14) and 0x7F).toByte(),
            ((s shr 7) and 0x7F).toByte(), (s and 0x7F).toByte(),
        )
        return head + body
    }
}

/**
 * MP3書き出し。エンコーダは jump3r(LAME 3.98.4 のJava移植版、LGPL v2.1+)を
 * 改変せずライブラリとして利用する。APIの差異に備えてリフレクションで呼び出す。
 */
class Mp3Sink(
    private val out: OutputStream,
    kbps: Int,
    quality: Int,
    tags: Id3Tags,
    cover: Cover?,
) : AudioSink {
    private val enc: Any
    private val encodeBuffer: Method
    private val encodeFinish: Method?
    private val closeM: Method?
    private val pcm: ByteArray
    private val outBuf: ByteArray
    private var fill = 0
    var bitrateApplied = true
        private set

    init {
        out.write(Id3v2.build(tags, cover))
        try {
            // javax.sound.sampled.AudioFormat(アプリ同梱の互換クラス)をリフレクションで生成
            val fmtCls = Class.forName("javax.sound.sampled.AudioFormat")
            val fmt = fmtCls.getConstructor(
                Float::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            ).newInstance(44100f, 16, 2, true, false)
            val cls = Class.forName("de.sciss.jump3r.lowlevel.LameEncoder")
            val full: Constructor<*>? = cls.constructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 5 && p[1] == Int::class.javaPrimitiveType && p[2] == Int::class.javaPrimitiveType &&
                    p[3] == Int::class.javaPrimitiveType && p[4] == Boolean::class.javaPrimitiveType
            }
            enc = if (full != null) {
                val mode = staticInt(cls, "CHANNEL_MODE_JOINT_STEREO") ?: staticInt(cls, "CHANNEL_MODE_AUTO") ?: -1
                full.newInstance(fmt, kbps, mode, quality.coerceIn(0, 9), false)
            } else {
                bitrateApplied = false
                cls.getConstructor(fmtCls).newInstance(fmt)
            }
            encodeBuffer = cls.getMethod(
                "encodeBuffer", ByteArray::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, ByteArray::class.java,
            )
            encodeFinish = runCatching { cls.getMethod("encodeFinish", ByteArray::class.java) }.getOrNull()
            closeM = runCatching { cls.getMethod("close") }.getOrNull()
            val size = (cls.getMethod("getPCMBufferSize").invoke(enc) as Int).coerceAtLeast(4608)
            pcm = ByteArray(size - size % 4)
            outBuf = ByteArray(size * 2 + 8192)
        } catch (e: Throwable) {
            throw IOException(T("MP3エンコーダを初期化できません: ", "Cannot initialize MP3 encoder: ") + "${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    private fun staticInt(cls: Class<*>, name: String): Int? = runCatching { cls.getField(name).getInt(null) }.getOrNull()

    private fun flushPcm() {
        if (fill == 0) return
        val n = try {
            encodeBuffer.invoke(enc, pcm, 0, fill, outBuf) as Int
        } catch (e: Throwable) {
            throw IOException(T("MP3エンコード中にエラー: ${e.cause?.message ?: e.message}", "MP3 encoding error: ${e.cause?.message ?: e.message}"))
        }
        if (n > 0) out.write(outBuf, 0, n)
        fill = 0
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        var o = off
        var left = len
        while (left > 0) {
            val n = minOf(left, pcm.size - fill)
            System.arraycopy(buf, o, pcm, fill, n)
            fill += n
            o += n
            left -= n
            if (fill == pcm.size) flushPcm()
        }
    }

    override fun finish() {
        flushPcm()
        val ef = encodeFinish
        if (ef != null) {
            val n = runCatching { ef.invoke(enc, outBuf) as Int }.getOrDefault(0)
            if (n > 0) out.write(outBuf, 0, n)
        }
        runCatching { closeM?.invoke(enc) }
        out.flush()
    }

    override fun abort() {
        runCatching { closeM?.invoke(enc) }
    }
}
