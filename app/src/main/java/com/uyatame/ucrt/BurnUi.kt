package com.uyatame.ucrt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * CD の書き込みの画面。書き込む内容(コピーした CD・選んだ曲)ができてから出す。
 * 空の CD-R を入れて「書き込む」を押すと書き込む。終わったら同じ内容でもう 1 枚書ける
 */
@Composable
fun BurnDialog(vm: MainViewModel) {
    val b = vm.burn ?: return
    val writing = vm.busy && (vm.busyKind == 3 || vm.busyKind == 4)
    var speed by remember { mutableIntStateOf(8) }
    var test by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text(T("書き込みを中止しますか？", "Cancel writing?")) },
            text = { Text(T("準備したデータは削除されます（もう一度書き込むには、読み取りと準備からやり直す必要があります）", "The prepared data will be deleted (to write again, you'll need to read and prepare it from the start)")) },
            confirmButton = { TextButton({ confirmClose = false; vm.closeBurn() }) { Text(T("中止する", "Cancel writing")) } },
            dismissButton = { TextButton({ confirmClose = false }) { Text(T("戻る", "Back")) } },
        )
    }
    val img = b.image
    val min = (img.seconds / 60).toInt()
    val sec = (img.seconds % 60).toInt()
    AlertDialog(
        onDismissRequest = { if (!writing) confirmClose = true },
        title = { Text(if (b.stage == 1) T("書き込み完了", "Write complete") else T("CDに書き込む", "Write to CD")) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(b.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    T("${img.tracks.size}曲・%d分%02d秒", "${img.tracks.size} tracks · %d:%02d").format(min, sec),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (writing) {
                    Text(
                        if (vm.busyKind == 4) T("消去中…", "Erasing…") else T("書き込み中… ${(vm.ripOverall * 100).toInt()}%", "Writing… ${(vm.ripOverall * 100).toInt()}%"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    androidx.compose.material3.LinearProgressIndicator({ vm.ripOverall }, Modifier.fillMaxWidth())
                    Text(
                        T("書き込み中はドライブを外したり、端末をスリープさせたりしないでください", "Don't disconnect the drive or let the device sleep while writing"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                } else if (b.stage == 0) {
                    Text(T("空のCD-R（またはCD-RW）を入れて「書き込む」をタップしてください", "Insert a blank CD-R (or CD-RW) and tap Write"), style = MaterialTheme.typography.bodyMedium)
                    Text(T("書き込み速度", "Write speed"), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(4, 8, 16, 0).forEach { x ->
                            FilterChip(speed == x, { speed = x }, label = { Text(if (x == 0) T("最大", "Max") else "${x}x") })
                        }
                    }
                    Text(
                        T("速度が遅いほど失敗しにくく、古いCDプレーヤーでも読み取りやすくなります", "Slower speeds are more reliable and play better on older CD players"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth().clickable { test = !test }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(test, { test = it })
                        Column {
                            Text(T("テスト書き込み", "Test write"), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                T("実際には書き込まず、最後まで書き込めるかどうかだけを確認します（ディスクはそのまま使えます）", "Simulates the entire write without burning anything (the disc stays blank)"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (b.message.isNotEmpty()) {
                    Text(
                        b.message, style = MaterialTheme.typography.bodyMedium,
                        color = if (b.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            when {
                writing -> TextButton({ vm.cancelRip() }) { Text(T("中止", "Stop")) }
                b.stage == 1 -> TextButton({ vm.burnAgain() }) { Text(T("もう1枚書き込む", "Write another")) }
                else -> Row {
                    if (b.canErase) TextButton({ vm.eraseDisc() }) { Text(T("消去", "Erase")) }
                    TextButton({ vm.writeDisc(speed, test) }) { Text(T("書き込む", "Write")) }
                }
            }
        },
        dismissButton = {
            if (!writing) TextButton({ if (b.stage == 1) vm.closeBurn() else confirmClose = true }) { Text(T("閉じる", "Close")) }
        },
    )
}

/** ライブラリの曲から CD を作るときの確認(曲間の長さを選ぶ) */
@Composable
fun BurnTracksDialog(vm: MainViewModel) {
    val req = vm.burnRequest ?: return
    var gap by remember { mutableIntStateOf(2) }
    val totalSec = req.second.sumOf { (if (it.endMs > 0) it.endMs - it.startMs else it.durationMs) } / 1000
    AlertDialog(
        onDismissRequest = { vm.burnRequest = null },
        title = { Text(T("CDに書き込む", "Write to CD")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(req.first, style = MaterialTheme.typography.titleSmall)
                Text(
                    T("${req.second.size}曲・約%d分", "${req.second.size} tracks · about %d min").format((totalSec + 59) / 60),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (totalSec > 80 * 60) {
                    Text(T("80分を超えるため、CD1枚に収まりません", "Too long for one CD (over 80 minutes)"), color = MaterialTheme.colorScheme.error)
                }
                Text(T("曲間", "Gap between tracks"), style = MaterialTheme.typography.labelLarge)
                listOf(2 to T("2秒（一般的なCDと同じ）", "2 seconds (standard for CDs)"), 0 to T("なし（ライブ盤など、曲がつながっている場合）", "None (for continuous tracks, e.g. live albums)")).forEach { (v, label) ->
                    Row(Modifier.fillMaxWidth().clickable { gap = v }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(gap == v, { gap = v })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    T(
                        "曲はCDの形式（44.1 kHz / 16bit）に変換してから書き込みます（ハイレゾの曲はサンプリング周波数も変換されます）。DSDの曲は書き込めません",
                        "Tracks are converted to CD format (44.1 kHz / 16-bit) before writing, and hi-res tracks are resampled. DSD tracks can't be written",
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton({
                vm.burnRequest = null
                vm.startBurnFromTracks(req.first, req.second, gap)
            }, enabled = totalSec <= 80 * 60 && !vm.busy) { Text(T("準備する", "Prepare")) }
        },
        dismissButton = { TextButton({ vm.burnRequest = null }) { Text(T("キャンセル", "Cancel")) } },
    )
}
