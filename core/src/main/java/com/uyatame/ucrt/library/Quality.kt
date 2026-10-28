package com.uyatame.ucrt.library

import android.net.Uri
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import java.util.Locale

/** 音源の品質の区分(ライブラリの絞り込みとバッジの色に使う) */
enum class QualityClass { HiRes, Cd, Lossy }

/** 曲の形式と音質の表示 */
object Quality {
    private val LOSSLESS = setOf("FLAC", "WAV", "ALAC", "AIFF", "WavPack", "APE")

    fun ext(uri: String): String {
        // ほとんどの URI は区切りの "/" が "%2F" になっているので、そのときだけ戻す(毎回戻すと遅い)
        val u = Cue.fileUri(uri)
        val s = if ('%' in u) Uri.decode(u) else u
        return s.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    }

    fun isDsd(t: LibTrack): Boolean = ext(t.uri).let { it == "dsf" || it == "dff" }

    /** コーデック名(FLAC / DSD / AAC など) */
    fun codec(t: LibTrack): String = if (t.codec.isNotEmpty()) t.codec else when (val e = ext(t.uri)) {
        "flac" -> "FLAC"
        "wav" -> "WAV"
        "aif", "aiff", "aifc" -> "AIFF"
        "wv" -> "WavPack"
        "ape" -> "APE"
        "dsf", "dff" -> "DSD"
        "mp3" -> "MP3"
        "ogg" -> "Ogg"
        "opus" -> "Opus"
        "aac" -> "AAC"
        // m4a は AAC と ALAC がある(読むときに codec に記録する。古い記録はビット数で見分ける)
        "m4a", "mp4" -> if (t.bits in 16..32) "ALAC" else "AAC"
        else -> e.uppercase().ifEmpty { "?" }
    }

    fun classOf(t: LibTrack): QualityClass {
        val c = codec(t)
        return when {
            c == "DSD" -> QualityClass.HiRes
            c in LOSSLESS && (t.rate > 48000 || t.bits > 16) -> QualityClass.HiRes
            c in LOSSLESS -> QualityClass.Cd
            else -> QualityClass.Lossy
        }
    }

    /** アルバム全体の区分(いちばん良い曲に合わせる) */
    fun classOf(a: LibAlbum): QualityClass = a.quality

    internal fun albumClass(tracks: List<LibTrack>): QualityClass =
        tracks.minOf { classOf(it).ordinal }.let { QualityClass.entries[it] }

    fun khz(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000}" else String.format(Locale.US, "%.1f", hz / 1000.0)

    /** 一覧に出す短い表記(例: "FLAC 96/24", "DSD128", "FLAC", "AAC") */
    fun badge(t: LibTrack): String {
        val c = codec(t)
        if (c == "DSD") return if (t.rate > 0 && t.rate % 44100 == 0) "DSD${t.rate / 44100}" else "DSD"
        if (classOf(t) == QualityClass.HiRes && t.rate > 0) {
            return "$c ${khz(t.rate)}" + (if (t.bits > 0) "/${t.bits}" else "")
        }
        return c
    }

    /** アルバムの表記(曲ごとに違えばいちばん良いもの) */
    fun badge(a: LibAlbum): String = a.badge

    internal fun albumBadge(tracks: List<LibTrack>): String = bestTrack(tracks)?.let { badge(it) }.orEmpty()

    /** アルバムの中でいちばん良い曲(区分、周波数、ビット数の順に比べる) */
    internal fun bestTrack(tracks: List<LibTrack>): LibTrack? =
        tracks.maxWithOrNull(compareBy<LibTrack>({ -classOf(it).ordinal }, { it.rate }, { it.bits }))

    /**
     * 区分の中の細かい分類(音質別の 2 段目の絞り込み)。
     * ハイレゾは "DSD256" や "96kHz/24bit"、CD音質と圧縮音源は形式("FLAC"、"AAC" など)
     */
    fun detail(t: LibTrack): String {
        val c = codec(t)
        if (classOf(t) != QualityClass.HiRes) return c
        if (c == "DSD") return badge(t)
        return listOfNotNull(
            t.rate.takeIf { it > 0 }?.let { "${khz(it)}kHz" },
            t.bits.takeIf { it > 0 }?.let { "${it}bit" },
        ).joinToString("/").ifEmpty { c }
    }

    fun detail(a: LibAlbum): String = a.detail

    /** 細かい分類の並び順: ハイレゾは DSD → PCM の、周波数・ビット数の高い順。形式は多い順(呼ぶ側で) */
    fun detailOrder(a: LibAlbum): Long {
        val t = a.best ?: return 0
        val dsd = if (codec(t) == "DSD") 0L else 1L
        return (dsd shl 40) - t.rate.toLong() * 64 - t.bits
    }

    fun label(q: QualityClass): String = when (q) {
        QualityClass.HiRes -> S(R.string.hi_res)
        QualityClass.Cd -> S(R.string.cd_quality)
        QualityClass.Lossy -> S(R.string.compressed)
    }
}
