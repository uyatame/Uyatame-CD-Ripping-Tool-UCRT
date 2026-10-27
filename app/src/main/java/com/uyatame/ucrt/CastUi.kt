package com.uyatame.ucrt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.player.cast.CastManager

/** キャスト先(Chromecast)を選ぶシート。開いている間、同じネットワークの機器を探す */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    // Android 17 から(このアプリが Android 17 向けのとき)、同じネットワークの機器を探す・つなぐには「付近のデバイス」の
    // 許可(ACCESS_LOCAL_NETWORK)が要る
    var allowed by remember { mutableStateOf(CastManager.hasLocalNetwork(ctx)) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { allowed = it }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (!allowed) ask.launch(CastManager.LOCAL_NETWORK) }
    DisposableEffect(allowed) {
        if (allowed) CastManager.startDiscovery(ctx)
        onDispose { if (allowed) CastManager.stopDiscovery() }
    }
    val cur = CastManager.connected
    val busy = CastManager.connecting
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(S(R.string.cast_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            // この端末で再生する(キャストをやめる)
            CastRow(AppIcons.Smartphone, S(R.string.cast_this_device), null, selected = cur == null, busy = false) {
                CastManager.disconnect()
                onDismiss()
            }
            for (d in CastManager.devices.sortedBy { it.name }) {
                CastRow(
                    if (cur?.id == d.id) AppIcons.CastConnected else AppIcons.Cast, d.name, d.model.ifEmpty { null },
                    selected = cur?.id == d.id, busy = busy?.id == d.id,
                ) { CastManager.connect(d) }
            }
            if (!allowed) {
                Text(S(R.string.cast_needs_local_network), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                androidx.compose.material3.Button({ ask.launch(CastManager.LOCAL_NETWORK) }, Modifier.padding(horizontal = 16.dp)) {
                    Text(S(R.string.allow))
                }
            } else if (CastManager.devices.isEmpty()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(S(R.string.cast_searching), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (cur != null) {
                var v by remember { mutableFloatStateOf(CastManager.volume) }
                Text(S(R.string.cast_volume, cur.name), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                Slider(v, { v = it }, onValueChangeFinished = { CastManager.changeVolume(v) })
            }
            Text(
                S(R.string.cast_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun CastRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    sub: String?,
    selected: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}
