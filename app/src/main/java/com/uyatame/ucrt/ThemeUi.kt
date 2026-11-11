package com.uyatame.ucrt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * テーマで形が変わる共通の部品(下のタブ・一覧の行・カードの見出しなど)。
 * どのテーマでも、並び・押せる場所・役割は同じ。変えるのは面・線・丸み・文字の太さだけ
 */

/** 下のタブ(縦向き・リキッドグラスでないとき) */
@Composable
fun ThemedNavBar(tabs: List<Triple<ImageVector, String, Int>>, selected: Int, onTab: (Int) -> Unit) {
    val ut = LocalUiTheme.current
    val sch = MaterialTheme.colorScheme
    when (ut.nav) {
        // 選んだタブだけ、アイコンと名前を横に並べた丸い面にする(ほかはアイコンと小さな名前)
        "pill" -> Surface(color = sch.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(68.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                tabs.forEach { (icon, label, i) ->
                    val sel = i == selected
                    Row(
                        Modifier.height(44.dp).clip(CircleShape).background(if (sel) sch.secondaryContainer else Color.Transparent)
                            .clickable { onTab(i) }.padding(horizontal = if (sel) 14.dp else 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, label, tint = if (sel) sch.onSecondaryContainer else sch.onSurfaceVariant)
                        if (sel) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                color = sch.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        // 中身の上に浮かぶ、丸い暗いバー(選んだタブは差し色の面)
        "floating" -> Box(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        ) {
            Surface(
                Modifier.fillMaxWidth().height(64.dp).shadow(8.dp, RoundedCornerShape(32.dp)),
                shape = RoundedCornerShape(32.dp),
                color = sch.inverseSurface,
                contentColor = sch.inverseOnSurface,
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEach { (icon, label, i) ->
                        val sel = i == selected
                        Column(
                            Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(26.dp)).clickable { onTab(i) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                Modifier.size(width = 44.dp, height = 28.dp).clip(CircleShape)
                                    .background(if (sel) sch.inversePrimary else Color.Transparent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    icon, null, Modifier.size(20.dp),
                                    tint = if (sel) sch.inverseSurface else sch.inverseOnSurface.copy(alpha = 0.75f),
                                )
                            }
                            Text(
                                label, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                color = if (sel) sch.inverseOnSurface else sch.inverseOnSurface.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
            }
        }
        // 平らな面と上の線。選んだタブは上に差し色の線を引き、文字を太くする
        "line" -> {
            val line = sch.outline
            Row(
                Modifier.fillMaxWidth().background(sch.background)
                    .drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                    .windowInsetsPadding(WindowInsets.navigationBars).height(64.dp),
            ) {
                tabs.forEach { (icon, label, i) ->
                    val sel = i == selected
                    val c = if (sel) sch.onBackground else sch.onSurfaceVariant
                    val mark = sch.primary
                    Column(
                        Modifier.weight(1f).fillMaxSize().clickable { onTab(i) }
                            .drawBehind { if (sel) drawRect(mark, Offset(size.width * 0.2f, 0f), androidx.compose.ui.geometry.Size(size.width * 0.6f, 3.dp.toPx())) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(icon, null, Modifier.size(22.dp), tint = c)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            label, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (sel) FontWeight.Black else FontWeight.Normal, color = c,
                        )
                    }
                }
            }
        }
        else -> NavigationBar {
            tabs.forEach { (icon, label, i) ->
                NavigationBarItem(i == selected, { onTab(i) }, { Icon(icon, null) }, label = { Text(label) })
            }
        }
    }
}

/**
 * 一覧の行の面と線(曲・アルバム・アーティストなど)。highlight は再生中の行など。
 * plain: 角の丸い面 / tinted: 行ごとに薄い面(行のあいだを少し空ける) / divided: 角なし・下に区切り線
 */
@Composable
fun Modifier.themedRow(highlight: Boolean = false): Modifier {
    val ut = LocalUiTheme.current
    val sch = MaterialTheme.colorScheme
    val hi = if (highlight) sch.secondaryContainer else Color.Transparent
    return when (ut.listRow) {
        "tinted" -> {
            val shape = RoundedCornerShape(14.dp)
            this.padding(vertical = 2.dp).clip(shape).background(if (highlight) sch.secondaryContainer else sch.surfaceContainerLow)
        }
        "divided" -> {
            val line = sch.outlineVariant
            this.clip(RectangleShape).background(hi)
                .drawBehind { drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
        }
        else -> this.clip(RoundedCornerShape(12.dp)).background(hi)
    }
}

/** カードの見出し(設定の区切りなど) */
@Composable
fun SectionTitle(title: String) {
    val ut = LocalUiTheme.current
    when (ut.sectionTitle) {
        "caps" -> Text(
            title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        "bold" -> Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        else -> Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** ジャケットの角の丸み(テーマで決めていればその値、なければ画面ごとの値) */
@Composable
fun themedCorner(default: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp {
    val c = LocalUiTheme.current.coverCorner
    return if (c >= 0) c.dp else default
}

/** カードの形・色・線(UCard が使う) */
@Composable
fun themedCard(
    modifier: Modifier,
    colors: androidx.compose.material3.CardColors?,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val ut = LocalUiTheme.current
    val sch = MaterialTheme.colorScheme
    when (ut.card) {
        "outlined" -> androidx.compose.material3.Card(
            modifier,
            colors = colors ?: androidx.compose.material3.CardDefaults.cardColors(containerColor = sch.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, sch.outlineVariant),
            content = content,
        )
        // 面を付けず、上に区切り線だけ引く
        "flat" -> androidx.compose.material3.Card(
            modifier.drawBehind {
                drawLine(sch.outlineVariant, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            },
            shape = RectangleShape,
            colors = colors ?: androidx.compose.material3.CardDefaults.cardColors(containerColor = Color.Transparent),
            content = content,
        )
        // 枠付きの表示窓(角は小さく、面は一段沈める)
        "panel" -> androidx.compose.material3.Card(
            modifier,
            shape = RoundedCornerShape(6.dp),
            colors = colors ?: androidx.compose.material3.CardDefaults.cardColors(containerColor = sch.surfaceContainerLow),
            border = androidx.compose.foundation.BorderStroke(1.dp, sch.outline.copy(alpha = 0.5f)),
            content = content,
        )
        else -> androidx.compose.material3.Card(modifier, colors = colors ?: androidx.compose.material3.CardDefaults.cardColors(), content = content)
    }
}

/** 帯(ミニプレーヤー・取り込み中など)の面。BarSurface が使う(リキッドグラスでないとき) */
@Composable
fun ThemedBar(onClick: () -> Unit, color: Color?, content: @Composable () -> Unit) {
    val ut = LocalUiTheme.current
    val sch = MaterialTheme.colorScheme
    when (ut.bars) {
        "floating" -> Surface(
            color = color ?: sch.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 4.dp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        ) { Box(Modifier.padding(horizontal = 4.dp)) { content() } }
        "line" -> {
            val line = sch.outline
            Surface(
                color = color ?: sch.background,
                modifier = Modifier.fillMaxWidth()
                    .drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                    .clickable(onClick = onClick),
            ) { content() }
        }
        else -> Surface(
            color = color ?: sch.surface,
            tonalElevation = if (color == null) 3.dp else 0.dp,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        ) { content() }
    }
}

/** 画面の見出し(ライブラリ・ディスク・ストリーミング・アップサンプル・設定など、タブの一番上) */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier) {
    val ut = LocalUiTheme.current
    val sch = MaterialTheme.colorScheme
    when (ut.header) {
        // 大きく太い文字と、下に差し色の短い線
        "large" -> Column(modifier) {
            Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.padding(top = 4.dp).size(width = 32.dp, height = 4.dp).clip(CircleShape).background(sch.primary))
        }
        // 字間を空けた文字と、下に端までの細い線
        "caps" -> {
            val line = sch.outline
            Text(
                title, style = MaterialTheme.typography.headlineSmall, letterSpacing = 2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = modifier.drawBehind { drawLine(line, Offset(0f, size.height + 4.dp.toPx()), Offset(size.width, size.height + 4.dp.toPx()), 1.dp.toPx()) }
                    .padding(bottom = 2.dp),
            )
        }
        else -> Text(title, style = MaterialTheme.typography.headlineMedium, modifier = modifier)
    }
}

/** ボタンの形(丸い端・角丸・小さな角) */
@Composable
fun themedButtonShape(): androidx.compose.ui.graphics.Shape = when (LocalUiTheme.current.button) {
    "soft" -> RoundedCornerShape(12.dp)
    "square" -> RoundedCornerShape(4.dp)
    else -> CircleShape
}

/** アイコンの台の形(ライブラリのカテゴリー・設定の項目・頭文字の丸など)。default は、その場所の今までの形 */
@Composable
fun themedTileShape(default: androidx.compose.ui.graphics.Shape): androidx.compose.ui.graphics.Shape = when (LocalUiTheme.current.tile) {
    "circle" -> CircleShape
    "soft" -> RoundedCornerShape(30)
    "square" -> RoundedCornerShape(4.dp)
    else -> default
}

/** ボタン(形だけテーマに合わせる。ほかは Material 3 のボタンと同じ) */
@Composable
fun UButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: androidx.compose.material3.ButtonColors = androidx.compose.material3.ButtonDefaults.buttonColors(),
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.material3.ButtonDefaults.ContentPadding,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) = androidx.compose.material3.Button(
    onClick, modifier, enabled, shape = themedButtonShape(), colors = colors, contentPadding = contentPadding, content = content,
)

@Composable
fun UTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: androidx.compose.material3.ButtonColors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(),
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.material3.ButtonDefaults.ContentPadding,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) = androidx.compose.material3.FilledTonalButton(
    onClick, modifier, enabled, shape = themedButtonShape(), colors = colors, contentPadding = contentPadding, content = content,
)

@Composable
fun UOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: androidx.compose.material3.ButtonColors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.material3.ButtonDefaults.ContentPadding,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) = androidx.compose.material3.OutlinedButton(
    onClick, modifier, enabled, shape = themedButtonShape(), colors = colors, contentPadding = contentPadding, content = content,
)

/** 進み具合のバー(値が決まっているもの)。default は Material 3 のまま、thick は太く丸いバー、blocks は区切った四角 */
@Composable
fun UProgress(progress: () -> Float, modifier: Modifier = Modifier) {
    val ut = LocalUiTheme.current
    if (ut.progress == "default") {
        androidx.compose.material3.LinearProgressIndicator(progress, modifier)
        return
    }
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.surfaceContainerHighest
    val blocks = ut.progress == "blocks"
    // (高さ・幅は、呼ぶ側が決めていればそちらが勝つ)
    androidx.compose.foundation.Canvas(modifier.width(240.dp).height(if (blocks) 8.dp else 6.dp)) {
        val p = progress().coerceIn(0f, 1f)
        if (blocks) {
            val n = (size.width / 10.dp.toPx()).toInt().coerceIn(4, 40)
            val gap = 2.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            val lit = (p * n + 0.5f).toInt()
            for (i in 0 until n) {
                drawRect(if (i < lit) on else off, Offset(i * (w + gap), 0f), androidx.compose.ui.geometry.Size(w, size.height))
            }
        } else {
            val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            drawRoundRect(off, cornerRadius = r)
            if (p > 0f) drawRoundRect(on, size = androidx.compose.ui.geometry.Size((size.width * p).coerceAtLeast(size.height), size.height), cornerRadius = r)
        }
    }
}
