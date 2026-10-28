package com.uyatame.ucrt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // カメラの切り欠きの横・タスクバーの場所まで中身を描く(バーを隠したときに、端に余白が残らないように)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        } else if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        // 画面のいちばん上で戻るボタンを押したとき: 再生中(や CD の取り込み中)は、終了せずに裏へ回す。
        // (Android 11 以前は戻るボタンで画面が終了し、再生も一緒に止まってしまう。ホームボタンと同じ動きにする。
        // 画面の中の戻る操作は、あとから登録される画面側の処理が先に受け取る)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (vm.player.isPlaying || vm.busy) {
                    moveTaskToBack(true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        setContent {
            // 解像度の低い端末(短い辺が 360dp 未満)では、画面全体を同じ割合で縮めて、並びが崩れないようにする(0.75 倍まで)。
            // 文字の大きさの設定は 1.3 倍までにする(大きすぎると、ボタンや曲名が収まらなくなるため)
            val d = androidx.compose.ui.platform.LocalDensity.current
            val conf = androidx.compose.ui.platform.LocalConfiguration.current
            val shortDp = minOf(conf.screenWidthDp, conf.screenHeightDp).coerceAtLeast(1)
            val scale = (shortDp / 360f).coerceIn(0.75f, 1f)
            val fixed = androidx.compose.ui.unit.Density(d.density * scale, d.fontScale.coerceIn(0.85f, 1.3f))
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides fixed) { App(vm) }
        }
    }

    /** 隠したナビゲーションバーは、ほかのアプリやダイアログから戻ると出てしまうことがあるので、かけ直す */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val st = vm.settings.value
        if (hasFocus && (st.hideNavBar || st.hideStatusBar)) applyNavBar(this, window.decorView, st.hideNavBar, st.hideStatusBar)
    }

    override fun onResume() {
        super.onResume()
        vm.scan()
        // カメラ・決済アプリなどに音を取られて止まっていたら、戻ってきたときに続きを再生する
        vm.player.resumeAfterInterruption()
        // 設定画面で「通知へのアクセス」を変えて戻ってきたときに反映する
        com.uyatame.ucrt.player.ExternalMedia.setEnabled(this, vm.settings.value.externalMedia)
    }

    override fun onStop() {
        super.onStop()
        // 選んだホーム画面のアイコンは、画面が裏に回ってから切り替える(表に出ている間だと閉じられることがある)
        if (!isChangingConfigurations) LauncherIcons.applyPending(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.scan()
    }
}
