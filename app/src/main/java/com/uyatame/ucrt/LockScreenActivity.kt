package com.uyatame.ucrt

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.data.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ロック画面。システムのロックの上に重なって、再生画面を出す(LockScreen を参照)。
 * 解除のスワイプ(向きは設定で選ぶ)では、システムの requestDismissKeyguard を呼ぶ。PIN・指紋があればシステムが認証を求め、
 * 通れば閉じる。通れなければ(取り消し・失敗)、ロック画面のまま、元の位置に戻る。
 */
class LockScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 再生の持ち主(MainViewModel)が居ないとき(アプリが終わったあと)は、見せるものが無いので閉じる
        val vm = MainViewModel.instance
        if (vm == null) { finish(); return }
        enableEdgeToEdge()
        // 状態バー・ナビゲーションバーを隠す(ロック画面を全画面に。端からのスワイプで一時的に出せる)
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.let {
                    it.hide(android.view.WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        }
        // システムのロックの上に出す(Android 8.1 からは属性、それより前はフラグ)。
        // 画面は点けない(画面が消えたときに先に出しておくので、点けると、消したとたんにまた点いてしまう)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        // スワイプだけのロック(PIN などが無い)なら、システムのロックをこの画面の下で消しておく
        // (UCRT のロック画面を閉じたとき、もう一度システムのスワイプの画面が出ないように。PIN などがあるときは付けない:
        // 付けると、この画面より先に PIN の入力が出てしまう)
        if (!(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardSecure) {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
        LockScreen.showing = true
        LockScreen.cancelNotification(this)
        // 指紋・顔など、ほかの方法でシステムのロックが解けたら閉じる(解けたあとに、ロック画面が残らないように)
        val present = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                com.uyatame.ucrt.player.AudioEngine.log("lock screen: unlocked by the system, closing")
                LockScreen.showing = false
                finish()
            }
        }
        userPresent = present
        androidx.core.content.ContextCompat.registerReceiver(
            this, present, IntentFilter(Intent.ACTION_USER_PRESENT), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val now = android.os.SystemClock.elapsedRealtime()
        com.uyatame.ucrt.player.AudioEngine.log(
            "lock screen: opened (screen on=${interactive()}, locked=${locked()}, " +
                "${now - LockScreen.screenOffAt} ms after screen off, ${now - LockScreen.screenOnAt} ms after screen on)",
        )
        // 最初に描けた時刻も残す(画面を点けてから、ロック画面が見えるまでの時間)
        window.decorView.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            var done = false
            override fun onDraw() {
                if (done) return
                done = true
                val t = android.os.SystemClock.elapsedRealtime()
                com.uyatame.ucrt.player.AudioEngine.log("lock screen: first frame (${t - LockScreen.screenOnAt} ms after screen on, screen on=${interactive()})")
            }
        })
        setContent {
            val d = LocalDensity.current
            val conf = LocalConfiguration.current
            val st by vm.settings.collectAsState()
            // 画面の向き(設定)
            LaunchedEffect(st.lockOrientation) { requestedOrientation = orientationFor(st.lockOrientation) }
            // タブレットモード(設定。自動なら、画面の短い辺が 600dp 以上の端末)
            val wide = when (st.lockTablet) { 1 -> true; 2 -> false; else -> conf.smallestScreenWidthDp >= 600 }
            val shortDp = minOf(conf.screenWidthDp, conf.screenHeightDp).coerceAtLeast(1)
            val scale = (shortDp / 360f).coerceIn(0.75f, 1f)
            val fixed = androidx.compose.ui.unit.Density(d.density * scale, d.fontScale.coerceIn(0.85f, 1.3f))
            CompositionLocalProvider(LocalDensity provides fixed, LocalWideLayout provides wide) {
                val s = st
                AppTheme(s.themeMode, s.dynamicColor, s.themeColor, s.textColor, s.appearance == 1) {
                    LockScreenContent(vm, s, onUnlock = { done -> unlock(done) })
                }
            }
        }
    }

    /** 設定の向きを、Activity の向きの値に */
    private fun orientationFor(v: Int): Int = when (v) {
        1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        3 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private var userPresent: BroadcastReceiver? = null

    /** 解除の認証を求めている間(システムの PIN・指紋の画面が前に出ても、この画面は閉じない) */
    private var dismissing = false

    /** システムのロックを解除する。解除できたら閉じる。done(ok) で、結果を画面に返す(ok = false なら、画面を元の位置に戻す) */
    private fun unlock(done: (Boolean) -> Unit) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        // システムのロックがかかっていない(画面を消してすぐ点けたときなど)なら、そのまま閉じる
        // (ロックが無いのに解除を求めると、失敗が返って閉じられなくなることがある)
        if (!km.isKeyguardLocked) { done(true); finish(); return }
        val secure = km.isKeyguardSecure
        dismissing = true
        if (Build.VERSION.SDK_INT >= 26) {
            // 端末によっては、システムが解除の結果を返さない(返事を待ったまま、画面が固まって見える)。
            // 少し待っても返事が無ければ、この画面を閉じて、システムのロック画面で解除してもらう
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val giveUp = Runnable {
                if (!dismissing) return@Runnable
                com.uyatame.ucrt.player.AudioEngine.log("lock screen: no answer to the unlock request, closing (secure=${km.isKeyguardSecure})")
                dismissing = false
                LockScreen.showing = false
                done(true)
                finish()
            }
            if (secure) {
                // PIN などのロック: システムに解除を頼むと、PIN の入力の画面が出る(普通のロック画面を挟まない)。
                // 端末によっては、その入力の画面がこの画面の後ろに隠れて、固まって見える(Android 17 の Xperia など)ので、
                // 少ししたら、この画面をロックの上から外して、入力の画面が見えるようにする。
                // 入力の画面で解除されたら(USER_PRESENT)閉じ、取り消されたら、またロックの上に出す
                handler.postDelayed({
                    if (dismissing && Build.VERSION.SDK_INT >= 27) {
                        com.uyatame.ucrt.player.AudioEngine.log("lock screen: stepping aside for the system PIN screen")
                        setShowWhenLocked(false)
                    }
                }, 400)
            } else {
                handler.postDelayed(giveUp, 2500)
            }
            fun answered(what: String) {
                handler.removeCallbacks(giveUp)
                dismissing = false
                com.uyatame.ucrt.player.AudioEngine.log("lock screen: unlock $what")
            }
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { answered("succeeded"); done(true); finish() }
                override fun onDismissCancelled() {
                    answered("cancelled")
                    // PIN の入力が取り消された: ロックの上に戻る
                    if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
                    done(false)
                }
                override fun onDismissError() {
                    // 解除を頼めなかった: 固まらないよう、この画面を閉じて、システムのロック画面で解除してもらう
                    answered("error, closing")
                    LockScreen.showing = false
                    done(true)
                    finish()
                }
            })
        } else {
            // Android 7 以前: ロックが「スワイプだけ」なら、これで解ける(PIN などがあるときは、システムが求める)
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            done(true)
            finish()
        }
    }

    /**
     * 裏に回ったら(ホームボタン・別のアプリ・画面を消したあと)閉じる。残すと、「すでに出ている」と見なして、次からロック画面が出なくなる。
     * 解除の認証を求めている間と、画面の向きが変わる間は、閉じない
     */
    override fun onStop() {
        super.onStop()
        // 画面が消えて止まったときは閉じない(次に画面を点けたとき、そのまま前に出ている)。
        // ただし、解除の返事を待っている途中なら閉じる(待ったまま残り、次も固まって見えないように)
        if (!interactive()) {
            if (dismissing) { dismissing = false; LockScreen.showing = false; finish() }
            return
        }
        com.uyatame.ucrt.player.AudioEngine.log("lock screen: went to background (dismissing=$dismissing)")
        if (!dismissing && !isChangingConfigurations) { LockScreen.showing = false; finish() }
    }

    override fun onResume() {
        super.onResume()
        // 画面が消えたときに用意しておいたが、点いたときに端末がロックされていなかった(「ロックされていなくても出す」がオフ)なら閉じる
        val vm = MainViewModel.instance
        // 設定の「ロック画面を見てみる」から開いたときは、ロックされていなくても閉じない
        val preview = intent?.getBooleanExtra(LockScreen.EXTRA_PREVIEW, false) == true
        if (vm != null && !preview && !vm.settings.value.lockAlways && !locked() && !dismissing) {
            com.uyatame.ucrt.player.AudioEngine.log("lock screen: device not locked, closing")
            LockScreen.showing = false
            finish()
        }
    }

    /** すでに出ているときに開き直された(singleInstance)。見てみるの印などを読み直せるよう、新しいものにする */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun interactive(): Boolean =
        runCatching { (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive }.getOrDefault(true)

    private fun locked(): Boolean =
        runCatching { (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked }.getOrDefault(false)

    override fun onDestroy() {
        LockScreen.showing = false
        userPresent?.let { runCatching { unregisterReceiver(it) } }
        userPresent = null
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        // 閉じるときに、アニメーションを付けない(解除の動きと重ならないように)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

/** ロック画面の中身: 制限つきの再生画面に、解除のスワイプの帯と、時計を重ねる */
@Composable
private fun LockScreenContent(vm: MainViewModel, s: AppSettings, onUnlock: (done: (Boolean) -> Unit) -> Unit) {
    // 曲が無くなったら(再生が終わった・止められた)閉じる
    val activity = androidx.compose.ui.platform.LocalContext.current as? Activity
    if (vm.player.current == null) LaunchedEffect(Unit) { activity?.finish() }
    // 戻るボタンでは閉じない(閉じるのは、解除のスワイプだけ)
    BackHandler(true) {}

    // 向き: 0 = 上, 1 = 下, 2 = 左, 3 = 右。軸(縦か横)と、動く向きの符号(上・左 = -1、下・右 = +1)に直す
    val dir = s.lockSwipe.coerceIn(0, 3)
    val vertical = dir <= 1
    val sign = if (dir == 0 || dir == 2) -1f else 1f
    val conf = LocalConfiguration.current
    val density = LocalDensity.current
    val sizePx = with(density) { (if (vertical) conf.screenHeightDp else conf.screenWidthDp).dp.toPx() }
    // 解除に要る距離(画面の約 25%)
    val threshold = sizePx * 0.25f

    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var busy by remember { mutableStateOf(false) }

    // ロック画面で操作できる部品(再生/一時停止・前へ・次へ・再生バー)の場所。NowPlaying が知らせる
    val hotspots = remember { mutableMapOf<String, androidx.compose.ui.geometry.Rect>() }
    // 指の動き(解除のスワイプ)。最後の位置で、解除するか元に戻すかを決める
    fun release(velocity: Float) {
        scope.launch {
            val moved = abs(offset.value)
            val fling = (velocity * sign) > 1800f
            if (moved >= threshold || (fling && moved > threshold * 0.3f)) {
                busy = true
                // 画面を少し動かしてから、システムのロックの解除へ(認証の画面が、後ろの再生画面と重ならないように)
                offset.animateTo(sign * sizePx * 0.35f, tween(120))
                onUnlock { ok -> if (!ok) scope.launch { offset.animateTo(0f, tween(220)); busy = false } }
            } else {
                offset.animateTo(0f, tween(220))
            }
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(dir) {
            // 画面全体を受け止める層。操作できる部品の上で触れたときだけ、その部品に指を通す。
            // それ以外(ほかのボタン・ジャケットなど。見た目だけ)は、子に渡さず、解除のスワイプとして受け取る
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (busy || hotspots.values.any { it.contains(down.position) }) return@awaitEachGesture
                down.consume()
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var total = 0f
                while (true) {
                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    tracker.addPosition(c.uptimeMillis, c.position)
                    if (!c.pressed) { c.consume(); break }
                    val d = c.position - c.previousPosition
                    total += if (vertical) d.y else d.x
                    c.consume()
                    // 設定の向きにだけ動かす(反対向きには動かさない)
                    val v = if (total * sign > 0) total else 0f
                    scope.launch { offset.snapTo(v) }
                }
                val vel = tracker.calculateVelocity()
                release(if (vertical) vel.y else vel.x)
            }
        },
    ) {
        // 画面全体が、指に付いて動く(動く向きだけ。反対向きには動かさない)
        Box(
            Modifier.fillMaxSize().offset {
                val o = offset.value.roundToInt()
                if (vertical) IntOffset(0, o) else IntOffset(o, 0)
            },
        ) {
            // 最初の 1 枚は軽く(黒と時計だけ)描いて、すぐにシステムのロック画面を隠す。
            // 再生画面(ジャケット・ページ・曲の情報など。重い)は、その次の描画から組み立てる
            var ready by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                androidx.compose.runtime.withFrameNanos { }
                ready = true
            }
            if (ready) {
                CompositionLocalProvider(LocalLockMode provides true, LocalLockHotspots provides hotspots) {
                    NowPlayingScreen(vm, s) {}
                }
            }
            if (s.lockClock != 2) LockClock(s.lockClock, s.lockClockColor)
        }
    }
}

/** 時計と日付(ロック画面らしく)。場所は設定で選ぶ(上・下) */
@Composable
private fun LockClock(pos: Int, colorArgb: Int) {
    // 色(設定。0 なら白)
    val base = if (colorArgb == 0) Color.White else Color(colorArgb)
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    val time = remember(now) { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(now)) }
    val date = remember(now) { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(now)) }
    // 時計の場所(設定): 上か下
    val at = if (pos == 1) Alignment.BottomCenter else Alignment.TopCenter
    Box(Modifier.fillMaxSize().padding(vertical = 56.dp), contentAlignment = at) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(time, style = MaterialTheme.typography.headlineMedium, color = base.copy(alpha = 0.9f * base.alpha))
            Text(date, style = MaterialTheme.typography.labelMedium, color = base.copy(alpha = 0.7f * base.alpha))
        }
    }
}
