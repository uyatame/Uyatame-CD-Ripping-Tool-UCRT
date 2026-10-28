package com.uyatame.ucrt

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * リキッドグラス(設定の「表示」で選ぶ見た目。Apple の iOS 26 のデザインに近づけたもの)。
 *
 * 下のタブとミニプレーヤーなどを、中身の上に浮かぶガラスにする。ガラスの後ろには、その下にある中身をぼかして
 * (Android 12 から)鮮やかさを少し上げて映し、ふちに光の反射(上は明るく、下は薄く)を描く。
 * 中身はガラスの下までスクロールでき、いちばん下はガラスに隠れない分だけ余白を足す(LocalBarInset)。
 * Android 11 までは、ぼかさずに半透明のガラスにする
 */

/** 見た目: 0 = 標準(今まで通り)、1 = リキッドグラス */
val LocalGlass = compositionLocalOf { false }

/** 中身の下に浮かんでいるガラスの高さ(スクロールするものの下の余白に足す。標準の見た目では 0) */
val LocalBarInset = compositionLocalOf { 0.dp }

/** 下に浮かんでいるガラスに隠れない余白 */
@Composable
fun barInset(): Dp = LocalBarInset.current

/** ガラスの後ろに映す中身(記録したもの)と、その画面の中での位置 */
class Backdrop(val layer: GraphicsLayer) {
    var origin = Offset.Zero
}

val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** この中身を記録して、ガラスの後ろに映せるようにする */
fun Modifier.backdropSource(b: Backdrop): Modifier = this
    .onGloballyPositioned { b.origin = it.positionInRoot() }
    .drawWithContent {
        b.layer.record { this@drawWithContent.drawContent() }
        drawLayer(b.layer)
    }

/**
 * ガラスの調整(設定の「表示」)。opacity: ガラスの濃さ(0.1〜0.95。小さいほど透ける)、tint: ガラスの色(null = テーマに合わせる)
 */
data class GlassStyle(val opacity: Float = 0.55f, val tint: Color? = null)

val LocalGlassStyle = compositionLocalOf { GlassStyle() }

/** ガラスの色(テーマの明暗に合わせるか、選んだ色) */
@Composable
fun glassTint(): Color {
    val st = LocalGlassStyle.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return st.tint ?: if (dark) Color(0xFF1F1F23) else Color(0xFFFAFAFC)
}

/** WCAG のコントラスト比 */
private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/**
 * ガラスの上の文字の色。設定の文字の色(テーマの文字の色)が、ガラスの上で読みやすければそのまま、
 * 読みにくければ(ガラスの色や濃さを変えて背景が明るく・暗くなったときなど)白か黒に切り替える
 */
@Composable
fun glassContent(alpha: Float = LocalGlassStyle.current.opacity): Color {
    val scheme = MaterialTheme.colorScheme
    val tint = glassTint()
    // ガラスの見た目の色 ≒ 後ろ(背景)の上に、ガラスの色を濃さの分だけ重ねた色
    val bg = androidx.compose.ui.graphics.lerp(scheme.background, tint.copy(alpha = 1f), alpha.coerceIn(0f, 1f))
    val want = scheme.onSurface
    return if (contrast(want, bg) >= 3.5f) want else if (bg.luminance() > 0.4f) Color(0xFF141418) else Color.White
}

/** 鮮やかさを上げる色の行列(Apple のガラスの「にじむ色」) */
private val vibrancy = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.6f) })

/**
 * ガラスの面。後ろの中身をぼかして映し、薄い色を重ね、ふちに光の反射を描く。
 * shape の形に切り取り、少し影を付けて浮かせる
 */
@Composable
fun GlassSurface(modifier: Modifier, shape: Shape, content: @Composable BoxScope.() -> Unit) {
    val b = LocalBackdrop.current
    val blur = rememberGraphicsLayer()
    val pos = remember { floatArrayOf(0f, 0f) }
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val canBlur = Build.VERSION.SDK_INT >= 31 && b != null
    // ガラスの色(後ろが透ける)。ぼかせない端末では、透け方を控えめにして読みやすくする
    // (後ろに映す中身が無いところ(再生画面など)は、なめらかな壁紙の上なので、ぼかさずに同じだけ透かす)
    val clear = Build.VERSION.SDK_INT >= 31
    val alpha = LocalGlassStyle.current.opacity.let { if (clear) it else maxOf(it, 0.88f) }
    val tint = glassTint().copy(alpha = alpha)
    val fg = glassContent(alpha)
    val rim = glassRim(dark)
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.10f else 0.30f),
        0.5f to Color.Transparent,
    )
    Box(
        modifier
            .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.22f))
            .clip(shape)
            .onGloballyPositioned { val p = it.positionInRoot(); pos[0] = p.x; pos[1] = p.y }
            .drawBehind {
                if (canBlur && b != null) {
                    val r = 22.dp.toPx()
                    blur.renderEffect = BlurEffect(r, r, TileMode.Clamp)
                    blur.colorFilter = vibrancy
                    blur.record(IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))) {
                        translate(b.origin.x - pos[0], b.origin.y - pos[1]) { drawLayer(b.layer) }
                    }
                    drawLayer(blur)
                }
                drawRect(tint)
                drawRect(sheen)
            }
            .border(1.dp, rim, shape),
    ) {
        // 文字とアイコンの色(黒に決め打ちにせず、ガラスの色と濃さに合わせて読みやすい色にする)
        CompositionLocalProvider(LocalContentColor provides fg) { content() }
    }
}

/** 下のタブ(浮かぶガラスのカプセル)。選んでいるタブの後ろに、ガラスのレンズが動いてくる */
@Composable
fun GlassTabBar(tabs: List<Triple<ImageVector, String, Int>>, selected: Int, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    val idx = tabs.indexOfFirst { it.third == selected }.coerceAtLeast(0)
    val at by animateFloatAsState(idx.toFloat(), spring(dampingRatio = 0.72f, stiffness = 420f), label = "lens")
    val accent = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    GlassSurface(modifier.fillMaxWidth().height(64.dp), RoundedCornerShape(32.dp)) {
        // 両端のタブの文字が、丸い角にかからないように内側に余白を取る
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp)) {
            val w = maxWidth / tabs.size
            // 選んでいるタブのレンズ(少し明るいガラス)
            Box(
                Modifier.offset(x = w * at).width(w).fillMaxHeight().clip(RoundedCornerShape(28.dp))
                    .drawBehind {
                        drawRect(accent.copy(alpha = if (dark) 0.22f else 0.13f))
                        drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.10f else 0.35f), 1f to Color.Transparent))
                    }
                    .border(0.8.dp, Color.White.copy(alpha = if (dark) 0.18f else 0.6f), RoundedCornerShape(28.dp)),
            )
            Row(Modifier.fillMaxSize()) {
                tabs.forEach { (icon, label, i) ->
                    val sel = i == selected
                    val col = if (sel) accent else LocalContentColor.current.copy(alpha = 0.8f)
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp))
                            .clickable(remember { MutableInteractionSource() }, null) { onTab(i) }
                            .padding(horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Icon(icon, label, Modifier.size(24.dp), tint = col)
                        // 長い名前(アップサンプルなど)は、選んでいるときは流して全部見せ、それ以外は省略する
                        Text(
                            label, color = col, fontSize = 10.sp, maxLines = 1, softWrap = false,
                            overflow = if (sel) TextOverflow.Clip else TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                            modifier = if (sel) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1200) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

/** 横向きの左のレール(縦長の浮かぶガラス) */
@Composable
fun GlassRail(tabs: List<Triple<ImageVector, String, Int>>, selected: Int, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    GlassSurface(modifier.width(76.dp), RoundedCornerShape(38.dp)) {
        Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            tabs.forEach { (icon, label, i) ->
                val sel = i == selected
                val col = if (sel) accent else LocalContentColor.current.copy(alpha = 0.8f)
                Column(
                    Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(30.dp))
                        .drawBehind {
                            if (sel) {
                                drawRect(accent.copy(alpha = if (dark) 0.22f else 0.13f))
                                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.10f else 0.35f), 1f to Color.Transparent))
                            }
                        }
                        .clickable(remember { MutableInteractionSource() }, null) { onTab(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(icon, label, Modifier.size(24.dp), tint = col)
                    Text(label, color = col, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * 下に出る帯(ミニプレーヤー・取り込みの進み具合など)の入れ物。
 * 標準の見た目では今まで通りの帯、リキッドグラスでは角の丸い浮かぶガラス
 */
@Composable
fun BarSurface(onClick: () -> Unit, color: Color? = null, content: @Composable () -> Unit) {
    if (LocalGlass.current) {
        GlassSurface(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clickable(onClick = onClick),
            RoundedCornerShape(24.dp),
        ) {
            // 色付きの帯(取り込み中など)は、ガラスにその色を薄く重ねる
            if (color != null) Box(Modifier.matchParentSize().drawBehind { drawRect(color.copy(alpha = 0.35f)) })
            // 角が大きく丸いので、端のボタンや文字が角にかからないよう、内側に余白を取る
            Box(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) { content() }
        }
    } else {
        androidx.compose.material3.Surface(
            color = color ?: MaterialTheme.colorScheme.surface,
            tonalElevation = if (color == null) 3.dp else 0.dp,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        ) { content() }
    }
}

/** ガラスのふちの光(左上が明るく、真ん中は薄く、右下に少し返る) */
fun glassRim(dark: Boolean): Brush = Brush.linearGradient(
    0f to Color.White.copy(alpha = if (dark) 0.30f else 0.80f),
    0.45f to Color.White.copy(alpha = if (dark) 0.05f else 0.18f),
    1f to Color.White.copy(alpha = if (dark) 0.16f else 0.45f),
    start = Offset(0f, 0f), end = Offset.Infinite,
)

/**
 * カード。標準の見た目では今まで通りの Card、リキッドグラスでは角の大きい半透明のガラス
 * (後ろの壁紙の色が透け、ふちに光、上に薄いつや)
 */
@Composable
fun UCard(
    modifier: Modifier = Modifier,
    colors: androidx.compose.material3.CardColors? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (!LocalGlass.current) {
        androidx.compose.material3.Card(modifier, colors = colors ?: androidx.compose.material3.CardDefaults.cardColors(), content = content)
        return
    }
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val shape = RoundedCornerShape(22.dp)
    val st = LocalGlassStyle.current
    val base = colors?.containerColor ?: glassTint()
    val alpha = if (Build.VERSION.SDK_INT >= 31) (st.opacity * 0.95f).coerceIn(0.08f, 0.95f) else maxOf(st.opacity, 0.85f)
    val sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.07f else 0.25f), 0.35f to Color.Transparent)
    androidx.compose.material3.Card(
        modifier.clip(shape).border(1.dp, glassRim(dark), shape).drawBehind { drawRect(sheen) },
        shape = shape,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = base.copy(alpha = alpha),
            contentColor = colors?.contentColor ?: glassContent(alpha),
        ),
        content = content,
    )
}

/**
 * リキッドグラスの壁紙(ガラスの後ろに透ける色)。テーマの色から作った、ぼんやりした光のにじみ
 */
fun Modifier.glassWallpaper(primary: Color, tertiary: Color, secondary: Color, base: Color, dark: Boolean): Modifier = drawBehind {
    drawRect(base)
    val a = if (dark) 0.30f else 0.22f
    fun blob(c: Color, cx: Float, cy: Float, r: Float) = drawCircle(
        Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy),
    )
    val w = size.width; val h = size.height; val m = maxOf(w, h)
    blob(primary, w * 0.1f, h * 0.08f, m * 0.65f)
    blob(tertiary, w * 0.95f, h * 0.55f, m * 0.6f)
    blob(secondary, w * 0.2f, h * 0.95f, m * 0.55f)
}

/** リキッドグラスのときだけ、中身をガラスのカプセルに入れる(標準の見た目では、そのまま) */
@Composable
fun GlassIf(shape: Shape, content: @Composable () -> Unit) {
    if (LocalGlass.current) GlassSurface(Modifier, shape) { Box(Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) { content() } }
    else content()
}
