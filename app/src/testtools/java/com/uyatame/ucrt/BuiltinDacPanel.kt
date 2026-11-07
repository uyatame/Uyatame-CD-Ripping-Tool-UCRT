package com.uyatame.ucrt

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.Keys
import com.uyatame.ucrt.player.AudioEngine
import com.uyatame.ucrt.player.BuiltinDacProbe
import com.uyatame.ucrt.player.VendorAudioProbe
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// このファイルは、テスト版(-PtestVersion を付けたビルド)にだけ入る。リリース版には、何もしない同名の部品(notesttools)が入る

/**
 * 内蔵 DAC(DAP のイヤホンジャックなど)を調べる。Android の標準の仕組みで、どんな形式・周波数を直接渡せると申告しているかを一覧にして、
 * DSD(ネイティブ)・DoP・PCM のどれで鳴らせそうかを出す。結果はログにも残る(設定のログで見られる)
 */
@Composable
fun BuiltinDacPanel(vm: MainViewModel, s: AppSettings) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    Section(S(R.string.builtin_dac)) {
        Text(S(R.string.builtin_dac_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.OutlinedButton({
            val r = BuiltinDacProbe.report(ctx)
            AudioEngine.log("builtin dac probe:\n$r")
            text = r
        }) { Text(S(R.string.builtin_dac_probe)) }
        // DoP で DSD を送ってみる(実験的)。診断で「DoPが通りそう」と出た端末向け。雑音が出たらオフにする
        SwitchRow(S(R.string.builtin_dop), S(R.string.builtin_dop_desc), s.builtinDop) { vm.set(Keys.builtinDop, it) }
        // メーカー独自の仕組み(Sony の WmPlayer など)を使えるかを調べる(実験的。読むだけ。数秒かかる)
        var vendorText by remember { mutableStateOf<String?>(null) }
        var vendorBusy by remember { mutableStateOf(false) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        androidx.compose.material3.OutlinedButton({
            vendorBusy = true
            scope.launch {
                val r = withContext(kotlinx.coroutines.Dispatchers.IO) { VendorAudioProbe.run(ctx) }
                AudioEngine.log("vendor audio probe:\n$r")
                vendorText = r
                vendorBusy = false
            }
        }, enabled = !vendorBusy) { Text(S(if (vendorBusy) R.string.vendor_probe_running else R.string.vendor_probe)) }
        Text(S(R.string.vendor_probe_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        vendorText?.let {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
            CopyResultButton("vendor audio probe", it)
        }
        text?.let {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
            CopyResultButton("builtin dac probe", it)
        }
    }
}

/** 調べた結果の文章を、そのままクリップボードにコピーするボタン(長い文章を、指で選ばずに送れるように) */
@Composable
private fun CopyResultButton(label: String, text: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.TextButton({
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        // Android 13 からは、システムが「コピーしました」を出すので、重ねない
        if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(ctx, S(R.string.copied), android.widget.Toast.LENGTH_SHORT).show()
    }) { Text(S(R.string.copy_result)) }
}
