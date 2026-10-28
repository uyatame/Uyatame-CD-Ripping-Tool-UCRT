package com.uyatame.ucrt

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.convert.ConvertSpec
import com.uyatame.ucrt.convert.DSD_RATES
import com.uyatame.ucrt.convert.DsreParams
import com.uyatame.ucrt.convert.OutFormat
import com.uyatame.ucrt.convert.PCM_RATES
import com.uyatame.ucrt.convert.encode
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.Keys
import org.json.JSONArray

/** アップサンプルのタブの番号 */
const val TAB_UPSAMPLE = 4

private fun khzLabel(r: Int): String = if (r % 1000 == 0) "${r / 1000}" else "%.1f".format(java.util.Locale.US, r / 1000.0)

/** DSD の周波数の表記(DSD64 など) */
private fun dsdLabel(r: Int): String = "DSD" + (r / (if (r % 48000 == 0) 48000 else 44100))

/** 保存した DSRE のプリセット */
private fun userPresets(raw: String): List<DsreParams> = runCatching {
    val a = JSONArray(raw)
    List(a.length()) { DsreParams.fromJson(a.getJSONObject(it)) }
}.getOrDefault(emptyList())

private fun encodePresets(list: List<DsreParams>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

/** 最初から入っているプリセットの表示名 */
private fun builtinName(p: DsreParams): String = when (p.name) {
    "natural" -> S(R.string.dsre_natural)
    "clear" -> S(R.string.dsre_clear)
    "strong" -> S(R.string.dsre_strong)
    else -> S(R.string.dsre_light)
}

/**
 * アップサンプルのタブ。上の切り替えで DSRE(高域を補ってアップサンプリング)と DSD 変換(TEAC Hi-Res Editor のように、
 * PCM ↔ DSD・DSD → DSD・PCM → PCM)を選ぶ。曲を並べて、まとめて書き出し先のフォルダに変換する(画面を閉じても続く)
 */
@Composable
fun UpsampleScreen(vm: MainViewModel, s: AppSettings) {
    val ctx = LocalContext.current
    val spec = s.upSpec
    fun save(n: ConvertSpec) = vm.set(Keys.upSpec, n.encode())
    val running = UpsampleJobs.running
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) UpsampleJobs.add(ctx, uris)
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            vm.set(Keys.upOutUri, uri.toString())
        }
    }
    WideCenter {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp + barInset()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(S(R.string.upsample))
            // ---- DSRE か DSD 変換か ----
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("DSRE", S(R.string.up_dsd_convert)).forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = spec.mode == i, onClick = { if (!running) save(spec.copy(mode = i, format = defaultFormat(spec, i))) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2), enabled = !running,
                    ) { Text(label, maxLines = 1) }
                }
            }
            Text(
                if (spec.mode == 0) S(R.string.up_dsre_desc) else S(R.string.up_dsd_desc),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // ---- 曲の並び ----
            QueueCard(running, onAdd = { pick.launch(arrayOf("audio/*", "application/octet-stream")) })
            // ---- 変換の設定 ----
            if (spec.mode == 0) DsreCard(vm, s, spec, running, ::save) else DsdCard(spec, running, ::save)
            EditCard(spec, running, ::save)
            // ---- 書き出し先 ----
            Section(S(R.string.up_output_folder)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (s.upOutUri == null) S(R.string.up_choose_folder_first) else folderLabel(s.upOutUri),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton({ folder.launch(null) }, enabled = !running) { Text(S(R.string.change)) }
                }
            }
            // ---- 始める・やめる ----
            val waiting = UpsampleJobs.items.count { it.state != UpsampleJobs.State.DONE }
            if (running) {
                OutlinedButton({ UpsampleJobs.stop() }, Modifier.fillMaxWidth()) { Text(S(R.string.up_stop)) }
            } else {
                Button(
                    {
                        val out = s.upOutUri
                        if (out == null) folder.launch(null) else UpsampleJobs.start(ctx, spec, Uri.parse(out))
                    },
                    Modifier.fillMaxWidth(), enabled = waiting > 0,
                ) { Text(if (s.upOutUri == null) S(R.string.up_choose_folder) else S(R.string.up_start, waiting)) }
            }
            Text(
                S(R.string.up_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 切り替えたときの出力の形式(DSRE は PCM だけ) */
private fun defaultFormat(spec: ConvertSpec, mode: Int): OutFormat =
    if (mode == 0 && spec.format.dsd) OutFormat.FLAC else spec.format

@Composable
private fun QueueCard(running: Boolean, onAdd: () -> Unit) {
    val items = UpsampleJobs.items
    UCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    S(R.string.up_queue, items.size), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
                )
                if (items.any { it.state == UpsampleJobs.State.DONE || it.state == UpsampleJobs.State.FAILED || it.state == UpsampleJobs.State.CANCELLED } && !running) {
                    TextButton({ UpsampleJobs.clearFinished() }) { Text(S(R.string.up_clear_done)) }
                }
                FilledTonalButton(onAdd) { Text(S(R.string.up_add)) }
            }
            if (items.isEmpty()) {
                Text(
                    S(R.string.up_empty), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items.forEachIndexed { i, it ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(it.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            it.info + "  ·  " + stateText(it), style = MaterialTheme.typography.bodySmall,
                            color = if (it.state == UpsampleJobs.State.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (it.state == UpsampleJobs.State.ANALYZING || it.state == UpsampleJobs.State.CONVERTING) {
                            LinearProgressIndicator({ it.progress }, Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp))
                        }
                    }
                    if (!running || (it.state != UpsampleJobs.State.CONVERTING && it.state != UpsampleJobs.State.ANALYZING)) {
                        IconButton({ UpsampleJobs.remove(it) }) { Icon(Icons.Filled.Clear, S(R.string.up_remove), Modifier.size(20.dp)) }
                    }
                }
            }
        }
    }
}

private fun stateText(it: UpsampleJobs.Item): String = when (it.state) {
    UpsampleJobs.State.WAITING -> S(R.string.up_waiting)
    UpsampleJobs.State.ANALYZING -> S(R.string.up_analyzing, (it.progress * 100).toInt())
    UpsampleJobs.State.CONVERTING -> S(R.string.up_converting_pct, (it.progress * 100).toInt())
    UpsampleJobs.State.DONE -> S(R.string.up_done, it.message, fmtMs(it.tookMs))
    UpsampleJobs.State.FAILED -> S(R.string.up_failed, it.message)
    UpsampleJobs.State.CANCELLED -> S(R.string.up_cancelled)
}

/** 選択肢のチップ(横に並べ、はみ出したら横にスクロール) */
@Composable
private fun <T> Chips(title: String, options: List<T>, selected: T, enabled: Boolean, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (o in options) FilterChip(o == selected, { onSelect(o) }, { Text(label(o)) }, enabled = enabled)
        }
    }
}

@Composable
private fun PcmOptions(spec: ConvertSpec, running: Boolean, rates: List<Int>?, save: (ConvertSpec) -> Unit) {
    Chips(S(R.string.up_format), listOf(OutFormat.FLAC, OutFormat.WAV), spec.format, !running, { it.name }) {
        save(spec.copy(format = it, bits = if (it == OutFormat.FLAC && spec.bits == 32) 24 else spec.bits))
    }
    if (rates != null) {
        Chips(S(R.string.up_rate), rates, spec.pcmRate, !running, { if (it == 0) S(R.string.up_rate_same) else khzLabel(it) + " kHz" }) {
            save(spec.copy(pcmRate = it))
        }
    }
    val bits = if (spec.format == OutFormat.WAV) listOf(16, 24, 32) else listOf(16, 24)
    Chips(S(R.string.up_bits), bits, spec.bits, !running, { if (it == 32) S(R.string.up_bits_float) else "$it bit" }) { save(spec.copy(bits = it)) }
}

@Composable
private fun DsdCard(spec: ConvertSpec, running: Boolean, save: (ConvertSpec) -> Unit) {
    Section(S(R.string.up_output)) {
        val dsd = spec.format.dsd
        Chips(S(R.string.up_output_kind), listOf(true, false), dsd, !running, { if (it) "DSD" else "PCM" }) {
            if (it != dsd) save(spec.copy(format = if (it) OutFormat.DSF else OutFormat.FLAC))
        }
        if (dsd) {
            Chips(S(R.string.up_format), listOf(OutFormat.DSF, OutFormat.DFF), spec.format, !running, { it.name }) { save(spec.copy(format = it)) }
            val fam48 = spec.dsdRate % 48000 == 0
            Chips(S(R.string.up_dsd_rate), DSD_RATES.filter { (it % 48000 == 0) == fam48 }, spec.dsdRate, !running, {
                dsdLabel(it) + " (" + "%.1f".format(java.util.Locale.US, it / 1e6) + " MHz)"
            }) { save(spec.copy(dsdRate = it)) }
            Chips(S(R.string.up_dsd_family), listOf(false, true), fam48, !running, { if (it) S(R.string.up_family_48) else S(R.string.up_family_44) }) {
                if (it != fam48) {
                    val mult = spec.dsdRate / (if (fam48) 3072000 else 2822400)
                    save(spec.copy(dsdRate = (if (it) 3072000 else 2822400) * mult))
                }
            }
            Text(S(R.string.up_dsd_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            PcmOptions(spec, running, PCM_RATES, save)
        }
    }
}

@Composable
private fun DsreCard(vm: MainViewModel, s: AppSettings, spec: ConvertSpec, running: Boolean, save: (ConvertSpec) -> Unit) {
    val ctx = LocalContext.current
    val p = spec.dsre
    val users = remember(s.dsrePresets) { userPresets(s.dsrePresets) }
    fun setP(n: DsreParams) = save(spec.copy(dsre = n.clamped()))
    var naming by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) message = runCatching {
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(DsreParams.export(users).toByteArray()) } ?: error("open")
            S(R.string.dsre_exported, users.size)
        }.getOrElse { S(R.string.dsre_export_failed, it.message) }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) message = runCatching {
            val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("open")
            val (got, bad) = DsreParams.import(raw)
            require(got.isNotEmpty()) { S(R.string.dsre_import_none) }
            val map = LinkedHashMap<String, DsreParams>()
            users.forEach { map[it.name.lowercase()] = it }
            got.forEach { map[it.name.lowercase()] = it }
            vm.set(Keys.dsrePresets, encodePresets(map.values.toList()))
            S(R.string.dsre_imported, got.size, bad)
        }.getOrElse { S(R.string.dsre_import_failed, it.message) }
    }
    if (naming) {
        var name by remember { mutableStateOf(p.name.takeIf { n -> users.any { it.name == n } }.orEmpty()) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(S(R.string.dsre_save_preset)) },
            text = { OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text(S(R.string.dsre_preset_name)) }) },
            confirmButton = {
                TextButton({
                    val n = name.trim()
                    if (n.isNotEmpty()) {
                        val np = p.copy(name = n)
                        vm.set(Keys.dsrePresets, encodePresets(users.filterNot { it.name.equals(n, true) } + np))
                        setP(np)
                        naming = false
                    }
                }) { Text(S(R.string.save)) }
            },
            dismissButton = { TextButton({ naming = false }) { Text(S(R.string.cancel)) } },
        )
    }
    Section(S(R.string.dsre_settings)) {
        // ---- プリセット ----
        Text(S(R.string.dsre_presets), style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in DsreParams.BUILTIN) {
                FilterChip(p.copy(name = b.name) == b, { setP(b) }, { Text(builtinName(b)) }, enabled = !running)
            }
            for (u in users) {
                FilterChip(p == u, { setP(u) }, { Text(u.name) }, enabled = !running)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            TextButton({ naming = true }, enabled = !running) { Text(S(R.string.dsre_save_preset)) }
            if (users.any { it == p }) {
                TextButton({
                    vm.set(Keys.dsrePresets, encodePresets(users.filterNot { it == p }))
                    setP(p.copy(name = ""))
                }, enabled = !running) { Text(S(R.string.delete)) }
            }
            TextButton({ export.launch("UCRT-DSRE-Presets.json") }, enabled = users.isNotEmpty()) { Text(S(R.string.dsre_export)) }
            TextButton({ import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text(S(R.string.dsre_import)) }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        // ---- 細かい値(変えると、名前のない設定になる) ----
        IntSlider(S(R.string.dsre_harmonics), p.harmonics, 1..24, { "$it" }, S(R.string.dsre_harmonics_desc)) { setP(p.copy(name = "", harmonics = it)) }
        IntSlider(S(R.string.dsre_strength), (p.strength * 100).toInt(), 10..80, { "%.2f".format(java.util.Locale.US, it / 100f) }, S(R.string.dsre_strength_desc)) {
            setP(p.copy(name = "", strength = it / 100f))
        }
        IntSlider(S(R.string.dsre_width), (p.width * 100).toInt(), 50..150, { "%.2f".format(java.util.Locale.US, it / 100f) }, S(R.string.dsre_width_desc)) {
            setP(p.copy(name = "", width = it / 100f))
        }
        IntSlider(S(R.string.dsre_dynamic), (p.dynamic * 100).toInt(), 100..150, { "%.2f".format(java.util.Locale.US, it / 100f) }, S(R.string.dsre_dynamic_desc)) {
            setP(p.copy(name = "", dynamic = it / 100f))
        }
        Chips(S(R.string.up_rate), DsreParams.RATES, p.rate, !running, { khzLabel(it) + " kHz" }) { setP(p.copy(name = "", rate = it)) }
        PcmOptions(spec, running, null, save)
    }
}

@Composable
private fun EditCard(spec: ConvertSpec, running: Boolean, save: (ConvertSpec) -> Unit) {
    Section(S(R.string.up_edit)) {
        SwitchRow(S(R.string.up_normalize), S(R.string.up_normalize_desc), spec.normalize) { if (!running) save(spec.copy(normalize = it)) }
        if (spec.normalize) {
            IntSlider(S(R.string.up_normalize_level), (spec.normalizeDb * 10).toInt(), -60..0, { "%.1f dBFS".format(java.util.Locale.US, it / 10f) }) {
                save(spec.copy(normalizeDb = it / 10f))
            }
        }
        SwitchRow(S(R.string.up_fade_in), null, spec.fadeIn) { if (!running) save(spec.copy(fadeIn = it)) }
        if (spec.fadeIn) {
            IntSlider(S(R.string.up_fade_len), (spec.fadeInSec * 10).toInt(), 1..100, { "%.1f s".format(java.util.Locale.US, it / 10f) }) {
                save(spec.copy(fadeInSec = it / 10f))
            }
        }
        SwitchRow(S(R.string.up_fade_out), null, spec.fadeOut) { if (!running) save(spec.copy(fadeOut = it)) }
        if (spec.fadeOut) {
            IntSlider(S(R.string.up_fade_len), (spec.fadeOutSec * 10).toInt(), 1..100, { "%.1f s".format(java.util.Locale.US, it / 10f) }) {
                save(spec.copy(fadeOutSec = it / 10f))
            }
        }
        SwitchRow(S(R.string.up_trim_start), null, spec.trimStart) { if (!running) save(spec.copy(trimStart = it)) }
        SwitchRow(S(R.string.up_trim_end), null, spec.trimEnd) { if (!running) save(spec.copy(trimEnd = it)) }
        if (spec.trimStart || spec.trimEnd) {
            IntSlider(S(R.string.up_trim_level), spec.trimDb.toInt(), -90..-30, { "$it dBFS" }, S(R.string.up_trim_level_desc)) {
                save(spec.copy(trimDb = it.toFloat()))
            }
        }
    }
}
