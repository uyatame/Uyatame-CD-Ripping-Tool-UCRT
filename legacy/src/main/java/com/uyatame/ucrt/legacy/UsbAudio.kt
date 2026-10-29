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
        com.uyatame.ucrt.legacy.usb.UsbStabilizer.enabled = p.usbStabilize
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

    /**
     * USB DAC の接続を解除する(抜き差ししたときに近い動作。本家と同じ)。一時停止して Android に返したうえで、DAC を USB でリセットする。
     * sync: 終わるまで待つ(アプリの終了。メイン以外のスレッドから)
     */
    fun disconnect(sync: Boolean = false) {
        if (!sync) {
            // 一時停止して出力を手放す(次に再生を始めたら、また直接出力で使う)
            app.player.releaseOutput()
            LegacyActivity.current?.snack(S(R.string.usb_dac_disconnecting))
        }
        val r = Runnable { UsbDac.disconnect() }
        if (sync) r.run() else Thread(r, "usb-dac-disconnect").start()
    }

    /** 使用の許可をもう一度求める */
    fun requestPermission() {
        LegacyActivity.current?.snack(when (UsbDac.requestPermissionAgain()) {
            0 -> S(R.string.usb_dac_not_connected)
            1 -> S(R.string.usb_permission_already)
            else -> S(R.string.usb_permission_requested)
        })
    }

    /** 設定のパネル(本家の BitPerfectPanel) */
    fun panel(act: LegacyActivity): View {
        val p = app.prefs
        val col = vbox(act)
        val sec = Section(act, S(R.string.usb_dac_direct_output))
        sec.add(switchRow(act, S(R.string.use_usb_dac_direct_output), S(R.string.controls_usb_dac_directly_with), p.usbDirect) { setDirect(act, it) })
        if (p.usbDirect) {
            // 使用の許可をもう一度求める(許可の画面が出なかった・断ってしまったとき)
            val row = com.uyatame.ucrt.legacy.ui.hbox(act)
            row.add(button(act, S(R.string.usb_request_permission), Btn.Outlined) { requestPermission() }, w = WRAP)
            sec.add(row)
            sec.add(note(act, S(R.string.usb_request_permission_desc)))
        }
        // 抜き差ししたときに近い動作で、DAC の接続を解除する
        if (UsbDac.findDac() != null) {
            val row = com.uyatame.ucrt.legacy.ui.hbox(act)
            row.add(button(act, S(R.string.disconnect_usb_dac), Btn.Outlined) { disconnect() }, w = WRAP)
            sec.add(row)
            sec.add(note(act, S(R.string.disconnect_usb_dac_desc)))
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
                val stab = com.uyatame.ucrt.legacy.usb.UsbStabilizer
                stab.enabled = it
                // 転送している間に切り替えたら、掛け直す(オフでも CPU を起こしておくのは続ける)
                stab.stop()
                if (UsbDac.streaming) stab.start(act)
            })
            if (inUse && hasVolume) {
                val steps = listOf(1, 2, 4)
                sec.add(segmented(act, S(R.string.usb_volume_step), steps.map { "$it" }, steps.indexOf(p.sp.getInt("usbVolumeStep", 4)).coerceAtLeast(0)) {
                    p.set("usbVolumeStep", steps[it])
                })
                sec.add(note(act, S(R.string.usb_volume_step_desc)))
            }
            sec.add(note(act, S(R.string.for_details_see_settings_log)))
        }
        sec.add(note(act, S(R.string.legacy_usb_dac_note)))
        col.add(sec.view)
        return col
    }
}
