package com.uyatame.ucrt

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.widthIn

/** Android の今の音の出力先(有線ヘッドホン・Bluetooth・スピーカーなど。Spotify アプリの音の行き先) */
class SystemOutput(val label: String, val icon: ImageVector)

private val MEDIA = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()

/** 音楽の出力先を調べる(Android 13 以降は実際の行き先、それより前はつながっている機器から推測する) */
private fun currentOutput(ctx: Context, am: AudioManager): SystemOutput {
    // Android 5.x は出力先の一覧を調べられないので、イヤホン・Bluetooth がつながっているかで推測する
    if (Build.VERSION.SDK_INT < 23) {
        return when (com.uyatame.ucrt.player.OutputDevices.legacyKind(ctx)) {
            com.uyatame.ucrt.player.OutputKind.Wired -> SystemOutput(S(R.string.output_wired), AppIcons.Headphones)
            com.uyatame.ucrt.player.OutputKind.Bluetooth -> SystemOutput(S(R.string.output_bluetooth), AppIcons.Headphones)
            else -> SystemOutput(S(R.string.output_speaker), AppIcons.Smartphone)
        }
    }
    val dev: AudioDeviceInfo? = if (Build.VERSION.SDK_INT >= 33) {
        runCatching { am.getAudioDevicesForAttributes(MEDIA).firstOrNull() }.getOrNull()
    } else {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // つながっていれば、有線 → USB → Bluetooth → 内蔵スピーカーの順に使われることが多い
        val order = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        order.firstNotNullOfOrNull { t -> outs.firstOrNull { it.type == t } }
    }
    return describe(dev)
}

private fun describe(d: AudioDeviceInfo?): SystemOutput {
    val name = d?.productName?.toString()?.trim().orEmpty()
    return when (d?.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET ->
            SystemOutput(S(R.string.output_wired), AppIcons.Headphones)
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST ->
            SystemOutput(name.ifEmpty { S(R.string.output_bluetooth) }, AppIcons.Headphones)
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
            SystemOutput(if (name.isEmpty()) S(R.string.output_usb) else S(R.string.output_usb_named, name), AppIcons.Usb)
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC ->
            SystemOutput(name.ifEmpty { "HDMI" }, AppIcons.Speaker)
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null -> SystemOutput(S(R.string.output_speaker), AppIcons.Smartphone)
        else -> SystemOutput(name.ifEmpty { S(R.string.output_other) }, AppIcons.Speaker)
    }
}

/** 出力先(機器の抜き差しで変わる) */
@Composable
fun rememberSystemOutput(): SystemOutput {
    val ctx = LocalContext.current
    val am = remember { androidx.core.content.ContextCompat.getSystemService(ctx, AudioManager::class.java)!! }
    var out by remember { mutableStateOf(currentOutput(ctx, am)) }
    DisposableEffect(am) {
        val main = Handler(Looper.getMainLooper())
        // 抜き差しの直後は、まだ行き先が切り替わっていないことがあるので、少し待ってから調べ直す
        val recheck = Runnable { out = currentOutput(ctx, am) }
        val stop = com.uyatame.ucrt.player.OutputDevices.watch(ctx, main) { main.removeCallbacks(recheck); main.postDelayed(recheck, 400) }
        onDispose {
            stop()
            main.removeCallbacks(recheck)
        }
    }
    return out
}

/** Android の出力の切り替え(Android 14 以降はメディアの出力先の選択、それより前は音量のパネル)を開く */
fun openOutputSwitcher(ctx: Context) {
    if (Build.VERSION.SDK_INT >= 34 && runCatching { MediaRouter2.getInstance(ctx).showSystemOutputSwitcher() }.getOrDefault(false)) return
    runCatching { ctx.startActivity(Intent(Settings.Panel.ACTION_VOLUME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** 出力先の表示。タップで出力の切り替えを開く */
@Composable
fun OutputChip(content: Color, sub: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val out = rememberSystemOutput()
    AssistChip(
        onClick = { openOutputSwitcher(ctx) },
        label = { Text(out.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(out.icon, null, Modifier.size(18.dp), tint = sub) },
        colors = AssistChipDefaults.assistChipColors(labelColor = content),
        border = BorderStroke(1.dp, sub.copy(alpha = 0.5f)),
        modifier = modifier.widthIn(max = 240.dp),
    )
}
