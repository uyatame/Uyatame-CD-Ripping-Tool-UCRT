package com.uyatame.ucrt.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.player.usb.UsbDac
import android.media.AudioFormat as AFormat

/** 出力先の種類 */
enum class OutputKind { Speaker, Wired, Usb, Bluetooth, UsbDirect, Other, Auto }

/**
 * 出力先 1 つ分。key は設定に保存する識別子
 * ("usb-direct" = 独自ドライバー, "" = 自動, それ以外は "種類|アドレス")
 */
class OutputDevice(val key: String, val kind: OutputKind, val name: String, val detail: String, val spec: String)

/** 端末の出力先の一覧と、その表示名 */
object OutputDevices {
    const val KEY_AUTO = ""
    const val KEY_USB_DIRECT = "usb-direct"

    private val usable = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )

    fun keyOf(d: AudioDeviceInfo) = "${d.type}|" + (if (Build.VERSION.SDK_INT >= 28) d.address else d.id.toString())

    private fun kindOf(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputKind.Speaker
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> OutputKind.Wired
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputKind.Usb
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID -> OutputKind.Bluetooth
        else -> OutputKind.Other
    }

    fun khz(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000}kHz" else "%.1fkHz".format(java.util.Locale.US, hz / 1000.0)

    /** 「最大 24bit / 192kHz」のような表記(分からなければ空) */
    private fun specOf(d: AudioDeviceInfo): String {
        // Android 12 からは、形式ごとに受け付ける周波数が分かる。いちばん高い周波数を受け付ける形式で表す
        // (例: Xperia のイヤホンジャックは 16bit なら 48kHz まで、float なら 192kHz まで)
        if (Build.VERSION.SDK_INT >= 31) {
            val best = runCatching {
                d.audioProfiles.mapNotNull { p ->
                    val r = p.sampleRates.maxOrNull() ?: return@mapNotNull null
                    val b = bitsOf(p.format)
                    val name = if (p.format == AFormat.ENCODING_PCM_FLOAT) "float" else if (b > 0) "${b}bit" else return@mapNotNull null
                    Triple(r, if (p.format == AFormat.ENCODING_PCM_FLOAT) 33 else b, name)
                }.maxWithOrNull(compareBy({ it.first }, { it.second }))
            }.getOrNull()
            if (best != null) return S(R.string.up_to) + "${best.third} / " + khz(best.first)
        }
        val rate = d.sampleRates.maxOrNull() ?: 0
        val bits = encodingsOf(d).maxOfOrNull { bitsOf(it) } ?: 0
        if (rate <= 0) return ""
        return S(R.string.up_to) + (if (bits > 0) "${bits}bit / " else "") + khz(rate)
    }

    /**
     * PCM の形式のビット数(PCM 以外は 0)。
     * float は Android の中のミキサーが受け取る形式で、出力先のビット数を表さないので数えない
     */
    private fun bitsOf(enc: Int): Int = when (enc) {
        AFormat.ENCODING_PCM_16BIT -> 16
        AFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AFormat.ENCODING_PCM_32BIT -> 32
        else -> 0
    }

    /**
     * 出力先が受け付ける形式。端末によっては encodings に一部しか載らず、
     * Android 12 からの「プロファイル」(形式ごとの周波数の組)のほうに 24bit などが載っていることがあるので、両方を合わせる
     */
    fun encodingsOf(d: AudioDeviceInfo): Set<Int> {
        val s = d.encodings.toMutableSet()
        if (Build.VERSION.SDK_INT >= 31) runCatching { d.audioProfiles.forEach { s.add(it.format) } }
        return s
    }

    /** ログ用: 出力先が Android に伝えている形式と周波数 */
    fun formatsText(d: AudioDeviceInfo): String {
        val enc = d.encodings.joinToString(",") { AudioEngine.encName(it) }
        val prof = if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                d.audioProfiles.joinToString(" ") { p -> AudioEngine.encName(p.format) + "@" + p.sampleRates.joinToString("/") }
            }.getOrDefault("")
        } else ""
        return "encodings=[$enc] rates=[${d.sampleRates.joinToString(",")}]" + if (prof.isNotEmpty()) " profiles=[$prof]" else ""
    }

    fun describe(d: AudioDeviceInfo): OutputDevice {
        val kind = kindOf(d.type)
        val product = d.productName?.toString()?.trim().orEmpty()
        val (name, detail) = when (kind) {
            OutputKind.Speaker -> Build.MODEL to S(R.string.built_in_speaker)
            OutputKind.Wired -> S(R.string.wired_headphones) to S(R.string.headphone_jack)
            OutputKind.Usb -> product.ifEmpty { "USB" } to S(R.string.usb_audio_via_android)
            OutputKind.Bluetooth -> product.ifEmpty { "Bluetooth" } to "Bluetooth"
            else -> product.ifEmpty { S(R.string.other_output) } to (if (d.type == AudioDeviceInfo.TYPE_HDMI) "HDMI" else "")
        }
        return OutputDevice(keyOf(d), kind, name, detail, specOf(d))
    }

    /** 選べる出力先(独自ドライバーの USB DAC を含む) */
    fun list(ctx: Context): List<OutputDevice> {
        val am = ctx.getSystemService(AudioManager::class.java)
        val out = ArrayList<OutputDevice>()
        val dac = UsbDac.findDac()
        if (dac != null) {
            out += OutputDevice(
                KEY_USB_DIRECT, OutputKind.UsbDirect, dac.productName?.trim()?.ifEmpty { null } ?: "USB DAC",
                S(R.string.usb_dac_bit_perfect), UsbDac.maxSpec(),
            )
        }
        val seen = HashSet<String>()
        for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.type !in usable) continue
            // 独自ドライバーで使う DAC は、Android の USB 出力としては出さない
            if (dac != null && kindOf(d.type) == OutputKind.Usb) continue
            if (!seen.add(keyOf(d))) continue
            out += describe(d)
        }
        return out
    }

    /** 設定で選ばれている Android の出力先(無ければ null = 自動) */
    fun find(ctx: Context, key: String): AudioDeviceInfo? {
        if (key.isEmpty() || key == KEY_USB_DIRECT) return null
        val am = ctx.getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { keyOf(it) == key }
    }

    /** 自動のときに Android が使う出力先 */
    fun defaultDevice(ctx: Context): OutputDevice? = defaultDeviceInfo(ctx)?.let { describe(it) }

    /** 自動のときに Android が使う出力先(Android 13 からは Android に聞き、それより前は推測する) */
    fun defaultDeviceInfo(ctx: Context): AudioDeviceInfo? {
        val am = ctx.getSystemService(AudioManager::class.java)
        val d = if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                am.getAudioDevicesForAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(),
                ).firstOrNull()
            }.getOrNull()
        } else null
        return (d ?: am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in usable && it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })
    }
}
