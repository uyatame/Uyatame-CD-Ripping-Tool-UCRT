package com.uyatame.ucrt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.uyatame.ucrt.aging.Aging
import com.uyatame.ucrt.aging.AgingAnalyzer
import com.uyatame.ucrt.aging.AgingClock
import com.uyatame.ucrt.aging.AgingSource
import com.uyatame.ucrt.player.SpectrumTap
import kotlinx.coroutines.delay

/** 音源の名前 */
private fun agingSourceName(s: AgingSource): String = when (s) {
    AgingSource.UCRT -> S(R.string.aging_src_ucrt)
    AgingSource.LIBRARY_AUTO -> S(R.string.aging_src_auto)
    AgingSource.LIBRARY_PICK -> S(R.string.aging_src_pick)
    AgingSource.PINK -> S(R.string.aging_src_pink)
    AgingSource.WHITE -> S(R.string.aging_src_white)
    AgingSource.BROWN -> S(R.string.aging_src_brown)
    AgingSource.SWEEP -> S(R.string.aging_src_sweep)
}

private fun agingSourceDesc(s: AgingSource): String = when (s) {
    AgingSource.UCRT -> S(R.string.aging_src_ucrt_desc)
    AgingSource.LIBRARY_AUTO -> S(R.string.aging_src_auto_desc)
    AgingSource.LIBRARY_PICK -> S(R.string.aging_src_pick_desc)
    AgingSource.SWEEP -> S(R.string.aging_src_sweep_desc)
    else -> ""
}

/** エージングの画面(始める前の設定と、進んでいる間の計器の画面) */
@Composable
fun AgingScreen(vm: MainViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        PageHeader(S(R.string.aging), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp + barInset()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                Aging.active -> AgingRunning(vm)
                Aging.finished -> AgingDone()
                else -> AgingSetup(vm)
            }
        }
    }
}

@Composable
private fun ColumnScope.AgingSetup(vm: MainViewModel) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Aging.loadSaved(ctx) }
    var hoursIdx by remember { mutableIntStateOf(1) }
    var interval by remember { mutableStateOf(true) }
    var source by remember { mutableStateOf(AgingSource.UCRT) }
    val choices = remember { vm.agingPickChoices() }
    var pick by remember { mutableIntStateOf(0) }

    Aging.saved?.let { p ->
        Section(S(R.string.aging_saved, (p * 100).toInt())) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UButton({
                    if (!Aging.resumeSaved(ctx, vm.viewModelScope, vm.player) { vm.agingItemsOf(it) }) {
                        vm.snack = S(R.string.aging_no_songs)
                    }
                }) { Text(S(R.string.aging_continue)) }
                UOutlinedButton({ Aging.discardSaved(ctx) }) { Text(S(R.string.aging_discard)) }
            }
        }
    }

    Section(S(R.string.aging)) {
        Text(S(R.string.aging_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Segmented(S(R.string.aging_hours), AgingClock.HOURS.map { "$it h" }, hoursIdx) { hoursIdx = it }
        SwitchRow(S(R.string.aging_interval), S(R.string.aging_interval_desc), interval) { interval = it }
    }

    Section(S(R.string.aging_source)) {
        for (s in AgingSource.entries) {
            Row(
                Modifier.fillMaxWidth().clickable { source = s }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = source == s, onClick = { source = s })
                Column(Modifier.weight(1f)) {
                    Text(agingSourceName(s), style = MaterialTheme.typography.bodyLarge)
                    val d = agingSourceDesc(s)
                    if (d.isNotEmpty()) Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (source == AgingSource.LIBRARY_PICK) {
            if (choices.isEmpty()) {
                Text(S(R.string.aging_pick_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                ChoiceList(S(R.string.aging_src_pick), choices.map { it.first }, pick.coerceIn(0, choices.lastIndex)) { pick = it }
            }
        }
    }

    Box(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Text(S(R.string.aging_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }

    UButton(
        {
            val items = when (source) {
                AgingSource.LIBRARY_AUTO -> vm.agingAutoItems()
                AgingSource.LIBRARY_PICK -> choices.getOrNull(pick)?.second.orEmpty()
                else -> null
            }
            val ok = Aging.start(ctx, vm.viewModelScope, vm.player, source, AgingClock.HOURS[hoursIdx], interval, items)
            if (!ok) vm.snack = S(R.string.aging_no_songs)
        },
        Modifier.fillMaxWidth().height(52.dp),
    ) { Text(S(R.string.aging_start)) }
}

/** 計器の枠(B: 枠付きの表示窓) */
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val sch = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth()
            .border(BorderStroke(1.dp, sch.outlineVariant), RoundedCornerShape(10.dp))
            .background(sch.surfaceContainerLow, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun PanelLabel(left: String, right: String = "") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(left, fontSize = 11.sp, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (right.isNotEmpty()) Text(right, fontSize = 11.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ColumnScope.AgingRunning(vm: MainViewModel) {
    val ctx = LocalContext.current
    val sch = MaterialTheme.colorScheme
    val accent = sch.primary
    var confirm by remember { mutableStateOf(false) }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(S(R.string.aging_stop_confirm)) },
            text = { Text(S(R.string.aging_stop_confirm_desc)) },
            confirmButton = { TextButton({ confirm = false; Aging.stop(ctx) }) { Text(S(R.string.aging_stop)) } },
            dismissButton = { TextButton({ confirm = false }) { Text(S(R.string.cancel)) } },
        )
    }

    // 経過・コース
    Panel {
        PanelLabel(S(R.string.aging_elapsed), S(R.string.aging_course, (Aging.targetMs / 3_600_000L).toInt()))
        Text(
            agingTime(Aging.playedMs), color = accent, fontSize = 44.sp, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
        )
        val blocks = 50
        val filled = (Aging.progress * blocks).toInt()
        Row(Modifier.fillMaxWidth().height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 0 until blocks) {
                val c = when {
                    i < filled -> accent
                    i == filled && !Aging.resting && !Aging.paused -> sch.tertiary
                    else -> sch.surfaceVariant
                }
                Box(Modifier.weight(1f).fillMaxSize().background(c, RoundedCornerShape(1.dp)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${(Aging.progress * 100).toInt()}%", fontSize = 12.sp, color = sch.onSurfaceVariant)
            Text(S(R.string.aging_remaining, agingTime(Aging.remainingWallMs)), fontSize = 12.sp, color = sch.onSurfaceVariant)
        }
    }

    AgingScopes(accent)

    // 状態・休憩・音源
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val state = when {
            Aging.paused -> S(R.string.aging_paused)
            Aging.resting -> S(R.string.aging_resting)
            else -> S(R.string.aging_playing)
        }
        StatTile(S(R.string.aging_state), state, Modifier.weight(1f))
        if (Aging.resting) StatTile(S(R.string.aging_rest_left), agingTime(Aging.restLeftMs), Modifier.weight(1f))
        else StatTile(S(R.string.aging_until_rest), if (Aging.untilRestMs < 0) S(R.string.aging_none) else agingTime(Aging.untilRestMs), Modifier.weight(1f))
        StatTile(S(R.string.aging_source), Aging.detail.ifEmpty { agingSourceName(Aging.source) }, Modifier.weight(1f))
    }
    Text(
        agingSourceName(Aging.source) + (Aging.outputLabel.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
        fontSize = 12.sp, color = sch.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        UButton({ Aging.togglePause() }, Modifier.weight(1f).height(52.dp)) {
            Text(if (Aging.paused) S(R.string.aging_resume) else S(R.string.pause))
        }
        UOutlinedButton({ confirm = true }, Modifier.weight(1f).height(52.dp)) { Text(S(R.string.aging_stop)) }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * スペクトラムと波形。作った音は、鳴らした音そのものをハイレゾ帯域まで分ける(AgingAnalyzer)。
 * ライブラリの曲は、再生の画面と同じ写し(SpectrumTap。20 kHz まで)から分ける
 */
@Composable
private fun AgingScopes(accent: Color) {
    val sch = MaterialTheme.colorScheme
    val library = !Aging.source.generated
    DisposableEffect(library) {
        if (library) SpectrumTap.acquire()
        onDispose { if (library) SpectrumTap.release() }
    }
    var frame by remember { mutableIntStateOf(0) }
    val state = remember { ScopeState() }
    LaunchedEffect(library) {
        while (true) {
            delay(66)
            state.update(library)
            frame++
        }
    }
    val density = LocalDensity.current
    val labelColor = sch.onSurfaceVariant.toArgb()
    val grid = sch.outlineVariant

    Panel {
        val an = state.analyzer
        PanelLabel(
            S(R.string.aging_spectrum),
            an?.let { "10 Hz – " + (if (it.highHz >= 1000) "%.0f kHz".format(java.util.Locale.ROOT, it.highHz / 1000) else "${it.highHz.toInt()} Hz") } ?: "",
        )
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            @Suppress("UNUSED_EXPRESSION") frame
            val a = state.analyzer ?: return@Canvas
            val v = state.smooth
            val w = size.width
            val h = size.height
            // 目盛り(dB の横線)
            for (i in 1..3) drawLine(grid, Offset(0f, h * i / 4), Offset(w, h * i / 4), 1f)
            val paint = android.graphics.Paint().apply {
                color = labelColor
                textSize = with(density) { 10.sp.toPx() }
                isAntiAlias = true
            }
            val nc = drawContext.canvas.nativeCanvas as android.graphics.Canvas
            for (hz in doubleArrayOf(20.0, 200.0, 2000.0, 20000.0)) {
                if (hz >= a.highHz) continue
                val x = a.positionOf(hz) * w
                drawLine(grid, Offset(x, 0f), Offset(x, h), 1f)
                val t = if (hz >= 1000) "${(hz / 1000).toInt()}k" else "${hz.toInt()}"
                nc.drawText(t, x + 3f, h - 3f, paint)
            }
            // 可聴域の上の端(20 kHz)。これより右は聞こえない帯域
            if (a.highHz > 21000) {
                val x = a.positionOf(20000.0) * w
                drawLine(sch.onSurfaceVariant, Offset(x, 0f), Offset(x, h), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                nc.drawText(S(R.string.aging_audible) + " ←", x - paint.measureText(S(R.string.aging_audible) + " ←") - 4f, paint.textSize + 2f, paint)
            }
            if (v.isEmpty()) return@Canvas
            // -80〜-20 dB を下から上へ
            fun y(db: Float) = h * (1f - ((db + 80f) / 60f).coerceIn(0f, 1f))
            val line = Path()
            val fill = Path()
            for (b in v.indices) {
                val x = (b + 0.5f) / v.size * w
                val yy = y(v[b])
                if (b == 0) { line.moveTo(x, yy); fill.moveTo(x, h); fill.lineTo(x, yy) } else { line.lineTo(x, yy); fill.lineTo(x, yy) }
            }
            fill.lineTo(w, h)
            fill.close()
            drawPath(fill, accent.copy(alpha = 0.18f))
            drawPath(line, accent, style = Stroke(2.dp.toPx()))
        }
        if (library) Text(S(R.string.aging_analyzer_library), fontSize = 11.sp, color = sch.onSurfaceVariant)
    }

    Panel {
        PanelLabel(S(R.string.aging_wave))
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            @Suppress("UNUSED_EXPRESSION") frame
            val w = size.width
            val h = size.height
            val l = state.waveL
            val r = state.waveR
            // 実効値 -20 dBFS の音が見やすいよう、少し大きくして描く
            val gain = 3f
            fun draw(src: FloatArray, mid: Float, c: Color) {
                val p = Path()
                for (i in src.indices) {
                    val x = i.toFloat() / (src.size - 1) * w
                    val y = mid - (src[i] * gain).coerceIn(-1f, 1f) * h / 4.4f
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                drawPath(p, c, style = Stroke(1.4.dp.toPx()))
            }
            draw(l, h / 4, accent)
            draw(r, h * 3 / 4, sch.tertiary)
        }
    }
}

/** スペクトラムと波形の値(画面の表示用。ゆっくり下がるようにならす) */
private class ScopeState {
    var analyzer: AgingAnalyzer? = null
    var smooth = FloatArray(0)
    val waveL = FloatArray(256)
    val waveR = FloatArray(256)
    private var tapL = FloatArray(0)
    private var tapR = FloatArray(0)
    private var stereo = FloatArray(0)

    fun update(library: Boolean) {
        val bands: FloatArray?
        if (!library) {
            val a = Aging.analyzer
            analyzer = a
            bands = a?.bandsDb()
            System.arraycopy(Aging.waveL, 0, waveL, 0, waveL.size)
            System.arraycopy(Aging.waveR, 0, waveR, 0, waveR.size)
        } else {
            val n = 8192
            if (tapL.size != n) { tapL = FloatArray(n); tapR = FloatArray(n); stereo = FloatArray(n * 2) }
            val rate = SpectrumTap.windowStereo(tapL, tapR, n, 0)
            if (rate <= 0) {
                bands = null
                waveL.fill(0f); waveR.fill(0f)
            } else {
                if (analyzer?.rate != rate) analyzer = AgingAnalyzer(rate)
                val a = analyzer!!
                val m = minOf(n, a.size)
                for (i in 0 until m) { stereo[2 * i] = tapL[n - m + i]; stereo[2 * i + 1] = tapR[n - m + i] }
                a.push(stereo, m)
                bands = a.bandsDb()
                for (i in waveL.indices) { waveL[i] = tapL[n - waveL.size + i]; waveR[i] = tapR[n - waveR.size + i] }
            }
        }
        if (bands == null) {
            // 音が無い: ゆっくり下げる
            for (i in smooth.indices) smooth[i] -= 2f
            return
        }
        if (smooth.size != bands.size) smooth = bands.copyOf()
        // 上がるときはすぐ、下がるときはゆっくり
        for (i in bands.indices) smooth[i] = if (bands[i] > smooth[i]) bands[i] else smooth[i] - 1.5f.coerceAtMost(smooth[i] - bands[i])
    }
}

@Composable
private fun ColumnScope.AgingDone() {
    Panel {
        Text(S(R.string.aging_done), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        UButton({ Aging.finished = false }) { Text(S(R.string.close)) }
    }
}
