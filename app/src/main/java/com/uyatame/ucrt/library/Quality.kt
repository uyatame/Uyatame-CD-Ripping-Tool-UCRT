package com.uyatame.ucrt.library

import android.net.Uri
import com.uyatame.ucrt.T
import java.util.Locale

/** 音源の品質の区分(ライブラリの絞り込みとバッジの色に使う) */
enum class QualityClass { HiRes, Cd, Lossy }

/** 曲の形式と音質の表示 */
object Quality {
    private val LOSSLESS = setOf("FLAC", "WAV", "ALAC", "AIFF")

    fun ext(uri: String): String {
        // ほとんどの URI は区切りの "/" が "%2F" になっているので、そのときだけ戻す(毎回戻すと遅い)
        val s = if ('%' in uri) Uri.decode(uri) else uri
        return s.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    }

    fun isDsd(t: LibTrack): Boolean = ext(t.uri).let { it == "dsf" || it == "dff" }

    /** コーデック名(FLAC / DSD / AAC など) */
    fun codec(t: LibTrack): String = when (val e = ext(t.uri)) {
        "flac" -> "FLAC"
        "wav" -> "WAV"
        "aif", "aiff" -> "AIFF"
        "dsf", "dff" -> "DSD"
        "mp3" -> "MP3"
        "ogg" -> "Ogg"
        "opus" -> "Opus"
        "aac" -> "AAC"
        // m4a は AAC と ALAC があり、ビット数が分かるのは ALAC だけ
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

    internal fun albumBadge(tracks: List<LibTrack>): String {
        val best = tracks.maxWithOrNull(compareBy<LibTrack>({ -classOf(it).ordinal }, { it.rate }, { it.bits })) ?: return ""
        return badge(best)
    }

    fun label(q: QualityClass): String = when (q) {
        QualityClass.HiRes -> T("ハイレゾ", "Hi-Res")
        QualityClass.Cd -> T("CD音質", "CD quality")
        QualityClass.Lossy -> T("圧縮音源", "Compressed")
    }
}
