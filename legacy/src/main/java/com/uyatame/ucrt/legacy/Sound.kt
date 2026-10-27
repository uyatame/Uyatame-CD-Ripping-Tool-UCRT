package com.uyatame.ucrt.legacy

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.uyatame.ucrt.EqCustom
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.eqPresetNameOf
import com.uyatame.ucrt.player.EqBand
import com.uyatame.ucrt.player.EqBands
import com.uyatame.ucrt.player.EqType
import com.uyatame.ucrt.player.ParamEq
import com.uyatame.ucrt.player.RgStore
import com.uyatame.ucrt.legacy.ui.Btn
import com.uyatame.ucrt.legacy.ui.Icons
import com.uyatame.ucrt.legacy.ui.Menu
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.ProgressBarView
import com.uyatame.ucrt.legacy.ui.Sheet
import com.uyatame.ucrt.legacy.ui.SliderView
import com.uyatame.ucrt.legacy.ui.SwitchView
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.UDialog
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.addDivider
import com.uyatame.ucrt.legacy.ui.bgd
import com.uyatame.ucrt.legacy.ui.bold
import com.uyatame.ucrt.legacy.ui.button
import com.uyatame.ucrt.legacy.ui.card
import com.uyatame.ucrt.legacy.ui.chip
import com.uyatame.ucrt.legacy.ui.chipRow
import com.uyatame.ucrt.legacy.ui.circle
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.enabled
import com.uyatame.ucrt.legacy.ui.field
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.icon
import com.uyatame.ucrt.legacy.ui.iconButton
import com.uyatame.ucrt.legacy.ui.rounded
import com.uyatame.ucrt.legacy.ui.tap
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox
import kotlin.math.roundToInt

/**
 * 音質設定(本家の SoundUi・AudioUi・EqUi・EqSimple)。
 * いちばん上の「ソースダイレクト」をオンにすると、その下の音の加工(イコライザー・DAR・ReplayGain)を通さずに音源のまま出す
 */
object Sound {
    const val MAIN = 0
    const val EQ = 1
    const val DAR = 2
    const val OUTPUT = 3
    const val DSD = 4
    const val RG = 5

    private val app get() = LegacyApp.app
    private val p get() = app.prefs

    fun title(page: Int): String = when (page) {
        EQ -> S(R.string.equalizer)
        DAR -> "DAR"
        OUTPUT -> S(R.string.usb_dac_direct_output)
        DSD -> S(R.string.dsd_playback)
        RG -> "ReplayGain"
        else -> S(R.string.sound_settings)
    }

    // ---- 今の設定 ----
    private val simple: FloatArray get() = EqBands.decode(p.eqSimpleGains.ifEmpty { p.sp.getString("eqGains", "") ?: "" })
    private val bandList: List<EqBand> get() = if (p.eqBands.isNotEmpty()) ParamEq.decode(p.eqBands) else ParamEq.fromGains(EqBands.decode(p.sp.getString("eqGains", "") ?: ""))
    private val darMode get() = p.sp.getInt("darMode", 0)
    private val darPcm get() = p.sp.getInt("darPcm", 0)
    private val darInteger get() = p.sp.getBoolean("darInteger", true)

    fun presetName(): String? = eqPresetNameOf(p.eqMode, simple, bandList, p.eqCustom)
    fun eqName(): String = presetName() ?: S(R.string.custom)

    private fun eqSummary(): String = if (!p.eqEnabled) S(R.string.off) else (if (p.eqMode == 1) "PRO · " else "") + eqName()
    private fun darSummary(): String = when (darMode) {
        1 -> S(R.string.pcm_upsampling) + " · " + listOf(S(R.string.dac_max), "×2", "×4", "×8", "×16").getOrElse(darPcm) { "" }
        2 -> S(R.string.convert_to_dsd) + " · " + listOf("DSD64", "DSD128", "DSD256").getOrElse(p.sp.getInt("darDsd", 0)) { "" }
        else -> S(R.string.off)
    }
    private fun rgSummary(): String = if (!p.rgEnabled) S(R.string.off) else
        (if (p.rgMode == 1) S(R.string.rg_album) else S(R.string.rg_track)) + (if (p.rgPreamp != 0) " · " + (if (p.rgPreamp > 0) "+" else "") + "${p.rgPreamp} dB" else "")
    private fun dsdSummary(): String = when (p.dsdMode) {
        1 -> "DoP"
        2 -> S(R.string.convert_to_pcm)
        else -> S(R.string.auto_native_dsd_dop_pcm_2)
    }

    /** 下から出す音質設定(再生画面から開く。本家の AudioSettingsSheet) */
    fun sheet(act: LegacyActivity) {
        val s = Sheet(act, full = true)
        val box = vbox(act)
        box.setPadding(dp(16), 0, dp(16), dp(24))
        s.add(box)
        var page = MAIN
        lateinit var build: () -> Unit
        build = {
            box.removeAllViews()
            if (page == MAIN) {
                box.add(text(act, title(MAIN), Type.TitleLarge), b = 12)
                box.add(main(act) { page = it; build() })
            } else {
                val top = hbox(act)
                top.add(iconButton(act, Icons.ArrowBack, S(R.string.back), Pal.onSurface) { page = MAIN; build() }, w = 48, h = 48)
                top.add(text(act, title(page), Type.TitleLarge), w = 0, weight = 1f)
                box.add(top, b = 12)
                box.add(sub(act, page))
            }
        }
        build()
        val un = Bus.on(Bus.SETTINGS) { if (s.isShowing) build() }
        s.setOnDismissListener { un() }
        s.show()
    }

    // =====================================================================
    // トップ
    // =====================================================================

    fun main(act: LegacyActivity, onOpen: (Int) -> Unit): View {
        val col = vbox(act)
        val direct = p.sourceDirect
        // 今の出力
        val now = hbox(act)
        now.bgd(rounded(Pal.surfaceVariant, 12f))
        now.setPadding(dp(16), dp(16), dp(16), dp(16))
        val ic = FrameLayout(act)
        ic.bgd(circle(Pal.surface))
        ic.add(icon(act, Output.icon(act), Pal.primary), w = 24, h = 24, gravity = Gravity.CENTER)
        now.add(ic, w = 40, h = 40, r = 14)
        val nc = vbox(act)
        nc.add(text(act, Output.name(act), Type.TitleSmall))
        nc.add(text(act, app.player.formatText.ifEmpty {
            when { direct -> S(R.string.source_direct); p.eqEnabled -> S(R.string.equalizer_on); else -> S(R.string.output_status_appears_here_during) }
        }, Type.BodySmall, Pal.onSurfaceVariant, lines = 2))
        now.add(nc, w = 0, weight = 1f)
        col.add(now, b = 12)
        // 音質
        col.add(text(act, S(R.string.sound_quality), Type.LabelLarge, Pal.primary), l = 8, b = 6, t = 4)
        val c1 = card(act)
        c1.add(soundRow(act, S(R.string.source_direct), S(R.string.plays_source_as_is_bypassing), direct, true, highlight = true, onToggle = { p.set("sourceDirect", it) }))
        c1.addDivider()
        c1.add(soundRow(act, S(R.string.equalizer), eqSummary(), p.eqEnabled, !direct, onToggle = { p.set("eqEnabled", it) }) { onOpen(EQ) })
        c1.addDivider()
        c1.add(soundRow(act, "DAR", S(R.string.upsampling_dsd_conversion) + " · " + darSummary(), darMode != 0, !direct, onToggle = { p.set("darMode", if (it) 1 else 0) }) { onOpen(DAR) })
        c1.addDivider()
        c1.add(soundRow(act, "ReplayGain", S(R.string.evens_out_loudness) + " · " + rgSummary(), p.rgEnabled, !direct, onToggle = { p.set("rgEnabled", it) }) { onOpen(RG) })
        if (direct) c1.add(note(act, S(R.string.equalizer_and_dar_are_bypassed), Pal.primary).also { it.setPadding(dp(16), dp(8), dp(16), dp(8)) })
        col.add(c1, b = 12)
        // 出力
        col.add(text(act, S(R.string.output_2), Type.LabelLarge, Pal.primary), l = 8, b = 6, t = 4)
        val c2 = card(act)
        c2.add(soundRow(act, S(R.string.usb_dac_direct_output), S(R.string.drives_usb_dac_with_apps), p.usbDirect, true, onToggle = { UsbAudio.setDirect(act, it) }) { onOpen(OUTPUT) })
        c2.addDivider()
        c2.add(soundRow(act, S(R.string.dsd_playback), dsdSummary(), null, true) { onOpen(DSD) })
        c2.addDivider()
        c2.add(soundRow(act, S(R.string.gapless_playback), S(R.string.plays_tracks_of_same_format), p.gapless, true, onToggle = { p.set("gapless", it) }))
        col.add(c2)
        return col
    }

    /**
     * 音質設定の 1 行(本家の SoundRow)。名前と今の設定を出し、右にスイッチ(checked が null なら無し)。
     * onOpen があれば、行をタップすると詳しい設定を開く
     */
    private fun soundRow(
        act: LegacyActivity, title: String, summary: String, checked: Boolean?, enabled: Boolean, highlight: Boolean = false,
        onToggle: (Boolean) -> Unit = {}, onOpen: (() -> Unit)? = null,
    ): View {
        val row = hbox(act)
        row.setPadding(dp(16), dp(12), dp(12), dp(12))
        val c = vbox(act)
        val t = text(act, title, Type.BodyLarge)
        if (highlight) t.bold()
        c.add(t)
        c.add(text(act, summary, Type.BodySmall, Pal.onSurfaceVariant, lines = 0))
        row.add(c, w = 0, weight = 1f)
        if (onOpen != null) row.add(icon(act, Icons.ArrowRight, Pal.onSurfaceVariant), w = 24, h = 24, l = 4, r = 4)
        if (checked != null) {
            if (onOpen != null) row.add(com.uyatame.ucrt.legacy.ui.divider(act), w = 1, h = 32, l = 8, r = 8)
            val sw = SwitchView(act, checked) { onToggle(it) }
            sw.isEnabled = enabled
            row.add(sw, w = WRAP, h = WRAP)
        }
        if (!enabled) row.alpha = 0.45f
        val click = onOpen ?: checked?.let { c0 -> { onToggle(!c0) } }
        if (click != null && enabled) row.tap(0f) { click() }
        return row
    }

    fun sub(act: LegacyActivity, page: Int): View = when (page) {
        EQ -> eqPanel(act)
        DAR -> darPanel(act)
        OUTPUT -> UsbAudio.panel(act)
        DSD -> dsdPanel(act)
        RG -> rgPanel(act)
        else -> View(act)
    }

    // =====================================================================
    // イコライザー
    // =====================================================================

    /** 画面を作り直しても選んだ帯域を覚えておく */
    private var selBand = -1
    private var selSimple = 5

    private fun previewSimple(g: FloatArray, preamp: Float) = app.dsp.setEq(p.eqActive, ParamEq.fromGains(g), preamp, p.eqAutoPreamp)
    private fun previewPro(b: List<EqBand>, preamp: Float) = app.dsp.setEq(p.eqActive, b, preamp, p.eqAutoPreamp)

    private fun saveSimple(g: FloatArray, preamp: Float) {
        p.sp.edit().putString("eqSimpleGains", EqBands.encode(g)).putFloat("eqPreamp", preamp).apply()
        Bus.emit(Bus.SETTINGS)
    }

    private fun savePro(b: List<EqBand>, preamp: Float) {
        val preset = EqBands.PRESETS.indexOfFirst { com.uyatame.ucrt.sameBandList(ParamEq.fromGains(it.gains), b) }
        p.sp.edit().putString("eqBands", ParamEq.encode(b)).putFloat("eqPreamp", preamp).putInt("eqPreset", preset).apply()
        Bus.emit(Bus.SETTINGS)
    }

    private fun eqPanel(act: LegacyActivity): View {
        val col = vbox(act)
        val on = p.eqEnabled
        val pro = p.eqMode == 1
        var preamp = p.eqPreamp
        val bands = bandList.toMutableList()
        var gains = simple
        val sec = Section(act, S(R.string.equalizer))
        sec.add(switchRow(act, S(R.string.use_equalizer), null, on) { p.set("eqEnabled", it) })
        if (p.sourceDirect) sec.add(note(act, S(R.string.equalizer_is_bypassed_while_source), Pal.error))
        sec.add(segmented(act, S(R.string.eq_type), listOf(S(R.string.eq_simple), "PRO"), p.eqMode) { m -> p.set("eqMode", m) })
        if (!pro) {
            // ---- シンプル(10 バンド) ----
            val head = hbox(act)
            val fl = text(act, freqLabel(EqBands.FREQS[selSimple]), Type.TitleSmall, Pal.onSurfaceVariant)
            head.add(fl, w = 0, weight = 1f)
            val vl = text(act, "", Type.TitleMedium, if (on) Pal.primary else Pal.onSurfaceVariant).bold()
            head.add(vl, w = WRAP)
            fun showVal() { val v = gains[selSimple]; vl.text = (if (v > 0) "+" else "") + "%.1f".format(java.util.Locale.US, v) + " dB"; fl.text = freqLabel(EqBands.FREQS[selSimple]) }
            showVal()
            sec.add(head)
            val row = hbox(act)
            lateinit var gv: GraphicEqView
            val labels = ArrayList<TextView>()
            fun showLabels() { labels.forEachIndexed { i, t -> t.setTextColor(if (i == selSimple) Pal.primary else Pal.onSurfaceVariant); if (i == selSimple) t.bold() else t.typeface = android.graphics.Typeface.DEFAULT } }
            gv = GraphicEqView(act, gains, { i -> selSimple = i; showVal(); showLabels() }, { g -> gains = g; showVal(); previewSimple(g, preamp) }, { saveSimple(gains, preamp) })
            gv.sel = selSimple
            gv.on = on
            row.add(gv, w = 0, weight = 1f)
            val pm = vbox(act, Gravity.CENTER_HORIZONTAL)
            fun bump(d: Float) {
                val g = gains.copyOf()
                g[selSimple] = ((g[selSimple] + d) * 2).roundToInt().div(2f).coerceIn(-SIMPLE_EQ_MAX, SIMPLE_EQ_MAX)
                gains = g; gv.gains = g; gv.invalidate(); showVal()
                previewSimple(g, preamp); saveSimple(g, preamp)
            }
            val plus = icon(act, Icons.Add, Pal.onPrimary)
            plus.tap(20f, circle(Pal.primary)) { bump(0.5f) }
            pm.add(plus, w = 40, h = 40, b = 12)
            pm.add(text(act, "±${SIMPLE_EQ_MAX.toInt()}", Type.LabelSmall, Pal.onSurfaceVariant), w = WRAP, b = 12)
            val minus = text(act, "−", Type.TitleLarge, Pal.onPrimary)
            minus.gravity = Gravity.CENTER
            minus.tap(20f, circle(Pal.primary)) { bump(-0.5f) }
            pm.add(minus, w = 40, h = 40)
            if (!on) { plus.enabled(false); minus.enabled(false) }
            row.add(pm, w = WRAP, l = 8)
            sec.add(row)
            val lrow = hbox(act)
            EqBands.LABELS.forEachIndexed { i, l ->
                val t = text(act, l, Type.LabelSmall, Pal.onSurfaceVariant)
                t.gravity = Gravity.CENTER
                if (on) t.tap(4f) { selSimple = i; gv.sel = i; showVal(); showLabels() }
                labels.add(t)
                lrow.add(t, w = 0, weight = 1f)
            }
            lrow.setPadding(0, 0, dp(56), 0)
            showLabels()
            sec.add(lrow)
            val flat = hbox(act, Gravity.CENTER)
            flat.add(button(act, S(R.string.flat), Btn.Outlined) { gains = FloatArray(EqBands.FREQS.size); previewSimple(gains, preamp); saveSimple(gains, preamp) }.also { if (!on) it.enabled(false) }, w = WRAP)
            sec.add(flat)
        } else {
            // ---- PRO(パラメトリック) ----
            val land = act.landscape
            lateinit var curve: EqCurveView
            val listBox = vbox(act)
            lateinit var buildList: () -> Unit
            curve = EqCurveView(act, bands, { i -> selBand = i; buildList() }, { i, f, g ->
                val b = bands.getOrNull(i) ?: return@EqCurveView
                bands[i] = b.copy(
                    freq = f.coerceIn(ParamEq.MIN_FREQ, ParamEq.MAX_FREQ),
                    gain = if (b.type.hasGain) ((g * 2).roundToInt() / 2f).coerceIn(-ParamEq.MAX_DB, ParamEq.MAX_DB) else b.gain,
                )
                previewPro(bands, preamp)
            }, { savePro(bands, preamp) }, { f, g ->
                if (bands.size < ParamEq.MAX_BANDS) {
                    bands.add(EqBand(EqType.Peak, f, ((g * 2).roundToInt() / 2f).coerceIn(-ParamEq.MAX_DB, ParamEq.MAX_DB), 1.41f))
                    selBand = bands.lastIndex
                    previewPro(bands, preamp); savePro(bands, preamp)
                }
            })
            curve.sel = selBand.coerceAtMost(bands.lastIndex)
            curve.on = on
            if (land) curve.heightDp = (act.resources.configuration.screenHeightDp * 0.55f).toInt().coerceIn(160, 230)
            buildList = {
                listBox.removeAllViews()
                val head = hbox(act)
                head.add(text(act, S(R.string.bands, bands.size, ParamEq.MAX_BANDS), Type.LabelLarge), w = 0, weight = 1f)
                head.add(button(act, S(R.string.add), Btn.Outlined, Icons.Add) {
                    bands.add(EqBand(EqType.Peak, freeFreq(bands), 0f, 1.41f))
                    selBand = bands.lastIndex
                    previewPro(bands, preamp); savePro(bands, preamp)
                }.also { if (!on || bands.size >= ParamEq.MAX_BANDS) it.enabled(false) }, w = WRAP)
                listBox.add(head, b = 8)
                if (bands.isNotEmpty()) {
                    listBox.add(chipRow(act, bands.mapIndexed { i, b ->
                        chip(act, "${i + 1}: ${freqLabel(b.freq)}", selBand == i) { selBand = if (selBand == i) -1 else i; curve.sel = selBand; buildList() }
                    }), b = 8)
                }
                val cur = bands.getOrNull(selBand)
                if (cur != null) listBox.add(bandEditor(act, selBand, cur, on, { nb -> bands[selBand] = nb; curve.invalidate(); previewPro(bands, preamp) }, { savePro(bands, preamp) }) {
                    bands.removeAt(selBand); selBand = -1; curve.sel = -1
                    previewPro(bands, preamp); savePro(bands, preamp)
                })
            }
            buildList()
            val hint = note(act, S(R.string.drag_point_to_change_its))
            if (land) {
                val r = hbox(act, Gravity.TOP)
                val l = vbox(act); l.add(curve); l.add(hint, t = 8)
                r.add(l, w = 0, weight = 0.55f, r = 12)
                r.add(listBox, w = 0, weight = 0.45f)
                sec.add(r)
            } else {
                sec.add(curve); sec.add(hint); sec.add(listBox)
            }
        }
        // ---- プリアンプ ----
        val pr = hbox(act)
        pr.add(text(act, S(R.string.preamp), Type.LabelMedium), w = 76)
        val pv = text(act, dbLabel(preamp), Type.LabelMedium)
        pv.gravity = Gravity.END
        val ps = SliderView(act, (preamp + ParamEq.MAX_DB) / (2 * ParamEq.MAX_DB), { f ->
            preamp = ((f * 2 * ParamEq.MAX_DB - ParamEq.MAX_DB) * 2).roundToInt() / 2f
            pv.text = dbLabel(preamp)
            if (pro) previewPro(bands, preamp) else previewSimple(gains, preamp)
        }, { if (pro) savePro(bands, preamp) else saveSimple(gains, preamp) })
        ps.isEnabled = on
        pr.add(ps, w = 0, weight = 1f)
        pr.add(pv, w = 64)
        sec.add(pr)
        sec.add(switchRow(act, S(R.string.prevent_clipping_automatically), S(R.string.lowers_overall_level_by_curves), p.eqAutoPreamp) { p.set("eqAutoPreamp", it) })
        sec.add(button(act, S(R.string.reset), Btn.Text) {
            preamp = 0f
            if (pro) { bands.clear(); bands.addAll(ParamEq.fromGains(EqBands.PRESETS[0].gains)); selBand = -1; previewPro(bands, preamp); savePro(bands, preamp) }
            else { gains = FloatArray(EqBands.FREQS.size); previewSimple(gains, preamp); saveSimple(gains, preamp) }
        }.also { if (!on) it.enabled(false) })
        col.add(sec.view, b = 12)
        // ---- プリセット ----
        val ps2 = Section(act, S(R.string.eq_presets))
        ps2.add(presetList(act, on, pro, { g ->
            if (pro) { val b = ParamEq.fromGains(g); previewPro(b, preamp); savePro(b, preamp); selBand = -1 }
            else { previewSimple(g, preamp); saveSimple(g, preamp) }
        }, { c ->
            if (c.pro || pro) {
                val b = c.bands()
                selBand = -1
                p.sp.edit().putInt("eqMode", 1).apply()
                previewPro(b, c.preamp); savePro(b, c.preamp)
            } else {
                previewSimple(c.gains(), c.preamp); saveSimple(c.gains(), c.preamp)
            }
        }) {
            if (pro) EqCustom("", true, ParamEq.encode(bands), preamp) else EqCustom("", false, EqBands.encode(gains), preamp)
        })
        col.add(ps2.view)
        return col
    }

    /** 新しく足す帯域の周波数(今ある帯域のあいだで、いちばん空いているところ) */
    private fun freeFreq(bands: List<EqBand>): Float {
        if (bands.isEmpty()) return 1000f
        val pos = (bands.map { freqToPos(it.freq) } + listOf(0f, 1f)).sorted()
        var best = 0.5f
        var gap = -1f
        for (i in 1 until pos.size) {
            val g = pos[i] - pos[i - 1]
            if (g > gap) { gap = g; best = (pos[i] + pos[i - 1]) / 2 }
        }
        return posToFreq(best)
    }

    private fun eqTypeName(t: EqType): String = when (t) {
        EqType.Peak -> S(R.string.peak)
        EqType.LowShelf -> S(R.string.low_shelf)
        EqType.HighShelf -> S(R.string.high_shelf)
        EqType.LowCut -> S(R.string.low_cut)
        EqType.HighCut -> S(R.string.high_cut)
    }

    /** 選んだ帯域の種類・周波数・利得・Q(本家の BandEditor) */
    private fun bandEditor(act: LegacyActivity, index: Int, b0: EqBand, on: Boolean, onChange: (EqBand) -> Unit, onDone: () -> Unit, onDelete: () -> Unit): View {
        var b = b0
        val box = vbox(act)
        box.bgd(rounded(Pal.surfaceVariant, 12f))
        box.setPadding(dp(12), dp(12), dp(12), dp(12))
        val head = hbox(act)
        head.add(text(act, S(R.string.band, index + 1), Type.TitleSmall), w = 0, weight = 1f)
        head.add(button(act, S(R.string.delete), Btn.Text, Icons.Delete) { onDelete() }.also { if (!on) it.enabled(false) }, w = WRAP)
        box.add(head)
        box.add(chipRow(act, EqType.entries.map { t ->
            chip(act, eqTypeName(t), b.type == t) {
                if (!on) return@chip
                val q = if (!t.hasGain && b.type.hasGain) 0.71f else if (t.hasGain && !b.type.hasGain) 1.41f else b.q
                b = b.copy(type = t, q = q)
                onChange(b); onDone()
            }
        }), t = 4, b = 4)
        fun slider(label: String, value: () -> String, pos: Float, en: Boolean, set: (Float) -> Unit) {
            val r = hbox(act)
            r.add(text(act, label, Type.LabelMedium), w = 68)
            val vt = text(act, value(), Type.LabelMedium)
            vt.gravity = Gravity.END
            val s = SliderView(act, pos.coerceIn(0f, 1f), { set(it); vt.text = value(); onChange(b) }, { onDone() })
            s.isEnabled = en
            r.add(s, w = 0, weight = 1f)
            r.add(vt, w = 76)
            box.add(r)
        }
        slider(S(R.string.frequency), { freqLabel(b.freq) }, freqToPos(b.freq), on) { b = b.copy(freq = posToFreq(it)) }
        slider(S(R.string.gain), { dbLabel(b.gain) }, (b.gain + ParamEq.MAX_DB) / (2 * ParamEq.MAX_DB), on && b.type.hasGain) {
            b = b.copy(gain = ((it * 2 * ParamEq.MAX_DB - ParamEq.MAX_DB) * 2).roundToInt() / 2f)
        }
        slider(S(R.string.width_q), { "Q " + "%.2f".format(java.util.Locale.US, b.q) }, qToPos(b.q), on) { b = b.copy(q = (posToQ(it) * 100).roundToInt() / 100f) }
        return box
    }

    /**
     * イコライザーのプリセット(標準のものと、保存したもの)の縦の一覧と、「今の設定を保存」(本家の EqPresetList)
     */
    private fun presetList(act: LegacyActivity, on: Boolean, pro: Boolean, onBuiltIn: (FloatArray) -> Unit, onCustom: (EqCustom) -> Unit, current: () -> EqCustom): View {
        val customs = EqCustom.decode(p.eqCustom)
        val now = presetName()
        fun saveList(list: List<EqCustom>) = p.set("eqCustom", EqCustom.encode(list))
        val col = vbox(act)
        val top = hbox(act)
        top.add(View(act), w = 0, h = 1, weight = 1f)
        top.add(button(act, S(R.string.save_current_eq), Btn.Text, Icons.Add) {
            nameDialog(act, S(R.string.save_current_eq), suggestName(customs)) { name ->
                saveList(customs.filter { it.name != name } + current().copy(name = name))
            }
        }.also { if (!on) it.enabled(false) }, w = WRAP)
        col.add(top)
        fun row(name: String, badge: String?, bands: List<EqBand>, selected: Boolean, onClick: () -> Unit, menu: ((View) -> Unit)?): View {
            val r = hbox(act)
            r.setPadding(dp(12), dp(8), if (menu != null) 0 else dp(12), dp(8))
            val dot = View(act)
            dot.bgd(circle(if (selected) Pal.primary else Pal.outlineVariant))
            r.add(dot, w = 10, h = 10, r = 10)
            val c = vbox(act)
            c.add(text(act, name, Type.BodyLarge))
            if (badge != null) c.add(text(act, badge, Type.LabelSmall, Pal.onSurfaceVariant))
            r.add(c, w = 0, weight = 1f, r = 8)
            r.add(CurveThumbView(act, bands), w = 110, h = 40)
            if (menu != null) {
                lateinit var m: View
                m = iconButton(act, Icons.MoreVert, S(R.string.menu)) { menu(m) }
                r.add(m, w = 48, h = 48)
            }
            r.tap(12f, rounded(if (selected) Pal.secondaryContainer else Pal.surface, 12f, if (selected) Pal.primary else Pal.outlineVariant, 1f)) { if (on) onClick() }
            return r
        }
        if (customs.isNotEmpty()) {
            col.add(text(act, S(R.string.saved_presets), Type.LabelLarge, Pal.onSurfaceVariant), b = 6)
            for (c in customs) {
                col.add(row(c.name, if (c.pro) "PRO" else S(R.string.eq_simple), c.bands(), now == c.name, { onCustom(c) }) { anchor ->
                    Menu(anchor)
                        .item(S(R.string.rename), Icons.Edit) {
                            nameDialog(act, S(R.string.rename), c.name) { name -> saveList(customs.filter { it.name != name || it === c }.map { if (it === c) it.copy(name = name) else it }) }
                        }
                        .item(S(R.string.overwrite_with_current), Icons.Add) { saveList(customs.map { if (it === c) current().copy(name = c.name) else it }) }
                        .item(S(R.string.delete), Icons.Delete) {
                            val d = UDialog(act).title(S(R.string.delete_preset_q, c.name))
                            d.button(S(R.string.cancel)) { d.dismiss() }
                            d.button(S(R.string.delete)) { saveList(customs.filter { it !== c }); d.dismiss() }
                            d.show()
                        }
                        .show()
                }, b = 6)
            }
            col.add(text(act, S(R.string.standard_presets), Type.LabelLarge, Pal.onSurfaceVariant), b = 6)
        }
        for (pr in EqBands.PRESETS) {
            val label = pr.label()
            col.add(row(label, null, ParamEq.fromGains(pr.gains), now == label, { onBuiltIn(pr.gains) }, null), b = 6)
        }
        return col
    }

    private fun suggestName(list: List<EqCustom>): String {
        var i = list.size + 1
        while (list.any { it.name == S(R.string.my_eq_n, i) }) i++
        return S(R.string.my_eq_n, i)
    }

    private fun nameDialog(act: LegacyActivity, title: String, initial: String, onDone: (String) -> Unit) {
        val d = UDialog(act).title(title)
        val f = field(act, initial, S(R.string.preset_name))
        d.view(f)
        d.button(S(R.string.cancel)) { d.dismiss() }
        d.button(S(R.string.save)) {
            val n = f.text.toString().replace('\n', ' ').trim()
            if (n.isNotEmpty()) { onDone(n); d.dismiss() }
        }
        d.show()
    }

    // =====================================================================
    // DAR・ReplayGain・DSD
    // =====================================================================

    private fun darPanel(act: LegacyActivity): View {
        val sec = Section(act, S(R.string.dar_upsampling_dsd_conversion))
        val options = listOf(
            S(R.string.off) to S(R.string.sends_source_unaltered_bit_perfect),
            S(R.string.pcm_upsampling) to S(R.string.upsamples_source_by_integer_multiple),
            S(R.string.convert_to_dsd) to S(R.string.converts_source_to_dsd_and),
        )
        sec.add(radioRows(act, options, darMode) { p.set("darMode", it) })
        if (darMode == 1) {
            sec.add(text(act, S(R.string.maximum_rate_multiple_of_44), Type.LabelLarge))
            sec.add(chipChoice(act, listOf(S(R.string.dac_maximum), "×2 (88.2 / 96k)", "×4 (176.4 / 192k)", "×8 (352.8 / 384k)", "×16 (705.6 / 768k)"), darPcm) { p.set("darPcm", it) })
            sec.add(switchRow(act, S(R.string.integer_multiples_only), S(R.string.on_upsamples_only_by_integer), darInteger) { p.set("darInteger", it) })
            sec.add(note(act, S(R.string.legacy_dar_limit, "${SoundDsp.trackMax / 1000}kHz")))
        }
        if (darMode == 2) {
            sec.add(text(act, S(R.string.dsd_rate), Type.LabelLarge))
            sec.add(chipChoice(act, listOf("DSD64", "DSD128", "DSD256"), p.sp.getInt("darDsd", 0)) { p.set("darDsd", it) })
            sec.add(note(act, S(R.string.if_dac_doesnt_support_selected)))
        }
        sec.add(note(act, S(R.string.dar_applies_to_pcm_tracks),
            if (darMode != 0 && (p.sourceDirect || (darMode == 2 && !p.usbDirect))) Pal.error else Pal.onSurfaceVariant))
        return sec.view
    }

    private fun rgPanel(act: LegacyActivity): View {
        val col = vbox(act)
        val sec = Section(act, "ReplayGain")
        sec.add(switchRow(act, "ReplayGain", S(R.string.uses_replaygain_values_written), p.rgEnabled) { p.set("rgEnabled", it) })
        if (p.rgEnabled) {
            sec.add(segmented(act, S(R.string.rg_based_on), listOf(S(R.string.rg_track), S(R.string.rg_album)), p.rgMode) { p.set("rgMode", it) })
            sec.add(note(act, S(R.string.rg_album_keeps_differences)))
            var pre = p.rgPreamp
            val lbl = text(act, S(R.string.rg_preamp, (if (pre > 0) "+" else "") + pre), Type.LabelLarge)
            sec.add(lbl)
            val s = SliderView(act, (pre + 12) / 24f, { f -> pre = (f * 24 - 12).roundToInt(); lbl.text = S(R.string.rg_preamp, (if (pre > 0) "+" else "") + pre) }, { p.set("rgPreamp", pre) })
            s.steps = 24
            sec.add(s)
            sec.add(switchRow(act, S(R.string.prevent_clipping), S(R.string.limits_gain_using_peak), p.rgNoClip) { p.set("rgNoClip", it) })
        }
        sec.add(note(act, S(R.string.rg_notes), if (p.rgEnabled && p.sourceDirect) Pal.error else Pal.onSurfaceVariant))
        col.add(sec.view, b = 12)
        // 解析
        val an = Section(act, S(R.string.rg_analysis))
        val total = app.lib.albums.sumOf { it.tracks.size }
        an.add(text(act, S(R.string.rg_analyzed_count, RgStore.count, total), Type.BodyMedium))
        if (Rg.running) {
            an.add(ProgressBarView(act, Rg.progress))
            an.add(text(act, "${(Rg.progress * 100).roundToInt()}%" + S(R.string.separator) + Rg.status, Type.BodySmall, Pal.onSurfaceVariant))
            an.add(button(act, S(R.string.cancel), Btn.Outlined) { Rg.cancel() }.also { (it.layoutParams as? LinearLayout.LayoutParams)?.width = WRAP })
        } else {
            an.add(button(act, S(R.string.rg_analyze_new), Btn.Filled) { Rg.start(act, false) })
            val r = hbox(act)
            r.add(button(act, S(R.string.rg_analyze_all), Btn.Outlined) { Rg.start(act, true) }, w = WRAP, r = 8)
            r.add(button(act, S(R.string.rg_clear_analyzed), Btn.Text) { Rg.clear() }.also { if (RgStore.count == 0) it.enabled(false) }, w = WRAP)
            an.add(r)
        }
        an.add(note(act, S(R.string.rg_analysis_notes)))
        col.add(an.view)
        return col
    }

    private fun dsdPanel(act: LegacyActivity): View {
        val sec = Section(act, S(R.string.dsd_playback))
        val options = listOf(
            S(R.string.auto_native_dsd_dop_pcm) to S(R.string.uses_native_dsd_when_possible),
            S(R.string.use_dop) to S(R.string.sends_dsd_over_pcm_dop),
            S(R.string.always_convert_to_pcm) to S(R.string.for_dacs_without_dsd_support),
        )
        sec.add(radioRows(act, options, p.dsdMode) { p.set("dsdMode", it) })
        sec.add(note(act, S(R.string.native_dsd_and_dop_are)))
        sec.add(switchRow(act, S(R.string.swap_native_dsd_byte_order), S(R.string.turn_on_only_if_your), p.sp.getBoolean("dsdSwap", false)) { p.set("dsdSwap", it) })
        return sec.view
    }
}
