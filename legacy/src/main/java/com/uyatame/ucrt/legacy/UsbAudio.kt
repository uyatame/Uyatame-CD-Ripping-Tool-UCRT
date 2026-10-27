package com.uyatame.ucrt.legacy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.ui.Btn
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.button
import com.uyatame.ucrt.legacy.ui.vbox
import com.uyatame.ucrt.legacy.usb.UsbDac
import com.uyatame.ucrt.legacy.usb.UsbStabilizer

/**
 * USB DAC への直接出力(本家の BitPerfectPanel・releaseUsbDac)。
 * Android の音声機能を通さず、USB オーディオクラスの DAC を直接動かす(usb/UsbDac)
 */
object UsbAudio {
    private val app get() = LegacyApp.app
    private val main = Handler(Looper.getMainLooper())

    val inUse: Boolean get() = UsbDac.inUse
    val name: String? get() = UsbDac.deviceName()
    val hasVolume: Boolean get() = UsbDac.hwVolume
    val volume: Int get() = UsbDac.volume()

    fun init(ctx: Context) {
        UsbDac.init(ctx)
        val p = app.prefs
        UsbDac.wantDirect = p.usbDirect
        UsbDac.setVolume(p.usbVolume)
        UsbStabilizer.enabled = p.usbStabilize
        app.player.usbDirect = p.usbDirect
        // 許可が得られた・DAC をつないだ/外したときは、今の位置から鳴らし直す(出力先を選び直す)
        UsbDac.onChanged = { main.post { if (app.player.usbDirect) app.player.reopenOutput() } }
        UsbDac.onDetached = { main.post { app.player.reopenOutput() } }
        UsbDac.onTransferTrouble = { msg, _ -> main.post { LegacyActivity.current?.snack(msg); app.player.reopenOutput() } }
        if (p.usbDirect) UsbDac.requestPermissionIfNeeded()
    }

    /** USB DAC がつながっているか */
    fun supported(ctx: Context): Boolean = UsbDac.findDac() != null

    fun setVolume(v: Int, save: Boolean) {
        UsbDac.setVolume(v)
        if (save) app.prefs.set("usbVolume", v)
    }

    fun setDirect(act: LegacyActivity, on: Boolean) {
        app.prefs.set("usbDirect", on)
        UsbDac.wantDirect = on
        app.player.usbDirect = on
        if (on) UsbDac.requestPermissionIfNeeded()
        app.player.reopenOutput()
        if (!on) Thread({ UsbDac.close(reattach = true) }, "usb-dac-release").start()
    }

    /** DAC を Android に返す(ほかのアプリの音を USB DAC から出したいとき)。sync: 閉じ終わるまで待つ(アプリの終了) */
    fun release(sync: Boolean = false) {
        app.player.usbDirect = false
        app.player.reopenOutput()
        val r = Runnable { UsbDac.close(reattach = true) }
        if (sync) r.run() else {
            Thread(r, "usb-dac-release").start()
            LegacyActivity.current?.snack(S(R.string.usb_dac_released))
        }
        // 設定はそのまま(次に再生を始めたら、また直接出力する)
        main.postDelayed({ app.player.usbDirect = app.prefs.usbDirect }, 1500)
    }

    /** 設定のパネル(本家の BitPerfectPanel) */
    fun panel(act: LegacyActivity): View {
        val p = app.prefs
        val col = vbox(act)
        val sec = Section(act, S(R.string.usb_dac_direct_output))
        sec.add(switchRow(act, S(R.string.use_usb_dac_direct_output), S(R.string.controls_usb_dac_directly_with), p.usbDirect) { setDirect(act, it) })
        if (inUse) {
            val row = com.uyatame.ucrt.legacy.ui.hbox(act)
            row.add(button(act, S(R.string.release_usb_dac), Btn.Outlined) { release() }, w = WRAP)
            sec.add(row)
            sec.add(note(act, S(R.string.release_usb_dac_desc)))
        }
        val st = UsbDac.status
        if (p.usbDirect && st.isNotEmpty()) sec.add(note(act, st, Pal.error))
        else if (p.usbDirect && inUse) sec.add(note(act, (name ?: "USB DAC") + "  " + UsbDac.maxSpec(), Pal.primary))
        if (p.usbDirect) {
            val cur = if (p.usbVolume < 0) volume else p.usbVolume
            sec.add(intSlider(act, S(R.string.dac_volume), cur, 0..100, { "$it" }) { setVolume(it, true) })
            sec.add(note(act, if (inUse && !hasVolume) S(R.string.this_dac_doesnt_support_volume) else S(R.string.adjusts_dacs_hardware_volume_directly)))
            sec.add(switchRow(act, S(R.string.usb_stabilize), S(R.string.usb_stabilize_desc), p.usbStabilize) {
                p.set("usbStabilize", it)
                UsbStabilizer.enabled = it
                if (!it) UsbStabilizer.stop()
            })
            sec.add(note(act, S(R.string.for_details_see_settings_log)))
        }
        sec.add(note(act, S(R.string.legacy_usb_dac_note)))
        col.add(sec.view)
        return col
    }
}
