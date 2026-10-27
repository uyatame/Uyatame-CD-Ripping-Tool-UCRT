package com.uyatame.ucrt.data

import com.uyatame.ucrt.R
import com.uyatame.ucrt.S

// 取り込みの形式と音質のおすすめ(app と legacy で共通)

enum class AudioFormat(val label: String, val ext: String, val mime: String) {
    WAV("WAV", "wav", "audio/x-wav"),
    FLAC("FLAC", "flac", "audio/flac"),
    AAC("AAC", "m4a", "audio/mp4"),
    MP3("MP3", "mp3", "audio/mpeg"),
}

val MP3_RATES = listOf(128, 160, 192, 224, 256, 320)

class QualityPreset(
    val title: String,
    val desc: String,
    val format: AudioFormat,
    val flacLevel: Int,
    val aacKbps: Int,
    val mp3Kbps: Int = 320,
)

val QUALITY_PRESETS: List<QualityPreset> get() = listOf(
    QualityPreset(S(R.string.lossless_flac), S(R.string.cd_quality_at_about_half), AudioFormat.FLAC, 5, 256),
    QualityPreset(S(R.string.high_quality_aac_320_kbps), S(R.string.nearly_indistinguishable_from_cd_about), AudioFormat.AAC, 5, 320),
    QualityPreset(S(R.string.standard_aac_256_kbps), S(R.string.balanced_quality_and_size_about), AudioFormat.AAC, 5, 256),
    QualityPreset(S(R.string.most_compatible_mp3_320_kbps), S(R.string.plays_on_older_players_and), AudioFormat.MP3, 5, 256, 320),
    QualityPreset(S(R.string.space_saver_aac_128_kbps), S(R.string.smallest_files_about_4_mb), AudioFormat.AAC, 5, 128),
    QualityPreset(S(R.string.uncompressed_wav), S(R.string.saves_cd_data_as_is), AudioFormat.WAV, 5, 256),
)
