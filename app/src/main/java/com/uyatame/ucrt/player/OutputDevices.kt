package com.uyatame.ucrt.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.uyatame.ucrt.T
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

    fun keyOf(d: AudioDeviceInfo) = "${d.type}|${d.address}"

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
        val rate = d.sampleRates.maxOrNull() ?: 0
        val bits = d.encodings.maxOfOrNull {
            when (it) {
                AFormat.ENCODING_PCM_16BIT -> 16
                AFormat.ENCODING_PCM_24BIT_PACKED -> 24
                AFormat.ENCODING_PCM_32BIT -> 32
                else -> 0
            }
        } ?: 0
        if (rate <= 0) return ""
        return T("最大 ", "Up to ") + (if (bits > 0) "${bits}bit / " else "") + khz(rate)
    }

    fun describe(d: AudioDeviceInfo): OutputDevice {
        val kind = kindOf(d.type)
        val product = d.productName?.toString()?.trim().orEmpty()
        val (name, detail) = when (kind) {
            OutputKind.Speaker -> Build.MODEL to T("本体スピーカー", "Built-in speaker")
            OutputKind.Wired -> T("有線イヤホン", "Wired headphones") to T("イヤホンジャック", "Headphone jack")
            OutputKind.Usb -> product.ifEmpty { "USB" } to T("USB オーディオ(Android の出力)", "USB audio (via Android)")
            OutputKind.Bluetooth -> product.ifEmpty { "Bluetooth" } to "Bluetooth"
            else -> product.ifEmpty { T("その他の出力", "Other output") } to (if (d.type == AudioDeviceInfo.TYPE_HDMI) "HDMI" else "")
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
                T("USB DAC · ビットパーフェクト", "USB DAC · Bit-perfect"), UsbDac.maxSpec(),
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
    fun defaultDevice(ctx: Context): OutputDevice? {
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
            ?.let { describe(it) }
    }
}
