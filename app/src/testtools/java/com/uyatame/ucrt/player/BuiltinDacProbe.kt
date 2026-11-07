package com.uyatame.ucrt.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S

/**
 * 内蔵 DAC(DAP のイヤホンジャックなど。Android の出力先として見えるもの)が、どんな形式を使えるかを調べる。
 *
 * Android の標準の仕組みで分かること:
 * - 出力先が Android に伝えている形式・周波数(Android 6.0 から。12 からは形式ごとの周波数)
 * - 直接再生(ミキサーを通さずに出力先へ渡す)できる形式(Android 13 から。AudioManager.getDirectPlaybackSupport)
 * - ミキサーの形式を指定してビットパーフェクトにできるか(Android 14 から。AudioMixerAttributes)
 * DSD をそのまま渡す公式の方法は無い(AudioFormat.ENCODING_DSD は Android 15 から)ので、
 * DoP(DSD を PCM に載せて送る)が通るかは、直接再生で 24bit の PCM が通るかが目安になる(実際に通るかは機種しだい)。
 */
object BuiltinDacProbe {
    /** 調べる周波数(DoP の元になる 176.4 / 352.8 kHz も含める) */
    private val RATES = intArrayOf(44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000)

    /** 形式の候補(名前, Android の値)。DSD は Android 15 から(31) */
    private val FORMATS = listOf(
        "16bit" to AudioFormat.ENCODING_PCM_16BIT,
        "24bit" to AudioFormat.ENCODING_PCM_24BIT_PACKED,
        "32bit" to AudioFormat.ENCODING_PCM_32BIT,
        "float" to AudioFormat.ENCODING_PCM_FLOAT,
        "DSD" to 31,
    )

    /** 1 つの出力先を調べた結果 */
    class Result(
        val name: String,
        val type: Int,
        /** 画面とログに出す文章(1 行ずつ) */
        val lines: List<String>,
        /** 内蔵 DAC に DoP(24bit PCM の 176.4 kHz 以上)を直接渡せそうか(DSD64 だけでも true) */
        val dopLikely: Boolean,
        /** DoP を直接渡せそうな DSD(DSD64 = 176.4 kHz、DSD128 = 352.8 kHz、DSD256 = 705.6 kHz。DoP の PCM は DSD の周波数の 1/16) */
        val dopRates: List<String>,
        /** 内蔵 DAC に DSD(ネイティブ)を直接渡せると申告しているか */
        val nativeDsd: Boolean,
    )

    /** 内蔵 DAC として調べる出力先の種類(有線のイヤホン・ライン出力) */
    private val builtin = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_LINE_DIGITAL,
    )

    /** 調べる(出力先ごとの結果。内蔵 DAC のものが無ければ空) */
    fun probe(ctx: Context): List<Result> {
        if (Build.VERSION.SDK_INT < 23) return emptyList()
        val am = androidx.core.content.ContextCompat.getSystemService(ctx, AudioManager::class.java) ?: return emptyList()
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in builtin }
            .map { probeOne(am, it) }
    }

    private fun probeOne(am: AudioManager, d: AudioDeviceInfo): Result {
        val lines = ArrayList<String>()
        val name = d.productName?.toString()?.trim().orEmpty().ifEmpty { typeName(d.type) }
        lines += "$name (${typeName(d.type)}, id ${d.id}" + (if (Build.VERSION.SDK_INT >= 28) ", ${d.address}" else "") + ")"
        // 1) 出力先が Android に伝えている形式と周波数
        lines += "  " + S(R.string.probe_reported) + ": " + OutputDevices.formatsText(d)
        // 2) 直接再生(Android 13 から)
        var dopLikely = false
        var nativeDsd = false
        val dopRates = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            for ((fname, enc) in FORMATS) {
                val ok = ArrayList<String>()
                for (r in RATES) {
                    val f = runCatching {
                        AudioFormat.Builder().setSampleRate(r).setEncoding(enc).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
                    }.getOrNull() ?: continue
                    val support = runCatching { AudioManager.getDirectPlaybackSupport(f, attrs) }.getOrDefault(0)
                    if (support != 0) ok += OutputDevices.khz(r)
                }
                lines += "  " + S(R.string.probe_direct) + " $fname: " + if (ok.isEmpty()) S(R.string.probe_none) else ok.joinToString(" ")
                if (fname == "24bit") {
                    // DoP は、DSD の周波数の 1/16 の PCM に載せて送る。その周波数の 24bit を直接渡せる DSD だけが通る可能性がある
                    for ((dname, pcm) in listOf("DSD64" to "176.4kHz", "DSD128" to "352.8kHz", "DSD256" to "705.6kHz")) {
                        if (pcm in ok) dopRates += dname
                    }
                    dopLikely = dopRates.isNotEmpty()
                }
                if (fname == "DSD" && ok.isNotEmpty()) nativeDsd = true
            }
        } else {
            lines += "  " + S(R.string.probe_direct_needs_13)
        }
        // 3) ミキサーの形式の指定(Android 14 から)
        if (Build.VERSION.SDK_INT >= 34) {
            val mixers = runCatching { am.getSupportedMixerAttributes(d) }.getOrDefault(emptyList())
            if (mixers.isEmpty()) lines += "  " + S(R.string.probe_mixer) + ": " + S(R.string.probe_none)
            for (m in mixers) {
                val f = m.format
                lines += "  " + S(R.string.probe_mixer) + ": " + AudioEngine.encName(f.encoding) + " " + OutputDevices.khz(f.sampleRate) +
                    " mask=${f.channelMask}" + (if (m.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) " " + S(R.string.probe_bit_perfect) else "")
                if (m.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    f.encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED && f.sampleRate >= 176400
                ) {
                    dopLikely = true
                    val n = when (f.sampleRate) { 176400 -> "DSD64"; 352800 -> "DSD128"; 705600 -> "DSD256"; else -> null }
                    if (n != null && n !in dopRates) dopRates += n
                }
                if (f.encoding == 31) nativeDsd = true
            }
        }
        return Result(name, d.type, lines, dopLikely, dopRates, nativeDsd)
    }

    private fun typeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX_LINE"
        else -> "type $type"
    }

    /** 画面とログに出す、まとめた文章 */
    fun report(ctx: Context): String {
        val r = probe(ctx)
        val head = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"
        if (r.isEmpty()) return head + "\n" + S(R.string.probe_no_builtin)
        return head + "\n" + r.joinToString("\n") { res ->
            res.lines.joinToString("\n") + "\n  → " + when {
                res.nativeDsd -> S(R.string.probe_result_native)
                // DoP は DSD の種類ごとに必要な周波数が違うので、通りそうな種類を書く(それ以外の DSD は PCM に変換する)
                res.dopLikely -> S(R.string.probe_result_dop, res.dopRates.joinToString(" / "))
                else -> S(R.string.probe_result_pcm)
            }
        }
    }
}

/**
 * 内蔵 DAC に、ミキサーを通さずに DoP(24bit PCM に載せた DSD)を送るための出力(AudioTrack)を作る。
 *
 * ミキサーを通ると、音量の処理・リサンプル・ミックスで DoP の目印が壊れて雑音になる。そのため、
 * 直接再生(Android 13 から)で 24bit PCM が通ると申告しているときだけ作る。作れなければ null(PCM に変換して鳴らす)。
 * Android 14 からは、ミキサーの形式をビットパーフェクトにする指定(AudioMixerAttributes)も試す。
 */
internal object BuiltinDop {
    /** その周波数の 24bit PCM を、内蔵 DAC に直接渡せるか(診断と同じ確かめ方) */
    fun canTry(ctx: Context, rate: Int): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val f = runCatching {
            AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        }.getOrNull() ?: return false
        return runCatching { AudioManager.getDirectPlaybackSupport(f, attrs) != 0 }.getOrDefault(false)
    }
}
