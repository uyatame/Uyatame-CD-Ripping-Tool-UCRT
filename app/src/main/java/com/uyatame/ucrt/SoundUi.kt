package com.uyatame.ucrt

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

    fun title(p: Int): String = when (p) {
        EQ -> T("イコライザー", "Equalizer")
        DAR -> "DAR"
        OUTPUT -> T("USB DAC直接出力", "USB DAC direct output")
        DSD -> T("DSDの再生", "DSD playback")
        else -> T("音質設定", "Sound settings")
    }
}

/** 詳細ページの中身 */
@Composable
fun SoundSubPage(vm: MainViewModel, s: AppSettings, page: Int) {
    when (page) {
        SoundPage.EQ -> EqualizerPanel(vm, s)
        SoundPage.DAR -> DarPanel(vm, s)
        SoundPage.OUTPUT -> BitPerfectPanel(vm, s)
        SoundPage.DSD -> DsdPanel(vm, s)
    }
}

private fun eqSummary(s: AppSettings): String = when {
    !s.eqEnabled -> T("オフ", "Off")
    else -> EqBands.PRESETS.getOrNull(s.eqPreset)?.let { T(it.ja, it.en) }
        ?: T("カスタム（${s.eqBandList.size}バンド）", "Custom (${s.eqBandList.size} bands)")
}

private fun darSummary(s: AppSettings): String = when (s.darMode) {
    1 -> T("PCMアップサンプリング", "PCM upsampling") + " · " +
        listOf(T("DACの最大値", "DAC max"), "×2", "×4", "×8", "×16").getOrElse(s.darPcm) { "" }
    2 -> T("DSDに変換", "Convert to DSD") + " · " + listOf("DSD64", "DSD128", "DSD256").getOrElse(s.darDsd) { "" }
    else -> T("オフ", "Off")
}

private fun dsdSummary(s: AppSettings): String = when (s.dsdMode) {
    1 -> "DoP"
    2 -> T("PCMに変換", "Convert to PCM")
    else -> T("自動（ネイティブDSD → DoP → PCM）", "Auto (native DSD → DoP → PCM)")
}

/**
 * 音質設定のトップ(WALKMAN の音質設定にならった並び)。
 * いちばん上の「ソースダイレクト」をオンにすると、その下の音の加工(イコライザー・DAR)を通さずに音源のまま出す。
 * 各行はスイッチで入り切りでき、右の矢印(行のタップ)で詳しい設定を開く。
 */
@Composable
fun SoundMain(vm: MainViewModel, s: AppSettings, onOpen: (Int) -> Unit) {
    val direct = s.sourceDirect
    NowOutputCard(s)
    SoundGroup(T("音質", "Sound quality")) {
        SoundRow(
            T("ソースダイレクト", "Source direct"),
            T("イコライザーとDARを通さず、音源をそのまま再生します", "Plays the source as is, bypassing the equalizer and DAR"),
            checked = direct, enabled = true,
            onToggle = { vm.set(Keys.sourceDirect, it) },
            highlight = true,
        )
        HorizontalDivider()
        SoundRow(
            T("イコライザー", "Equalizer"), eqSummary(s),
            checked = s.eqEnabled, enabled = !direct,
            onToggle = { vm.set(Keys.eqEnabled, it) },
            onOpen = { onOpen(SoundPage.EQ) },
        )
        HorizontalDivider()
        SoundRow(
            "DAR", T("アップサンプリング・DSD変換", "Upsampling / DSD conversion") + " · " + darSummary(s),
            checked = s.darMode != 0, enabled = !direct,
            onToggle = { vm.set(Keys.darMode, if (it) 1 else 0) },
            onOpen = { onOpen(SoundPage.DAR) },
        )
        if (direct) {
            Text(
                T("ソースダイレクトがオンのため、イコライザーとDARは無効です", "The equalizer and DAR are bypassed while source direct is on"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    SoundGroup(T("出力", "Output")) {
        SoundRow(
            T("USB DAC直接出力", "USB DAC direct output"),
            T("独自ドライバーでUSB DACを直接駆動します（ビットパーフェクト・ネイティブDSD再生）", "Drives the USB DAC with the app's own driver (bit-perfect, native DSD)"),
            checked = s.outputMode == 2, enabled = true,
            onToggle = { vm.setOutputMode(if (it) 2 else 0) },
            onOpen = { onOpen(SoundPage.OUTPUT) },
        )
        HorizontalDivider()
        SoundRow(
            T("DSDの再生", "DSD playback"), dsdSummary(s),
            checked = null, enabled = true,
            onOpen = { onOpen(SoundPage.DSD) },
        )
        HorizontalDivider()
        SoundRow(
            T("ギャップレス再生", "Gapless playback"),
            T("同じ形式の曲が続くとき、曲間に無音を入れずに再生します", "Plays tracks of the same format without silence between them"),
            checked = s.gapless, enabled = true,
            onToggle = { vm.set(Keys.gapless, it) },
        )
    }
}

/** 今の出力の状態(出力先と、ビットパーフェクトかどうか) */
@Composable
private fun NowOutputCard(s: AppSettings) {
    val dev = AudioEngine.output
    val st = AudioEngine.outputStatus
    val perfect = AudioEngine.bitPerfectActive
    Card(
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
                    dev?.name ?: T("出力先", "Output"),
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    st.ifEmpty {
                        when {
                            s.sourceDirect -> T("ソースダイレクト", "Source direct")
                            s.eqEnabled -> T("イコライザー使用中", "Equalizer on")
                            else -> T("再生中はここに出力の状態が表示されます", "Output status appears here during playback")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (perfect) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
        Card(Modifier.fillMaxWidth()) { Column(content = content) }
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
