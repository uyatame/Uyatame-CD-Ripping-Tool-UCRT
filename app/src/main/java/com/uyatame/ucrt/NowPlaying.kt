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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.unit.Dp
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
        // 一覧で読んだ小さな画像があれば先に出し、再生画面用の元の大きさの画像に差し替える
        value = ArtLoader.peek(k)
        if (k != null) {
            value = kotlinx.coroutines.runInterruptible(Dispatchers.IO) { ArtLoader.load(ctx, k, item.artTrackUri, item.artCoverUri, fallbackUri = item.artFallbackUri) } ?: value
        }
    }
    return img
}

/**
 * ジャケットの代表色。鮮やかな色を重く数えて平均し、背景に使いやすい明るさにそろえる。
 */
internal fun dominantColor(img: ImageBitmap): Color {
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

@Composable
private fun playerColors(ambient: Boolean): PlayerColors = if (ambient) {
    PlayerColors(Color.White, Color.White.copy(alpha = 0.75f), Color(0xFFF3D58C), true, Color.White.copy(alpha = 0.12f))
} else {
    PlayerColors(
        MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.colorScheme.tertiary, false, MaterialTheme.colorScheme.secondaryContainer,
    )
}

/** 再生画面の背景が暗いときは、状態バーの文字を白にする(右に出したままの再生画面では変えない) */
@Composable
private fun PlayerStatusBar(ambient: Boolean, inPane: Boolean) {
    val dark = ambient || MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3 < 0.5f }
    val view = LocalView.current
    DisposableEffect(dark, inPane) {
        if (inPane) return@DisposableEffect onDispose {}
        val w = (view.context as? Activity)?.window
        val c = w?.let { WindowCompat.getInsetsController(it, view) }
        val before = c?.isAppearanceLightStatusBars
        c?.isAppearanceLightStatusBars = !dark
        onDispose { if (before != null) c?.isAppearanceLightStatusBars = before }
    }
}

/** ジャケットの代表色(背景の色付けに使う。色が変わるときはゆっくり変える) */
@Composable
private fun rememberTint(art: ImageBitmap?): Color {
    val base by produceState(Color(0xFF2A2A2A), art) {
        val a = art
        value = if (a == null) Color(0xFF2A2A2A) else withContext(Dispatchers.Default) { runCatching { dominantColor(a) }.getOrNull() } ?: Color(0xFF2A2A2A)
    }
    val tint by animateColorAsState(base, tween(700), label = "tint")
    return tint
}

/**
 * 再生画面の背景。アンビエントモードでは、ジャケットを敷いて(設定によってぼかし)その色で染め、
 * 一時停止中は dim の分だけ暗くする。オフのときは上の方を薄く色付けする
 */
@Composable
private fun PlayerBackground(ambient: Boolean, blur: Boolean, blurLevel: Int, art: ImageBitmap?, tint: Color, dim: Float) {
    if (ambient) {
        if (art != null) {
            // ぼかさない設定では、ぼかしに対応していない端末(Android 11 以前)と同じく、そのまま薄く敷く
            Image(
                // ぼかしの強さは設定で 1〜10(1 段階で半径 14dp。5 が以前と同じ 70dp)
                art, null, if (blur) Modifier.fillMaxSize().blur((blurLevel * 14).dp) else Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, alpha = 0.85f,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.8f))),
            ),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
    } else {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(tint.copy(alpha = 0.22f), Color.Transparent)),
            ),
        )
    }
}

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
    val tint = rememberTint(art)
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
    var showCast by remember { mutableStateOf(false) }
    if (showCast) CastSheet { showCast = false }
    if (showSound) AudioSettingsSheet(vm, s) { showSound = false }
    if (showOutput) OutputDeviceSheet(vm, s) { showOutput = false }
    if (showVolume) VolumeSheet(vm, { showVolume = false })
    if (showTimer) SleepTimerDialog(p) { showTimer = false }
    if (showGuide) GuideDialog { showGuide = false }
    if (savingQueue) SaveQueueDialog(vm) { savingQueue = false }

    val pager = rememberPagerState(initialPage = 1) { 3 }
    val scope = rememberCoroutineScope()
    // タブレットモードの右の再生画面は閉じない(戻るは左の画面に任せる)
    val inPane = LocalInPane.current
    BackHandler(enabled = !inPane, onBack = onClose)
    // 左右のページを表示中は、戻るで再生中のページへ
    BackHandler(enabled = pager.currentPage != 1) { scope.launch { pager.animateScrollToPage(1) } }

    PlayerStatusBar(ambient, inPane)
    val col = playerColors(ambient)

    // 下にある画面へタッチが抜けないよう、空の pointerInput で受け止める
    Box(Modifier.fillMaxSize().background(if (ambient) Color.Black else MaterialTheme.colorScheme.surface).pointerInput(Unit) {}) {
        PlayerBackground(ambient, s.ambientBlur, s.ambientBlurLevel, art, tint, dim)
        CompositionLocalProvider(LocalContentColor provides col.content) {
            // 横向きでは、カメラの切り欠きが左右に来るので、そこも避ける
            val bars = when {
                inPane -> Modifier.windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End),
                )
                isLandscape() -> Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                else -> Modifier.systemBarsPadding()
            }
            Column(Modifier.fillMaxSize().then(bars)) {
                // ---- 上の帯: 閉じる・ページの切り替え・メニュー ----
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (inPane) Spacer(Modifier.size(48.dp))
                    else IconButton(onClose) { Icon(Icons.Filled.KeyboardArrowDown, S(R.string.close), tint = col.content) }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                        listOf(S(R.string.favorites), S(R.string.playing), S(R.string.queue)).forEachIndexed { i, l ->
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
                    // キャスト(Chromecast)
                    val castTo = com.uyatame.ucrt.player.cast.CastManager.connected
                    IconButton({ showCast = true }) {
                        Icon(
                            if (castTo != null) AppIcons.CastConnected else AppIcons.Cast, S(R.string.cast_title),
                            tint = if (castTo != null) col.accent else col.content,
                        )
                    }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, S(R.string.menu), tint = col.content) }
                        DropdownMenu(menu, { menu = false }) {
                            val lib = vm.libTrackOf(cur)
                            DropdownMenuItem(
                                text = { Text(if (showLyrics) S(R.string.hide_lyrics) else S(R.string.show_lyrics)) },
                                leadingIcon = { Icon(AppIcons.Lyrics, null) },
                                onClick = { menu = false; showLyrics = !showLyrics; scope.launch { pager.animateScrollToPage(1) } },
                            )
                            DropdownMenuItem(
                                text = { Text(S(R.string.sleep_timer)) },
                                leadingIcon = { Icon(AppIcons.Timer, null) },
                                onClick = { menu = false; showTimer = true },
                            )
                            DropdownMenuItem(
                                text = { Text(S(R.string.song_details)) },
                                leadingIcon = { Icon(Icons.Filled.Info, null) },
                                onClick = { menu = false; vm.infoItem = cur },
                            )
                            if (lib != null) {
                                vm.albumKeyOf(lib.uri)?.let { k ->
                                    DropdownMenuItem(
                                        text = { Text(S(R.string.go_to_album)) },
                                        leadingIcon = { Icon(AppIcons.Album, null) },
                                        onClick = { menu = false; vm.openRoute("album:$k") },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(S(R.string.go_to_artist)) },
                                    leadingIcon = { Icon(Icons.Filled.Person, null) },
                                    onClick = { menu = false; vm.openRoute("artist:" + artistOf(lib)) },
                                )
                                DropdownMenuItem(
                                    text = { Text(S(R.string.add_to_playlist_2)) },
                                    leadingIcon = { Icon(AppIcons.PlaylistAdd, null) },
                                    onClick = { menu = false; vm.playlistPick = listOf(lib.uri) },
                                )
                                DropdownMenuItem(
                                    text = { Text(S(R.string.select_multiple_add_to_playlist)) },
                                    leadingIcon = { Icon(Icons.Filled.CheckCircle, null) },
                                    onClick = { menu = false; vm.startSelection(lib.uri) },
                                )
                                val check = vm.store.isCheck(lib.uri)
                                DropdownMenuItem(
                                    text = { Text(if (check) S(R.string.remove_from_sound_check) else S(R.string.add_to_sound_check)) },
                                    leadingIcon = { Icon(AppIcons.Headphones, null) },
                                    onClick = { menu = false; vm.toggleCheck(lib.uri) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(S(R.string.sound_settings)) },
                                leadingIcon = { Icon(AppIcons.Tune, null) },
                                onClick = { menu = false; showSound = true },
                            )
                            DropdownMenuItem(
                                text = { Text(S(R.string.gesture_guide)) },
                                leadingIcon = { Icon(Icons.Filled.Info, null) },
                                onClick = { menu = false; showGuide = true },
                            )
                            HorizontalDivider()
                            if (vm.usbDacInUse) {
                                DropdownMenuItem(
                                    text = { Text(S(R.string.release_usb_dac)) },
                                    leadingIcon = { Icon(AppIcons.Usb, null) },
                                    onClick = { menu = false; vm.releaseUsbDac() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(S(R.string.stop_playback)) },
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
        // 横向きの電話(高さ 360dp 前後)では、再生ボタンをもう一回り小さくして縦に収める
        val land = isLandscape()
        val tight = land && maxHeight < 420.dp
        val pageH = maxHeight
        val playSize = if (tight) 56.dp else if (compact) 64.dp else 80.dp
        val skipSize = if (tight) 44.dp else if (compact) 50.dp else 60.dp
        var seeking by remember(cur.id) { mutableStateOf<Float?>(null) }
        val dur = p.durationMs.coerceAtLeast(1)
        // 再生位置(0.25 秒ごとに変わる)は、ここでは読まない。読むのは SeekBar と歌詞の中だけにして、
        // 再生画面の全体が 0.25 秒ごとに描き直されないようにする(性能の低い端末向け)
        val lib = vm.libTrackOf(cur)

        // ---- ジャケット(または歌詞) ----
        val artPane: @Composable (Modifier) -> Unit = { m ->
            Box(m, contentAlignment = Alignment.Center) {
                if (showLyrics) {
                    if (lyrics != null) {
                        LyricsView(lyrics, { p.positionMs }, col, Modifier.fillMaxSize()) { p.seek(it) }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Icon(AppIcons.Lyrics, null, Modifier.size(40.dp), tint = col.sub)
                            Spacer(Modifier.height(12.dp))
                            Text(S(R.string.no_lyrics_found), style = MaterialTheme.typography.titleMedium, color = col.content)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                S(R.string.add_lrc_file_with_same),
                                style = MaterialTheme.typography.bodySmall, color = col.sub, textAlign = TextAlign.Center,
                            )
                            TextButton(onToggleLyrics) { Text(S(R.string.back_to_artwork), color = col.accent) }
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
        }
        // ---- 曲の情報と操作(縦向きではジャケットの下、横向きでは右) ----
        val controls: @Composable ColumnScope.() -> Unit = {
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
            // ---- 出力先・ビットパーフェクト・EQ・DAR ----
            SoundStatus(s, col, compact, onOutput, onSound)
            Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
            // ---- 曲名(タップでアルバム・アーティストへ)とお気に入り ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val fav = lib != null && vm.store.isFav(lib.uri)
                // 音質チェックに入れる・外す(お気に入りと同じように 1 回押すだけ)
                val check = lib != null && vm.store.isCheck(lib.uri)
                val marks: @Composable () -> Unit = {
                    IconButton({ lib?.let { vm.toggleFav(it.uri) } }, enabled = lib != null) {
                        Icon(
                            if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            if (fav) S(R.string.remove_from_favorites) else S(R.string.add_to_favorites),
                            tint = when { lib == null -> col.sub.copy(alpha = 0.3f); fav -> col.accent; else -> col.sub },
                        )
                    }
                    IconButton({ lib?.let { vm.toggleCheck(it.uri) } }, enabled = lib != null) {
                        Icon(
                            AppIcons.Headphones,
                            if (check) S(R.string.remove_from_sound_check) else S(R.string.add_to_sound_check),
                            tint = when { lib == null -> col.sub.copy(alpha = 0.3f); check -> col.accent; else -> col.sub },
                        )
                    }
                }
                // 曲名が真ん中に来るように、左右のボタンの幅をそろえる:
                // 通常はお気に入りと音質チェックを縦に重ねる(右の歌詞ボタンと同じ幅)。
                // 高さに余裕がない画面では横に並べ、右にも同じ幅の空きを作る
                if (compact) marks() else Column { marks() }
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
                                    text = { Text(S(R.string.go_to_album_2, lib.album)) },
                                    leadingIcon = { Icon(AppIcons.Album, null) },
                                    onClick = { related = false; vm.openRoute("album:$k") },
                                )
                            }
                            val ar = artistOf(lib)
                            DropdownMenuItem(
                                text = { Text(S(R.string.go_to_artist_2, ar)) },
                                leadingIcon = { Icon(Icons.Filled.Person, null) },
                                onClick = { related = false; vm.openRoute("artist:$ar") },
                            )
                        }
                    }
                }
                if (compact) Spacer(Modifier.width(48.dp))
                IconButton(onToggleLyrics) {
                    Icon(
                        AppIcons.Lyrics, if (showLyrics) S(R.string.hide_lyrics) else S(R.string.show_lyrics),
                        tint = when { showLyrics -> col.accent; lyrics != null -> col.content; else -> col.sub.copy(alpha = 0.4f) },
                    )
                }
            }
            Spacer(Modifier.height(if (compact) 4.dp else 12.dp))
            // ---- 再生位置 ----
            SeekBar(p, dur, seeking, col, { seeking = it }) {
                seeking?.let { p.seek((it * dur).toLong()) }
                seeking = null
            }
            Spacer(Modifier.height(if (compact) 2.dp else 10.dp))
            // ---- 再生操作 ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton({ p.cycleRepeat() }) {
                    Icon(
                        if (p.repeatMode == 2) AppIcons.RepeatOne else AppIcons.Repeat,
                        when (p.repeatMode) { 1 -> S(R.string.repeat_all); 2 -> S(R.string.repeat_one); else -> S(R.string.repeat_off) },
                        tint = if (p.repeatMode != 0) col.accent else col.sub,
                    )
                }
                OutlinedIconButton({ p.prev() }, Modifier.size(skipSize), border = BorderStroke(2.dp, col.content)) {
                    Icon(AppIcons.SkipPrevious, S(R.string.previous_track), Modifier.size(skipSize * 0.47f), tint = col.content)
                }
                OutlinedIconButton({ p.toggle() }, Modifier.size(playSize), shape = CircleShape, border = BorderStroke(2.5.dp, col.content)) {
                    Icon(
                        if (p.isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow,
                        if (p.isPlaying) S(R.string.pause) else S(R.string.play),
                        Modifier.size(playSize * 0.52f), tint = col.content,
                    )
                }
                OutlinedIconButton({ p.next() }, Modifier.size(skipSize), border = BorderStroke(2.dp, col.content)) {
                    Icon(AppIcons.SkipNext, S(R.string.next_track), Modifier.size(skipSize * 0.47f), tint = col.content)
                }
                IconButton({ p.toggleShuffle() }) {
                    Icon(AppIcons.Shuffle, S(R.string.shuffle_2), tint = if (p.shuffle) col.accent else col.sub)
                }
            }
            Spacer(Modifier.height(if (compact) 0.dp else 6.dp))
            // ---- 下の帯: 音量・タイマー・曲番号・再生リスト ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onVolume) { Icon(AppIcons.Volume, S(R.string.volume), tint = col.sub) }
                // 残りの分は、時計に合わせて自分で更新する(画面の全体は再生位置では描き直さないので)
                val sleepAt = p.sleepAtMs
                fun leftNow() = if (sleepAt > 0) ((sleepAt - SystemClock.elapsedRealtime()) / 60_000L + 1).coerceAtLeast(1) else 0L
                val sleepLeft by produceState(leftNow(), sleepAt) {
                    while (sleepAt > 0) {
                        value = leftNow()
                        delay(1000)
                    }
                    value = 0L
                }
                if (sleepLeft > 0 || p.stopAfterTrack) {
                    TextButton(onTimer) {
                        Icon(AppIcons.Timer, null, Modifier.size(18.dp), tint = col.accent)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (p.stopAfterTrack) S(R.string.end_of_song) else S(R.string.min, sleepLeft),
                            color = col.accent, style = MaterialTheme.typography.labelMedium,
                        )
                    }
                } else {
                    IconButton(onTimer) { Icon(AppIcons.Timer, S(R.string.sleep_timer), tint = col.sub) }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    S(R.string.label_3, p.index + 1, p.queue.size),
                    style = MaterialTheme.typography.labelMedium, color = col.sub,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onQueue) { Icon(AppIcons.Queue, S(R.string.queue), tint = col.sub) }
            }
            Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
        }
        if (land) {
            // 横向き: 左にジャケット、右に情報と操作。高さが足りなければ右側だけスクロールする
            // (収まるときはスクロールを切り、下へのスワイプで閉じる操作を右側でも使えるようにする)
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                artPane(Modifier.weight(0.42f).fillMaxHeight().padding(vertical = 8.dp))
                Spacer(Modifier.width(20.dp))
                val st = rememberScrollState()
                Column(Modifier.weight(0.58f).fillMaxHeight().verticalScroll(st, enabled = st.maxValue > 0)) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = pageH).padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        content = controls,
                    )
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = if (compact) 16.dp else 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                artPane(Modifier.weight(1f).fillMaxWidth().padding(vertical = if (compact) 6.dp else 14.dp))
                controls()
            }
        }
    }
}

/**
 * 出力先・ビットパーフェクト・イコライザーのチップと、DAR の中身・注意の表示。
 * 再生画面と、ほかのアプリの音を UCRT で鳴らしているときの画面で使う
 */
@Composable
private fun SoundStatus(s: AppSettings, col: PlayerColors, compact: Boolean, onOutput: () -> Unit, onSound: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // ---- 出力先・ビットパーフェクト・EQ ----
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val chipColors = if (col.ambient) {
                FilterChipDefaults.filterChipColors(labelColor = col.sub, selectedLabelColor = Color.Black, selectedContainerColor = col.accent)
            } else FilterChipDefaults.filterChipColors()
            val outDev = AudioEngine.output
            val castTo = com.uyatame.ucrt.player.cast.CastManager.connected
            FilterChip(
                selected = false,
                onClick = onOutput,
                label = { Text(castTo?.name ?: outDev?.name ?: S(R.string.output), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    Icon(
                        if (castTo != null) AppIcons.CastConnected else outputIcon(outDev?.kind ?: OutputKind.Auto), null,
                        Modifier.size(18.dp), tint = if (col.ambient) col.sub else MaterialTheme.colorScheme.primary,
                    )
                },
                colors = chipColors,
                border = if (col.ambient) BorderStroke(1.dp, col.sub) else FilterChipDefaults.filterChipBorder(enabled = true, selected = false),
                modifier = Modifier.widthIn(max = 180.dp),
            )
            val border = if (col.ambient) BorderStroke(1.dp, col.sub) else FilterChipDefaults.filterChipBorder(enabled = true, selected = true)
            // 状態の表示は、実際にその状態のときだけ出す
            val perfect = AudioEngine.bitPerfectActive
            if (perfect) {
                FilterChip(selected = true, onClick = onSound, label = { Text(S(R.string.chip_bit_perfect)) }, colors = chipColors, border = border)
            } else if (s.sourceDirect) {
                FilterChip(selected = true, onClick = onSound, label = { Text(S(R.string.chip_source_direct)) }, colors = chipColors, border = border)
            }
            if (!perfect && !s.sourceDirect && s.eqEnabled) {
                val name = eqPresetName(s) ?: S(R.string.custom)
                FilterChip(selected = true, onClick = onSound, label = { Text(S(R.string.chip_eq, name)) }, colors = chipColors, border = border)
            }
            // 音質設定を開く(いつでも開けるように、アイコンだけのボタン)
            FilterChip(
                selected = false,
                onClick = onSound,
                label = { Icon(AppIcons.Tune, S(R.string.sound_settings), Modifier.size(18.dp)) },
                colors = chipColors,
                border = if (col.ambient) BorderStroke(1.dp, col.sub) else FilterChipDefaults.filterChipBorder(enabled = true, selected = false),
            )
        }
        // ---- DAR が効いているときの中身(変換の方法と、実際に送っている周波数・ビットレート) ----
        AudioEngine.darInfo.takeIf { it.isNotEmpty() }?.let { dar ->
            Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
            Text(
                dar, style = MaterialTheme.typography.labelMedium, color = col.accent,
                maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSound).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        // 設定どおりにできなかったとき(整数倍だけの設定で、出力先に合わせて整数倍でない変換をしているなど)や、
        // DSD を最大音量で出しているときの注意
        AudioEngine.darWarn.takeIf { it.isNotEmpty() }?.let { w ->
            Text(
                w, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

/** 再生位置のバーと時刻。再生位置を読むのはここだけ(0.25 秒ごとに描き直すのを、この部分だけにする) */
@Composable
private fun SeekBar(
    p: PlayerController,
    dur: Long,
    seeking: Float?,
    col: PlayerColors,
    onSeeking: (Float) -> Unit,
    onDone: () -> Unit,
) {
    val frac = seeking ?: (p.positionMs.toFloat() / dur).coerceIn(0f, 1f)
    Slider(
        value = frac,
        onValueChange = onSeeking,
        onValueChangeFinished = onDone,
        colors = if (col.ambient) {
            SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.28f))
        } else SliderDefaults.colors(),
    )
    Row(Modifier.fillMaxWidth()) {
        Text(fmtMs((frac * dur).toLong()), style = MaterialTheme.typography.labelMedium, color = col.sub)
        Spacer(Modifier.weight(1f))
        Text("-" + fmtMs(((1f - frac) * dur).toLong().coerceAtLeast(0)), style = MaterialTheme.typography.labelMedium, color = col.sub)
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
        // 画像の縦横の比率のまま出す(正方形に切り抜かない。8cm CD の短冊ジャケット(約 1:2.5)も収まるよう、1:3〜3:1 まで)
        Modifier.widthIn(max = 460.dp).aspectRatio(
            art?.let { (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(0.33f, 3f) } ?: 1f,
            matchHeightConstraintsFirst = true,
        )
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
private fun LyricsView(l: Lyrics, posMs: () -> Long, col: PlayerColors, modifier: Modifier, onSeek: (Long) -> Unit) {
    val st = rememberLazyListState()
    // 今の行が変わったときだけ描き直す(再生位置が変わるたびには描き直さない)
    val now by remember(l) { derivedStateOf { if (l.synced) l.lines.indexOfLast { it.timeMs <= posMs() + 250 } else -1 } }
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
            PlayArt(vm, item, Modifier.size(44.dp), 6.dp, 44.dp)
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
            Text(S(R.string.no_favorite_songs_yet), style = MaterialTheme.typography.titleMedium, color = col.content)
            Spacer(Modifier.height(6.dp))
            Text(
                S(R.string.tap_heart_in_player_to),
                style = MaterialTheme.typography.bodySmall, color = col.sub, textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(S(R.string.favorite_songs), style = MaterialTheme.typography.titleMedium, color = col.content)
                    Text(S(R.string.songs_4, songs.size), style = MaterialTheme.typography.bodySmall, color = col.sub)
                }
                IconButton({ vm.playTracks(songs, 0, shuffle = true) }) { Icon(AppIcons.Shuffle, S(R.string.shuffle_2), tint = col.content) }
                IconButton({ vm.playTracks(songs, 0) }) { Icon(Icons.Filled.PlayArrow, S(R.string.play), tint = col.content) }
            }
        }
        itemsIndexed(items, key = { _, it -> "f:" + it.artTrackUri }) { i, it ->
            PanelRow(
                vm, it, it.artTrackUri == playingUri, col, { vm.playTracks(songs, i) },
                trailing = {
                    IconButton({ vm.toggleFav(songs[i].uri) }) {
                        Icon(Icons.Filled.Favorite, S(R.string.remove_from_favorites), tint = col.accent)
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
            title = { Text(S(R.string.clear_songs_after_this_one)) },
            confirmButton = { TextButton({ p.clearUpcoming(); confirmClear = false }) { Text(S(R.string.clear)) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text(S(R.string.cancel)) } },
        )
    }
    // 後ろの曲の合計は、再生リストが変わったときだけ数える(再生位置は見出しの中で読む)
    val after = remember(q, p.index) { q.drop(p.index + 1).sumOf { it.durationMs } }
    LazyColumn(state = st, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp)) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val left = after + (p.durationMs - p.positionMs).coerceAtLeast(0)
                    Text(S(R.string.queue), style = MaterialTheme.typography.titleMedium, color = col.content)
                    Text(
                        S(R.string.of_left, p.index + 1, q.size, fmtMs(left)),
                        style = MaterialTheme.typography.bodySmall, color = col.sub,
                    )
                }
                if (q.any { it.uri != null }) {
                    IconButton(onSave) { Icon(AppIcons.PlaylistAdd, S(R.string.save_as_playlist), tint = col.content) }
                }
                if (p.index < q.lastIndex) {
                    IconButton({ confirmClear = true }) { Icon(Icons.Filled.Delete, S(R.string.clear_upcoming), tint = col.content) }
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
                    IconButton({ p.removeAt(i) }) { Icon(Icons.Filled.Close, S(R.string.remove_from_queue), tint = col.sub) }
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
        title = { Text(S(R.string.save_as_playlist)) },
        text = {
            Column {
                Text(
                    S(R.string.saves_songs_in_queue_except),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(S(R.string.name)) })
            }
        },
        confirmButton = { TextButton({ vm.saveQueueAsPlaylist(name); onDismiss() }, enabled = name.isNotBlank()) { Text(S(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(S(R.string.cancel)) } },
    )
}

// =====================================================================
// 音量・スリープタイマー・操作ガイド
// =====================================================================

/** 音量。USB DAC 直接出力のときは DAC の音量、それ以外は端末のメディア音量 */
@Composable
private fun VolumeSheet(vm: MainViewModel, onDismiss: () -> Unit, system: Boolean = false) {
    val ctx = LocalContext.current
    // ほかのアプリの音(system)は、DAC を直接使っていても Android の音量で変える
    val usb = !system && AudioEngine.output?.kind == OutputKind.UsbDirect
    val am = remember { androidx.core.content.ContextCompat.getSystemService(ctx, AudioManager::class.java)!! }
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
    val land = isLandscape()
    val title: @Composable () -> Unit = {
        Text(S(R.string.volume), style = MaterialTheme.typography.titleLarge)
        Text(
            (AudioEngine.output?.name ?: S(R.string.output)) + if (usb) S(R.string.dac_volume_2) else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val buttons: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedIconButton({ setVol(v - 1, true) }, Modifier.size(52.dp)) { Text("−", fontSize = 24.sp) }
            Spacer(Modifier.width(48.dp))
            OutlinedIconButton({ setVol(v + 1, true) }, Modifier.size(52.dp)) { Text("+", fontSize = 24.sp) }
        }
        Text(
            S(R.string.trace_around_dial_for_larger),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    // 横向きでは高さが足りないので、半分だけ開かずに全体を出す
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = land)) {
        if (land) {
            // 横向き: 左にダイヤル、右に名前とボタン
            Row(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                VolumeDial(v, max, { touching = it }, { setVol(it, false) }, { setVol(v, true) }, 200.dp)
                Spacer(Modifier.width(32.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    title()
                    Spacer(Modifier.height(16.dp))
                    buttons()
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                title()
                Spacer(Modifier.height(16.dp))
                VolumeDial(v, max, { touching = it }, { setVol(it, false) }, { setVol(v, true) })
                Spacer(Modifier.height(8.dp))
                buttons()
            }
        }
    }
}

/**
 * 大きなダイヤル。指でなぞった角度の分だけ変わる(急に最大にならないよう、触った場所へは飛ばない)。
 */
@Composable
private fun VolumeDial(value: Int, max: Int, onTouch: (Boolean) -> Unit, onChange: (Int) -> Unit, onDone: () -> Unit, dialSize: Dp = 240.dp) {
    val startAngle = 135f
    val sweep = 270f
    val cur by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val touch by rememberUpdatedState(onTouch)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val active = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(dialSize).pointerInput(max) {
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
        title = { Text(S(R.string.sleep_timer)) },
        text = {
            // 横向きの電話では収まらないので、スクロールできるようにする
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    when {
                        p.stopAfterTrack -> S(R.string.stops_at_end_of_this)
                        left > 0 -> S(R.string.stops_in_about_min, left)
                        else -> S(R.string.pauses_playback_after_selected_time)
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                listOf(15, 30, 45, 60, 90, 120).forEach { m ->
                    Text(
                        S(R.string.min_2, m),
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { p.setSleep(m); onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    S(R.string.at_end_of_this_song),
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { p.stopAtTrackEnd(true); onDismiss() }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        confirmButton = {
            if (left > 0 || p.stopAfterTrack) {
                TextButton({ p.setSleep(0); onDismiss() }) { Text(S(R.string.turn_off_timer)) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(S(R.string.close)) } },
    )
}

@Composable
private fun GuideDialog(onDismiss: () -> Unit) {
    val rows = listOf(
        S(R.string.swipe_artwork_left_or_right) to S(R.string.previous_next_song),
        S(R.string.tap_artwork) to S(R.string.show_or_hide_lyrics),
        S(R.string.swipe_screen_left_or_right) to S(R.string.favorites_queue),
        S(R.string.swipe_down) to S(R.string.close_player),
        S(R.string.swipe_up) to S(R.string.sound_settings),
        S(R.string.tap_title) to S(R.string.go_to_album_or_artist),
        S(R.string.swipe_mini_player_left_or) to S(R.string.previous_next_song),
        S(R.string.drag_handle_in_queue) to S(R.string.reorder_songs),
        S(R.string.long_press_song_or_album) to S(R.string.play_next_favorites_playlists_and),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S(R.string.gesture_guide)) },
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
        confirmButton = { TextButton(onDismiss) { Text(S(R.string.close)) } },
    )
}

// =====================================================================
// ほかの音楽アプリ(ストリーミングサービスなど)の再生画面
// =====================================================================

/**
 * ほかのアプリで再生中の曲を、このアプリの再生画面と同じ見た目(アンビエントモードを含む)で表示・操作する。
 * 音はそのアプリから出るので、音質の表示や再生リストなどは無い
 */
@Composable
fun ExternalPlayerScreen(vm: MainViewModel, s: AppSettings, onClose: () -> Unit) {
    val ext = com.uyatame.ucrt.player.ExternalMedia
    val n = ext.now ?: return
    val ctx = LocalContext.current
    val ambient = s.ambientPlayer
    val art = n.art
    val tint = rememberTint(art)
    val dim by animateFloatAsState(if (n.playing) 0f else 0.4f, tween(500), label = "dim")
    var showVolume by remember { mutableStateOf(false) }
    if (showVolume) VolumeSheet(vm, { showVolume = false }, system = true)
    BackHandler(onBack = onClose)
    PlayerStatusBar(ambient, inPane = false)
    val col = playerColors(ambient)
    // 再生位置は自分で進める(アプリからの知らせは、再生・一時停止・移動のときにしか来ない)
    var pos by remember { androidx.compose.runtime.mutableLongStateOf(n.positionNow()) }
    LaunchedEffect(n) {
        while (true) {
            pos = n.positionNow()
            // 止まっている間は位置が進まない(再生し直すと n が変わるので、ここからやり直す)
            if (!n.playing) break
            delay(500)
        }
    }
    var seeking by remember(n.title) { mutableStateOf<Float?>(null) }
    val dur = n.durationMs.coerceAtLeast(1)

    Box(Modifier.fillMaxSize().background(if (ambient) Color.Black else MaterialTheme.colorScheme.surface).pointerInput(Unit) {}) {
        PlayerBackground(ambient, s.ambientBlur, s.ambientBlurLevel, art, tint, dim)
        CompositionLocalProvider(LocalContentColor provides col.content) {
            val bars = if (isLandscape()) Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            else Modifier.systemBarsPadding()
            Column(Modifier.fillMaxSize().then(bars)) {
                // ---- 上の帯: 閉じる・どのアプリで再生中か・アプリを開く ----
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Filled.KeyboardArrowDown, S(R.string.close), tint = col.content) }
                    Text(
                        S(R.string.playing_in_app, n.appName), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, S(R.string.menu), tint = col.content) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(S(R.string.open_app, n.appName)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) },
                                onClick = { menu = false; ext.openApp(ctx) },
                            )
                        }
                    }
                }
                var drag by remember { mutableStateOf(0f) }
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                        // 下へスワイプで閉じる(いつもの再生画面と同じ)
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (drag > 220f) onClose()
                                drag = 0f
                            },
                            onDragCancel = { drag = 0f },
                        ) { _, d -> drag += d }
                    },
                ) {
                    val compact = maxHeight < 640.dp || maxWidth < 340.dp
                    val tiny = maxHeight < 540.dp
                    val land = isLandscape()
                    val tight = land && maxHeight < 420.dp
                    val pageH = maxHeight
                    val playSize = if (tight) 56.dp else if (compact) 64.dp else 80.dp
                    val skipSize = if (tight) 44.dp else if (compact) 50.dp else 60.dp
                    val artPane: @Composable (Modifier) -> Unit = { m ->
                        Box(m, contentAlignment = Alignment.Center) {
                            SwipeCover(
                                art, compact,
                                overlay = seeking?.let { fmtMs((it * dur).toLong()) },
                                onPrev = { if (n.canPrev) ext.previous() }, onNext = { if (n.canNext) ext.next() }, onTap = {},
                            )
                        }
                    }
                    val controls: @Composable ColumnScope.() -> Unit = {
                        // ---- 再生しているアプリ(タップで開く) ----
                        val chipColors = if (col.ambient) {
                            FilterChipDefaults.filterChipColors(labelColor = col.sub)
                        } else FilterChipDefaults.filterChipColors()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = false,
                                onClick = { ext.openApp(ctx) },
                                label = { Text(n.appName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = {
                                    Icon(
                                        AppIcons.MusicNote, null, Modifier.size(18.dp),
                                        tint = if (col.ambient) col.sub else MaterialTheme.colorScheme.primary,
                                    )
                                },
                                colors = chipColors,
                                border = if (col.ambient) BorderStroke(1.dp, col.sub) else FilterChipDefaults.filterChipBorder(enabled = true, selected = false),
                                modifier = Modifier.widthIn(max = 180.dp),
                            )
                        }
                        Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
                        // ---- 曲名 ----
                        Text(
                            n.title, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold,
                        )
                        if (n.artist.isNotEmpty()) {
                            Text(
                                n.artist, style = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                                color = col.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (n.album.isNotEmpty() && !tiny) {
                            Text(n.album, style = MaterialTheme.typography.bodyMedium, color = col.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(if (compact) 4.dp else 12.dp))
                        // ---- 再生位置(長さが分からない曲・シークできないアプリでは操作できない) ----
                        if (n.durationMs > 0) {
                            val frac = seeking ?: (pos.toFloat() / dur).coerceIn(0f, 1f)
                            Slider(
                                value = frac,
                                onValueChange = { seeking = it },
                                onValueChangeFinished = {
                                    seeking?.let { ext.seekTo((it * dur).toLong()) }
                                    seeking = null
                                },
                                enabled = n.canSeek,
                                colors = if (col.ambient) {
                                    SliderDefaults.colors(
                                        thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.28f),
                                        disabledThumbColor = Color.White.copy(alpha = 0.6f), disabledActiveTrackColor = Color.White.copy(alpha = 0.6f),
                                        disabledInactiveTrackColor = Color.White.copy(alpha = 0.2f),
                                    )
                                } else SliderDefaults.colors(),
                            )
                            Row(Modifier.fillMaxWidth()) {
                                Text(fmtMs((frac * dur).toLong()), style = MaterialTheme.typography.labelMedium, color = col.sub)
                                Spacer(Modifier.weight(1f))
                                Text("-" + fmtMs(((1f - frac) * dur).toLong().coerceAtLeast(0)), style = MaterialTheme.typography.labelMedium, color = col.sub)
                            }
                        }
                        Spacer(Modifier.height(if (compact) 2.dp else 10.dp))
                        // ---- 再生操作 ----
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            IconButton({ showVolume = true }) { Icon(AppIcons.Volume, S(R.string.volume), tint = col.sub) }
                            OutlinedIconButton(
                                { ext.previous() }, Modifier.size(skipSize), enabled = n.canPrev,
                                border = BorderStroke(2.dp, if (n.canPrev) col.content else col.sub.copy(alpha = 0.3f)),
                            ) {
                                Icon(AppIcons.SkipPrevious, S(R.string.previous_track), Modifier.size(skipSize * 0.47f), tint = if (n.canPrev) col.content else col.sub.copy(alpha = 0.3f))
                            }
                            OutlinedIconButton({ ext.playPause() }, Modifier.size(playSize), shape = CircleShape, border = BorderStroke(2.5.dp, col.content)) {
                                Icon(
                                    if (n.playing) AppIcons.Pause else Icons.Filled.PlayArrow,
                                    if (n.playing) S(R.string.pause) else S(R.string.play),
                                    Modifier.size(playSize * 0.52f), tint = col.content,
                                )
                            }
                            OutlinedIconButton(
                                { ext.next() }, Modifier.size(skipSize), enabled = n.canNext,
                                border = BorderStroke(2.dp, if (n.canNext) col.content else col.sub.copy(alpha = 0.3f)),
                            ) {
                                Icon(AppIcons.SkipNext, S(R.string.next_track), Modifier.size(skipSize * 0.47f), tint = if (n.canNext) col.content else col.sub.copy(alpha = 0.3f))
                            }
                            IconButton({ ext.openApp(ctx) }) {
                                Icon(Icons.AutoMirrored.Filled.ExitToApp, S(R.string.open_app, n.appName), tint = col.sub)
                            }
                        }
                        Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
                        Text(
                            S(R.string.external_audio_note), style = MaterialTheme.typography.labelSmall, color = col.sub,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
                    }
                    if (land) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            artPane(Modifier.weight(0.42f).fillMaxHeight().padding(vertical = 8.dp))
                            Spacer(Modifier.width(20.dp))
                            val st = rememberScrollState()
                            Column(Modifier.weight(0.58f).fillMaxHeight().verticalScroll(st, enabled = st.maxValue > 0)) {
                                Column(
                                    Modifier.fillMaxWidth().heightIn(min = pageH).padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    content = controls,
                                )
                            }
                        }
                    } else {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = if (compact) 16.dp else 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            artPane(Modifier.weight(1f).fillMaxWidth().padding(vertical = if (compact) 6.dp else 14.dp))
                            controls()
                        }
                    }
                }
            }
        }
    }
}
