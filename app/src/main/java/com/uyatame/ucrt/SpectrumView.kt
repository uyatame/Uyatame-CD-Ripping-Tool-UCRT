package com.uyatame.ucrt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.uyatame.ucrt.player.SpectrumAnalyzer
import com.uyatame.ucrt.player.SpectrumTap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** スペクトラムアナライザーの見せ方(設定の順) */
object SpectrumStyle {
    const val BARS = 0
    const val MIRROR = 1
    const val LINE = 2
    const val WAVE = 3
    const val CIRCLE = 4
    const val COUNT = 5

    fun label(i: Int): String = when (i) {
        MIRROR -> S(R.string.spectrum_mirror)
        LINE -> S(R.string.spectrum_line)
        WAVE -> S(R.string.spectrum_wave)
        CIRCLE -> S(R.string.spectrum_circle)
        else -> S(R.string.spectrum_bars)
    }
}

/** 声の帯域(300 Hz〜6 kHz)を強めて見せるときの重み(周波数ごと) */
private val vocalPoints = arrayOf(
    30f to 0.25f, 80f to 0.40f, 150f to 0.65f, 300f to 1.05f, 500f to 1.20f, 1000f to 1.40f,
    2000f to 1.55f, 3500f to 1.35f, 6000f to 1.05f, 10000f to 0.80f, 16000f to 0.55f, 20000f to 0.40f,
)

private fun vocalWeight(hz: Float): Float {
    val f = hz.coerceIn(vocalPoints.first().first, vocalPoints.last().first)
    for (i in 0 until vocalPoints.lastIndex) {
        val (f0, w0) = vocalPoints[i]
        val (f1, w1) = vocalPoints[i + 1]
        if (f <= f1) return w0 + (w1 - w0) * ((ln(f) - ln(f0)) / (ln(f1) - ln(f0))).coerceIn(0f, 1f)
    }
    return vocalPoints.last().second
}

/** 動きをなめらかにする(上がるときは速く、下がるときはゆっくり。ピークは少し残す) */
private class SpectrumMotion {
    val bands = FloatArray(SpectrumTap.BANDS)
    val peaks = FloatArray(SpectrumTap.BANDS)
    val wave = FloatArray(128)
    private var last = 0L

    /** 1 フレーム分動かす。いちばん大きく動いた量を返す(止まっていれば描き直さなくてよい) */
    fun step(a: SpectrumAnalyzer, vocal: Boolean, now: Long): Float {
        var moved = 0f
        val dt = if (last == 0L) 1f / 60 else ((now - last).coerceIn(1_000_000L, 200_000_000L) / 1e9f)
        last = now
        val up = 1f - exp(-60f * dt)
        val down = 1f - exp(-9f * dt)
        val wr = 1f - exp(-50f * dt)
        val n = bands.size
        for (i in 0 until n) {
            var t = a.bands[i]
            if (vocal) {
                val hz = 35.0 * Math.pow(20000.0 / 35.0, (i + 0.5) / n)
                t = (t * vocalWeight(hz.toFloat())).coerceIn(0f, 1f)
            }
            // 床を少し切って、真ん中あたりの動きを見やすくする
            t = ((t - 0.06f) / 0.94f).coerceIn(0f, 1f)
            val d = (t - bands[i]) * (if (t > bands[i]) up else down)
            bands[i] += d
            val p = if (bands[i] >= peaks[i]) bands[i] else max(bands[i], peaks[i] - 0.9f * dt)
            moved = max(moved, max(kotlin.math.abs(d), kotlin.math.abs(p - peaks[i])))
            peaks[i] = p
        }
        for (i in wave.indices) {
            val d = ((a.wave[i] * 1.2f).coerceIn(-1f, 1f) - wave[i]) * wr
            wave[i] += d
            moved = max(moved, kotlin.math.abs(d))
        }
        return moved
    }
}

/**
 * 再生の画面のスペクトラムアナライザー。タップで見せ方を切り替える(onStyle)。
 * 画面が動いている間だけ計算する(画面の描画のタイミングで回すので、裏に回ると止まる)
 */
@Composable
fun SpectrumView(
    style: Int,
    vocal: Boolean,
    refreshMs: Int,
    delayMs: Int,
    color: Color,
    height: Dp,
    onStyle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(Unit) {
        SpectrumTap.acquire()
        onDispose { SpectrumTap.release() }
    }
    val analyzer = remember { SpectrumAnalyzer() }
    val motion = remember { SpectrumMotion() }
    var tick by remember { mutableIntStateOf(0) }
    val refresh by rememberUpdatedState(refreshMs.coerceIn(8, 200))
    val delay by rememberUpdatedState(delayMs)
    val vocalNow by rememberUpdatedState(vocal)
    LaunchedEffect(Unit) {
        var lastCalc = 0L
        var still = false
        while (true) {
            if (still) {
                // 止まっている間(一時停止・無音)は、画面の描き直しを頼まずに間隔をあけて音を確かめるだけにする
                kotlinx.coroutines.delay(refresh.toLong().coerceAtLeast(50L))
                val before = analyzer.bands.copyOf()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { analyzer.update(delay) }
                if (!analyzer.bands.contentEquals(before)) { still = false; lastCalc = 0L }
                continue
            }
            val now = withFrameNanos { it }
            if (now - lastCalc >= refresh * 1_000_000L) {
                lastCalc = now
                // 周波数の計算(FFT)は、画面のスレッドの外で行う
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { analyzer.update(delay) }
            }
            val moved = motion.step(analyzer, vocalNow, now)
            tick++
            // 動きがほとんど無くなったら、止まったものとして描き直しをやめる
            if (moved < 0.0005f) still = true
        }
    }
    val hi = Color(
        red = color.red + (1f - color.red) * 0.55f, green = color.green + (1f - color.green) * 0.55f,
        blue = color.blue + (1f - color.blue) * 0.55f, alpha = 1f,
    )
    Canvas(
        modifier.fillMaxWidth().height(height)
            .clickable(remember { MutableInteractionSource() }, null) { onStyle((style + 1) % SpectrumStyle.COUNT) },
    ) {
        // 描き直しの合図(値そのものは使わない)
        if (tick < 0) return@Canvas
        val b = motion.bands
        val n = b.size
        val w = size.width
        val h = size.height
        when (style) {
            SpectrumStyle.MIRROR -> {
                val slot = w / n; val bw = slot * 0.62f; val cy = h / 2
                for (i in 0 until n) {
                    val bh = max(1f, h * 0.5f * b[i])
                    drawRoundRect(color.copy(alpha = 0.9f), Offset(i * slot + (slot - bw) / 2, cy - bh), Size(bw, bh * 2), CornerRadius(bw * 0.4f))
                }
            }
            SpectrumStyle.LINE -> {
                val path = smoothPath(b, { i -> w * i / (n - 1) }, { v -> h * (1 - v) })
                val fill = Path().apply { addPath(path); lineTo(w, h); lineTo(0f, h); close() }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0f))))
                drawPath(path, Brush.horizontalGradient(listOf(color, hi, color)), style = Stroke(2.5f * density, cap = StrokeCap.Round))
            }
            SpectrumStyle.WAVE -> {
                val wv = motion.wave
                val path = smoothPath(wv, { i -> w * i / (wv.size - 1) }, { v -> h / 2 - v * h * 0.45f })
                drawPath(path, Brush.horizontalGradient(listOf(color.copy(alpha = 0.5f), hi, color)), style = Stroke(2f * density, cap = StrokeCap.Round))
            }
            SpectrumStyle.CIRCLE -> {
                val cx = w / 2; val cy = h / 2
                val r = min(w, h) * 0.26f
                for (i in 0 until n) {
                    val a = i.toDouble() / n * PI * 2 - PI / 2
                    val c = cos(a).toFloat(); val s = sin(a).toFloat()
                    val o = r + h * 0.22f * b[i]
                    drawLine(color.copy(alpha = 0.9f), Offset(cx + c * r, cy + s * r), Offset(cx + c * o, cy + s * o), 2f * density, StrokeCap.Round)
                }
                drawCircle(hi.copy(alpha = 0.5f), r, Offset(cx, cy), style = Stroke(1f * density))
            }
            else -> {
                val slot = w / n; val bw = slot * 0.62f
                val brush = Brush.verticalGradient(listOf(hi, color.copy(alpha = 0.85f)), 0f, h)
                for (i in 0 until n) {
                    val x = i * slot + (slot - bw) / 2
                    val bh = max(2f, h * b[i])
                    drawRoundRect(brush, Offset(x, h - bh), Size(bw, bh), CornerRadius(bw * 0.35f))
                    val py = (h - h * motion.peaks[i]).coerceAtMost(h - 2f)
                    drawLine(hi.copy(alpha = 0.8f), Offset(x, py), Offset(x + bw, py), 1.5f * density)
                }
            }
        }
    }
}

private fun smoothPath(v: FloatArray, x: (Int) -> Float, y: (Float) -> Float): Path {
    val p = Path()
    if (v.isEmpty()) return p
    p.moveTo(x(0), y(v[0]))
    for (i in 1 until v.size) {
        val mx = (x(i - 1) + x(i)) / 2
        p.cubicTo(mx, y(v[i - 1]), mx, y(v[i]), x(i), y(v[i]))
    }
    return p
}
