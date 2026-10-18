package com.uyatame.ucrt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.Keys
import com.uyatame.ucrt.data.eqBandList
import com.uyatame.ucrt.player.EqBand
import com.uyatame.ucrt.player.EqBands
import com.uyatame.ucrt.player.EqType
import com.uyatame.ucrt.player.ParamEq
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

private val LN_MIN = ln(ParamEq.MIN_FREQ.toDouble())
private val LN_MAX = ln(ParamEq.MAX_FREQ.toDouble())

/** 周波数 ↔ 0〜1 の位置(対数) */
private fun freqToPos(f: Float): Float = ((ln(f.toDouble()) - LN_MIN) / (LN_MAX - LN_MIN)).toFloat()
private fun posToFreq(p: Float): Float = exp(LN_MIN + (LN_MAX - LN_MIN) * p.coerceIn(0f, 1f)).toFloat()

private val Q_LN_MIN = ln(ParamEq.MIN_Q.toDouble())
private val Q_LN_MAX = ln(ParamEq.MAX_Q.toDouble())
private fun qToPos(q: Float): Float = ((ln(q.toDouble()) - Q_LN_MIN) / (Q_LN_MAX - Q_LN_MIN)).toFloat()
private fun posToQ(p: Float): Float = exp(Q_LN_MIN + (Q_LN_MAX - Q_LN_MIN) * p.coerceIn(0f, 1f)).toFloat()

fun freqLabel(f: Float): String = if (f >= 1000f) {
    val k = f / 1000f
    (if (k >= 10f) "%.1f".format(java.util.Locale.US, k).removeSuffix(".0") else "%.2f".format(java.util.Locale.US, k).trimEnd('0').trimEnd('.')) + " kHz"
} else "${f.roundToInt()} Hz"

private fun dbLabel(v: Float): String {
    val r = (v * 10).roundToInt() / 10f
    return (if (r > 0) "+" else "") + (if (r % 1f == 0f) r.toInt().toString() else r.toString()) + " dB"
}

fun eqTypeName(t: EqType): String = when (t) {
    EqType.Peak -> T("ピーク", "Peak")
    EqType.LowShelf -> T("ローシェルフ", "Low shelf")
    EqType.HighShelf -> T("ハイシェルフ", "High shelf")
    EqType.LowCut -> T("ローカット", "Low cut")
    EqType.HighCut -> T("ハイカット", "High cut")
}

private fun sameBands(a: List<EqBand>, b: List<EqBand>): Boolean =
    a.size == b.size && a.indices.all { i ->
        a[i].type == b[i].type && abs(a[i].freq - b[i].freq) < 0.5f && abs(a[i].gain - b[i].gain) < 0.05f && abs(a[i].q - b[i].q) < 0.01f
    }

/** 今の帯域に合うプリセットの番号(無ければ -1 = カスタム) */
private fun matchPreset(bands: List<EqBand>): Int =
    EqBands.PRESETS.indexOfFirst { p -> sameBands(ParamEq.fromGains(p.gains), bands) }

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

/**
 * イコライザー(パラメトリック EQ)。
 * 曲線の上の点をドラッグして周波数(左右)と利得(上下)を変え、点を選ぶと種類・Q(幅)も変えられる。
 * 何もないところをダブルタップすると帯域を足せる(最大 16)。
 */
@Composable
fun EqualizerPanel(vm: MainViewModel, s: AppSettings) {
    val bands = remember(s.eqBands, s.eqGains) { mutableStateListOf<EqBand>().apply { addAll(s.eqBandList) } }
    var preamp by remember(s.eqPreamp) { mutableFloatStateOf(s.eqPreamp) }
    var preset by remember(s.eqPreset, s.eqBands) { mutableIntStateOf(s.eqPreset) }
    var sel by remember { mutableIntStateOf(-1) }
    val on = s.eqEnabled
    fun preview() = vm.previewEq(bands.toList(), preamp)
    fun save() {
        preset = matchPreset(bands)
        vm.saveEq(bands.toList(), preamp, preset)
    }

    Section(T("イコライザー", "Equalizer")) {
        SwitchRow(T("イコライザーを使う", "Use equalizer"), null, s.eqEnabled) { vm.set(Keys.eqEnabled, it) }
        if (s.sourceDirect) {
            Text(
                T("ソースダイレクトがオンのため、イコライザーは使われません", "Source direct is on, so the equalizer is bypassed"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EqBands.PRESETS.forEachIndexed { i, p ->
                FilterChip(
                    selected = preset == i,
                    onClick = {
                        bands.clear()
                        bands.addAll(ParamEq.fromGains(p.gains))
                        sel = -1
                        preview()
                        save()
                    },
                    label = { Text(T(p.ja, p.en)) },
                    enabled = on,
                )
            }
            if (preset < 0) FilterChip(true, {}, label = { Text(T("カスタム", "Custom")) }, enabled = on)
        }
        // 横向き: 左に曲線、右に帯域の一覧と選んだ帯域の設定を並べ、点を動かしながら値も見えるようにする
        val land = isLandscape()
        val screenH = LocalConfiguration.current.screenHeightDp
        val curveHeight = if (land) (screenH * 0.55f).dp.coerceIn(160.dp, 230.dp) else 230.dp
        val curve: @Composable () -> Unit = {
            EqCurve(
                bands, sel, on, curveHeight,
                onSelect = { sel = it },
                onMove = { i, f, g ->
                    val b = bands.getOrNull(i) ?: return@EqCurve
                    bands[i] = b.copy(
                        freq = f.coerceIn(ParamEq.MIN_FREQ, ParamEq.MAX_FREQ),
                        gain = if (b.type.hasGain) ((g * 2).roundToInt() / 2f).coerceIn(-ParamEq.MAX_DB, ParamEq.MAX_DB) else b.gain,
                    )
                    preview()
                },
                onMoveEnd = { save() },
                onAdd = { f, g ->
                    if (bands.size < ParamEq.MAX_BANDS) {
                        bands.add(EqBand(EqType.Peak, f, ((g * 2).roundToInt() / 2f).coerceIn(-ParamEq.MAX_DB, ParamEq.MAX_DB), 1.41f))
                        sel = bands.lastIndex
                        preview()
                        save()
                    }
                },
            )
        }
        val hint: @Composable () -> Unit = {
            Text(
                T(
                    "点をドラッグして周波数と強さを変えます。点をタップすると種類や幅を変えられます。何もないところをダブルタップすると帯域を追加します",
                    "Drag a point to change its frequency and level. Tap a point to change its type and width. Double-tap an empty spot to add a band",
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val bandList: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    T("バンド ${bands.size} / ${ParamEq.MAX_BANDS}", "Bands ${bands.size} / ${ParamEq.MAX_BANDS}"),
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    {
                        bands.add(EqBand(EqType.Peak, freeFreq(bands), 0f, 1.41f))
                        sel = bands.lastIndex
                        preview()
                        save()
                    },
                    enabled = on && bands.size < ParamEq.MAX_BANDS,
                ) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text(T("追加", "Add"))
                }
            }
            // 帯域の一覧(番号をタップして選ぶ)
            if (bands.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    bands.forEachIndexed { i, b ->
                        FilterChip(
                            selected = sel == i,
                            onClick = { sel = if (sel == i) -1 else i },
                            label = { Text("${i + 1}: ${freqLabel(b.freq)}") },
                            enabled = on,
                        )
                    }
                }
            }
            val cur = bands.getOrNull(sel)
            if (cur != null) {
                BandEditor(
                    sel, cur, on,
                    onChange = { bands[sel] = it; preview() },
                    onDone = { save() },
                    onDelete = {
                        bands.removeAt(sel)
                        sel = -1
                        preview()
                        save()
                    },
                )
            }
        }
        if (land) {
            Row {
                Column(Modifier.weight(0.55f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    curve()
                    hint()
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(0.45f), verticalArrangement = Arrangement.spacedBy(8.dp)) { bandList() }
            }
        } else {
            curve()
            hint()
            bandList()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(T("プリアンプ", "Preamp"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
            Slider(
                value = preamp,
                onValueChange = {
                    preamp = (it * 2).roundToInt() / 2f
                    preview()
                },
                onValueChangeFinished = { save() },
                valueRange = -ParamEq.MAX_DB..ParamEq.MAX_DB,
                enabled = on,
                modifier = Modifier.weight(1f),
            )
            Text(dbLabel(preamp), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
        }
        SwitchRow(
            T("音割れを自動で防ぐ", "Prevent clipping automatically"),
            T("曲線のいちばん持ち上がった分だけ、全体の音量を下げます", "Lowers the overall level by the highest boost in the curve"),
            s.eqAutoPreamp,
        ) { vm.set(Keys.eqAutoPreamp, it) }
        TextButton({
            bands.clear()
            bands.addAll(ParamEq.fromGains(EqBands.PRESETS[0].gains))
            sel = -1
            preamp = 0f
            preview()
            save()
        }, enabled = on) { Text(T("リセット", "Reset")) }
    }
}

/** 選んだ帯域の種類・周波数・利得・Q */
@Composable
private fun BandEditor(
    index: Int,
    b: EqBand,
    enabled: Boolean,
    onChange: (EqBand) -> Unit,
    onDone: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(T("バンド ${index + 1}", "Band ${index + 1}"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onDelete, enabled = enabled) {
                Icon(Icons.Filled.Delete, null)
                Spacer(Modifier.width(4.dp))
                Text(T("削除", "Delete"))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EqType.entries.forEach { t ->
                FilterChip(
                    b.type == t,
                    {
                        // カットに切り替えたときは、幅をカットの標準(0.71)にする
                        val q = if (!t.hasGain && b.type.hasGain) 0.71f else if (t.hasGain && !b.type.hasGain) 1.41f else b.q
                        onChange(b.copy(type = t, q = q))
                        onDone()
                    },
                    label = { Text(eqTypeName(t)) },
                    enabled = enabled,
                )
            }
        }
        ParamSlider(T("周波数", "Freq"), freqLabel(b.freq), freqToPos(b.freq), enabled, { onChange(b.copy(freq = posToFreq(it))) }, onDone)
        ParamSlider(
            T("強さ", "Gain"), dbLabel(b.gain), (b.gain + ParamEq.MAX_DB) / (2 * ParamEq.MAX_DB), enabled && b.type.hasGain,
            { onChange(b.copy(gain = ((it * 2 * ParamEq.MAX_DB - ParamEq.MAX_DB) * 2).roundToInt() / 2f)) }, onDone,
        )
        ParamSlider(
            T("幅 (Q)", "Width (Q)"), "Q " + "%.2f".format(java.util.Locale.US, b.q), qToPos(b.q), enabled,
            { onChange(b.copy(q = (posToQ(it) * 100).roundToInt() / 100f)) }, onDone,
        )
    }
}

@Composable
private fun ParamSlider(label: String, value: String, pos: Float, enabled: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(68.dp))
        Slider(pos.coerceIn(0f, 1f), onChange, Modifier.weight(1f), enabled = enabled, onValueChangeFinished = onDone)
        Text(value, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp), textAlign = TextAlign.End)
    }
}

/** 周波数特性の曲線と、帯域の点 */
@Composable
private fun EqCurve(
    bands: SnapshotStateList<EqBand>,
    selected: Int,
    enabled: Boolean,
    curveH: Dp,
    onSelect: (Int) -> Unit,
    onMove: (Int, Float, Float) -> Unit,
    onMoveEnd: () -> Unit,
    onAdd: (Float, Float) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = cs.onSurfaceVariant)
    val handleText = TextStyle(fontSize = 11.sp, color = cs.onPrimary)
    val handleTextOff = TextStyle(fontSize = 11.sp, color = cs.primary)
    val maxDb = ParamEq.MAX_DB
    // 保存すると設定から帯域の一覧が作り直されるので、ドラッグの処理(最初に作ったまま)からは、いつも今の一覧と処理を使う
    // (古い一覧を書き換えて、2 回目からのドラッグで曲線が指についてこなくなるのを防ぐ)
    val bandsNow by rememberUpdatedState(bands)
    val selectNow by rememberUpdatedState(onSelect)
    val moveNow by rememberUpdatedState(onMove)
    val moveEndNow by rememberUpdatedState(onMoveEnd)
    val addNow by rememberUpdatedState(onAdd)
    Canvas(
        Modifier.fillMaxWidth().height(curveH).clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant)
            .pointerInput(enabled) {
                var lastTap = 0L
                var lastPos = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabled) return@awaitEachGesture
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val pad = 18.dp.toPx()
                    fun xOf(f: Float) = freqToPos(f) * w
                    fun yOf(db: Float) = h / 2 - db / maxDb * (h / 2 - pad)
                    fun dbAt(y: Float) = (h / 2 - y) / (h / 2 - pad) * maxDb
                    fun handleY(b: EqBand) = yOf(if (b.type.hasGain) b.gain else ParamEq.responseDb(listOf(b), b.freq.toDouble()).toFloat())
                    // 指の近くの点(28dp 以内)
                    val touch = 28.dp.toPx()
                    var hit = -1
                    var best = Float.MAX_VALUE
                    bandsNow.forEachIndexed { i, b ->
                        val d = (Offset(xOf(b.freq), handleY(b)) - down.position).getDistance()
                        if (d < touch && d < best) { best = d; hit = i }
                    }
                    if (hit >= 0) {
                        down.consume()
                        selectNow(hit)
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            if (c.positionChanged()) {
                                c.consume()
                                moved = true
                                moveNow(hit, posToFreq(c.position.x / w), dbAt(c.position.y))
                            }
                        }
                        if (moved) moveEndNow()
                        return@awaitEachGesture
                    }
                    // 何もないところ: タップで選択を外し、ダブルタップで帯域を追加
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    val now = up.uptimeMillis
                    if (now - lastTap < 350 && (up.position - lastPos).getDistance() < 48.dp.toPx()) {
                        addNow(posToFreq(up.position.x / w), dbAt(up.position.y))
                        lastTap = 0L
                    } else {
                        selectNow(-1)
                        lastTap = now
                        lastPos = up.position
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val pad = 18.dp.toPx()
        fun xOf(f: Float) = freqToPos(f) * w
        fun yOf(db: Float) = h / 2 - db / maxDb * (h / 2 - pad)
        val alpha = if (enabled) 1f else 0.4f
        // 目盛り(周波数)
        for (f in listOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f)) {
            val x = xOf(f)
            drawLine(cs.outlineVariant.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, h), 1f)
            if (f == 100f || f == 1000f || f == 10000f || f == 50f || f == 500f || f == 5000f) {
                val t = if (f >= 1000f) "${(f / 1000).toInt()}k" else "${f.toInt()}"
                drawText(measurer, t, Offset(x + 3, h - 14.dp.toPx()), labelStyle)
            }
        }
        // 目盛り(dB)
        for (db in listOf(-12f, -6f, 0f, 6f, 12f)) {
            val y = yOf(db)
            drawLine(
                if (db == 0f) cs.outline else cs.outlineVariant.copy(alpha = 0.6f),
                Offset(0f, y), Offset(w, y), if (db == 0f) 2f else 1f,
            )
            drawText(measurer, (if (db > 0) "+" else "") + db.toInt(), Offset(4f, y - 13.dp.toPx()), labelStyle)
        }
        // 曲線
        val list = bands.toList()
        val n = 160
        val path = Path()
        val fill = Path()
        for (i in 0..n) {
            val p = i.toFloat() / n
            val db = ParamEq.responseDb(list, posToFreq(p).toDouble()).toFloat().coerceIn(-maxDb * 1.2f, maxDb * 1.2f)
            val x = p * w
            val y = yOf(db)
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, yOf(0f)); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(w, yOf(0f))
        fill.close()
        drawPath(fill, cs.primary.copy(alpha = 0.15f * alpha))
        drawPath(path, cs.primary.copy(alpha = alpha), style = Stroke(width = 3.dp.toPx()))
        // 点
        val r = 11.dp.toPx()
        list.forEachIndexed { i, b ->
            val y = yOf(if (b.type.hasGain) b.gain else ParamEq.responseDb(listOf(b), b.freq.toDouble()).toFloat())
            val c = Offset(xOf(b.freq), y.coerceIn(r, h - r))
            if (i == selected) {
                drawCircle(cs.primary.copy(alpha = alpha), r, c)
            } else {
                drawCircle(cs.surface.copy(alpha = alpha), r, c)
                drawCircle(cs.primary.copy(alpha = alpha), r, c, style = Stroke(width = 2.dp.toPx()))
            }
            val label = measurer.measure("${i + 1}", if (i == selected) handleText else handleTextOff)
            drawText(label, topLeft = Offset(c.x - label.size.width / 2f, c.y - label.size.height / 2f))
        }
        if (!enabled) drawRect(Color.Transparent)
    }
}
