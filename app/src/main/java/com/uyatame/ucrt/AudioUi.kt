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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    Section(T("USB DAC直接出力", "USB DAC direct output")) {
        SwitchRow(
            T("USB DAC直接出力を使う", "Use USB DAC direct output"),
            T(
                "アプリ独自のドライバーでUSB DACを直接駆動します（Androidのオーディオ機能を経由しません）。ソースダイレクトをオンにすると、音源のデータを一切加工せずに送ります（ビットパーフェクト）。使用中はDACを独占するため、通知音などはDACから鳴りません",
                "Controls the USB DAC directly with the app's own driver, bypassing Android's audio system. With source direct on, audio data is sent completely unaltered (bit-perfect). The app takes exclusive control of the DAC, so notification sounds won't play through it",
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
                Text(T("DACの音量", "DAC volume"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(84.dp))
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
                    T("このDACはUSB経由の音量調整に対応していないため、ソフトウェアで音量を下げています。ビットパーフェクトにするには100に設定し、DAC本体のボタンで音量を調整してください（DSDはこの設定に関係なく、DAC本体の音量で再生されます）",
                        "This DAC doesn't support volume control over USB, so volume is reduced in software. For bit-perfect playback, set this to 100 and use the DAC's own buttons (DSD always plays at the DAC's own volume)")
                } else {
                    T("DAC本体の音量を直接調整します（音質は変わりません）。端末の音量キーでも調整できます",
                        "Adjusts the DAC's hardware volume directly, with no loss in quality. You can also use the phone's volume keys")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (mode == 2) {
            Text(
                T("詳しい動作ログは「設定 → ログ」で確認できます", "For details, see Settings → Log"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** DAR(PCM の曲を高い周波数の PCM や DSD にして DAC へ送る) */
@Composable
fun DarPanel(vm: MainViewModel, s: AppSettings) {
    Section(T("DAR（アップサンプリング・DSD変換）", "DAR (upsampling / DSD conversion)")) {
        val options = listOf(
            T("オフ", "Off") to
                T("音源をそのまま送ります（ビットパーフェクト）", "Sends the source unaltered (bit-perfect)"),
            T("PCMアップサンプリング", "PCM upsampling") to
                T("音源を整数倍の高い周波数（例: 44.1 kHz → 352.8 kHz）に変換し、24 / 32bitで送ります",
                    "Upsamples the source by an integer multiple (e.g. 44.1 kHz → 352.8 kHz) and sends it as 24- or 32-bit audio"),
            T("DSDに変換", "Convert to DSD") to
                T("音源をDSDに変換し、ネイティブDSDまたはDoPで送ります。DSD対応のDACで使用してください",
                    "Converts the source to DSD and sends it as native DSD or DoP. Requires a DSD-capable DAC"),
        )
        options.forEachIndexed { i, (title, desc) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.set(Keys.darMode, i) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(s.darMode == i, { vm.set(Keys.darMode, i) })
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (s.darMode == 1) {
            Text(T("アップサンプリングの上限（44.1 / 48 kHzの倍数）", "Maximum rate (multiple of 44.1 / 48 kHz)"), style = MaterialTheme.typography.labelLarge)
            val labels = listOf(
                T("DACの最大値", "DAC maximum"), "×2 (88.2 / 96k)", "×4 (176.4 / 192k)", "×8 (352.8 / 384k)", "×16 (705.6 / 768k)",
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, l ->
                    FilterChip(s.darPcm == i, { vm.set(Keys.darPcm, i) }, label = { Text(l) })
                }
            }
            SwitchRow(
                T("整数倍でのみ変換", "Integer multiples only"),
                T(
                    "オン: 44.1 kHz → 176.4 kHzのように整数倍でのみ変換します（最も高音質）。出力先が整数倍の周波数に対応していない場合は、やむを得ず出力先の周波数に合わせ、その旨を赤字で表示します。\nオフ: 出力先が対応する最も高い周波数に変換します（44.1 kHz → 192 kHzのような整数倍でない変換も含みます）。\nUSB DAC直接出力では常に整数倍で変換します",
                    "On: upsamples only by integer multiples (e.g. 44.1 → 176.4 kHz) for the cleanest result. If the output doesn't accept any integer multiple, the output's rate is used instead and a warning appears in red.\nOff: uses the highest rate the output accepts, including non-integer conversions (e.g. 44.1 → 192 kHz).\nUSB DAC direct output always uses integer multiples",
                ),
                s.darInteger,
            ) { vm.set(Keys.darInteger, it) }
            AudioEngine.darWarn.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (s.darMode == 2) {
            Text(T("DSDの周波数", "DSD rate"), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("DSD64", "DSD128", "DSD256").forEachIndexed { i, l ->
                    FilterChip(s.darDsd == i, { vm.set(Keys.darDsd, i) }, label = { Text(l) })
                }
            }
            Text(
                T(
                    "DACが対応していない周波数の場合は、より低い周波数で変換します。DSD256は処理が重く、端末によっては音が途切れることがあります（その場合はDSD128以下にしてください）。送信方式（ネイティブDSD / DoP）は下の「DSDの再生」の設定に従います",
                    "If the DAC doesn't support the selected rate, a lower DSD rate is used. DSD256 is demanding and may stutter on some phones (if so, choose DSD128 or lower). Whether native DSD or DoP is used depends on the DSD playback setting below",
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            T(
                "DARは、ソースダイレクトがオフのときにPCMの曲（ファイル・CD）に適用されます。PCMアップサンプリングはUSB DAC直接出力でも、イヤホンジャックなどの通常の出力でも使え、通常の出力では出力先が対応する周波数まで上げます。DSDへの変換はUSB DAC直接出力のときのみ使えます。DSDの曲はそのまま再生します。オンにするとビットパーフェクトではなくなり、電池の消費も増えます",
                "DAR applies to PCM tracks (files and CDs) when source direct is off. PCM upsampling works with both USB DAC direct output and the standard output (such as the headphone jack); with the standard output, it upsamples only as high as the output accepts. DSD conversion requires USB DAC direct output. DSD tracks play unchanged. With DAR on, playback is no longer bit-perfect and uses more battery",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (s.darMode != 0 && (s.sourceDirect || (s.darMode == 2 && s.outputMode != 2))) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** DSD(DSF / DFF)の再生方法 */
@Composable
fun DsdPanel(vm: MainViewModel, s: AppSettings) {
    Section(T("DSDの再生", "DSD playback")) {
        val options = listOf(
            T("自動（ネイティブDSD → DoP → PCM変換）", "Auto (native DSD → DoP → PCM)") to
                T("ネイティブDSDを優先し、使えない場合はDoP、それも使えない場合はPCMに変換します",
                    "Uses native DSD when possible, then DoP, and otherwise converts to PCM"),
            T("DoPを使う", "Use DoP") to
                T("ネイティブDSDを使わず、DoP（DSD over PCM）で送ります。DoP対応のDACで使用してください",
                    "Sends DSD over PCM (DoP) instead of native DSD. Requires a DoP-capable DAC"),
            T("常にPCMに変換", "Always convert to PCM") to
                T("DSD非対応のDACや端末のスピーカー向けです。イコライザーも使えます", "For DACs without DSD support or the phone's speaker. The equalizer also works"),
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
                "ネイティブDSDとDoPは、ビットパーフェクト再生がオンのときに使われます。オフのときはPCMに変換して再生します",
                "Native DSD and DoP are used only when bit-perfect playback is on. Otherwise, DSD is converted to PCM",
            ),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(
            T("ネイティブDSDのバイト順を入れ替える", "Swap native DSD byte order"),
            T("ネイティブDSDで「ザー」というノイズしか出ないDACの場合のみオンにしてください", "Turn on only if your DAC plays native DSD as static"),
            s.dsdSwap,
        ) { vm.set(Keys.dsdSwap, it) }
    }
}

/** プレイヤー画面から開く音質設定(トップと詳細ページをシートの中で切り替える) */
@Composable
fun AudioSettingsSheet(vm: MainViewModel, s: AppSettings, onDismiss: () -> Unit) {
    var page by remember { mutableIntStateOf(SoundPage.MAIN) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        androidx.activity.compose.BackHandler(enabled = page != SoundPage.MAIN) { page = SoundPage.MAIN }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (page == SoundPage.MAIN) {
                Text(SoundPage.title(page), style = MaterialTheme.typography.titleLarge)
                SoundMain(vm, s) { page = it }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.IconButton({ page = SoundPage.MAIN }) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, T("戻る", "Back"),
                        )
                    }
                    Text(SoundPage.title(page), style = MaterialTheme.typography.titleLarge)
                }
                SoundSubPage(vm, s, page)
            }
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
                T("出力先を選択", "Choose an output"), style = MaterialTheme.typography.titleMedium,
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
                T("Androidに任せる", "Let Android decide") + (auto?.let { " · ${it.name}" } ?: ""),
            )
            devices.forEach { d ->
                item(d.key, d.kind, d.name, listOf(d.detail, d.spec).filter { it.isNotEmpty() }.joinToString(" · "))
            }
            Text(
                T(
                    "USB DACを選ぶとビットパーフェクト再生になります（アプリがDACを直接駆動します）",
                    "Choosing the USB DAC enables bit-perfect playback (the app controls the DAC directly)",
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
