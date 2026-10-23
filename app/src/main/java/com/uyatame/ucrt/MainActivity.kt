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
        setContent { App(vm) }
    }

    /** 隠したナビゲーションバーは、ほかのアプリやダイアログから戻ると出てしまうことがあるので、かけ直す */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && vm.settings.value.hideNavBar) applyNavBar(this, window.decorView, true)
    }

    override fun onResume() {
        super.onResume()
        vm.scan()
        // 設定画面で「通知へのアクセス」を変えて戻ってきたときに反映する
        com.uyatame.ucrt.player.ExternalMedia.setEnabled(this, vm.settings.value.externalMedia)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.scan()
    }
}
