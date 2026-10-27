package com.uyatame.ucrt.legacy

import android.content.Context
import android.media.AudioManager
import android.view.View
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.ui.Icons
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.Sheet
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.bgd
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.icon
import com.uyatame.ucrt.legacy.ui.rounded
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox

/**
 * 出力先(本家の OutputDevices)。Android 6.0 より前は出力先の一覧を取れないので、
 * イヤホン・Bluetooth・USB DAC がつながっているかから推測する
 */
object Output {
    enum class Kind { Speaker, Wired, Bluetooth, Usb }

    @Suppress("DEPRECATION")
    fun kind(ctx: Context): Kind {
        if (UsbAudio.inUse) return Kind.Usb
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when {
            am.isBluetoothA2dpOn -> Kind.Bluetooth
            am.isWiredHeadsetOn -> Kind.Wired
            else -> Kind.Speaker
        }
    }

    fun name(ctx: Context): String = when (kind(ctx)) {
        Kind.Usb -> UsbAudio.name?.let { S(R.string.output_usb_named, it) } ?: S(R.string.output_usb)
        Kind.Bluetooth -> S(R.string.output_bluetooth)
        Kind.Wired -> S(R.string.output_wired)
        Kind.Speaker -> S(R.string.output_speaker)
    }

    fun icon(ctx: Context): String = when (kind(ctx)) {
        Kind.Usb -> Icons.Usb
        Kind.Bluetooth -> Icons.Bluetooth
        Kind.Wired -> Icons.Headphones
        Kind.Speaker -> Icons.Smartphone
    }

    /** 出力先のシート(本家の OutputDeviceSheet。古い Android では今の出力先を見せる) */
    fun sheet(act: LegacyActivity) {
        val s = Sheet(act)
        val col = vbox(act)
        col.setPadding(dp(16), 0, dp(16), dp(24))
        col.add(text(act, S(R.string.current_output), Type.LabelLarge, Pal.onSurfaceVariant), b = 8)
        val card = hbox(act)
        card.bgd(rounded(Pal.secondaryContainer, 16f))
        card.setPadding(dp(16), dp(16), dp(16), dp(16))
        card.add(icon(act, icon(act), Pal.onSecondaryContainer), w = 24, h = 24, r = 16)
        val c = vbox(act)
        c.add(text(act, name(act), Type.TitleMedium, Pal.onSecondaryContainer))
        val spec = LegacyApp.app.player.formatText
        if (spec.isNotEmpty()) c.add(text(act, spec, Type.BodySmall, Pal.onSecondaryContainer, lines = 2))
        card.add(c, w = 0, weight = 1f)
        col.add(card)
        col.add(text(act, S(R.string.legacy_output_auto), Type.BodySmall, Pal.onSurfaceVariant, lines = 0), t = 12)
        if (UsbAudio.supported(act)) {
            col.add(com.uyatame.ucrt.legacy.ui.button(act, S(R.string.legacy_usb_dac_settings), com.uyatame.ucrt.legacy.ui.Btn.Tonal, Icons.Usb) {
                s.dismiss(); act.closePlayer(); act.openSettings(8)
            }, w = WRAP, t = 12)
        }
        s.add(col)
        s.show()
    }
}
