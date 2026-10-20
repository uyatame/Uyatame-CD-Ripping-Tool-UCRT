package com.uyatame.ucrt

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.spotify.SpKind
import com.uyatame.ucrt.spotify.SpotifyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Spotify アプリで再生中の曲の全画面表示。見た目は通常の再生画面に合わせる
 * (アンビエントモードとぼかしの設定も同じものを使う)。音質や EQ などは Spotify アプリの側なので出さない
 */
@Composable
fun SpotifyNowPlaying(sp: SpotifyViewModel, s: AppSettings, onClose: () -> Unit, onOpenInTab: () -> Unit) {
    val ctx = LocalContext.current
    var showUpNext by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    if (showUpNext) SpotifyUpNextSheet(sp) { showUpNext = false }
    if (showDevices) SpotifyDeviceSheet(sp) { showDevices = false }
    // 再生中の曲のアルバムやアーティストのページを、Spotify のタブで開く
    val goTo: (String, String) -> Unit = { uri, title -> sp.openUri(uri, title); onOpenInTab() }
    val ambient = s.ambientPlayer
    val art = sp.trackArt
    val base by produceState(Color(0xFF2A2A2A), art) {
        val a = art
        value = if (a == null) Color(0xFF2A2A2A) else withContext(Dispatchers.Default) { runCatching { dominantColor(a) }.getOrNull() } ?: Color(0xFF2A2A2A)
    }
    val tint by animateColorAsState(base, tween(700), label = "tint")
    val dim by animateFloatAsState(if (sp.paused) 0.4f else 0f, tween(500), label = "dim")
    BackHandler(onBack = onClose)

    // 状態バーの文字色を背景に合わせる
    val dark = ambient || MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3 < 0.5f }
    val view = LocalView.current
    DisposableEffect(dark) {
        val w = (view.context as? Activity)?.window
        val c = w?.let { WindowCompat.getInsetsController(it, view) }
        val before = c?.isAppearanceLightStatusBars
        c?.isAppearanceLightStatusBars = !dark
        onDispose { if (before != null) c?.isAppearanceLightStatusBars = before }
    }
    val content = if (ambient) Color.White else MaterialTheme.colorScheme.onSurface
    val sub = if (ambient) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
    val accent = if (ambient) Color(0xFFF3D58C) else MaterialTheme.colorScheme.tertiary

    // 再生位置は、再生中は 0.5 秒ごとに進める
    var pos by remember { mutableLongStateOf(sp.positionMs()) }
    LaunchedEffect(sp.paused, sp.trackUri) {
        while (true) {
            pos = sp.positionMs()
            if (sp.paused) break
            delay(500)
        }
    }
    var seeking by remember { mutableStateOf<Float?>(null) }

    // 下へスワイプで閉じる(指に合わせて画面が下がり、少しだけなら元に戻る)
    val closeNow by rememberUpdatedState(onClose)
    val scope = rememberCoroutineScope()
    val shift = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 120.dp.toPx() }
    // 操作したことが指に伝わるように、軽く振動させる(閉じる位置まで下げたとき・再生・曲送り・お気に入り)
    val haptic = LocalHapticFeedback.current
    var armed by remember { mutableStateOf(false) }
    // 縦に余裕のある画面(Xperia 1 VI などの縦長の画面)では、曲名とボタンを一回り大きくする
    val roomy = !isLandscape() && LocalConfiguration.current.screenHeightDp >= 760
    val skipSize = if (roomy) 62.dp else 56.dp
    val playSize = if (roomy) 84.dp else 76.dp
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { translationY = shift.value }
            .background(if (ambient) Color.Black else MaterialTheme.colorScheme.surface)
            // (下にある画面へタッチが抜けないように、ここで受け止める)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        armed = false
                        scope.launch {
                            if (shift.value > threshold) {
                                closeNow()
                                shift.snapTo(0f)
                            } else {
                                shift.animateTo(0f)
                            }
                        }
                    },
                    onDragCancel = { armed = false; scope.launch { shift.animateTo(0f) } },
                ) { change, d ->
                    change.consume()
                    val next = (shift.value + d).coerceAtLeast(0f)
                    if ((next > threshold) != armed) {
                        armed = next > threshold
                        if (armed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    scope.launch { shift.snapTo(next) }
                }
            },
    ) {
        if (ambient) {
            if (art != null) {
                Image(
                    art, null, if (s.ambientBlur) Modifier.fillMaxSize().blur((s.ambientBlurLevel * 14).dp) else Modifier.fillMaxSize(),
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
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.22f), Color.Transparent))))
        }
        CompositionLocalProvider(LocalContentColor provides content) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                // ---- 上の帯 ----
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Filled.KeyboardArrowDown, S(R.string.close), tint = content) }
                    // 再生元(プレイリスト・アルバムなど)。タップでそのページへ
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = sp.contextUri.isNotEmpty()) { goTo(sp.contextUri, sp.contextTitle) }
                            .padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (sp.contextTitle.isNotEmpty()) S(R.string.spotify_playing_from) else S(R.string.spotify_playing_in_spotify),
                            style = MaterialTheme.typography.labelMedium, color = sub, maxLines = 1,
                        )
                        if (sp.contextTitle.isNotEmpty()) {
                            Text(
                                sp.contextTitle, style = MaterialTheme.typography.labelLarge, color = content,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, S(R.string.menu), tint = content) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(S(R.string.spotify_add_to_playlist)) }, {
                                menu = false
                                sp.pickPlaylistFor = listOf(sp.trackUri)
                            })
                            if (sp.trackAlbumUri.isNotEmpty()) {
                                DropdownMenuItem({ Text(S(R.string.spotify_go_to_album)) }, { menu = false; goTo(sp.trackAlbumUri, sp.trackAlbum) })
                            }
                            if (sp.trackArtistUri.isNotEmpty()) {
                                DropdownMenuItem({ Text(S(R.string.spotify_go_to_artist)) }, { menu = false; goTo(sp.trackArtistUri, sp.trackArtist) })
                            }
                            DropdownMenuItem({ Text(S(R.string.spotify_open_spotify_app)) }, {
                                menu = false
                                ctx.packageManager.getLaunchIntentForPackage("com.spotify.music")?.let { runCatching { ctx.startActivity(it) } }
                            })
                        }
                    }
                }

                val cover: @Composable (Modifier) -> Unit = { m -> CoverBox(art, m.aspectRatio(1f), 12.dp) }
                val controls: @Composable ColumnScope.() -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                sp.trackTitle, style = if (roomy) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, color = content,
                            )
                            Text(
                                sp.trackArtist, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = sub,
                                modifier = Modifier.clickable(enabled = sp.trackArtistUri.isNotEmpty()) { goTo(sp.trackArtistUri, sp.trackArtist) },
                            )
                            if (sp.trackAlbum.isNotEmpty()) {
                                Text(
                                    sp.trackAlbum, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = sub,
                                    modifier = Modifier.clickable(enabled = sp.trackAlbumUri.isNotEmpty()) { goTo(sp.trackAlbumUri, sp.trackAlbum) },
                                )
                            }
                        }
                        // お気に入りの曲に入れる・外す
                        val liked = sp.saved[sp.trackUri] == true
                        IconButton({
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            sp.setSaved(ctx, sp.trackUri, SpKind.TRACK, sp.trackTitle, !liked)
                        }) {
                            Icon(
                                if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                spSaveLabel(SpKind.TRACK, liked), tint = if (liked) accent else sub,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // ---- 再生位置 ----
                    val dur = sp.durationMs.coerceAtLeast(1)
                    val frac = seeking ?: (pos.toFloat() / dur).coerceIn(0f, 1f)
                    Slider(
                        value = frac,
                        onValueChange = { seeking = it },
                        onValueChangeFinished = {
                            seeking?.let { f -> sp.seekTo(ctx, (f * dur).toLong()); pos = (f * dur).toLong() }
                            seeking = null
                        },
                        enabled = sp.canSeek,
                        colors = if (ambient) {
                            SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.28f))
                        } else SliderDefaults.colors(),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(fmtMs((frac * dur).toLong()), style = MaterialTheme.typography.labelMedium, color = sub)
                        Spacer(Modifier.weight(1f))
                        Text("-" + fmtMs(((1f - frac) * dur).toLong().coerceAtLeast(0)), style = MaterialTheme.typography.labelMedium, color = sub)
                    }
                    Spacer(Modifier.height(10.dp))
                    // ---- 再生操作 ----
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton({ sp.toggleRepeat(ctx) }) {
                            Icon(
                                if (sp.repeatMode == 1) AppIcons.RepeatOne else AppIcons.Repeat,
                                when (sp.repeatMode) { 2 -> S(R.string.repeat_all); 1 -> S(R.string.repeat_one); else -> S(R.string.repeat_off) },
                                tint = if (sp.repeatMode != 0) accent else sub,
                            )
                        }
                        OutlinedIconButton(
                            { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); sp.prev(ctx) },
                            Modifier.size(skipSize), enabled = sp.canSkipPrev, border = BorderStroke(2.dp, content),
                        ) {
                            Icon(AppIcons.SkipPrevious, S(R.string.spotify_previous_track), Modifier.size(skipSize * 0.46f), tint = content)
                        }
                        OutlinedIconButton(
                            { haptic.performHapticFeedback(HapticFeedbackType.LongPress); sp.toggle(ctx) },
                            Modifier.size(playSize), shape = CircleShape, border = BorderStroke(2.5.dp, content),
                        ) {
                            Icon(
                                if (sp.paused) Icons.Filled.PlayArrow else AppIcons.Pause,
                                if (sp.paused) S(R.string.play) else S(R.string.pause),
                                Modifier.size(playSize * 0.52f), tint = content,
                            )
                        }
                        OutlinedIconButton(
                            { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); sp.next(ctx) },
                            Modifier.size(skipSize), enabled = sp.canSkipNext, border = BorderStroke(2.dp, content),
                        ) {
                            Icon(AppIcons.SkipNext, S(R.string.next_track), Modifier.size(skipSize * 0.46f), tint = content)
                        }
                        IconButton({ sp.toggleShuffle(ctx) }) {
                            Icon(AppIcons.Shuffle, S(R.string.shuffle_2), tint = if (sp.shuffle) accent else sub)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // ---- 下の帯: 再生する機器・次に再生 ----
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ showDevices = true }) { Icon(AppIcons.Speaker, S(R.string.spotify_devices), tint = sub) }
                        Spacer(Modifier.weight(1f))
                        // この端末の中での出力先(有線ヘッドホン・Bluetooth など)。タップで Android の出力の切り替え
                        OutputChip(content, sub)
                        Spacer(Modifier.weight(1f))
                        IconButton({ showUpNext = true }) { Icon(AppIcons.Queue, S(R.string.spotify_up_next), tint = sub) }
                    }
                }

                if (isLandscape()) {
                    // 横向き: 左にジャケット、右に曲名と操作
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        Box(Modifier.fillMaxHeight().weight(1f), contentAlignment = Alignment.Center) { cover(Modifier.fillMaxHeight()) }
                        Column(Modifier.weight(1f).widthIn(max = 520.dp)) { controls() }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Box(Modifier.fillMaxWidth().weight(1f, fill = false).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            cover(Modifier.fillMaxWidth())
                        }
                        Column { controls() }
                    }
                }
            }
        }
    }
}

// ---------------- 次に再生 ----------------

/** 次に再生される曲(Spotify アプリの再生待ち)。タップでその曲まで飛ぶ */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SpotifyUpNextSheet(sp: SpotifyViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    // 曲が変わるたびに読み直す
    LaunchedEffect(sp.trackUri) { sp.loadUpNext() }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(S(R.string.spotify_up_next), style = MaterialTheme.typography.titleLarge)
            Text(
                S(R.string.spotify_up_next_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp),
            )
            when {
                sp.upNextLoading && sp.upNext.isEmpty() -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
                sp.upNextError != null -> Text(sp.upNextError.orEmpty(), color = MaterialTheme.colorScheme.error)
                sp.upNext.isEmpty() -> Text(S(R.string.spotify_nothing_up_next), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(sp.upNext.size) { i ->
                    val t = sp.upNext[i]
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { sp.skipTo(ctx, i) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(28.dp), textAlign = TextAlign.Center,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                t.sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (t.durationMs > 0) {
                            Text(fmtMs(t.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- 再生する機器 ----------------

/** Spotify Connect の機器を選んで再生を移す。この端末に戻す、音量の調整も */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SpotifyDeviceSheet(sp: SpotifyViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { sp.loadDevices() }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        // 横向きでは高さが足りないので、スクロールできるようにする
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(S(R.string.spotify_devices), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton({ sp.loadDevices() }) { Text(S(R.string.spotify_reload)) }
            }
            // 再生中の機器の音量
            val active = sp.devices.firstOrNull { it.active }
            val vol = sp.volume ?: active?.takeIf { it.supportsVolume }?.volume?.let { it / 100f }
            if (vol != null) {
                var v by remember(vol) { mutableStateOf(vol) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Volume, S(R.string.volume), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(v, { v = it }, Modifier.weight(1f).padding(start = 8.dp), onValueChangeFinished = { sp.setVolume(ctx, v) })
                }
            }
            if (sp.devicesLoading && sp.devices.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }
            sp.devices.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !d.active && !d.restricted) { sp.transferTo(d) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (d.type.equals("Smartphone", true)) AppIcons.Smartphone else AppIcons.Speaker, null,
                        tint = if (d.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (d.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (d.active) S(R.string.spotify_playing_on_this_device) else d.type,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!sp.devicesLoading && sp.devices.isEmpty()) {
                Text(S(R.string.spotify_no_devices), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.OutlinedButton({ sp.playOnThisPhone(ctx) }, Modifier.padding(top = 8.dp)) {
                Text(S(R.string.spotify_play_on_this_phone))
            }
            Text(
                S(R.string.spotify_devices_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
