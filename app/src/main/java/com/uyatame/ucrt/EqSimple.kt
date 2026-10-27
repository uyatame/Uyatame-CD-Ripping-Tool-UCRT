package com.uyatame.ucrt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.eqBandList
import com.uyatame.ucrt.data.eqSimpleList
import com.uyatame.ucrt.player.EqBand
import com.uyatame.ucrt.player.EqBands
import com.uyatame.ucrt.player.ParamEq
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** シンプルのイコライザーで動かせる幅(±dB。標準のプリセットの「パーフェクト」の +11 dB が収まるように) */
const val SIMPLE_EQ_MAX = 12f

/** 今の設定が、あるプリセットと同じなら、その名前(どれとも違えば null) */
fun eqPresetName(s: AppSettings): String? = eqPresetNameOf(s.eqMode, s.eqSimpleList, s.eqBandList, s.eqCustom)

// ---------------- シンプル(10 バンド) ----------------

/**
 * WALKMAN のイコライザーのような 10 バンドの画面。帯域をタップして選び、上下になぞるか「+」「−」で調整する。
 * 選んだ帯域の値を上に出し、パラメトリック EQ と同じ周波数特性の曲線を重ねて描く(ZX300 のように)
 */
@Composable
fun GraphicEq(
    gains: FloatArray,
    enabled: Boolean,
    onChange: (FloatArray) -> Unit,
    onDone: () -> Unit,
) {
    var sel by remember { mutableIntStateOf(5) }
    val cs = MaterialTheme.colorScheme
    val gainsNow by rememberUpdatedState(gains)
    val changeNow by rememberUpdatedState(onChange)
    val doneNow by rememberUpdatedState(onDone)
    fun set(i: Int, v: Float) {
        val g = gainsNow.copyOf()
        g[i] = ((v * 2).roundToInt() / 2f).coerceIn(-SIMPLE_EQ_MAX, SIMPLE_EQ_MAX)
        changeNow(g)
    }
    val n = EqBands.FREQS.size
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ---- 選んだ帯域と値 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                freqLabel(EqBands.FREQS[sel]), style = MaterialTheme.typography.titleSmall,
                color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            val v = gains[sel]
            Text(
                (if (v > 0) "+" else "") + "%.1f".format(java.util.Locale.US, v) + " dB",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = if (enabled) cs.primary else cs.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(
                Modifier.weight(1f).height(230.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHighest)
                    .pointerInput(enabled) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (!enabled) return@awaitEachGesture
                            val colW = size.width.toFloat() / n
                            val h = size.height.toFloat()
                            val pad = 14.dp.toPx()
                            fun dbAt(y: Float) = (h / 2 - y) / (h / 2 - pad) * SIMPLE_EQ_MAX
                            val i = (down.position.x / colW).toInt().coerceIn(0, n - 1)
                            sel = i
                            down.consume()
                            // 押したところから上下になぞると、その帯域を動かす(押しただけなら選ぶだけ)
                            val startY = down.position.y
                            var moved = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                if (c.positionChanged()) {
                                    c.consume()
                                    if (moved || abs(c.position.y - startY) > 6.dp.toPx()) {
                                        moved = true
                                        set(i, dbAt(c.position.y))
                                    }
                                }
                            }
                            if (moved) doneNow()
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                val colW = w / n
                val pad = 14.dp.toPx()
                fun yOf(db: Float) = h / 2 - db / SIMPLE_EQ_MAX * (h / 2 - pad)
                val alpha = if (enabled) 1f else 0.4f
                // 選んだ帯域の列を明るく
                drawRect(cs.primary.copy(alpha = 0.10f * alpha), Offset(sel * colW, 0f), Size(colW, h))
                // 目盛りの横線(1 dB ごと。0 dB は太く)
                val dash = colW * 0.62f
                for (i in 0 until n) {
                    val cx = (i + 0.5f) * colW
                    for (db in -SIMPLE_EQ_MAX.toInt()..SIMPLE_EQ_MAX.toInt()) {
                        val y = yOf(db.toFloat())
                        drawLine(
                            cs.onSurfaceVariant.copy(alpha = (if (db == 0) 0.55f else 0.18f) * alpha),
                            Offset(cx - dash / 2, y), Offset(cx + dash / 2, y), if (db == 0) 2.dp.toPx() else 1.dp.toPx(),
                        )
                    }
                    // 0 dB から今の値までを薄く塗り、今の値に太い線
                    val y = yOf(gains[i])
                    val y0 = yOf(0f)
                    drawRect(
                        cs.primary.copy(alpha = 0.22f * alpha),
                        Offset(cx - dash / 2, minOf(y, y0)), Size(dash, abs(y - y0)),
                    )
                    drawRoundRect(
                        cs.primary.copy(alpha = alpha), Offset(cx - dash / 2 - 2.dp.toPx(), y - 3.dp.toPx()),
                        Size(dash + 4.dp.toPx(), 6.dp.toPx()), CornerRadius(3.dp.toPx()),
                    )
                }
                // 周波数特性の曲線(実際に掛かる音の変化)を重ねる。列の中心が各帯域の周波数(1 オクターブずつ)
                val bands = ParamEq.fromGains(gains)
                val path = Path()
                val steps = 120
                val f0 = EqBands.FREQS.first().toDouble()
                val curve = ParamEq.responseCurve(
                    bands,
                    DoubleArray(steps + 1) { k -> (f0 * Math.pow(2.0, (k.toFloat() / steps * w / colW - 0.5f).toDouble())).coerceIn(10.0, 22000.0) },
                )
                for (k in 0..steps) {
                    val x = k.toFloat() / steps * w
                    val db = curve[k].toFloat().coerceIn(-SIMPLE_EQ_MAX * 1.3f, SIMPLE_EQ_MAX * 1.3f)
                    val y = yOf(db)
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, cs.tertiary.copy(alpha = 0.9f * alpha), style = Stroke(width = 2.5f.dp.toPx(), cap = StrokeCap.Round))
            }
            // ---- 「+」「−」(選んだ帯域を 0.5 dB ずつ) ----
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(
                    { set(sel, gains[sel] + 0.5f); onDone() }, enabled = enabled && gains[sel] < SIMPLE_EQ_MAX,
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) { Icon(Icons.Filled.Add, "+0.5 dB") }
                Text("±${SIMPLE_EQ_MAX.toInt()}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                FilledIconButton({ set(sel, gains[sel] - 0.5f); onDone() }, enabled = enabled && gains[sel] > -SIMPLE_EQ_MAX) {
                    Text("−", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        // ---- 周波数の名前(タップで選ぶ) ----
        Row(Modifier.fillMaxWidth().padding(end = 56.dp)) {
            EqBands.LABELS.forEachIndexed { i, l ->
                Text(
                    l, textAlign = TextAlign.Center, maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (i == sel) FontWeight.Bold else null,
                    color = if (i == sel) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f).clickable(enabled = enabled) { sel = i },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton({ onChange(FloatArray(n)); onDone() }, enabled = enabled) { Text(S(R.string.flat)) }
        }
    }
}

// ---------------- プリセットの一覧 ----------------

/** 一覧の 1 行に添える、周波数特性の小さな絵 */
@Composable
private fun CurveThumb(bands: List<EqBand>, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val pts = remember(bands) {
        val lnMin = ln(20.0)
        val lnMax = ln(20000.0)
        val c = ParamEq.responseCurve(bands, DoubleArray(48) { k -> kotlin.math.exp(lnMin + (lnMax - lnMin) * k / 47.0) })
        FloatArray(48) { c[it].toFloat() }
    }
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerHighest)) {
        val w = size.width
        val h = size.height
        val range = 12f
        fun yOf(db: Float) = h / 2 - db.coerceIn(-range, range) / range * (h / 2 - 3.dp.toPx())
        drawLine(cs.outlineVariant, Offset(0f, h / 2), Offset(w, h / 2), 1.dp.toPx())
        val path = Path()
        val fill = Path()
        pts.forEachIndexed { k, db ->
            val x = k / (pts.size - 1f) * w
            val y = yOf(db)
            if (k == 0) { path.moveTo(x, y); fill.moveTo(x, h / 2); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(w, h / 2)
        fill.close()
        drawPath(fill, cs.primary.copy(alpha = 0.18f))
        drawPath(path, cs.primary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** プリセットの 1 行(名前・波形・選んでいるか。保存したものは名前の変更・削除のメニュー付き) */
@Composable
private fun PresetRow(
    name: String,
    badge: String?,
    bands: List<EqBand>,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    menu: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) cs.secondaryContainer else cs.surface)
            .border(1.dp, if (selected) cs.primary else cs.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = if (menu != null) 0.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(10.dp).clip(CircleShape).background(if (selected) cs.primary else cs.outlineVariant),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) Text(badge, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        CurveThumb(bands, Modifier.width(110.dp).height(40.dp))
        if (menu != null) menu()
    }
}

/**
 * イコライザーのプリセット(標準のものと、保存したもの)の縦の一覧と、「今の設定を保存」。
 * シンプルでは標準のものと、シンプルで保存したもの。PRO ではすべて(シンプルのものは 10 バンドの帯域として読み込む)。
 * PRO で保存したものをシンプルで選ぶと、PRO に切り替えて読み込む
 */
@Composable
fun EqPresetList(
    vm: MainViewModel,
    s: AppSettings,
    enabled: Boolean,
    onApplyBuiltIn: (FloatArray) -> Unit,
    onApplyCustom: (EqCustom) -> Unit,
    current: () -> EqCustom,
) {
    val customs = remember(s.eqCustom) { EqCustom.decode(s.eqCustom) }
    val now = eqPresetName(s)
    var saving by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<EqCustom?>(null) }
    var deleting by remember { mutableStateOf<EqCustom?>(null) }
    fun saveList(list: List<EqCustom>) = vm.set(com.uyatame.ucrt.data.Keys.eqCustom, EqCustom.encode(list))

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton({ saving = true }, enabled = enabled) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(S(R.string.save_current_eq))
            }
        }
        if (customs.isNotEmpty()) {
            Text(S(R.string.saved_presets), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            customs.forEach { c ->
                PresetRow(
                    c.name, if (c.pro) "PRO" else S(R.string.eq_simple), c.bands(), now == c.name, enabled,
                    onClick = { onApplyCustom(c) },
                    menu = {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            IconButton({ open = true }, enabled = enabled) { Icon(Icons.Filled.MoreVert, S(R.string.menu)) }
                            DropdownMenu(open, { open = false }) {
                                DropdownMenuItem(
                                    text = { Text(S(R.string.rename)) }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                    onClick = { open = false; renaming = c },
                                )
                                DropdownMenuItem(
                                    text = { Text(S(R.string.overwrite_with_current)) }, leadingIcon = { Icon(Icons.Filled.Add, null) },
                                    onClick = {
                                        open = false
                                        val cur = current()
                                        saveList(customs.map { if (it === c) cur.copy(name = c.name) else it })
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(S(R.string.delete)) }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                    onClick = { open = false; deleting = c },
                                )
                            }
                        }
                    },
                )
            }
            Text(S(R.string.standard_presets), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        EqBands.PRESETS.forEach { p ->
            val bands = remember(p) { ParamEq.fromGains(p.gains) }
            val label = p.label()
            PresetRow(label, null, bands, now == label, enabled, onClick = { onApplyBuiltIn(p.gains) })
        }
    }

    if (saving) {
        NameDialog(S(R.string.save_current_eq), suggestName(customs), { saving = false }) { name ->
            val cur = current().copy(name = name)
            // 同じ名前があれば置き換える
            saveList(customs.filter { it.name != name } + cur)
        }
    }
    renaming?.let { c ->
        NameDialog(S(R.string.rename), c.name, { renaming = null }) { name ->
            saveList(customs.filter { it.name != name || it === c }.map { if (it === c) it.copy(name = name) else it })
        }
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(S(R.string.delete_preset_q, c.name)) },
            confirmButton = { TextButton({ saveList(customs.filter { it !== c }); deleting = null }) { Text(S(R.string.delete)) } },
            dismissButton = { TextButton({ deleting = null }) { Text(S(R.string.cancel)) } },
        )
    }
}

private fun suggestName(list: List<EqCustom>): String {
    var i = list.size + 1
    while (list.any { it.name == S(R.string.my_eq_n, i) }) i++
    return S(R.string.my_eq_n, i)
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name, { name = it.replace('\n', ' ') }, singleLine = true,
                label = { Text(S(R.string.preset_name)) }, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton({ onDone(name.trim()); onDismiss() }, enabled = name.isNotBlank()) { Text(S(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(S(R.string.cancel)) } },
    )
}
