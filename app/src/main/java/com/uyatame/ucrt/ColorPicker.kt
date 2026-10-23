package com.uyatame.ucrt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** 色を HSV(色相 0〜360・鮮やかさ 0〜1・明るさ 0〜1)にする */
fun argbToHsv(argb: Int): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }

fun hsvToArgb(h: Float, s: Float, v: Float): Int = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))

/** #RRGGBB の形の文字(読めなければ null) */
fun parseHex(text: String): Int? {
    val t = text.trim().removePrefix("#")
    if (t.length != 6) return null
    return t.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
}

fun hexOf(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/**
 * カラーピッカー。四角(横: 鮮やかさ、縦: 明るさ)と色相の帯で色を選び、#RRGGBB でも入力できる。
 * preview: 選んでいる色の見え方の見本(テーマの色なら色の丸、文字の色なら背景の上の文字など)
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onReset: (() -> Unit)? = null,
    preview: @Composable (Int) -> Unit = { c -> Box(Modifier.size(40.dp).clip(CircleShape).background(Color(c))) },
) {
    val hsv0 = remember { argbToHsv(initial) }
    var hue by remember { mutableFloatStateOf(hsv0[0]) }
    var sat by remember { mutableFloatStateOf(hsv0[1]) }
    var value by remember { mutableFloatStateOf(hsv0[2]) }
    val color = hsvToArgb(hue, sat, value)
    // 入力欄: 色をつまみで変えたら書き換え、入力が正しい形になったら色に反映する
    var hex by remember { mutableStateOf(hexOf(initial)) }
    fun sync() { hex = hexOf(hsvToArgb(hue, sat, value)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ---- 鮮やかさ(横)と明るさ(縦) ----
                val hueColor = Color(hsvToArgb(hue, 1f, 1f))
                Canvas(
                    Modifier.fillMaxWidth().aspectRatio(1.4f).clip(RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectTapGestures {
                                sat = (it.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (it.y / size.height).coerceIn(0f, 1f)
                                sync()
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(onDragStart = {
                                sat = (it.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (it.y / size.height).coerceIn(0f, 1f)
                                sync()
                            }) { change, _ ->
                                change.consume()
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                sync()
                            }
                        },
                ) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val c = Offset(sat * size.width, (1f - value) * size.height)
                    drawCircle(Color.White, 10.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.Black.copy(alpha = 0.5f), 12.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                }
                // ---- 色相 ----
                val hues = remember { (0..6).map { Color(hsvToArgb(it * 60f, 1f, 1f)) } }
                Canvas(
                    Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp))
                        .pointerInput(Unit) {
                            detectTapGestures { hue = (it.x / size.width).coerceIn(0f, 1f) * 360f; sync() }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(onDragStart = { hue = (it.x / size.width).coerceIn(0f, 1f) * 360f; sync() }) { change, _ ->
                                change.consume()
                                hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                                sync()
                            }
                        },
                ) {
                    drawRect(Brush.horizontalGradient(hues))
                    val x = hue / 360f * size.width
                    drawCircle(Color.White, size.height / 2 - 2.dp.toPx(), Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
                }
                // ---- 見本と #RRGGBB ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    preview(color)
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        hex,
                        { t ->
                            hex = t
                            parseHex(t)?.let { c ->
                                val h = argbToHsv(c)
                                hue = h[0]; sat = h[1]; value = h[2]
                            }
                        },
                        singleLine = true, label = { Text(S(R.string.color_code)) },
                        isError = parseHex(hex) == null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = { TextButton({ onPick(color); onDismiss() }) { Text(S(R.string.ok)) } },
        dismissButton = {
            Row {
                if (onReset != null) TextButton({ onReset(); onDismiss() }) { Text(S(R.string.default_label)) }
                TextButton(onDismiss) { Text(S(R.string.cancel)) }
            }
        },
    )
}

/** 設定の行: 名前・説明と、今の色の丸(タップでカラーピッカー) */
@Composable
fun ColorSettingRow(title: String, desc: String?, shown: Color, isDefault: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) {
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(shown).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isDefault) Text("A", color = MaterialTheme.colorScheme.surface, style = MaterialTheme.typography.labelLarge)
        }
    }
}
