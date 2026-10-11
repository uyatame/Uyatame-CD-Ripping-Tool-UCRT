@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.uyatame.ucrt

import android.app.Activity
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.outputMode
import com.uyatame.ucrt.library.ArtLoader
import com.uyatame.ucrt.library.QualityClass
import com.uyatame.ucrt.player.AudioEngine
import com.uyatame.ucrt.player.AudioInfo
import com.uyatame.ucrt.player.EqBands
import com.uyatame.ucrt.player.Lyrics
import com.uyatame.ucrt.player.LyricsLoader
import com.uyatame.ucrt.player.OutputKind
import com.uyatame.ucrt.player.PlayItem
import com.uyatame.ucrt.player.PlayerController
import com.uyatame.ucrt.player.usb.UsbDac
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 再生中の曲のジャケット画像 */
@Composable
private fun rememberArt(vm: MainViewModel, item: PlayItem): ImageBitmap? {
    if (item.cdTrack != null) return vm.cdCover
    val ctx = LocalContext.current
    val gen = ArtLoader.generation
    val img by produceState<ImageBitmap?>(ArtLoader.peek(item.artKey), item.artKey, item.artTrackUri, gen) {
        val k = item.artKey
        value = ArtLoader.peek(k)
        if (value == null && k != null) {
            value = withContext(Dispatchers.IO) { ArtLoader.load(ctx, k, item.artTrackUri, item.artCoverUri) }
        }
    }
    return img
}

/**
 * ジャケットの代表色。鮮やかな色を重く数えて平均し、背景に使いやすい明るさにそろえる。
 */
private fun dominantColor(img: ImageBitmap): Color {
    val src = img.asAndroidBitmap()
    val small = Bitmap.createScaledBitmap(src, 24, 24, true)
    val hsv = FloatArray(3)
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var w = 0.0
    for (y in 0 until small.height) for (x in 0 until small.width) {
        val c = small.getPixel(x, y)
        android.graphics.Color.colorToHSV(c, hsv)
        val wt = 0.05 + hsv[1] * hsv[2]
        r += android.graphics.Color.red(c) * wt
        g += android.graphics.Color.green(c) * wt
        b += android.graphics.Color.blue(c) * wt
        w += wt
    }
    if (small !== src) small.recycle()
    if (w <= 0) return Color(0xFF2A2A2A)
    val avg = android.graphics.Color.rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt())
    android.graphics.Color.colorToHSV(avg, hsv)
    hsv[1] = hsv[1].coerceAtMost(0.75f)
    hsv[2] = hsv[2].coerceIn(0.32f, 0.58f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** 再生画面の文字色など */
private class PlayerColors(val content: Color, val sub: Color, val accent: Color, val ambient: Boolean, val panel: Color)

/**
 * 全画面のプレイヤー。左右のページで「お気に入り|再生中|再生リスト」を切り替える。
 * アンビエントモードでは、ジャケットの色で画面を染め、一時停止中は暗くする。
 */
@Composable
fun NowPlayingScreen(vm: MainViewModel, s: AppSettings, onClose: () -> Unit) {
    val p = vm.player
    val cur = p.current ?: return
    val ctx = LocalContext.current
    val ambient = s.ambientPlayer
    val art = rememberArt(vm, cur)
    val base by produceState(Color(0xFF2A2A2A), art) {
        val a = art
        value = if (a == null) Color(0xFF2A2A2A) else withContext(Dispatchers.Default) { runCatching { dominantColor(a) }.getOrNull() } ?: Color(0xFF2A2A2A)
    }
    val tint by animateColorAsState(base, tween(700), label = "tint")
    val dim by animateFloatAsState(if (p.isPlaying) 0f else 0.4f, tween(500), label = "dim")
    val detail by produceState<AudioInfo.Detail?>(null, cur) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { AudioInfo.detail(ctx, cur) }.getOrNull() }
    }
    val lyrics by produceState<Lyrics?>(null, cur.id) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { LyricsLoader.load(ctx, cur) }.getOrNull() }
    }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showSound by remember { mutableStateOf(false) }
    var showOutput by remember { mutableStateOf(false) }
    var showVolume by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var savingQueue by remember { mutableStateOf(false) }
    if (showSound) AudioSettingsSheet(vm, s) { showSound = false }
    if (showOutput) OutputDeviceSheet(vm, s) { showOutput = false }
    if (showVolume) VolumeSheet(vm) { showVolume = false }
    if (showTimer) SleepTimerDialog(p) { showTimer = false }
    if (showGuide) GuideDialog { showGuide = false }
    if (savingQueue) SaveQueueDialog(vm) { savingQueue = false }

    val pager = rememberPagerState(initialPage = 1) { 3 }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    // 左右のページを表示中は、戻るで再生中のページへ
    BackHandler(enabled = pager.currentPage != 1) { scope.launch { pager.animateScrollToPage(1) } }

    val dark = ambient || MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3 < 0.5f }
    val view = LocalView.current
    DisposableEffect(dark) {
        val w = (view.context as? Activity)?.window
        val c = w?.let { WindowCompat.getInsetsController(it, view) }
        val before = c?.isAppearanceLightStatusBars
        c?.isAppearanceLightStatusBars = !dark
        onDispose { if (before != null) c?.isAppearanceLightStatusBars = before }
    }
    val col = if (ambient) {
        PlayerColors(Color.White, Color.White.copy(alpha = 0.75f), Color(0xFFF3D58C), true, Color.White.copy(alpha = 0.12f))
    } else {
        PlayerColors(
            MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.tertiary, false, MaterialTheme.colorScheme.secondaryContainer,
        )
    }

    // 下にある画面へタッチが抜けないよう、空の pointerInput で受け止める
    Box(Modifier.fillMaxSize().background(if (ambient) Color.Black else MaterialTheme.colorScheme.surface).pointerInput(Unit) {}) {
        // ---- 背景 ----
        if (ambient) {
            if (art != null) {
                Image(art, null, Modifier.fillMaxSize().blur(70.dp), contentScale = ContentScale.Crop, alpha = 0.85f)
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.8f))),
                ),
            )
            // 一時停止中は暗くする
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(tint.copy(alpha = 0.22f), Color.Transparent)),
                ),
            )
        }
        CompositionLocalProvider(LocalContentColor provides col.content) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                // ---- 上の帯: 閉じる・ページの切り替え・メニュー ----
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Filled.KeyboardArrowDown, T("閉じる", "Close"), tint = col.content) }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                        listOf(T("お気に入り", "Favorites"), T("再生中", "Playing"), T("再生リスト", "Queue")).forEachIndexed { i, l ->
                            val sel = pager.currentPage == i
                            Column(
                                Modifier.clip(RoundedCornerShape(8.dp)).clickable { scope.launch { pager.animateScrollToPage(i) } }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    l, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                                    color = if (sel) col.content else col.sub.copy(alpha = 0.6f),
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                )
                                Box(
                                    Modifier.padding(top = 3.dp).size(width = 16.dp, height = 3.dp).clip(CircleShape)
                                        .background(if (sel) col.accent else Color.Transparent),
                                )
                            }
                        }
                    }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, T("メニュー", "Menu"), tint = col.content) }
                        DropdownMenu(menu, { menu = false }) {
                            val lib = vm.libTrackOf(cur)
                            DropdownMenuItem(
                                text = { Text(if (showLyrics) T("歌詞を隠す", "Hide lyrics") else T("歌詞を表示", "Show lyrics")) },
                                leadingIcon = { Icon(AppIcons.Lyrics, null) },
                                onClick = { menu = false; showLyrics = !showLyrics; scope.launch { pager.animateScrollToPage(1) } },
                            )
                            DropdownMenuItem(
                                text = { Text(T("スリープタイマー", "Sleep timer")) },
                                leadingIcon = { Icon(AppIcons.Timer, null) },
                                onClick = { menu = false; showTimer = true },
                            )
                            DropdownMenuItem(
                                text = { Text(T("曲の詳細情報", "Song details")) },
                                leadingIcon = { Icon(Icons.Filled.Info, null) },
                                onClick = { menu = false; vm.infoItem = cur },
                            )
                            if (lib != null) {
                                vm.albumKeyOf(lib.uri)?.let { k ->
                                    DropdownMenuItem(
                                        text = { Text(T("アルバムを表示", "Go to album")) },
                                        leadingIcon = { Icon(AppIcons.Album, null) },
                                        onClick = { menu = false; vm.openRoute("album:$k") },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(T("アーティストを表示", "Go to artist")) },
                                    leadingIcon = { Icon(Icons.Filled.Person, null) },
                                    onClick = { menu = false; vm.openRoute("artist:" + artistOf(lib)) },
                                )
                                DropdownMenuItem(
                                    text = { Text(T("プレイリストに追加", "Add to playlist")) },
                                    leadingIcon = { Icon(AppIcons.PlaylistAdd, null) },
                                    onClick = { menu = false; vm.playlistPick = listOf(lib.uri) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(T("音質設定", "Sound settings")) },
                                leadingIcon = { Icon(AppIcons.Tune, null) },
                                onClick = { menu = false; showSound = true },
                            )
                            DropdownMenuItem(
                                text = { Text(T("操作ガイド", "Gesture guide")) },
                                leadingIcon = { Icon(Icons.Filled.Info, null) },
                                onClick = { menu = false; showGuide = true },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(T("再生を終了", "Stop playback")) },
                                leadingIcon = { Icon(Icons.Filled.Close, null) },
                                onClick = { menu = false; p.stop(); onClose() },
                            )
                        }
                    }
                }
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                    when (page) {
                        0 -> FavoritesPanel(vm, col)
                        1 -> PlayerPage(
                            vm, s, cur, art, detail, lyrics, showLyrics, col,
                            onToggleLyrics = { showLyrics = !showLyrics },
                            onClose = onClose,
                            onSound = { showSound = true },
                            onOutput = { showOutput = true },
                            onVolume = { showVolume = true },
                            onTimer = { showTimer = true },
                            onQueue = { scope.launch { pager.animateScrollToPage(2) } },
                        )
                        else -> QueuePanel(vm, col) { savingQueue = true }
                    }
                }
            }
        }
    }
}

// =====================================================================
// 再生中のページ
// =====================================================================

@Composable
private fun PlayerPage(
    vm: MainViewModel,
    s: AppSettings,
    cur: PlayItem,
    art: ImageBitmap?,
    detail: AudioInfo.Detail?,
    lyrics: Lyrics?,
    showLyrics: Boolean,
    col: PlayerColors,
    onToggleLyrics: () -> Unit,
    onClose: () -> Unit,
    onSound: () -> Unit,
    onOutput: () -> Unit,
    onVolume: () -> Unit,
    onTimer: () -> Unit,
    onQueue: () -> Unit,
) {
    val p = vm.player
    val closeNow by rememberUpdatedState(onClose)
    val soundNow by rememberUpdatedState(onSound)
    var drag by remember { mutableStateOf(0f) }
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // 下へスワイプで閉じる、上へスワイプで音質設定
            detectVerticalDragGestures(
                onDragEnd = {
                    if (drag > 220f) closeNow() else if (drag < -220f) soundNow()
                    drag = 0f
                },
                onDragCancel = { drag = 0f },
            ) { _, d -> drag += d }
        },
    ) {
        // 画面の小さい端末(高さが足りない・幅が狭い)では、文字やボタンを小さくし、余白を詰める
        val compact = maxHeight < 640.dp || maxWidth < 340.dp
        val tiny = maxHeight < 540.dp
        val playSize = if (compact) 64.dp else 80.dp
        val skipSize = if (compact) 50.dp else 60.dp
        var seeking by remember(cur.id) { mutableStateOf<Float?>(null) }
        val dur = p.durationMs.coerceAtLeast(1)
        val frac = seeking ?: (p.positionMs.toFloat() / dur).coerceIn(0f, 1f)
        val lib = vm.libTrackOf(cur)

        Column(
            Modifier.fillMaxSize().padding(horizontal = if (compact) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- ジャケット(または歌詞) ----
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(vertical = if (compact) 6.dp else 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (showLyrics) {
                    if (lyrics != null) {
                        LyricsView(lyrics, p.positionMs, col, Modifier.fillMaxSize()) { p.seek(it) }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Icon(AppIcons.Lyrics, null, Modifier.size(40.dp), tint = col.sub)
                            Spacer(Modifier.height(12.dp))
                            Text(T("歌詞が見つかりません", "No lyrics found"), style = MaterialTheme.typography.titleMedium, color = col.content)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                T(
                                    "曲と同じ名前の .lrc ファイルを同じフォルダに置くか、曲に歌詞を埋め込むと表示されます",
                                    "Put a .lrc file with the same name next to the song, or embed lyrics in the file",
                                ),
                                style = MaterialTheme.typography.bodySmall, color = col.sub, textAlign = TextAlign.Center,
                            )
                            TextButton(onToggleLyrics) { Text(T("ジャケットに戻る", "Back to artwork"), color = col.accent) }
                        }
                    }
                } else {
                    SwipeCover(
                        art, compact,
                        overlay = seeking?.let { fmtMs((it * dur).toLong()) },
                        onPrev = { p.prev() }, onNext = { p.next() }, onTap = onToggleLyrics,
                    )
                }
            }
            // ---- 音質 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (detail?.hiRes == true) {
                    QualityBadge("Hi-Res", QualityClass.HiRes)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    detail?.text ?: " ", style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                    color = col.accent, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
            // ---- 出力先・ビットパーフェクト・EQ ----
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val chipColors = if (col.ambient) {
                    FilterChipDefaults.filterChipColors(labelColor = col.sub, selectedLabelColor = Color.Black, selectedContainerColor = col.accent)
                } else FilterChipDefaults.filterChipColors()
                val outDev = AudioEngine.output
                FilterChip(
                    selected = false,
                    onClick = onOutput,
                    label = { Text(outDev?.name ?: T("出力先", "Output"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        Icon(
                            outputIcon(outDev?.kind ?: OutputKind.Auto), null,
                            Modifier.size(18.dp), tint = if (col.ambient) col.sub else MaterialTheme.colorScheme.primary,
                        )
                    },
                    colors = chipColors,
                    border = if (col.ambient) BorderStroke(1.dp, col.sub) else FilterChipDefaults.filterChipBorder(enabled = true, selected = false),
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                val mode = s.outputMode
                val bpSel = mode != 0
                val eqSel = s.eqEnabled && !(mode != 0 && AudioEngine.bitPerfectActive)
                FilterChip(
                    selected = bpSel,
                    onClick = onSound,
                    label = { Text(if (mode != 0 && AudioEngine.bitPerfectActive) "Bit-perfect ✓" else "Bit-perfect") },
                    colors = chipColors,
                    border = if (col.ambient) BorderStroke(1.dp, col.sub) else FilterChipDefaults.filterChipBorder(enabled = true, selected = bpSel),
                )
                FilterChip(
                    selected = eqSel,
                    onClick = onSound,
                    label = {
                        val name = EqBands.PRESETS.getOrNull(s.eqPreset)?.let { T(it.ja, it.en) } ?: T("カスタム", "Custom")
                        Text("EQ" + if (s.eqEnabled) " · $name" else " OFF")
                    },
                    colors = chipColors,
                    border = if (col.ambient) BorderStroke(1.dp, col.sub) else FilterChipDefaults.filterChipBorder(enabled = true, selected = eqSel),
                )
            }
            Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
            // ---- 曲名(タップでアルバム・アーティストへ)とお気に入り ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val fav = lib != null && vm.store.isFav(lib.uri)
                IconButton({ lib?.let { vm.toggleFav(it.uri) } }, enabled = lib != null) {
                    Icon(
                        if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        if (fav) T("お気に入りから外す", "Remove from favorites") else T("お気に入りに追加", "Add to favorites"),
                        tint = when { lib == null -> col.sub.copy(alpha = 0.3f); fav -> col.accent; else -> col.sub },
                    )
                }
                var related by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f)) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = lib != null) { related = true }
                            .padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            cur.title, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            cur.artist, style = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                            color = col.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (cur.album.isNotEmpty() && !tiny) {
                            Text(cur.album, style = MaterialTheme.typography.bodyMedium, color = col.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (lib != null) {
                        DropdownMenu(related, { related = false }) {
                            vm.albumKeyOf(lib.uri)?.let { k ->
                                DropdownMenuItem(
                                    text = { Text(T("アルバム「${lib.album}」を表示", "Go to album \"${lib.album}\"")) },
                                    leadingIcon = { Icon(AppIcons.Album, null) },
                                    onClick = { related = false; vm.openRoute("album:$k") },
                                )
                            }
                            val ar = artistOf(lib)
                            DropdownMenuItem(
                                text = { Text(T("アーティスト「$ar」を表示", "Go to artist \"$ar\"")) },
                                leadingIcon = { Icon(Icons.Filled.Person, null) },
                                onClick = { related = false; vm.openRoute("artist:$ar") },
                            )
                        }
                    }
                }
                IconButton(onToggleLyrics) {
                    Icon(
                        AppIcons.Lyrics, if (showLyrics) T("歌詞を隠す", "Hide lyrics") else T("歌詞を表示", "Show lyrics"),
                        tint = when { showLyrics -> col.accent; lyrics != null -> col.content; else -> col.sub.copy(alpha = 0.4f) },
                    )
                }
            }
            Spacer(Modifier.height(if (compact) 4.dp else 12.dp))
            // ---- 再生位置 ----
            Slider(
                value = frac,
                onValueChange = { seeking = it },
                onValueChangeFinished = {
                    seeking?.let { p.seek((it * dur).toLong()) }
                    seeking = null
                },
                colors = if (col.ambient) {
                    SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.28f))
                } else SliderDefaults.colors(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmtMs((frac * dur).toLong()), style = MaterialTheme.typography.labelMedium, color = col.sub)
                Spacer(Modifier.weight(1f))
                Text("-" + fmtMs(((1f - frac) * dur).toLong().coerceAtLeast(0)), style = MaterialTheme.typography.labelMedium, color = col.sub)
            }
            Spacer(Modifier.height(if (compact) 2.dp else 10.dp))
            // ---- 再生操作 ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton({ p.cycleRepeat() }) {
                    Icon(
                        if (p.repeatMode == 2) AppIcons.RepeatOne else AppIcons.Repeat,
                        when (p.repeatMode) { 1 -> T("全曲リピート", "Repeat all"); 2 -> T("1曲リピート", "Repeat one"); else -> T("リピートなし", "Repeat off") },
                        tint = if (p.repeatMode != 0) col.accent else col.sub,
                    )
                }
                OutlinedIconButton({ p.prev() }, Modifier.size(skipSize), border = BorderStroke(2.dp, col.content)) {
                    Icon(AppIcons.SkipPrevious, T("前の曲", "Previous track"), Modifier.size(skipSize * 0.47f), tint = col.content)
                }
                OutlinedIconButton({ p.toggle() }, Modifier.size(playSize), shape = CircleShape, border = BorderStroke(2.5.dp, col.content)) {
                    Icon(
                        if (p.isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow,
                        if (p.isPlaying) T("一時停止", "Pause") else T("再生", "Play"),
                        Modifier.size(playSize * 0.52f), tint = col.content,
                    )
                }
                OutlinedIconButton({ p.next() }, Modifier.size(skipSize), border = BorderStroke(2.dp, col.content)) {
                    Icon(AppIcons.SkipNext, T("次の曲", "Next track"), Modifier.size(skipSize * 0.47f), tint = col.content)
                }
                IconButton({ p.toggleShuffle() }) {
                    Icon(AppIcons.Shuffle, T("シャッフル", "Shuffle"), tint = if (p.shuffle) col.accent else col.sub)
                }
            }
            Spacer(Modifier.height(if (compact) 0.dp else 6.dp))
            // ---- 下の帯: 音量・タイマー・曲番号・再生リスト ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onVolume) { Icon(AppIcons.Volume, T("音量", "Volume"), tint = col.sub) }
                val sleepLeft = if (p.sleepAtMs > 0) ((p.sleepAtMs - SystemClock.elapsedRealtime()) / 60_000L + 1).coerceAtLeast(1) else 0L
                if (sleepLeft > 0 || p.stopAfterTrack) {
                    TextButton(onTimer) {
                        Icon(AppIcons.Timer, null, Modifier.size(18.dp), tint = col.accent)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (p.stopAfterTrack) T("曲の終わりで停止", "End of song") else T("残り${sleepLeft}分", "${sleepLeft} min"),
                            color = col.accent, style = MaterialTheme.typography.labelMedium,
                        )
                    }
                } else {
                    IconButton(onTimer) { Icon(AppIcons.Timer, T("スリープタイマー", "Sleep timer"), tint = col.sub) }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    T("${p.index + 1} / ${p.queue.size}", "${p.index + 1} / ${p.queue.size}"),
                    style = MaterialTheme.typography.labelMedium, color = col.sub,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onQueue) { Icon(AppIcons.Queue, T("再生リスト", "Queue"), tint = col.sub) }
            }
            Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
        }
    }
}

/** ジャケット。左右にスワイプすると前後の曲へ、タップで歌詞 */
@Composable
private fun SwipeCover(
    art: ImageBitmap?,
    compact: Boolean,
    overlay: String?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onTap: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val shift = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    val prevNow by rememberUpdatedState(onPrev)
    val nextNow by rememberUpdatedState(onNext)
    Box(
        Modifier.widthIn(max = 460.dp).aspectRatio(1f, matchHeightConstraintsFirst = true)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val v = shift.value
                        val w = width.toFloat()
                        scope.launch {
                            when {
                                v <= -w * 0.22f -> {
                                    shift.animateTo(-w, tween(150))
                                    nextNow()
                                    shift.snapTo(w * 0.6f)
                                    shift.animateTo(0f, tween(220))
                                }
                                v >= w * 0.22f -> {
                                    shift.animateTo(w, tween(150))
                                    prevNow()
                                    shift.snapTo(-w * 0.6f)
                                    shift.animateTo(0f, tween(220))
                                }
                                else -> shift.animateTo(0f, tween(200))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { shift.animateTo(0f, tween(200)) } },
                ) { change, d ->
                    change.consume()
                    scope.launch { shift.snapTo(shift.value + d) }
                }
            }
            .graphicsLayer {
                translationX = shift.value
                rotationZ = shift.value / width * 5f
                alpha = 1f - (abs(shift.value) / width * 0.7f).coerceIn(0f, 0.7f)
            }
            .shadow(if (compact) 12.dp else 24.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onTap),
    ) {
        CoverBox(art, Modifier.fillMaxSize(), 12.dp)
        if (overlay != null) {
            // 再生位置をドラッグしている間は、ジャケットの上に大きく時刻を出す
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Text(overlay, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 歌詞。時刻付きなら今の行を強調して追いかけ、行をタップするとその位置へ */
@Composable
private fun LyricsView(l: Lyrics, posMs: Long, col: PlayerColors, modifier: Modifier, onSeek: (Long) -> Unit) {
    val st = rememberLazyListState()
    val now = if (l.synced) l.lines.indexOfLast { it.timeMs <= posMs + 250 } else -1
    LaunchedEffect(now) {
        if (now >= 0) runCatching { st.animateScrollToItem((now - 2).coerceAtLeast(0)) }
    }
    LazyColumn(state = st, modifier = modifier, contentPadding = PaddingValues(vertical = 32.dp)) {
        itemsIndexed(l.lines) { i, line ->
            val active = i == now
            Text(
                line.text.ifEmpty { if (l.synced) "♪" else "" },
                style = if (l.synced) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    !l.synced -> col.content
                    active -> col.content
                    else -> col.sub.copy(alpha = 0.5f)
                },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = l.synced) { onSeek(line.timeMs) }
                    .padding(horizontal = 8.dp, vertical = if (l.synced) 7.dp else 2.dp),
            )
        }
    }
}

// =====================================================================
// お気に入り・再生リストのページ
// =====================================================================

/** 再生画面の一覧の行 */
@Composable
private fun PanelRow(
    vm: MainViewModel,
    item: PlayItem,
    active: Boolean,
    col: PlayerColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (active) col.panel else Color.Transparent)
            .clickable(onClick = onClick).padding(start = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Box {
            PlayArt(vm, item, Modifier.size(44.dp), 6.dp)
            if (active) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(AppIcons.Bars, null, Modifier.size(18.dp), tint = Color.White) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = col.content,
            )
            Text(item.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = col.sub)
        }
        Text(fmtMs(item.durationMs), style = MaterialTheme.typography.labelMedium, color = col.sub, modifier = Modifier.padding(horizontal = 6.dp))
        trailing?.invoke()
    }
}

@Composable
private fun FavoritesPanel(vm: MainViewModel, col: PlayerColors) {
    val map = vm.trackMap
    val songs = remember(vm.store.favTracks, map) { vm.store.favTracks.mapNotNull { map[it] } }
    val items = remember(songs) { songs.map { vm.itemOf(it) } }
    val playingUri = vm.player.current?.uri?.toString()
    if (songs.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.FavoriteBorder, null, Modifier.size(48.dp), tint = col.sub)
            Spacer(Modifier.height(12.dp))
            Text(T("お気に入りの曲はまだありません", "No favorite songs yet"), style = MaterialTheme.typography.titleMedium, color = col.content)
            Spacer(Modifier.height(6.dp))
            Text(
                T("再生中のページのハートのボタンで追加できます", "Tap the heart on the player to add the current song"),
                style = MaterialTheme.typography.bodySmall, color = col.sub, textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(T("お気に入りの曲", "Favorite songs"), style = MaterialTheme.typography.titleMedium, color = col.content)
                    Text(T("${songs.size}曲", "${songs.size} songs"), style = MaterialTheme.typography.bodySmall, color = col.sub)
                }
                IconButton({ vm.playTracks(songs, 0, shuffle = true) }) { Icon(AppIcons.Shuffle, T("シャッフル", "Shuffle"), tint = col.content) }
                IconButton({ vm.playTracks(songs, 0) }) { Icon(Icons.Filled.PlayArrow, T("再生", "Play"), tint = col.content) }
            }
        }
        itemsIndexed(items, key = { _, it -> "f:" + it.artTrackUri }) { i, it ->
            PanelRow(
                vm, it, it.artTrackUri == playingUri, col, { vm.playTracks(songs, i) },
                trailing = {
                    IconButton({ vm.toggleFav(songs[i].uri) }) {
                        Icon(Icons.Filled.Favorite, T("お気に入りから外す", "Remove from favorites"), tint = col.accent)
                    }
                },
            )
        }
    }
}

@Composable
private fun QueuePanel(vm: MainViewModel, col: PlayerColors, onSave: () -> Unit) {
    val p = vm.player
    val q = p.queue
    val st = rememberLazyListState(initialFirstVisibleItemIndex = p.index.coerceAtLeast(0))
    val reorder = rememberReorder(st, 1) { a, b -> p.move(a, b) }
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(T("この曲より後ろを消去しますか?", "Clear the songs after this one?")) },
            confirmButton = { TextButton({ p.clearUpcoming(); confirmClear = false }) { Text(T("消去", "Clear")) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text(T("キャンセル", "Cancel")) } },
        )
    }
    val left = q.drop(p.index + 1).sumOf { it.durationMs } + (p.durationMs - p.positionMs).coerceAtLeast(0)
    LazyColumn(state = st, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp)) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(T("再生リスト", "Queue"), style = MaterialTheme.typography.titleMedium, color = col.content)
                    Text(
                        T("${p.index + 1} / ${q.size}曲 ・ 残り ${fmtMs(left)}", "${p.index + 1} of ${q.size} · ${fmtMs(left)} left"),
                        style = MaterialTheme.typography.bodySmall, color = col.sub,
                    )
                }
                if (q.any { it.uri != null }) {
                    IconButton(onSave) { Icon(AppIcons.PlaylistAdd, T("プレイリストとして保存", "Save as playlist"), tint = col.content) }
                }
                if (p.index < q.lastIndex) {
                    IconButton({ confirmClear = true }) { Icon(Icons.Filled.Delete, T("この曲より後ろを消去", "Clear upcoming"), tint = col.content) }
                }
            }
        }
        itemsIndexed(q, key = { _, it -> it.id }) { i, it ->
            val dragging = reorder.dragging == i
            PanelRow(
                vm, it, i == p.index, col, { p.jumpTo(i) },
                modifier = Modifier.reorderItem(reorder, i).then(
                    if (dragging) Modifier.clip(RoundedCornerShape(12.dp)).background(
                        if (col.ambient) Color.Black.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) else Modifier,
                ),
                leading = { DragHandle(reorder, i, col.sub) },
                trailing = {
                    IconButton({ p.removeAt(i) }) { Icon(Icons.Filled.Close, T("再生リストから外す", "Remove from queue"), tint = col.sub) }
                },
            )
        }
    }
}

@Composable
private fun SaveQueueDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var name by remember {
        mutableStateOf(java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(T("プレイリストとして保存", "Save as playlist")) },
        text = {
            Column {
                Text(
                    T("今の再生リストの曲(CD の曲を除く)を保存します", "Saves the songs in the queue (except CD tracks)"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(T("名前", "Name")) })
            }
        },
        confirmButton = { TextButton({ vm.saveQueueAsPlaylist(name); onDismiss() }, enabled = name.isNotBlank()) { Text(T("保存", "Save")) } },
        dismissButton = { TextButton(onDismiss) { Text(T("キャンセル", "Cancel")) } },
    )
}

// =====================================================================
// 音量・スリープタイマー・操作ガイド
// =====================================================================

/** 音量。USB DAC 直接出力のときは DAC の音量、それ以外は端末のメディア音量 */
@Composable
private fun VolumeSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val usb = AudioEngine.output?.kind == OutputKind.UsbDirect
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    val max = if (usb) 100 else am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    fun read(): Int = if (usb) UsbDac.volume() else am.getStreamVolume(AudioManager.STREAM_MUSIC)
    var v by remember(usb) { mutableIntStateOf(read()) }
    var touching by remember { mutableStateOf(false) }
    // 本体の音量ボタンで変えたときも表示を合わせる
    LaunchedEffect(usb) {
        while (true) {
            delay(400)
            if (!touching) v = read()
        }
    }
    fun setVol(nv: Int, save: Boolean) {
        val x = nv.coerceIn(0, max)
        v = x
        if (usb) vm.setUsbVolume(x, save) else runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, x, 0) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(T("音量", "Volume"), style = MaterialTheme.typography.titleLarge)
            Text(
                (AudioEngine.output?.name ?: T("出力先", "Output")) + if (usb) T(" ・ DAC の音量", " · DAC volume") else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            VolumeDial(v, max, { touching = it }, { setVol(it, false) }, { setVol(v, true) })
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedIconButton({ setVol(v - 1, true) }, Modifier.size(52.dp)) { Text("−", fontSize = 24.sp) }
                Spacer(Modifier.width(48.dp))
                OutlinedIconButton({ setVol(v + 1, true) }, Modifier.size(52.dp)) { Text("+", fontSize = 24.sp) }
            }
            Text(
                T("ダイヤルをなぞって回すと、大きく変えられます", "Trace the dial to change it quickly"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * 大きなダイヤル。指でなぞった角度の分だけ変わる(急に最大にならないよう、触った場所へは飛ばない)。
 */
@Composable
private fun VolumeDial(value: Int, max: Int, onTouch: (Boolean) -> Unit, onChange: (Int) -> Unit, onDone: () -> Unit) {
    val startAngle = 135f
    val sweep = 270f
    val cur by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val touch by rememberUpdatedState(onTouch)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val active = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(240.dp).pointerInput(max) {
            var acc = 0f
            var prev = 0f
            fun angleOf(o: Offset): Float =
                Math.toDegrees(atan2((o.y - size.height / 2f).toDouble(), (o.x - size.width / 2f).toDouble())).toFloat()
            detectDragGestures(
                onDragStart = { o -> touch(true); acc = cur.toFloat(); prev = angleOf(o) },
                onDragEnd = { touch(false); done() },
                onDragCancel = { touch(false); done() },
            ) { ch, _ ->
                ch.consume()
                val a = angleOf(ch.position)
                var d = a - prev
                if (d > 180f) d -= 360f
                if (d < -180f) d += 360f
                prev = a
                acc = (acc + d / sweep * max).coerceIn(0f, max.toFloat())
                val nv = acc.roundToInt()
                if (nv != cur) change(nv)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(16.dp)) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val tl = Offset(inset, inset)
            drawArc(track, startAngle, sweep, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val f = value.toFloat() / max
            drawArc(active, startAngle, sweep * f, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val ang = Math.toRadians((startAngle + sweep * f).toDouble())
            val rad = (size.width - stroke) / 2f
            val c = Offset(size.width / 2f + rad * cos(ang).toFloat(), size.height / 2f + rad * sin(ang).toFloat())
            drawCircle(Color.White, stroke * 0.75f, c)
            drawCircle(active, stroke * 0.75f, c, style = Stroke(3.dp.toPx()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", fontSize = 56.sp, fontWeight = FontWeight.Bold)
            Text("/ $max", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SleepTimerDialog(p: PlayerController, onDismiss: () -> Unit) {
    val left = if (p.sleepAtMs > 0) ((p.sleepAtMs - SystemClock.elapsedRealtime()) / 60_000L + 1).coerceAtLeast(1) else 0L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(T("スリープタイマー", "Sleep timer")) },
        text = {
            Column {
                Text(
                    when {
                        p.stopAfterTrack -> T("今の曲が終わったら止まります", "Stops at the end of this song")
                        left > 0 -> T("あと約${left}分で止まります", "Stops in about $left min")
                        else -> T("指定した時間がたつと、再生を一時停止します", "Pauses playback after the chosen time")
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                listOf(15, 30, 45, 60, 90, 120).forEach { m ->
                    Text(
                        T("${m}分", "$m min"),
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { p.setSleep(m); onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    T("この曲の終わりで停止", "At the end of this song"),
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { p.stopAtTrackEnd(true); onDismiss() }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        confirmButton = {
            if (left > 0 || p.stopAfterTrack) {
                TextButton({ p.setSleep(0); onDismiss() }) { Text(T("タイマーを止める", "Turn off")) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(T("閉じる", "Close")) } },
    )
}

@Composable
private fun GuideDialog(onDismiss: () -> Unit) {
    val rows = listOf(
        T("ジャケットを左右にスワイプ", "Swipe the artwork") to T("前の曲 / 次の曲", "Previous / next song"),
        T("ジャケットをタップ", "Tap the artwork") to T("歌詞の表示・非表示", "Show or hide lyrics"),
        T("画面を左右にスワイプ", "Swipe the screen sideways") to T("お気に入り / 再生リスト", "Favorites / queue"),
        T("画面を下にスワイプ", "Swipe down") to T("再生画面を閉じる", "Close the player"),
        T("画面を上にスワイプ", "Swipe up") to T("音質設定", "Sound settings"),
        T("曲名をタップ", "Tap the title") to T("アルバム・アーティストを表示", "Go to the album or artist"),
        T("ミニプレーヤーを左右にスワイプ", "Swipe the mini player") to T("前の曲 / 次の曲", "Previous / next song"),
        T("再生リストのつまみをドラッグ", "Drag the handle in the queue") to T("曲の順番を入れ替え", "Reorder songs"),
        T("曲やアルバムを長押し", "Long-press a song or album") to T("次に再生・お気に入り・プレイリストなど", "Play next, favorites, playlists and more"),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(T("操作ガイド", "Gesture guide")) },
        text = {
            LazyColumn {
                itemsIndexed(rows) { _, (k, v) ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(k, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(T("閉じる", "Close")) } },
    )
}
