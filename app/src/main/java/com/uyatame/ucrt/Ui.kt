@file:OptIn(ExperimentalMaterial3Api::class)

package com.uyatame.ucrt

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.QUALITY_PRESETS
import com.uyatame.ucrt.data.presetIndex
import com.uyatame.ucrt.data.qualityLabel
import com.uyatame.ucrt.library.ArtLoader
import com.uyatame.ucrt.player.PlayItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun AppTheme(mode: Int, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = if (dynamic) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(vm: MainViewModel) {
    val s by vm.settings.collectAsState()
    AppTheme(s.themeMode, s.dynamicColor) {
        val view = LocalView.current
        val keep = vm.busy && s.keepScreenOn
        DisposableEffect(keep) {
            view.keepScreenOn = keep
            onDispose { view.keepScreenOn = false }
        }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var settingsPage by rememberSaveable { mutableIntStateOf(0) }
        var showPlayer by rememberSaveable { mutableStateOf(false) }
        // 再生画面やメニューから「アルバムを表示」などを選んだら、ライブラリのタブへ
        var seenNav by remember { mutableIntStateOf(vm.libNavEvent) }
        LaunchedEffect(vm.libNavEvent) {
            if (vm.libNavEvent != seenNav) {
                seenNav = vm.libNavEvent
                tab = 0
                showPlayer = false
            }
        }
        val snackHost = remember { SnackbarHostState() }

        // 新しい音楽CDを読み込んだら、ディスク画面を自動で開く
        var seenDisc by rememberSaveable { mutableIntStateOf(vm.discEvent) }
        LaunchedEffect(vm.discEvent) {
            if (vm.discEvent != seenDisc) {
                seenDisc = vm.discEvent
                if (s.autoOpenDisc) tab = 1
            }
        }
        LaunchedEffect(vm.actionSnack) {
            val a = vm.actionSnack ?: return@LaunchedEffect
            vm.actionSnack = null
            val res = snackHost.showSnackbar(a.text, a.action, withDismissAction = true, duration = SnackbarDuration.Long)
            if (res == SnackbarResult.ActionPerformed) {
                when (a) {
                    is ActionSnack.OpenAlbum -> {
                        tab = 0
                        val key = vm.albums.firstOrNull { it.title == a.album && (it.artist == a.artist || it.tracks.any { t -> t.artist == a.artist }) }?.key
                            ?: vm.albums.firstOrNull { it.title == a.album }?.key
                        if (key != null) vm.libPush("album:$key")
                    }
                    is ActionSnack.UndoMeta -> vm.undoMeta()
                }
            }
        }
        LaunchedEffect(vm.snack) {
            val m = vm.snack
            if (m != null) {
                vm.snack = null
                snackHost.showSnackbar(m)
            }
        }
        BackHandler(enabled = tab == 2 && settingsPage != 0) { settingsPage = 0 }

        Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackHost) },
            bottomBar = {
                Column {
                    if (vm.busy) RipBar(vm) { tab = 1 }
                    if (vm.player.current != null) MiniPlayer(vm) { showPlayer = true }
                    NavigationBar {
                        // ライブラリを表示中にもう一度押すと、ライブラリのトップへ戻る
                        NavigationBarItem(tab == 0, { if (tab == 0) vm.libHome() else tab = 0 }, { Icon(AppIcons.Library, null) }, label = { Text(T("ライブラリ", "Library")) })
                        NavigationBarItem(tab == 1, { tab = 1 }, { Icon(AppIcons.Album, null) }, label = { Text(T("ディスク", "Disc")) })
                        NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Settings, null) }, label = { Text(T("設定", "Settings")) })
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> LibraryScreen(vm, s)
                    1 -> CdScreen(vm, s) { tab = 2; settingsPage = 0 }
                    else -> SettingsScreen(vm, s, settingsPage) { settingsPage = it }
                }
            }
        }
        AnimatedVisibility(
            visible = showPlayer && vm.player.current != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen(vm, s) { showPlayer = false }
        }
        }
        GlobalSheets(vm)
    }
}

// ---------------- 共通部品 ----------------

@Composable
fun ScreenHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, T("戻る", "Back")) }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun SwitchRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) {
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked, onChange)
    }
}

@Composable
fun IntSlider(
    title: String,
    value: Int,
    range: IntRange,
    label: (Int) -> String,
    desc: String? = null,
    onDone: (Int) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label(v.roundToInt()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (desc != null) {
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = v,
            onValueChange = { v = it },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            onValueChangeFinished = { onDone(v.roundToInt()) },
        )
    }
}

@Composable
fun Segmented(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = selected == i,
                    onClick = { onSelect(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

@Composable
fun ChoiceList(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        options.forEachIndexed { i, o ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onSelect(i) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected == i, { onSelect(i) })
                Text(o, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun QualityOptions(vm: MainViewModel, s: AppSettings) {
    val cur = s.presetIndex()
    Column {
        QUALITY_PRESETS.forEachIndexed { i, p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.setPreset(p) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(cur == i, { vm.setPreset(p) })
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.bodyLarge)
                    Text(p.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (cur < 0) {
            Text(
                T("カスタム設定中:${s.qualityLabel()}(詳細設定で変更)", "Custom: ${s.qualityLabel()} (change in Advanced settings)"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(104.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (body.isNotEmpty()) {
            Text(
                body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null && onAction != null) FilledTonalButton(onAction) { Text(action) }
    }
}

@Composable
fun CoverBox(img: ImageBitmap?, modifier: Modifier, corner: Dp = 8.dp) {
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) {
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(AppIcons.Album, null, Modifier.fillMaxSize(0.45f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ArtImage(key: String?, trackUri: String?, coverUri: String?, modifier: Modifier, corner: Dp = 8.dp) {
    val ctx = LocalContext.current
    val gen = ArtLoader.generation
    val img by produceState<ImageBitmap?>(ArtLoader.peek(key), key, trackUri, coverUri, gen) {
        // 曲やアルバムが変わったら、まず前の画像を消してから読み込む
        value = ArtLoader.peek(key)
        if (value == null && key != null) {
            value = withContext(Dispatchers.IO) { ArtLoader.load(ctx, key, trackUri, coverUri) }
        }
    }
    CoverBox(img, modifier, corner)
}

@Composable
fun PlayArt(vm: MainViewModel, item: PlayItem, modifier: Modifier, corner: Dp = 8.dp) {
    if (item.cdTrack != null) CoverBox(vm.cdCover, modifier, corner)
    else ArtImage(item.artKey, item.artTrackUri, item.artCoverUri, modifier, corner)
}

// ---------------- プレイヤー ----------------

/** 取り込みの進み具合(どのタブからでも見える) */
@Composable
fun RipBar(vm: MainViewModel, onOpen: () -> Unit) {
    val p = vm.ripOverall
    val elapsed = System.currentTimeMillis() - vm.ripStartMs
    val eta = if (p > 0.03f && vm.ripStartMs > 0) ((elapsed / p) * (1 - p)).toLong() else -1L
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Download, null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        T("取り込み中 ${vm.ripIndex + 1} / ${vm.ripCount} 曲目", "Ripping track ${vm.ripIndex + 1} of ${vm.ripCount}"),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "${(p * 100).toInt()}%" + if (eta >= 0) T(" ・ 残り約${fmtMs(eta)}", " · about ${fmtMs(eta)} left") else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton({ vm.cancelRip() }) { Text(T("中止", "Cancel")) }
            }
            LinearProgressIndicator({ p }, Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp))
        }
    }
}

@Composable
fun MiniPlayer(vm: MainViewModel, onOpen: () -> Unit) {
    val p = vm.player
    val cur = p.current ?: return
    val scope = rememberCoroutineScope()
    val shift = remember { Animatable(0f) }
    val density = LocalDensity.current
    val limit = with(density) { 72.dp.toPx() }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column {
            LinearProgressIndicator(
                progress = { if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(
                Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                    // 左右にスワイプすると、前後の曲へ
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val v = shift.value
                                scope.launch {
                                    if (v <= -limit) vm.player.next() else if (v >= limit) vm.player.prev()
                                    shift.animateTo(0f)
                                }
                            },
                            onDragCancel = { scope.launch { shift.animateTo(0f) } },
                        ) { change, d ->
                            change.consume()
                            scope.launch { shift.snapTo((shift.value + d).coerceIn(-limit * 2, limit * 2)) }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).graphicsLayer {
                        translationX = shift.value
                        alpha = 1f - (kotlin.math.abs(shift.value) / (limit * 2.5f)).coerceIn(0f, 0.7f)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayArt(vm, cur, Modifier.size(44.dp), 6.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cur.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (p.isCd) T("CD ・ ", "CD · ") else "") + cur.artist,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton({ p.toggle() }) {
                    Icon(if (p.isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow, if (p.isPlaying) T("一時停止", "Pause") else T("再生", "Play"))
                }
                IconButton({ p.next() }) { Icon(AppIcons.SkipNext, T("次の曲", "Next track")) }
            }
        }
    }
}
