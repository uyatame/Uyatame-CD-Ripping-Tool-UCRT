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
    Section(S(R.string.usb_dac_direct_output)) {
        SwitchRow(
            S(R.string.use_usb_dac_direct_output),
            S(R.string.controls_usb_dac_directly_with),
            mode == 2,
        ) { vm.setOutputMode(if (it) 2 else 0) }
        if (mode == 2) {
            // 使用の許可をもう一度求める(許可の画面が出なかった・断ってしまったとき)
            androidx.compose.material3.OutlinedButton({ vm.requestUsbPermission() }) { Text(S(R.string.usb_request_permission)) }
            Text(S(R.string.usb_request_permission_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 抜き差ししたときに近い動作で、DAC の接続を解除する(Android に返して、USB でリセットする)
        if (vm.usbDacPresent) {
            androidx.compose.material3.OutlinedButton({ vm.disconnectUsbDac() }) { Text(S(R.string.disconnect_usb_dac)) }
            Text(S(R.string.disconnect_usb_dac_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
                Text(S(R.string.dac_volume), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(84.dp))
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
                    S(R.string.this_dac_doesnt_support_volume)
                } else {
                    S(R.string.adjusts_dacs_hardware_volume_directly)
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (mode == 2) {
            SwitchRow(S(R.string.usb_stabilize), S(R.string.usb_stabilize_desc), s.usbStabilize) {
                vm.set(com.uyatame.ucrt.data.Keys.usbStabilize, it)
            }
            Text(
                S(R.string.for_details_see_settings_log),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * DAC を常に起こしておく(試験的)。ほかのアプリの再生にも効く。
 * 対象の機器に無音を流し続けて、省電力で眠らないようにする
 */
@Composable
fun DacKeepAliveSection(vm: MainViewModel, s: AppSettings) {
    val keep = com.uyatame.ucrt.player.DacKeepAlive
    // 出力先を選んで無音を流すので、出力先を選べない Android 5.x では使えない
    if (android.os.Build.VERSION.SDK_INT < 23) {
        Section(S(R.string.dac_keepalive)) {
            Text(S(R.string.needs_android_6), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Section(S(R.string.dac_keepalive)) {
        SwitchRow(S(R.string.dac_keepalive_switch), S(R.string.dac_keepalive_desc), s.dacKeepAlive) {
            vm.set(com.uyatame.ucrt.data.Keys.dacKeepAlive, it)
        }
        if (s.dacKeepAlive) {
            Segmented(
                S(R.string.dac_keepalive_target),
                listOf(S(R.string.auto), "USB DAC", S(R.string.headphone_jack)),
                s.dacKeepAliveTarget,
            ) { vm.set(com.uyatame.ucrt.data.Keys.dacKeepAliveTarget, it) }
            Text(
                when {
                    keep.activeDevice.isNotEmpty() -> S(R.string.dac_keepalive_active, keep.activeDevice)
                    UsbDac.active -> S(R.string.dac_keepalive_direct)
                    else -> S(R.string.dac_keepalive_waiting)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (keep.activeDevice.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** DAR(PCM の曲を高い周波数の PCM や DSD にして DAC へ送る) */
@Composable
fun DarPanel(vm: MainViewModel, s: AppSettings) {
    Section(S(R.string.dar_upsampling_dsd_conversion)) {
        val options = listOf(
            S(R.string.off) to
                S(R.string.sends_source_unaltered_bit_perfect),
            S(R.string.pcm_upsampling) to
                S(R.string.upsamples_source_by_integer_multiple),
            S(R.string.convert_to_dsd) to
                S(R.string.converts_source_to_dsd_and),
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
            Text(S(R.string.maximum_rate_multiple_of_44), style = MaterialTheme.typography.labelLarge)
            val labels = listOf(
                S(R.string.dac_maximum), "×2 (88.2 / 96k)", "×4 (176.4 / 192k)", "×8 (352.8 / 384k)", "×16 (705.6 / 768k)",
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, l ->
                    FilterChip(s.darPcm == i, { vm.set(Keys.darPcm, i) }, label = { Text(l) })
                }
            }
            SwitchRow(
                S(R.string.integer_multiples_only),
                S(R.string.on_upsamples_only_by_integer),
                s.darInteger,
            ) { vm.set(Keys.darInteger, it) }
            AudioEngine.darWarn.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (s.darMode == 2) {
            Text(S(R.string.dsd_rate), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("DSD64", "DSD128", "DSD256").forEachIndexed { i, l ->
                    FilterChip(s.darDsd == i, { vm.set(Keys.darDsd, i) }, label = { Text(l) })
                }
            }
            Text(
                S(R.string.if_dac_doesnt_support_selected),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            S(R.string.dar_applies_to_pcm_tracks),
            style = MaterialTheme.typography.bodySmall,
            color = if (s.darMode != 0 && (s.sourceDirect || (s.darMode == 2 && s.outputMode != 2))) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** ReplayGain(曲に書かれた値で、曲ごとの音の大きさの違いをそろえる) */
@Composable
fun ReplayGainPanel(vm: MainViewModel, s: AppSettings) {
    Section("ReplayGain") {
        SwitchRow("ReplayGain", S(R.string.uses_replaygain_values_written), s.rgEnabled) { vm.set(Keys.rgEnabled, it) }
        if (s.rgEnabled) {
            Segmented(S(R.string.rg_based_on), listOf(S(R.string.rg_track), S(R.string.rg_album)), s.rgMode) { vm.set(Keys.rgMode, it) }
            Text(
                S(R.string.rg_album_keeps_differences),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 動かしている間は手元の値で見せ、指を離したら保存する(動かすたびに保存すると、設定を何度も書き直して重い)
            var pre by remember(s.rgPreamp) { mutableIntStateOf(s.rgPreamp) }
            Text(S(R.string.rg_preamp, (if (pre > 0) "+" else "") + pre), style = MaterialTheme.typography.labelLarge)
            androidx.compose.material3.Slider(
                value = pre.toFloat(),
                onValueChange = { pre = it.roundToInt() },
                onValueChangeFinished = { vm.set(Keys.rgPreamp, pre) },
                valueRange = -12f..12f, steps = 23,
            )
            SwitchRow(S(R.string.prevent_clipping), S(R.string.limits_gain_using_peak), s.rgNoClip) { vm.set(Keys.rgNoClip, it) }
        }
        Text(
            S(R.string.rg_notes),
            style = MaterialTheme.typography.bodySmall,
            color = if (s.rgEnabled && s.sourceDirect) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ReplayGainAnalysisPanel(vm)
}

/** ライブラリの曲をまとめて解析して、ReplayGain の値を求める */
@Composable
private fun ReplayGainAnalysisPanel(vm: MainViewModel) {
    Section(S(R.string.rg_analysis)) {
        val total = remember(vm.albums) { vm.albums.sumOf { it.tracks.size } }
        Text(S(R.string.rg_analyzed_count, RgCount.n, total), style = MaterialTheme.typography.bodyMedium)
        if (vm.rgAnalyzing) {
            androidx.compose.material3.LinearProgressIndicator(progress = { vm.rgProgress }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Text(
                "${(vm.rgProgress * 100).roundToInt()}%" + S(R.string.separator) + vm.rgStatus,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
            androidx.compose.material3.OutlinedButton({ vm.cancelReplayGainAnalysis() }) { Text(S(R.string.cancel)) }
        } else {
            androidx.compose.material3.Button({ vm.analyzeReplayGain(false) }, Modifier.fillMaxWidth()) { Text(S(R.string.rg_analyze_new)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedButton({ vm.analyzeReplayGain(true) }) { Text(S(R.string.rg_analyze_all)) }
                TextButton({ vm.clearAnalyzedReplayGain() }, enabled = RgCount.n > 0) { Text(S(R.string.rg_clear_analyzed)) }
            }
        }
        Text(S(R.string.rg_analysis_notes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** DSD(DSF / DFF)の再生方法 */
@Composable
fun DsdPanel(vm: MainViewModel, s: AppSettings) {
    Section(S(R.string.dsd_playback)) {
        val options = listOf(
            S(R.string.auto_native_dsd_dop_pcm) to
                S(R.string.uses_native_dsd_when_possible),
            S(R.string.use_dop) to
                S(R.string.sends_dsd_over_pcm_dop),
            S(R.string.always_convert_to_pcm) to
                S(R.string.for_dacs_without_dsd_support),
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
            S(R.string.native_dsd_and_dop_are),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(
            S(R.string.swap_native_dsd_byte_order),
            S(R.string.turn_on_only_if_your),
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
                            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, S(R.string.back),
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
        val stop = com.uyatame.ucrt.player.OutputDevices.watch(ctx, android.os.Handler(android.os.Looper.getMainLooper())) {
            devices = com.uyatame.ucrt.player.OutputDevices.list(ctx)
            auto = com.uyatame.ucrt.player.OutputDevices.defaultDevice(ctx)
        }
        onDispose { stop() }
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
                Text(S(R.string.current_output), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                S(R.string.choose_output), style = MaterialTheme.typography.titleMedium,
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
                S(R.string.automatic),
                S(R.string.let_android_decide) + (auto?.let { " · ${it.name}" } ?: ""),
            )
            devices.forEach { d ->
                item(d.key, d.kind, d.name, listOf(d.detail, d.spec).filter { it.isNotEmpty() }.joinToString(" · "))
            }
            Text(
                S(R.string.choosing_usb_dac_enables_bit),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** 解析済みの ReplayGain の曲の数(画面に出す。RgStore から知らせを受けて変える) */
object RgCount {
    var n by androidx.compose.runtime.mutableIntStateOf(0)

    init {
        n = com.uyatame.ucrt.player.RgStore.count
        com.uyatame.ucrt.player.RgStore.onCount = { n = it }
    }
}
