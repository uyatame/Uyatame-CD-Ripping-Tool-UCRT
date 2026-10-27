package com.uyatame.ucrt.legacy

import android.view.View
import android.widget.LinearLayout
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.cast.CastManager
import com.uyatame.ucrt.legacy.ui.Icons
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.ProgressBarView
import com.uyatame.ucrt.legacy.ui.Sheet
import com.uyatame.ucrt.legacy.ui.SliderView
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.icon
import com.uyatame.ucrt.legacy.ui.tap
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox

/** キャスト先(Chromecast)を選ぶシート(本家の CastSheet)。開いている間、同じネットワークの機器を探す */
object CastSheet {
    fun show(act: LegacyActivity) {
        val s = Sheet(act, full = true)
        val list = vbox(act)
        list.setPadding(dp(16), 0, dp(16), dp(24))
        s.add(list)
        var sig = ""
        fun build() {
            val cur = CastManager.connected
            val busy = CastManager.connecting
            val devs = CastManager.devices.sortedBy { it.name }
            val k = "${cur?.id}/${busy?.id}/${devs.joinToString { it.id }}"
            if (k == sig) return
            sig = k
            list.removeAllViews()
            list.add(text(act, S(R.string.cast_title), Type.TitleLarge), b = 8)
            list.add(row(act, Icons.Smartphone, S(R.string.cast_this_device), null, cur == null, false) {
                CastManager.disconnect()
                s.dismiss()
            })
            for (d in devs) {
                list.add(row(act, if (cur?.id == d.id) Icons.CastConnected else Icons.Cast, d.name, d.model.ifEmpty { null }, cur?.id == d.id, busy?.id == d.id) {
                    CastManager.connect(d)
                })
            }
            if (devs.isEmpty()) {
                val r = hbox(act)
                r.setPadding(dp(16), dp(16), dp(16), dp(16))
                r.add(ProgressBarView(act, -1f, 3), w = 24, r = 12)
                r.add(text(act, S(R.string.cast_searching), Type.BodyMedium, Pal.onSurface, lines = 0), w = 0, weight = 1f)
                list.add(r)
            }
            if (cur != null) {
                list.add(text(act, S(R.string.cast_volume, cur.name), Type.LabelLarge, Pal.onSurface), t = 12)
                list.add(SliderView(act, CastManager.volume, {}, { v -> CastManager.changeVolume(v) }), h = 48)
            }
            list.add(text(act, S(R.string.cast_note), Type.BodySmall, Pal.onSurfaceVariant, lines = 0), t = 12)
        }
        build()
        val unsub = Bus.on(Bus.PLAYER) { build() }
        // Android 17 から、同じネットワークの機器を探すには「付近のデバイス」の許可が要る
        if (!CastManager.hasLocalNetwork(act)) com.uyatame.ucrt.legacy.compat.Api23Perm.request(act, arrayOf(CastManager.LOCAL_NETWORK))
        CastManager.startDiscovery(act)
        s.setOnDismissListener { unsub(); CastManager.stopDiscovery() }
        s.show()
    }

    private fun row(act: LegacyActivity, d: String, title: String, sub: String?, selected: Boolean, busy: Boolean, onClick: () -> Unit): View {
        val r = hbox(act)
        r.setPadding(dp(12), dp(12), dp(12), dp(12))
        val c = if (selected) Pal.primary else Pal.onSurfaceVariant
        r.add(icon(act, d, c, 24), w = 24, h = 24, r = 16)
        val col: LinearLayout = vbox(act)
        col.add(text(act, title, Type.BodyLarge, if (selected) Pal.primary else Pal.onSurface))
        if (sub != null) col.add(text(act, sub, Type.BodySmall, Pal.onSurfaceVariant))
        r.add(col, w = 0, weight = 1f)
        if (busy) r.add(ProgressBarView(act, -1f, 3), w = 24)
        r.tap(12f) { onClick() }
        return r
    }
}
