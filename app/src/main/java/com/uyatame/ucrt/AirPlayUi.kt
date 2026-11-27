package com.uyatame.ucrt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.airplay.AirPlayBridge
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.Keys

/**
 * AirPlay 受信の詳細画面 (ストリーミングのタブから開く)。
 * 状態 (接続待ち・接続中・再生中) と接続元・再生中の曲を見せ、受信機の名前を変えられる。
 */
@Composable
fun AirPlayDetailScreen(vm: MainViewModel, s: AppSettings, onBack: () -> Unit) {
    BackHandler { onBack() }
    WideCenter {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp + barInset()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PageHeader("AirPlay", onBack)
            Section(S(R.string.airplay_status)) {
                val statusText = when (AirPlayBridge.status) {
                    AirPlayBridge.Status.OFF -> S(R.string.airplay_status_off)
                    AirPlayBridge.Status.WAITING -> S(R.string.airplay_status_waiting)
                    AirPlayBridge.Status.CONNECTED -> S(R.string.airplay_status_connected)
                    AirPlayBridge.Status.PLAYING -> S(R.string.airplay_status_playing)
                }
                Text(statusText, style = MaterialTheme.typography.titleMedium)
                if (AirPlayBridge.clientLabel.isNotEmpty()) {
                    Text(
                        S(R.string.airplay_client) + ": " + AirPlayBridge.clientLabel,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (AirPlayBridge.nowTitle.isNotEmpty()) {
                    val line = if (AirPlayBridge.nowArtist.isNotEmpty()) {
                        AirPlayBridge.nowTitle + " — " + AirPlayBridge.nowArtist
                    } else {
                        AirPlayBridge.nowTitle
                    }
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Section(S(R.string.airplay_receiver_name)) {
                var name by remember(s.airplayName) { mutableStateOf(s.airplayName) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    S(R.string.airplay_name_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.set(Keys.airplayName, name.trim()) }) {
                        Text(S(R.string.airplay_apply))
                    }
                }
            }
            Text(
                S(R.string.streaming_airplay_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
