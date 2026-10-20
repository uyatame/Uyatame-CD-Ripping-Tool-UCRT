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
            title = { Text(S(R.string.cancel_writing)) },
            text = { Text(S(R.string.prepared_data_will_be_deleted)) },
            confirmButton = { TextButton({ confirmClose = false; vm.closeBurn() }) { Text(S(R.string.cancel_writing_2)) } },
            dismissButton = { TextButton({ confirmClose = false }) { Text(S(R.string.back)) } },
        )
    }
    val img = b.image
    val min = (img.seconds / 60).toInt()
    val sec = (img.seconds % 60).toInt()
    AlertDialog(
        onDismissRequest = { if (!writing) confirmClose = true },
        title = { Text(if (b.stage == 1) S(R.string.write_complete) else S(R.string.write_to_cd)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(b.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    S(R.string.tracks_3, img.tracks.size, min, "%02d".format(sec)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (writing) {
                    Text(
                        if (vm.busyKind == 4) S(R.string.erasing) else S(R.string.writing, (vm.ripOverall * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    androidx.compose.material3.LinearProgressIndicator({ vm.ripOverall }, Modifier.fillMaxWidth())
                    Text(
                        S(R.string.dont_disconnect_drive_or_let),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                } else if (b.stage == 0) {
                    Text(S(R.string.insert_blank_cd_r_or), style = MaterialTheme.typography.bodyMedium)
                    Text(S(R.string.write_speed), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(4, 8, 16, 0).forEach { x ->
                            FilterChip(speed == x, { speed = x }, label = { Text(if (x == 0) S(R.string.max) else "${x}x") })
                        }
                    }
                    Text(
                        S(R.string.slower_speeds_are_more_reliable),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth().clickable { test = !test }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(test, { test = it })
                        Column {
                            Text(S(R.string.test_write), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                S(R.string.simulates_entire_write_without_burning),
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
                writing -> TextButton({ vm.cancelRip() }) { Text(S(R.string.stop)) }
                b.stage == 1 -> TextButton({ vm.burnAgain() }) { Text(S(R.string.write_another)) }
                else -> Row {
                    if (b.canErase) TextButton({ vm.eraseDisc() }) { Text(S(R.string.erase)) }
                    TextButton({ vm.writeDisc(speed, test) }) { Text(S(R.string.write)) }
                }
            }
        },
        dismissButton = {
            if (!writing) TextButton({ if (b.stage == 1) vm.closeBurn() else confirmClose = true }) { Text(S(R.string.close)) }
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
        title = { Text(S(R.string.write_to_cd)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(req.first, style = MaterialTheme.typography.titleSmall)
                Text(
                    S(R.string.tracks_about_min, req.second.size, (totalSec + 59) / 60),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (totalSec > 80 * 60) {
                    Text(S(R.string.too_long_for_one_cd), color = MaterialTheme.colorScheme.error)
                }
                Text(S(R.string.gap_between_tracks), style = MaterialTheme.typography.labelLarge)
                listOf(2 to S(R.string.n2_seconds_standard_for_cds), 0 to S(R.string.none_for_continuous_tracks_e)).forEach { (v, label) ->
                    Row(Modifier.fillMaxWidth().clickable { gap = v }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(gap == v, { gap = v })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    S(R.string.tracks_are_converted_to_cd),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton({
                vm.burnRequest = null
                vm.startBurnFromTracks(req.first, req.second, gap)
            }, enabled = totalSec <= 80 * 60 && !vm.busy) { Text(S(R.string.prepare)) }
        },
        dismissButton = { TextButton({ vm.burnRequest = null }) { Text(S(R.string.cancel)) } },
    )
}
