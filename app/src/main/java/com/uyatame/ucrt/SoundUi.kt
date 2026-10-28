package com.uyatame.ucrt

import com.uyatame.ucrt.data.rgEffective
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.Keys
import com.uyatame.ucrt.data.eqBandList
import com.uyatame.ucrt.data.outputMode
import com.uyatame.ucrt.player.AudioEngine
import com.uyatame.ucrt.player.EqBands

/** 音質設定の詳細ページ */
object SoundPage {
    const val MAIN = 0
    const val EQ = 1
    const val DAR = 2
    const val OUTPUT = 3
    const val DSD = 4
    const val RG = 5

    fun title(p: Int): String = when (p) {
        EQ -> S(R.string.equalizer)
        DAR -> "DAR"
        OUTPUT -> S(R.string.usb_dac_direct_output)
        DSD -> S(R.string.dsd_playback)
        RG -> "ReplayGain"
        else -> S(R.string.sound_settings)
    }
}

/** 詳細ページの中身 */
@Composable
fun SoundSubPage(vm: MainViewModel, s: AppSettings, page: Int) {
    when (page) {
        SoundPage.EQ -> EqualizerPanel(vm, s)
        SoundPage.DAR -> DarPanel(vm, s)
        SoundPage.OUTPUT -> {
            BitPerfectPanel(vm, s)
            DacKeepAliveSection(vm, s)
        }
        SoundPage.DSD -> DsdPanel(vm, s)
        SoundPage.RG -> ReplayGainPanel(vm, s)
    }
}

private fun eqSummary(s: AppSettings): String = when {
    !s.eqEnabled -> S(R.string.off)
    else -> (if (s.eqMode == 1) "PRO · " else "") + (eqPresetName(s) ?: S(R.string.custom))
}

private fun darSummary(s: AppSettings): String = when (s.darMode) {
    1 -> S(R.string.pcm_upsampling) + " · " +
        listOf(S(R.string.dac_max), "×2", "×4", "×8", "×16").getOrElse(s.darPcm) { "" }
    2 -> S(R.string.convert_to_dsd) + " · " + listOf("DSD64", "DSD128", "DSD256").getOrElse(s.darDsd) { "" }
    else -> S(R.string.off)
}

private fun rgSummary(s: AppSettings): String = when {
    !s.rgEnabled -> S(R.string.off)
    else -> (if (s.rgMode == 1) S(R.string.rg_album) else S(R.string.rg_track)) +
        (if (s.rgPreamp != 0) " · " + (if (s.rgPreamp > 0) "+" else "") + "${s.rgPreamp} dB" else "")
}

private fun dsdSummary(s: AppSettings): String = when (s.dsdMode) {
    1 -> "DoP"
    2 -> S(R.string.convert_to_pcm)
    else -> S(R.string.auto_native_dsd_dop_pcm_2)
}

/**
 * 音質設定のトップ(WALKMAN の音質設定にならった並び)。
 * いちばん上の「ソースダイレクト」をオンにすると、その下の音の加工(イコライザー・DAR)を通さずに音源のまま出す。
 * 各行はスイッチで入り切りでき、右の矢印(行のタップ)で詳しい設定を開く。
 */
@Composable
fun SoundMain(vm: MainViewModel, s: AppSettings, onOpen: (Int) -> Unit) {
    val direct = s.sourceDirect
    NowOutputCard(vm, s)
    SoundGroup(S(R.string.sound_quality)) {
        SoundRow(
            S(R.string.source_direct),
            S(R.string.plays_source_as_is_bypassing),
            checked = direct, enabled = true,
            onToggle = { vm.set(Keys.sourceDirect, it) },
            highlight = true,
        )
        HorizontalDivider()
        SoundRow(
            S(R.string.equalizer), eqSummary(s),
            checked = s.eqEnabled, enabled = !direct,
            onToggle = { vm.set(Keys.eqEnabled, it) },
            onOpen = { onOpen(SoundPage.EQ) },
        )
        HorizontalDivider()
        SoundRow(
            "DAR", S(R.string.upsampling_dsd_conversion) + " · " + darSummary(s),
            checked = s.darMode != 0, enabled = !direct,
            onToggle = { vm.set(Keys.darMode, if (it) 1 else 0) },
            onOpen = { onOpen(SoundPage.DAR) },
        )
        HorizontalDivider()
        SoundRow(
            "ReplayGain", S(R.string.evens_out_loudness) + " · " + rgSummary(s),
            checked = s.rgEnabled, enabled = !direct,
            onToggle = { vm.set(Keys.rgEnabled, it) },
            onOpen = { onOpen(SoundPage.RG) },
        )
        if (direct) {
            Text(
                S(R.string.equalizer_and_dar_are_bypassed),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    SoundGroup(S(R.string.output_2)) {
        SoundRow(
            S(R.string.usb_dac_direct_output),
            S(R.string.drives_usb_dac_with_apps),
            checked = s.outputMode == 2, enabled = true,
            onToggle = { vm.setOutputMode(if (it) 2 else 0) },
            onOpen = { onOpen(SoundPage.OUTPUT) },
        )
        HorizontalDivider()
        SoundRow(
            S(R.string.dsd_playback), dsdSummary(s),
            checked = null, enabled = true,
            onOpen = { onOpen(SoundPage.DSD) },
        )
        HorizontalDivider()
        SoundRow(
            S(R.string.gapless_playback),
            S(R.string.plays_tracks_of_same_format),
            checked = s.gapless, enabled = true,
            onToggle = { vm.set(Keys.gapless, it) },
        )
    }
}

/** 今の出力の状態(出力先と、ビットパーフェクトかどうか) */
@Composable
private fun NowOutputCard(vm: MainViewModel, s: AppSettings) {
    val dev = AudioEngine.output
    val st = AudioEngine.outputStatus
    val perfect = AudioEngine.bitPerfectActive
    UCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (perfect) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(dev?.let { outputIcon(it.kind) } ?: AppIcons.Speaker, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    dev?.name ?: S(R.string.output),
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    st.ifEmpty {
                        when {
                            s.sourceDirect -> S(R.string.source_direct)
                            s.eqEnabled -> S(R.string.equalizer_on)
                            else -> S(R.string.output_status_appears_here_during)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (perfect) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutputRoute(vm, s)
    }
}

/**
 * 音の出力経路(再生中の曲 → アプリの中の処理 → 出力先)。再生画面の下から引き出す音質のシートなどに出す
 */
@Composable
private fun OutputRoute(vm: MainViewModel, s: AppSettings) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cur = vm.player.current ?: return
    val detail = androidx.compose.runtime.produceState<com.uyatame.ucrt.player.AudioInfo.Detail?>(null, cur) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.uyatame.ucrt.player.AudioInfo.detail(ctx, cur) }.getOrNull()
        }
    }.value
    val cast = com.uyatame.ucrt.player.cast.CastManager.connected
    val steps = buildList {
        add(S(R.string.route_source) to (detail?.text ?: cur.title))
        val fx = buildList {
            if (s.sourceDirect) add(S(R.string.source_direct))
            else {
                if (s.rgEffective != 0) add("ReplayGain")
                if (s.eqEnabled) add(S(R.string.equalizer))
                if (s.darMode != 0) add("DAR")
            }
        }
        add(S(R.string.route_process) to (if (fx.isEmpty()) S(R.string.route_none) else fx.joinToString(" → ")) +
            AudioEngine.darInfo.takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty())
        val dev = AudioEngine.output
        add(
            S(R.string.route_output) to (
                if (cast != null) S(R.string.cast_title) + ": " + cast.name
                else listOfNotNull(dev?.name, dev?.spec?.takeIf { it.isNotEmpty() }, AudioEngine.outputStatus.takeIf { it.isNotEmpty() })
                    .joinToString("\n").ifEmpty { S(R.string.output) }
                ),
        )
    }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
        Text(S(R.string.route_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        steps.forEachIndexed { i, (k, v) ->
            if (i > 0) Text("↓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            Row(Modifier.padding(top = 2.dp)) {
                Text(k, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (AudioEngine.bitPerfectActive) {
            Text(S(R.string.route_bitperfect), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun SoundGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp, top = 4.dp),
        )
        UCard(Modifier.fillMaxWidth()) { Column(content = content) }
    }
}

/**
 * 音質設定の 1 行。名前と今の設定を出し、右にスイッチ(checked が null なら無し)。
 * onOpen があれば、行をタップすると詳しい設定を開き、スイッチとのあいだに区切り線を入れる
 */
@Composable
private fun SoundRow(
    title: String,
    summary: String,
    checked: Boolean?,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit = {},
    onOpen: (() -> Unit)? = null,
    highlight: Boolean = false,
) {
    val click: (() -> Unit)? = onOpen ?: checked?.let { c -> { onToggle(!c) } }
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f)
            .let { m -> if (click != null && enabled) m.clickable(onClick = click) else m }
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (highlight) FontWeight.SemiBold else null,
            )
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onOpen != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (checked != null) {
            if (onOpen != null) {
                VerticalDivider(Modifier.height(32.dp).padding(horizontal = 8.dp))
            }
            Switch(checked, { onToggle(it) }, enabled = enabled)
        }
    }
}
