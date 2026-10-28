package com.uyatame.ucrt.player

import android.content.Context
import android.net.Uri
import com.uyatame.ucrt.player.aac.AacPcmDecoder
import com.uyatame.ucrt.player.dsd.DsdFile
import com.uyatame.ucrt.player.dsd.DsdPcmDecoder
import com.uyatame.ucrt.player.dsd.RandomFile
import com.uyatame.ucrt.player.mp3.Mp3PcmDecoder
import com.uyatame.ucrt.player.vorbis.VorbisPcmDecoder
import java.io.Closeable
import java.io.IOException

/**
 * どの形式の曲でも、アプリ内蔵のデコーダーで開く(FLAC・ALAC・AAC・MP3・Vorbis・WavPack・APE・AIFF・WAV・DSD)。
 * OS のデコーダーを使わないので、古い Android(4.0〜)でも同じように再生できる
 */
internal object Decoders {
    /** 開いた曲(使い終わったら close) */
    class Opened(val decoder: PcmDecoder, private val res: Closeable) : Closeable {
        override fun close() {
            runCatching { res.close() }
        }
    }

    /** 拡張子から形式。分からなければ null */
    fun kindOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "flac" -> "flac"
        "aif", "aiff", "aifc" -> "aiff"
        "wv" -> "wavpack"
        "ape" -> "ape"
        "wav", "wave" -> "wav"
        "mp3", "mp2", "mpga" -> "mp3"
        "m4a", "mp4", "m4b", "3gp" -> "mp4"
        "aac", "adts" -> "aac"
        "ogg", "oga" -> "ogg"
        "dsf", "dff" -> "dsd"
        else -> null
    }

    /** 中身(先頭のバイト)から形式。分からなければ null */
    fun sniff(src: RandomInput): String? {
        val b = ByteArray(12)
        var n = 0
        while (n < b.size) {
            val r = src.read(n.toLong(), b, n, b.size - n)
            if (r <= 0) break
            n += r
        }
        if (n < 4) return null
        fun at(o: Int, s: String) = o + s.length <= n && s.indices.all { b[o + it] == s[it].code.toByte() }
        return when {
            at(0, "fLaC") -> "flac"
            at(0, "FORM") && (at(8, "AIFF") || at(8, "AIFC")) -> "aiff"
            at(0, "FRM8") || at(0, "DSD ") -> "dsd"
            at(0, "wvpk") -> "wavpack"
            at(0, "MAC ") -> "ape"
            (at(0, "RIFF") || at(0, "RF64")) && at(8, "WAVE") -> "wav"
            at(0, "OggS") -> "ogg"
            at(4, "ftyp") -> "mp4"
            // ID3 が先頭にあるのは MP3 が多い(FLAC のこともあるので、MP3 で開けなければ FLAC を試す)
            at(0, "ID3") -> "mp3"
            (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xF6) == 0xF0 -> "aac"
            (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            else -> null
        }
    }

    /** 形式に合ったデコーダー(まだ open() していない) */
    fun create(kind: String, src: RandomInput): PcmDecoder = when (kind) {
        "flac", "aiff", "wavpack", "ape" -> BuiltinCodecs.create(kind, src)
        "wav" -> WavDecoder(src)
        "mp3" -> Mp3PcmDecoder(src)
        "aac" -> AacPcmDecoder(src)
        "ogg" -> VorbisPcmDecoder(src)
        "mp4" -> if (Mp4Probe.audioEntry(src) == "alac") AlacDecoder(src) else AacPcmDecoder(src)
        else -> throw IOException("unknown kind $kind")
    }

    /**
     * 曲を開いて、デコーダーを open() する。読めなければ IOException。
     * name は表示名(拡張子で形式を決める。分からなければ中身から)
     */
    fun open(ctx: Context, uri: Uri, name: String, hq: Boolean = false): Opened {
        var kind = kindOf(name)
        if (kind == "dsd" || (kind == null && DsdFile.isDsd(name))) return openDsd(ctx, uri, hq)
        val input = BuiltinCodecs.openInput(ctx, uri) ?: throw IOException("cannot open $uri")
        try {
            if (kind == null) kind = sniff(input)
            if (kind == "dsd") {
                input.close()
                return openDsd(ctx, uri, hq)
            }
            if (kind == null) throw IOException("unknown format: $name")
            val tried = if (kind == "mp3") listOf("mp3", "flac") else listOf(kind)
            var last: Exception? = null
            for (k in tried) {
                try {
                    val d = create(k, input)
                    d.open()
                    return Opened(d, input)
                } catch (e: Exception) {
                    last = e
                }
            }
            throw (last as? IOException) ?: IOException(last?.message ?: "cannot decode $name", last)
        } catch (e: Exception) {
            input.close()
            throw e
        }
    }

    /** hq: ファイルの変換用の、雑音の少ない DSD → PCM(計算は重い) */
    private fun openDsd(ctx: Context, uri: Uri, hq: Boolean = false): Opened {
        val f = RandomFile.open(ctx, uri) ?: throw IOException("cannot open $uri")
        try {
            val d = DsdPcmDecoder(f, hq)
            d.open()
            return Opened(d, f)
        } catch (e: Exception) {
            f.close()
            throw e
        }
    }
}
