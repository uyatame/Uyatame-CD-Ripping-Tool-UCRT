@file:OptIn(ExperimentalMaterial3Api::class)

package com.uyatame.ucrt

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.Keys
import com.uyatame.ucrt.data.outputMode
import com.uyatame.ucrt.player.usb.UsbDac
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.ui.draw.clip
import com.uyatame.ucrt.player.AudioEngine
import com.uyatame.ucrt.player.EqBands
import kotlin.math.abs
import kotlin.math.roundToInt

private fun db(v: Float): String {
    val r = (v * 2).roundToInt() / 2f
    return (if (r > 0) "+" else "") + (if (r % 1f == 0f) r.toInt().toString() else r.toString()) + " dB"
}

/** 出力方式(通常 / Android のビットパーフェクト / USB DAC 直接出力)の設定 */
@Composable
fun BitPerfectPanel(vm: MainViewModel, s: AppSettings) {
    val mode = s.outputMode
    Section(T("ビットパーフェクト再生", "Bit-perfect playback")) {
        SwitchRow(
            T("ビットパーフェクトで再生する", "Bit-perfect playback"),
            T(
                "アプリの独自ドライバーで USB DAC を直接動かし、音源のデータを一切加工せずに送ります(Android の音声機能は通しません)。使用中は DAC を独占するため、通知音などは DAC から鳴りません。オフにすると Android の標準の出力になり、イコライザーが使えます",
                "The app's own driver drives the USB DAC directly and sends the audio data unaltered, bypassing Android audio. The DAC is used exclusively, so notification sounds won't play through it. Turn off to use Android's standard output (the equalizer works there)",
            ),
            mode == 2,
        ) { vm.setOutputMode(if (it) 2 else 0) }
        val st = AudioEngine.outputStatus
        if (mode != 0 && st.isNotEmpty()) {
            Text(
                st, style = MaterialTheme.typography.bodySmall,
                color = if (AudioEngine.bitPerfectActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (mode == 2) {
            var vol by remember(s.usbVolume, UsbDac.active) {
                mutableFloatStateOf((if (s.usbVolume < 0) UsbDac.volume() else s.usbVolume).toFloat())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(T("DAC の音量", "DAC volume"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(84.dp))
                Slider(
                    value = vol,
                    onValueChange = {
                        vol = it
                        vm.setUsbVolume(it.roundToInt(), false)
                    },
                    onValueChangeFinished = { vm.setUsbVolume(vol.roundToInt(), true) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                )
                Text("${vol.roundToInt()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
            }
            Text(
                if (UsbDac.active && !UsbDac.hwVolume) {
                    T("この DAC は USB からの音量調整に対応していないため、ソフトウェアで音量を下げています。100 にするとビットパーフェクトになるので、DAC 本体のボタンで音量を調整してください(DSD はこの音量に関係なく、DAC 本体の音量で鳴ります)",
                        "This DAC has no USB volume control, so volume is applied in software. Set 100 for bit-perfect and use the DAC's own buttons (DSD always plays at the DAC's own volume)")
                } else {
                    T("DAC 本体の音量を直接調整します(音質は変わりません)。本体の音量キーでも変えられます",
                        "Adjusts the DAC's own volume (no quality loss). The phone's volume keys also work")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (mode == 2) {
            Text(
                T("詳しい動作の記録は「設定 → ログ」で確認できます", "Details are recorded in Settings → Log"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    DsdPanel(vm, s)
}

/** DSD(DSF / DFF)の再生方法 */
@Composable
fun DsdPanel(vm: MainViewModel, s: AppSettings) {
    Section(T("DSD の再生", "DSD playback")) {
        val options = listOf(
            T("自動(ネイティブ DSD → DoP → PCM 変換)", "Auto (native DSD → DoP → PCM)") to
                T("ネイティブ DSD を優先します。使えないときは DoP、それも無理なら PCM に変換します",
                    "Native DSD is preferred, then DoP, then conversion to PCM"),
            T("DoP を使う", "Use DoP") to
                T("ネイティブ DSD を使わず、DoP(DSD over PCM)で送ります。DoP に対応した DAC で使ってください",
                    "Sends DSD over PCM instead of native DSD. Use only with a DoP-capable DAC"),
            T("常に PCM に変換", "Always convert to PCM") to
                T("DSD 非対応の DAC やスマホのスピーカー向け。イコライザーも使えます", "For DACs without DSD or the phone speaker. The equalizer works too"),
        )
        options.forEachIndexed { i, (title, desc) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.set(Keys.dsdMode, i) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(s.dsdMode == i, { vm.set(Keys.dsdMode, i) })
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            T(
                "ネイティブ DSD と DoP は、ビットパーフェクト再生がオンのときに使われます。オフのときは PCM に変換して再生します",
                "Native DSD and DoP are used when bit-perfect playback is on. Otherwise DSD is converted to PCM",
            ),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(
            T("ネイティブ DSD のバイト順を入れ替える", "Swap native DSD byte order"),
            T("ネイティブ DSD で「ザー」という雑音しか出ない DAC のときだけオンにしてください", "Turn on only if native DSD plays as noise on your DAC"),
            s.dsdSwap,
        ) { vm.set(Keys.dsdSwap, it) }
    }
}

/** イコライザーの設定 */
@Composable
fun EqualizerPanel(vm: MainViewModel, s: AppSettings) {
    val gains = remember(s.eqGains) { mutableStateListOf<Float>().apply { addAll(EqBands.decode(s.eqGains).toList()) } }
    var preamp by remember(s.eqPreamp) { mutableFloatStateOf(s.eqPreamp) }
    var preset by remember(s.eqPreset) { mutableIntStateOf(s.eqPreset) }
    fun save() = vm.saveEq(gains.toFloatArray(), preamp, preset)
    fun matchPreset(): Int = EqBands.PRESETS.indexOfFirst { p -> p.gains.indices.all { abs(p.gains[it] - gains[it]) < 0.01f } }

    Section(T("イコライザー", "Equalizer")) {
        SwitchRow(T("イコライザーを使う", "Use equalizer"), null, s.eqEnabled) { vm.set(Keys.eqEnabled, it) }
        if (s.outputMode != 0) {
            Text(
                T("ビットパーフェクト再生中は、音を加工しないためイコライザーは効きません", "The equalizer has no effect during bit-perfect playback"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EqBands.PRESETS.forEachIndexed { i, p ->
                FilterChip(
                    selected = preset == i,
                    onClick = {
                        preset = i
                        p.gains.forEachIndexed { k, g -> gains[k] = g }
                        vm.previewEq(gains.toFloatArray(), preamp)
                        save()
                    },
                    label = { Text(T(p.ja, p.en)) },
                    enabled = s.eqEnabled,
                )
            }
            if (preset < 0) FilterChip(true, {}, label = { Text(T("カスタム", "Custom")) }, enabled = s.eqEnabled)
        }
        EqBands.LABELS.forEachIndexed { i, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label + "Hz", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(52.dp),
                )
                Slider(
                    value = gains[i],
                    onValueChange = {
                        gains[i] = (it * 2).roundToInt() / 2f
                        preset = matchPreset()
                        vm.previewEq(gains.toFloatArray(), preamp)
                    },
                    onValueChangeFinished = { save() },
                    valueRange = -EqBands.MAX_DB..EqBands.MAX_DB,
                    enabled = s.eqEnabled,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    db(gains[i]), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(64.dp), textAlign = TextAlign.End,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(T("全体", "Preamp"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(52.dp))
            Slider(
                value = preamp,
                onValueChange = {
                    preamp = (it * 2).roundToInt() / 2f
                    vm.previewEq(gains.toFloatArray(), preamp)
                },
                onValueChangeFinished = { save() },
                valueRange = -EqBands.MAX_DB..EqBands.MAX_DB,
                enabled = s.eqEnabled,
                modifier = Modifier.weight(1f),
            )
            Text(db(preamp), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
        }
        SwitchRow(
            T("音割れを自動で防ぐ", "Prevent clipping automatically"),
            T("持ち上げた分だけ全体の音量を下げます", "Lowers the overall level by the amount you boost"),
            s.eqAutoPreamp,
        ) { vm.set(Keys.eqAutoPreamp, it) }
        TextButton({
            preset = 0
            for (k in gains.indices) gains[k] = 0f
            preamp = 0f
            vm.previewEq(gains.toFloatArray(), preamp)
            save()
        }, enabled = s.eqEnabled) { Text(T("リセット", "Reset")) }
    }
}

/** プレイヤー画面から開く音響設定 */
@Composable
fun AudioSettingsSheet(vm: MainViewModel, s: AppSettings, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(T("音響設定", "Sound"), style = MaterialTheme.typography.titleLarge)
            BitPerfectPanel(vm, s)
            EqualizerPanel(vm, s)
        }
    }
}

/** 出力先の種類ごとのアイコン */
fun outputIcon(k: com.uyatame.ucrt.player.OutputKind): androidx.compose.ui.graphics.vector.ImageVector = when (k) {
    com.uyatame.ucrt.player.OutputKind.Speaker -> AppIcons.Smartphone
    com.uyatame.ucrt.player.OutputKind.Wired -> AppIcons.Headphones
    com.uyatame.ucrt.player.OutputKind.Usb, com.uyatame.ucrt.player.OutputKind.UsbDirect -> AppIcons.Usb
    com.uyatame.ucrt.player.OutputKind.Bluetooth -> AppIcons.Bluetooth
    else -> AppIcons.Speaker
}

/** 出力先を選ぶシート(プレイヤー画面の出力先の表示から開く) */
@Composable
fun OutputDeviceSheet(vm: MainViewModel, s: AppSettings, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var devices by remember { mutableStateOf(com.uyatame.ucrt.player.OutputDevices.list(ctx)) }
    var auto by remember { mutableStateOf(com.uyatame.ucrt.player.OutputDevices.defaultDevice(ctx)) }
    // イヤホンの抜き差しなどで一覧を更新する
    androidx.compose.runtime.DisposableEffect(Unit) {
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        val cb = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) {
                devices = com.uyatame.ucrt.player.OutputDevices.list(ctx)
                auto = com.uyatame.ucrt.player.OutputDevices.defaultDevice(ctx)
            }
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) {
                devices = com.uyatame.ucrt.player.OutputDevices.list(ctx)
                auto = com.uyatame.ucrt.player.OutputDevices.defaultDevice(ctx)
            }
        }
        am.registerAudioDeviceCallback(cb, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { am.unregisterAudioDeviceCallback(cb) }
    }
    val selectedKey = if (s.outputMode == 2) com.uyatame.ucrt.player.OutputDevices.KEY_USB_DIRECT else s.outputDevice
    val current = AudioEngine.output
        ?: devices.firstOrNull { it.key == selectedKey }
        ?: auto
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (current != null) {
                Text(T("使用中の出力先", "Current output"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(outputIcon(current.kind), null, Modifier.padding(end = 16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(current.name, style = MaterialTheme.typography.titleMedium)
                            if (current.detail.isNotEmpty()) Text(current.detail, style = MaterialTheme.typography.bodySmall)
                            if (current.spec.isNotEmpty()) Text(current.spec, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text(
                T("出力先を選んでください", "Choose an output"), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            @Composable
            fun item(key: String, kind: com.uyatame.ucrt.player.OutputKind, title: String, sub: String) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { vm.setOutputDevice(key); onDismiss() }.padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(outputIcon(kind), null, Modifier.padding(end = 16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        if (sub.isNotEmpty()) {
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (key == selectedKey) {
                        Text("✓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item(
                com.uyatame.ucrt.player.OutputDevices.KEY_AUTO, com.uyatame.ucrt.player.OutputKind.Auto,
                T("自動", "Automatic"),
                T("Android に任せる", "Let Android decide") + (auto?.let { " · ${it.name}" } ?: ""),
            )
            devices.forEach { d ->
                item(d.key, d.kind, d.name, listOf(d.detail, d.spec).filter { it.isNotEmpty() }.joinToString(" · "))
            }
            Text(
                T(
                    "USB DAC を選ぶとビットパーフェクト再生になります(アプリが DAC を直接動かします)",
                    "Choosing the USB DAC turns on bit-perfect playback (the app drives the DAC directly)",
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
