@file:OptIn(ExperimentalMaterial3Api::class)

package com.uyatame.ucrt

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.basicMarquee
import androidx.activity.compose.BackHandler
import android.content.res.Configuration
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
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
fun AppTheme(mode: Int, dynamic: Boolean, seed: Int, text: Int = 0, glass: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = if (dynamic && android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (seed != 0) {
        remember(seed, dark) { ThemeColors.scheme(seed, dark) }
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    // 文字の色を選んでいれば、本文の文字と、控えめな文字(説明など。背景の色に少し寄せる)をその色にする
    val withText = if (text == 0) scheme else remember(scheme, text) {
        val c = androidx.compose.ui.graphics.Color(text)
        scheme.copy(
            onBackground = c, onSurface = c,
            onSurfaceVariant = androidx.compose.ui.graphics.lerp(c, scheme.surface, 0.3f),
        )
    }
    // リキッドグラス: メニュー・ダイアログ・シートの面を少し透かす(後ろの壁紙の色がにじむ。読みにくくならない程度に)
    val glassy = if (!glass) withText else remember(withText) {
        withText.copy(
            surfaceContainer = withText.surfaceContainer.copy(alpha = 0.90f),
            surfaceContainerHigh = withText.surfaceContainerHigh.copy(alpha = 0.92f),
            surfaceContainerLow = withText.surfaceContainerLow.copy(alpha = 0.93f),
            surfaceContainerHighest = withText.surfaceContainerHighest.copy(alpha = 0.70f),
        )
    }
    MaterialTheme(colorScheme = glassy, content = content)
}

@Composable
fun App(vm: MainViewModel) {
    val s by vm.settings.collectAsState()
    val sp: com.uyatame.ucrt.spotify.SpotifyViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    AppTheme(s.themeMode, s.dynamicColor, s.themeColor, s.textColor, s.appearance == 1) {
        val view = LocalView.current
        val keep = vm.busy && s.keepScreenOn
        DisposableEffect(keep) {
            view.keepScreenOn = keep
            onDispose { view.keepScreenOn = false }
        }
        // ナビゲーションバーを隠す(設定)。ほかのアプリから戻ったときなどに出てしまう分は MainActivity でかけ直す
        DisposableEffect(s.hideNavBar, s.hideStatusBar) {
            (view.context as? android.app.Activity)?.let { applyNavBar(it, view, s.hideNavBar, s.hideStatusBar) }
            onDispose {}
        }
        // 窓の地の色もテーマの色にする(バーを隠したり出したりする間に、白い余白が見えないように)
        val bg = MaterialTheme.colorScheme.background
        androidx.compose.runtime.SideEffect {
            (view.context as? android.app.Activity)?.window?.decorView?.setBackgroundColor(bg.toArgb())
        }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var settingsPage by rememberSaveable { mutableIntStateOf(0) }
        var showPlayer by rememberSaveable { mutableStateOf(false) }
        var showSpotifyPlayer by rememberSaveable { mutableStateOf(false) }
        // ストリーミングのタブで Spotify の画面を開いているか
        var spotifyOpen by rememberSaveable { mutableStateOf(false) }
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
        // お知らせは 1 つずつ順に出す。LaunchedEffect(vm.snack) のように値をキーにすると、
        // 受け取ってすぐ null に戻した時点で作り直されて、表示中のお知らせがすぐ消えてしまうので、変化を集めて出す
        LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { vm.actionSnack }.collect { a ->
                if (a == null) return@collect
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
        }
        LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { vm.snack }.collect { m ->
                if (m == null) return@collect
                vm.snack = null
                snackHost.showSnackbar(m)
            }
        }
        // 再生が終わって曲がなくなったら、再生画面を開いた印も戻す(次に再生したとき、勝手に全画面で開かないように)
        // (起動直後のまだ曲を読み込んでいないときは戻さない。曲があった状態から無くなったときだけ)
        val hasCurrent = vm.player.current != null
        var hadCurrent by remember { mutableStateOf(false) }
        LaunchedEffect(hasCurrent) {
            if (!hasCurrent && hadCurrent) showPlayer = false
            hadCurrent = hasCurrent
        }
        BackHandler(enabled = tab == 2 && settingsPage != 0) { settingsPage = 0 }

        // 横向きとタブレットモードでは、下のタブの代わりに左のレールで切り替える(ミニプレーヤーなどは中身の下に出す)
        val conf = LocalConfiguration.current
        val tablet = when (s.tabletMode) {
            1 -> true
            2 -> false
            else -> conf.smallestScreenWidthDp >= 600
        }
        val land = isLandscape() || tablet
        // タブレットモードで幅が広ければ、再生画面を右に出したままにする
        val pane = tablet && conf.screenWidthDp >= 840 && vm.player.current != null
        val paneWidth = (conf.screenWidthDp * 0.36f).coerceIn(340f, 440f).dp
        // 全画面の再生画面(UCRT の曲・ほかのアプリの曲)を出しているか。出している間は、お知らせが再生画面に隠れないように上に重ねる
        val playerOverlay = (showPlayer && vm.player.current != null && !pane) ||
            (vm.externalPlayerOpen && com.uyatame.ucrt.player.ExternalMedia.now != null) ||
            (showSpotifyPlayer && sp.hasTrack && s.spotifyEnabled)
        // ライブラリを表示中にもう一度押すと、ライブラリのトップへ戻る
        // (ストリーミングのタブも同じく、表示中にもう一度押すと、Spotify の中のトップ → ストリーミングのトップの順に戻る)
        val onTab: (Int) -> Unit = { t ->
            if (t == 0 && tab == 0) vm.libHome()
            else if (t == TAB_STREAMING && tab == TAB_STREAMING) { if (spotifyOpen && sp.stack.isNotEmpty()) sp.home() else spotifyOpen = false }
            else tab = t
        }
        val tabs = listOf(
            Triple(AppIcons.Library, S(R.string.library), 0),
            Triple(AppIcons.Album, S(R.string.disc), 1),
            Triple(AppIcons.Cloud, S(R.string.streaming), TAB_STREAMING),
            Triple(AppIcons.Upsample, S(R.string.upsample), TAB_UPSAMPLE),
            Triple(Icons.Filled.Settings, S(R.string.settings), 2),
        )
        // リキッドグラス: 下のタブとミニプレーヤーなどを、中身の上に浮かぶガラスにする
        val glass = s.appearance == 1
        val backdrop = rememberBackdrop()
        var barsPx by remember { mutableIntStateOf(0) }
        val barsDp = with(LocalDensity.current) { barsPx.toDp() }
        CompositionLocalProvider(LocalTablet provides tablet, LocalShowArt provides s.listArt, LocalTrackArt provides s.trackArt, LocalGlass provides glass,
            LocalGlassStyle provides GlassStyle(s.glassOpacity / 100f, if (s.glassTint == 0) null else Color(s.glassTint))) {
        val sch = MaterialTheme.colorScheme
        val darkUi = sch.surface.luminance() < 0.5f
        Box(
            Modifier.fillMaxSize().then(
                if (glass) Modifier.glassWallpaper(sch.primary, sch.tertiary, sch.secondary, sch.background, darkUi) else Modifier,
            ),
        ) {
        Row(Modifier.fillMaxSize()) {
        if (land && glass) {
            Box(
                Modifier.fillMaxHeight().windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                ).padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) { GlassRail(tabs, tab, onTab) }
        } else if (land) {
            NavigationRail(
                Modifier.fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
            ) {
                Spacer(Modifier.weight(1f))
                tabs.forEach { (icon, label, i) ->
                    NavigationRailItem(tab == i, { onTab(i) }, { Icon(icon, null) }, label = { Text(label) })
                }
                Spacer(Modifier.weight(1f))
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            // リキッドグラスでは、後ろの壁紙を見せる
            containerColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.background,
            // 全画面の再生画面を出している間は、お知らせをその上に出す(下の重ね書きを参照)
            snackbarHost = { if (!playerOverlay) SnackbarHost(snackHost, if (glass) Modifier.padding(bottom = barsDp) else Modifier) },
            // 横向き: 左の端はレールが受け持つので、中身は上・右・下だけ避ける
            contentWindowInsets = if (land) {
                WindowInsets.systemBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom)
            } else ScaffoldDefaults.contentWindowInsets,
            bottomBar = if (glass) ({}) else ({
                Column(
                    if (land) {
                        Modifier.windowInsetsPadding(
                            WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.End + WindowInsetsSides.Bottom),
                        )
                    } else Modifier,
                ) {
                    if (vm.busy) RipBar(vm) { tab = 1 }
                    if (vm.player.current != null && !pane) MiniPlayer(vm) { showPlayer = true }
                    // Spotify アプリで再生中の曲(Spotify の機能を使っているとき。ストリーミングのタブでは、一時停止中でも出す)
                    if (s.spotifyEnabled && sp.connected && sp.hasTrack && (tab == TAB_STREAMING || !sp.paused)) SpotifyBar(sp) { showSpotifyPlayer = true }
                    if (!land) {
                        NavigationBar {
                            tabs.forEach { (icon, label, i) ->
                                NavigationBarItem(tab == i, { onTab(i) }, { Icon(icon, null) }, label = { Text(label) })
                            }
                        }
                    }
                }
            }),
        ) { pad ->
            // タブごとに、スクロールの位置などを覚えておく(タブを切り替えて戻ったときに、元の位置に戻れるように)
            val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            val screens: @Composable () -> Unit = {
                tabStates.SaveableStateProvider("tab$tab") {
                    when (tab) {
                        0 -> LibraryScreen(vm, s)
                        1 -> CdScreen(vm, s) { tab = 2; settingsPage = 0 }
                        TAB_STREAMING -> StreamingScreen(vm, sp, s, spotifyOpen, { spotifyOpen = it }) { showSpotifyPlayer = true }
                        TAB_UPSAMPLE -> UpsampleScreen(vm, s)
                        else -> SettingsScreen(vm, s, settingsPage) { settingsPage = it }
                    }
                }
            }
            if (!glass) {
                Box(Modifier.padding(pad).fillMaxSize()) { screens() }
            } else {
                // 中身は画面の下の端まで広げ(ガラスの下までスクロールできる)、いちばん下にはガラスの分の余白を足す
                val dir = LocalLayoutDirection.current
                // 後ろの中身をぼかして映すのは Android 12 から(それより前は、記録もしない)
                val blurOk = android.os.Build.VERSION.SDK_INT >= 31
                CompositionLocalProvider(LocalBackdrop provides (if (blurOk) backdrop else null)) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier.fillMaxSize().padding(top = pad.calculateTopPadding(), start = pad.calculateStartPadding(dir), end = pad.calculateEndPadding(dir))
                                .then(if (blurOk) Modifier.backdropSource(backdrop) else Modifier),
                        ) {
                            CompositionLocalProvider(LocalBarInset provides barsDp) { screens() }
                        }
                        Column(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { barsPx = it.height }
                                .windowInsetsPadding(
                                    WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                                )
                                .padding(bottom = 8.dp),
                        ) {
                            if (vm.busy) RipBar(vm) { tab = 1 }
                            if (vm.player.current != null && !pane) MiniPlayer(vm) { showPlayer = true }
                            if (s.spotifyEnabled && sp.connected && sp.hasTrack && (tab == TAB_STREAMING || !sp.paused)) SpotifyBar(sp) { showSpotifyPlayer = true }
                            if (!land) GlassTabBar(tabs, tab, onTab, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
                        }
                    }
                }
            }
        }
        if (pane) {
            // タブレットモード: 右に再生画面を出したままにする
            Box(Modifier.width(paneWidth).fillMaxHeight()) {
                CompositionLocalProvider(LocalInPane provides true) {
                    NowPlayingScreen(vm, s) {}
                }
            }
        }
        }
        AnimatedVisibility(
            visible = showPlayer && vm.player.current != null && !pane,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen(vm, s) { showPlayer = false }
        }
        // ほかの音楽アプリ(ストリーミングサービスなど)の曲の再生画面。そのアプリが終了したら閉じる
        val extNow = com.uyatame.ucrt.player.ExternalMedia.now != null
        LaunchedEffect(extNow) { if (!extNow) vm.externalPlayerOpen = false }
        AnimatedVisibility(
            visible = vm.externalPlayerOpen && com.uyatame.ucrt.player.ExternalMedia.now != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            ExternalPlayerScreen(vm, s) { vm.externalPlayerOpen = false }
        }
        // Spotify アプリで再生中の曲の全画面表示(下のバーをタップすると開く)
        AnimatedVisibility(
            visible = showSpotifyPlayer && sp.hasTrack && s.spotifyEnabled,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            SpotifyNowPlaying(sp, s, onClose = { showSpotifyPlayer = false }) {
                showSpotifyPlayer = false
                tab = TAB_STREAMING
                spotifyOpen = true
            }
        }
        if (playerOverlay) {
            SnackbarHost(
                snackHost,
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 16.dp),
            )
        }
        }
        }
        GlobalSheets(vm)
        SpotifyDialogs(sp)
        // Spotify のお知らせ(つながらない・キューに追加したなど)も、画面の下に出す
        LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { sp.message }.collect { m ->
                if (m == null) return@collect
                sp.message = null
                snackHost.showSnackbar(m, withDismissAction = true)
            }
        }
    }
}

// ---------------- 共通部品 ----------------

/** ナビゲーションバーを隠す・出す。隠している間も、画面の下の端からスワイプすると一時的に出る */
fun applyNavBar(activity: android.app.Activity, view: android.view.View, hide: Boolean, hideStatus: Boolean = false) {
    val c = androidx.core.view.WindowCompat.getInsetsController(activity.window, view)
    val nav = androidx.core.view.WindowInsetsCompat.Type.navigationBars()
    val status = androidx.core.view.WindowInsetsCompat.Type.statusBars()
    // 中身は画面の端まで描く(隠したバーの場所に、白い余白が残らないように)
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(activity.window, false)
    if (hide || hideStatus) {
        c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    if (hide) c.hide(nav) else c.show(nav)
    if (hideStatus) c.hide(status) else c.show(status)
}

/** タブレットモードか(設定と画面の大きさで決まる) */
val LocalTablet = androidx.compose.runtime.compositionLocalOf { false }

/** タブレットモードで、右に出したままの再生画面の中か */
val LocalInPane = androidx.compose.runtime.compositionLocalOf { false }

/**
 * 横向きの画面か(幅が高さより広い)。横向きでは、下のタブの代わりに左のレールを使い、
 * 再生画面やアルバムのページを左右に分ける。縦向きのときの見た目は変えない
 */
@Composable
fun isLandscape(): Boolean {
    // タブレットモードの右の再生画面の中は、幅が狭いので縦向きと同じ並びにする
    if (LocalInPane.current) return false
    val c = LocalConfiguration.current
    return c.orientation == Configuration.ORIENTATION_LANDSCAPE || c.screenWidthDp > c.screenHeightDp
}

/** 横向きのときだけ、中身を読みやすい幅にして左右の中央に寄せる(設定のページなど) */
@Composable
fun WideCenter(maxWidth: Dp = 720.dp, content: @Composable () -> Unit) {
    if (isLandscape() || LocalTablet.current) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = maxWidth).fillMaxSize()) { content() }
        }
    } else {
        content()
    }
}

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
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S(R.string.back)) }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    UCard(Modifier.fillMaxWidth()) {
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
                S(R.string.custom_change_in_advanced_settings, s.qualityLabel()),
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
            // 正方形に近い画像は枠いっぱいに切り抜く。8cm CD の縦長の短冊ジャケットなど、縦横の比率が大きく違う画像は、
            // 切り抜くと絵の大部分が隠れるので、全体が収まるように縮めて出す
            val r = img.width.toFloat() / img.height.coerceAtLeast(1)
            Image(img, null, Modifier.fillMaxSize(), contentScale = if (r in 0.8f..1.25f) ContentScale.Crop else ContentScale.Fit)
        } else {
            Icon(AppIcons.Album, null, Modifier.fillMaxSize(0.45f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * ジャケット画像(一覧など)。表示する大きさに合わせた小さな画像を読む。
 * 設定で「一覧にジャケットを表示」がオフなら、画像を読まずに目印だけ出す(性能の低い端末向け)
 */
@Composable
fun ArtImage(
    key: String?, trackUri: String?, coverUri: String?, modifier: Modifier, corner: Dp = 8.dp, size: Dp? = null,
    fallbackUri: String? = null,
) {
    if (!LocalShowArt.current) {
        CoverBox(null, modifier, corner)
        return
    }
    if (size != null) {
        // 大きさが決まっているとき(一覧の行など)は、BoxWithConstraints(行ごとに subcompose して重い)を使わない
        val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
        ArtImageAt(key, trackUri, coverUri, ArtLoader.bucketOf(px), modifier, corner, fallbackUri)
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val w = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val h = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val px = maxOf(w, h).takeIf { it > 0 } ?: with(density) { 96.dp.roundToPx() }
        ArtImageAt(key, trackUri, coverUri, ArtLoader.bucketOf(px), Modifier.fillMaxSize(), corner, fallbackUri)
    }
}

@Composable
private fun ArtImageAt(key: String?, trackUri: String?, coverUri: String?, bucket: Int, modifier: Modifier, corner: Dp, fallbackUri: String? = null) {
    val ctx = LocalContext.current
    val gen = ArtLoader.generation
    val img by produceState(remember(key, bucket) { ArtLoader.peek(key, bucket) }, key, trackUri, coverUri, gen, bucket) {
        // 曲やアルバムが変わったら、まず前の画像を消してから読み込む
        value = ArtLoader.peek(key, bucket)
        if (key != null) {
            // 画面から外れたら(取り消されたら)、読み込みの順番待ちもやめる
            value = kotlinx.coroutines.runInterruptible(Dispatchers.IO) { ArtLoader.load(ctx, key, trackUri, coverUri, bucket, fallbackUri) } ?: value
        }
    }
    CoverBox(img, modifier, corner)
}

/** 一覧にジャケットを表示するか(設定) */
val LocalShowArt = androidx.compose.runtime.compositionLocalOf { true }

/** 曲の一覧で、曲ごとに埋め込まれたジャケットを出すか(設定。オフならアルバムのジャケット) */
val LocalTrackArt = androidx.compose.runtime.compositionLocalOf { true }

/** 曲の一覧に出すジャケット(曲ごと、またはアルバムのもの) */
@Composable
fun TrackArt(vm: MainViewModel, t: com.uyatame.ucrt.library.LibTrack, modifier: Modifier, corner: Dp, size: Dp) {
    val r = vm.artRef(t, LocalTrackArt.current)
    ArtImage(r.key, r.trackUri, r.coverUri, modifier, corner, size, r.fallbackUri)
}

@Composable
fun PlayArt(vm: MainViewModel, item: PlayItem, modifier: Modifier, corner: Dp = 8.dp, size: Dp? = null) {
    if (item.cdTrack != null) CoverBox(vm.cdCover, modifier, corner)
    else ArtImage(item.artKey, item.artTrackUri, item.artCoverUri, modifier, corner, size, item.artFallbackUri)
}

// ---------------- プレイヤー ----------------

/** 取り込みの進み具合(どのタブからでも見える) */
@Composable
fun RipBar(vm: MainViewModel, onOpen: () -> Unit) {
    val p = vm.ripOverall
    val elapsed = System.currentTimeMillis() - vm.ripStartMs
    val eta = if (p > 0.03f && vm.ripStartMs > 0) ((elapsed / p) * (1 - p)).toLong() else -1L
    var confirmCancel by remember { mutableStateOf(false) }
    if (confirmCancel) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(S(R.string.stop_ripping)) },
            text = {
                Text(
                    S(R.string.track_being_ripped_wont_be),
                )
            },
            confirmButton = { TextButton({ confirmCancel = false; vm.cancelRip() }) { Text(S(R.string.stop_2)) } },
            dismissButton = { TextButton({ confirmCancel = false }) { Text(S(R.string.continue_label)) } },
        )
    }
    BarSurface(onOpen, MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Download, null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        vm.busyTitle(),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "${(p * 100).toInt()}%" + if (eta >= 0) S(R.string.about_left, fmtMs(eta)) else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton({ confirmCancel = true }) { Text(S(R.string.stop)) }
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
    BarSurface(onOpen) {
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
                    PlayArt(vm, cur, Modifier.size(44.dp), 6.dp, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            cur.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 3000),
                        )
                        Text(
                            (if (p.isCd) S(R.string.cd) else "") + cur.artist,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.72f),
                        )
                    }
                }
                IconButton({ p.toggle() }) {
                    Icon(if (p.isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow, if (p.isPlaying) S(R.string.pause) else S(R.string.play))
                }
                IconButton({ p.next() }) { Icon(AppIcons.SkipNext, S(R.string.next_track)) }
            }
        }
    }
}
