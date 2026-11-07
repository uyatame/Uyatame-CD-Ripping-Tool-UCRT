package com.uyatame.ucrt

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.library.LibTrack
import com.uyatame.ucrt.lyrics.LrcDoc
import com.uyatame.ucrt.lyrics.LrcDocument
import com.uyatame.ucrt.lyrics.LrcLine
import com.uyatame.ucrt.player.PlayItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * 歌詞の編集(lyrics-editor-module を、UCRT に合わせて作り直したもの)。
 * 時刻付きの歌詞(LRC)を、再生しながら 1 行ずつ「タグ」を押して作れる。LRCLIB で探すこともできる。
 *
 * 再生は UCRT の再生をそのまま使う(USB DAC 直接出力・イコライザーも効く。別の再生を作らないので、音が二重にならない)。
 * 波形は UCRT の内蔵のデコーダーで作る(FLAC・ALAC・MP3・AAC・DSD・APE・WavPack など、再生できる形式はすべて)。
 * 保存は、曲と同じフォルダの LRC ファイルか、曲への埋め込み(FLAC・MP3・M4A。今の曲情報の書き込みの仕組みで)
 */
object LyricsIO {
    /** 波形の山の数の下限・上限(長い曲でも拡大して見られるよう、1 秒に 25 個を目安に) */
    const val WAVE_BUCKETS_MIN = 1200
    const val WAVE_BUCKETS_MAX = 30000

    /** 長さ(ミリ秒)に合った波形の山の数 */
    fun waveBuckets(durationMs: Long): Int = (durationMs / 40).coerceIn(WAVE_BUCKETS_MIN.toLong(), WAVE_BUCKETS_MAX.toLong()).toInt()

    /** 今の歌詞(LRC ファイル・埋め込み)を、編集できる形で読む。無ければ空の 1 行 */
    fun load(ctx: Context, item: PlayItem): LrcDocument {
        val l = runCatching { com.uyatame.ucrt.player.LyricsLoader.load(ctx, item) }.getOrNull()
            ?: return LrcDocument(listOf(LrcLine(LrcDoc.newId(), null, "")))
        val lines = l.lines.map { LrcLine(LrcDoc.newId(), if (l.synced) it.timeMs else null, it.text) }
        return LrcDocument(lines.ifEmpty { listOf(LrcLine(LrcDoc.newId(), null, "")) })
    }

    /** 曲と同じフォルダに「曲の名前.lrc」を書く(あれば上書き)。書けなければ例外 */
    fun saveLrc(ctx: Context, item: PlayItem, doc: LrcDocument) {
        val uri = item.uri ?: throw IOException("no file")
        val folder = item.folderId ?: throw IOException(S(R.string.lyrics_cannot_lrc_here))
        val cr = ctx.contentResolver
        val name = cr.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: throw IOException(S(R.string.lyrics_cannot_lrc_here))
        val lrcName = name.substringBeforeLast('.') + ".lrc"
        val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
        // すでにあれば、そのファイルに書く(大文字・小文字の違いは同じとみなす)
        var target: Uri? = null
        cr.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { c ->
            while (c.moveToNext()) if (c.getString(1).equals(lrcName, ignoreCase = true)) target = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        val dst = target ?: DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, folder), "application/octet-stream", lrcName)
            ?: throw IOException(S(R.string.lyrics_cannot_lrc_here))
        cr.openOutputStream(dst, "wt")?.use { it.write(LrcDoc.encode(doc).toByteArray(Charsets.UTF_8)) } ?: throw IOException(S(R.string.lyrics_cannot_lrc_here))
    }

    /** 曲に埋め込む(FLAC・MP3・M4A)。ほかの曲情報は、ライブラリの今の値のまま書き直す(曲情報の編集と同じ仕組み) */
    fun saveEmbedded(ctx: Context, t: LibTrack, album: com.uyatame.ucrt.library.LibAlbum?, doc: LrcDocument) {
        if (!com.uyatame.ucrt.tags.TagExtra.supported(t.uri)) throw IOException(S(R.string.lyrics_embed_unsupported))
        val tracks = album?.tracks.orEmpty()
        val v = com.uyatame.ucrt.tags.TagValues(
            t.title, t.artist, t.album, t.albumArtist, t.year,
            t.track, maxOf(tracks.size, tracks.maxOfOrNull { it.track } ?: 0), t.disc, (tracks.maxOfOrNull { it.disc } ?: 1).coerceAtLeast(1),
            com.uyatame.ucrt.tags.TagExtra(lyrics = LrcDoc.encode(doc).trimEnd()),
        )
        if (!com.uyatame.ucrt.tags.TagEditor.edit(ctx, Uri.parse(t.uri), v, null)) throw IOException(S(R.string.lyrics_embed_unsupported))
    }

    /**
     * 波形(山の高さ 0〜1 を buckets 個)。内蔵のデコーダーで頭から読む(数秒〜十数秒かかるので、画面とは別に)。
     * 読めなければ null
     */
    suspend fun waveform(ctx: Context, uri: Uri, name: String, buckets: Int = WAVE_BUCKETS_MIN, progress: (Float) -> Unit = {}): FloatArray? =
        withContext(Dispatchers.IO) {
            val opened = runCatching { com.uyatame.ucrt.player.Decoders.open(ctx, uri, name) }.getOrNull() ?: return@withContext null
            val ctxJob = coroutineContext
            opened.use { o -> peaks(o.decoder, buckets, { ctxJob.isActive }, progress) }
        }

    /** 開いたデコーダーから、波形の山の高さ(0〜1)を buckets 個。長さが分からなければ null */
    internal fun peaks(d: com.uyatame.ucrt.player.PcmDecoder, buckets: Int, active: () -> Boolean = { true }, progress: (Float) -> Unit = {}): FloatArray? {
        val total = d.totalSamples.takeIf { it > 0 } ?: return null
        val full = (1L shl (d.bitsPerSample.coerceIn(8, 32) - 1)).toFloat()
        val peaks = FloatArray(buckets)
        var lastP = -1
        while (active() && d.decodeNext()) {
            val n = d.blockSize
            val first = d.frameSample
            for (ch in 0 until d.channels) {
                val s = d.samples[ch]
                // 4 サンプルごとに見れば、山の高さには十分(全部見るより速い)
                var i = 0
                while (i < n) {
                    val b = ((first + i) * buckets / total).toInt().coerceIn(0, buckets - 1)
                    val v = Math.abs(s[i]) / full
                    if (v > peaks[b]) peaks[b] = v
                    i += 4
                }
            }
            val p = ((first + n) * 100 / total).toInt()
            if (p != lastP) { lastP = p; progress(p / 100f) }
        }
        return peaks
    }
}

private fun timeText(ms: Long): String {
    val cs = (ms.coerceAtLeast(0) + 5) / 10
    return "%d:%02d.%02d".format(cs / 6000, (cs / 100) % 60, cs % 100)
}

/** 歌詞の編集の画面(画面いっぱい)。item は今の曲 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyricsEditorPage(vm: MainViewModel, item: PlayItem, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = vm.player
    val lib = vm.libTrackOf(item)
    var lines by remember(item) { mutableStateOf<List<LrcLine>>(emptyList()) }
    var meta by remember(item) { mutableStateOf(LrcDocument(emptyList())) }
    var loaded by remember(item) { mutableStateOf(false) }
    var dirty by remember(item) { mutableStateOf(false) }
    var selected by remember(item) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var busyProgress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var peaks by remember(item) { mutableStateOf<FloatArray?>(null) }
    var wavePct by remember(item) { mutableStateOf(0f) }
    var menu by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    // 波形の拡大(1 で曲の全体。2・4… で、今の位置のまわりを広げて見せる)
    var zoom by remember { mutableIntStateOf(1) }
    var searching by remember { mutableStateOf<List<com.uyatame.ucrt.meta.Lrclib.Hit>?>(null) }
    val uri = item.uri
    val fileName = remember(item) { uri?.let { Uri.decode(it.toString()).substringAfterLast('/') }.orEmpty() }

    fun change(l: List<LrcLine>) { lines = l; dirty = true }
    fun doc() = LrcDocument(lines, meta.offsetMs, meta.metadata)

    LaunchedEffect(item) {
        val d = withContext(Dispatchers.IO) { LyricsIO.load(ctx, item) }
        meta = d; lines = d.lines; loaded = true
        if (uri != null) peaks = LyricsIO.waveform(ctx, uri, fileName, LyricsIO.waveBuckets(item.durationMs)) { wavePct = it }
    }
    // 再生位置(0.1 秒ごと。画面の全体ではなく、位置を使う所だけが描き直される)
    var pos by remember { mutableLongStateOf(0L) }
    val playingThis = p.current?.uri == item.uri
    LaunchedEffect(playingThis) { while (true) { pos = if (playingThis) p.positionMs - item.startMs else 0L; delay(100) } }
    val dur = item.durationMs.coerceAtLeast(1)
    // 歌っている行(行が変わったときだけ、画面を組み直す。位置が動くたびに一覧ごと組み直さないように)
    val current by remember(lines, meta.offsetMs) { androidx.compose.runtime.derivedStateOf { LrcDoc.indexAt(lines, pos, meta.offsetMs) } }

    fun seek(ms: Long) { if (playingThis) p.seek(item.startMs + ms.coerceIn(0, dur)) }
    /** 選んでいる行に、今の位置の時刻を付けて、次の(時刻の無い)行へ */
    fun stampSelected() {
        if (lines.isEmpty()) return
        val i = selected.coerceIn(0, lines.lastIndex)
        change(LrcDoc.setTime(lines, i, pos + meta.offsetMs))
        val n = LrcDoc.nextUntimed(lines, i)
        if (n >= 0) selected = n
    }
    fun close() { if (dirty) confirmLeave = true else onClose() }
    BackHandler { close() }

    fun runBusy(label: String, block: suspend () -> Unit) {
        scope.launch {
            busy = label; busyProgress = null
            try { block() } catch (e: Exception) { message = e.message ?: e.javaClass.simpleName } finally { busy = null; busyProgress = null }
        }
    }

    // 文字やアイコンの色を背景に合わせる(指定しないと黒になり、ダークモードで見えない。本体の画面と同じ直し方)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding()) {
        // ---- 上の帯 ----
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S(R.string.back)) }
            Column(Modifier.weight(1f)) {
                Text(S(R.string.lyrics_edit), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(item.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton({
                runBusy(S(R.string.lyrics_searching)) {
                    searching = withContext(Dispatchers.IO) { com.uyatame.ucrt.meta.Lrclib.search(item.title, item.artist, item.durationMs / 1000.0) }
                }
            }) { Icon(Icons.Filled.Search, S(R.string.lyrics_search)) }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, S(R.string.menu)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(S(R.string.lyrics_paste)) }, onClick = { menu = false; pasting = true })
                    DropdownMenuItem(text = { Text(S(R.string.lyrics_sort)) }, onClick = { menu = false; change(LrcDoc.sortTimed(lines)) })
                    DropdownMenuItem(text = { Text(S(R.string.lyrics_clear_times)) }, onClick = { menu = false; change(lines.map { it.copy(timeMs = null) }) })
                }
            }
            Box {
                Button({ saveMenu = true }, enabled = loaded && busy == null, modifier = Modifier.padding(end = 8.dp)) { Text(S(R.string.save)) }
                DropdownMenu(saveMenu, { saveMenu = false }) {
                    DropdownMenuItem(text = { Text(S(R.string.lyrics_save_lrc)) }, onClick = {
                        saveMenu = false
                        runBusy(S(R.string.lyrics_saving)) {
                            withContext(Dispatchers.IO) { LyricsIO.saveLrc(ctx, item, doc()) }
                            dirty = false; vm.lyricsGen++; message = S(R.string.lyrics_saved)
                        }
                    })
                    if (lib != null && com.uyatame.ucrt.tags.TagExtra.supported(lib.uri)) {
                        DropdownMenuItem(text = { Text(S(R.string.lyrics_save_embed)) }, onClick = {
                            saveMenu = false
                            runBusy(S(R.string.lyrics_saving)) {
                                withContext(Dispatchers.IO) { LyricsIO.saveEmbedded(ctx, lib, vm.albumByKey(vm.albumKeyOf(lib.uri)), doc()) }
                                dirty = false; vm.lyricsGen++; message = S(R.string.lyrics_saved)
                            }
                        })
                    }
                }
            }
        }
        busy?.let {
            if (busyProgress != null) LinearProgressIndicator({ busyProgress ?: 0f }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (!playingThis) {
            Text(
                S(R.string.lyrics_not_playing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        // ---- 波形(タップ・なぞって移動。時刻の付いた行の印も出す) ----
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                S(R.string.lyrics_wave_span, timeText(dur / zoom).substringBeforeLast('.')), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            TextButton({ zoom = (zoom / 2).coerceAtLeast(1) }, Modifier.size(width = 44.dp, height = 32.dp), enabled = zoom > 1, contentPadding = PaddingValues(0.dp)) {
                Text("−", style = MaterialTheme.typography.titleMedium)
            }
            Text("×$zoom", style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton({ zoom = (zoom * 2).coerceAtMost(MAX_ZOOM) }, Modifier.size(width = 44.dp, height = 32.dp), enabled = zoom < MAX_ZOOM, contentPadding = PaddingValues(0.dp)) {
                Text("+", style = MaterialTheme.typography.titleMedium)
            }
        }
        Waveform(peaks, wavePct, { pos }, dur, zoom, lines, current, Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 12.dp)) { seek(it) }
        // ---- 再生と、タグ ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PosText { pos }
            TextButton({ seek(pos - 5000) }, enabled = playingThis) { Text("−5s") }
            IconButton({ if (playingThis) p.toggle() }, enabled = playingThis) {
                Icon(if (p.isPlaying && playingThis) AppIcons.Pause else Icons.Filled.PlayArrow, S(R.string.play))
            }
            TextButton({ seek(pos + 5000) }, enabled = playingThis) { Text("+5s") }
            Spacer(Modifier.weight(1f))
            FilledTonalButton({ stampSelected() }, enabled = playingThis && lines.isNotEmpty()) { Text(S(R.string.lyrics_tag)) }
        }
        Text(
            S(R.string.lyrics_tag_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        HorizontalDivider(Modifier.padding(top = 4.dp))
        // ---- 行 ----
        val listState = rememberLazyListState()
        // 再生中は、歌っている行が見えるように
        LaunchedEffect(current) { if (current >= 0 && p.isPlaying && playingThis) runCatching { listState.animateScrollToItem((current - 2).coerceAtLeast(0)) } }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 24.dp + barInset())) {
            itemsIndexed(lines, key = { _, l -> l.id }) { i, l ->
                val sel = i == selected
                val now = i == current
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (now) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .border(if (sel) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(0.dp, androidx.compose.ui.graphics.Color.Transparent), RoundedCornerShape(10.dp))
                        .clickable { selected = i }.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 時刻(タップでその位置へ。長押しで消す)
                    Text(
                        l.timeMs?.let { timeText(it) } ?: "--:--.--",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = if (l.timeMs != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(72.dp).clip(RoundedCornerShape(6.dp)).combinedClickable(
                            onClick = { selected = i; l.timeMs?.let { seek(it - meta.offsetMs) } },
                            onLongClick = { change(LrcDoc.setTime(lines, i, null)) },
                        ).padding(vertical = 8.dp, horizontal = 2.dp),
                    )
                    BasicTextField(
                        l.text, { change(LrcDoc.setText(lines, i, it)) },
                        Modifier.weight(1f).padding(vertical = 6.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                    if (l.timeMs != null) {
                        TextButton({ change(LrcDoc.nudge(lines, i, -100)) }, Modifier.size(width = 44.dp, height = 36.dp), contentPadding = PaddingValues(0.dp)) { Text("−.1") }
                        TextButton({ change(LrcDoc.nudge(lines, i, 100)) }, Modifier.size(width = 44.dp, height = 36.dp), contentPadding = PaddingValues(0.dp)) { Text("+.1") }
                    }
                    IconButton({ change(LrcDoc.insertAfter(lines, i)); selected = i + 1 }, Modifier.size(36.dp)) { Icon(Icons.Filled.Add, S(R.string.lyrics_add_line), Modifier.size(18.dp)) }
                    IconButton({ change(LrcDoc.delete(lines, i)); if (selected > i) selected-- }, Modifier.size(36.dp)) { Icon(Icons.Filled.Delete, S(R.string.delete), Modifier.size(18.dp)) }
                }
            }
            item(key = "add") {
                TextButton({ change(LrcDoc.insertAfter(lines, lines.lastIndex)); selected = lines.lastIndex }) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text(S(R.string.lyrics_add_line))
                }
            }
        }
    }
    }

    // ---- ダイアログ ----
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            text = { Text(S(R.string.lyrics_discard)) },
            confirmButton = { TextButton({ confirmLeave = false; onClose() }) { Text(S(R.string.lyrics_discard_ok)) } },
            dismissButton = { TextButton({ confirmLeave = false }) { Text(S(R.string.cancel)) } },
        )
    }
    message?.let { m ->
        AlertDialog(onDismissRequest = { message = null }, text = { Text(m) }, confirmButton = { TextButton({ message = null }) { Text(S(R.string.ok)) } })
    }
    if (pasting) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text(S(R.string.lyrics_paste)) },
            text = {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp), label = { Text(S(R.string.lyrics_paste_hint)) })
            },
            confirmButton = {
                TextButton({
                    pasting = false
                    // LRC を貼ったら時刻ごと読み、普通の文章なら行に分ける
                    val d = LrcDoc.parse(text)
                    if (d.lines.isNotEmpty()) { meta = LrcDocument(emptyList(), d.offsetMs, d.metadata); change(d.lines); selected = 0 }
                }, enabled = text.isNotBlank()) { Text(S(R.string.ok)) }
            },
            dismissButton = { TextButton({ pasting = false }) { Text(S(R.string.cancel)) } },
        )
    }
    searching?.let { hits ->
        AlertDialog(
            onDismissRequest = { searching = null },
            title = { Text(S(R.string.lyrics_search)) },
            text = {
                if (hits.isEmpty()) Text(S(R.string.lyrics_search_none))
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(hits) { _, h ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                searching = null
                                val src = h.synced ?: h.plain
                                if (src.isNullOrBlank()) { message = S(R.string.lyrics_search_instrumental); return@clickable }
                                val d = LrcDoc.parse(src)
                                meta = LrcDocument(emptyList(), d.offsetMs, d.metadata); change(d.lines); selected = 0
                            }.padding(8.dp),
                        ) {
                            Text("${h.track} / ${h.artist}", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(h.album, timeText((h.durationSec * 1000).toLong()).substringBeforeLast('.'),
                                    if (h.synced != null) S(R.string.lyrics_synced) else if (h.instrumental) S(R.string.lyrics_instrumental) else S(R.string.lyrics_plain),
                                ).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton({ searching = null }) { Text(S(R.string.close)) } },
        )
    }
}

/** 今の位置の時刻(位置が動くたびに、ここだけ描き直す) */
@Composable
private fun PosText(posOf: () -> Long) {
    Text(timeText(posOf()), style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.widthIn(min = 76.dp))
}

/** 波形の拡大の上限(×32 で、4 分の曲なら 7.5 秒ぶん) */
private const val MAX_ZOOM = 32

/**
 * 波形。タップ・左右になぞると、その位置へ移動。時刻の付いた行の場所に細い印。
 * zoom が 2 以上なら、曲の 1/zoom の長さを、今の位置が真ん中に来るように見せる(なぞっている間は、見せる範囲を動かさない)
 */
@Composable
private fun Waveform(
    peaks: FloatArray?, loadPct: Float, posOf: () -> Long, dur: Long, zoom: Int, lines: List<LrcLine>, current: Int,
    modifier: Modifier, onSeek: (Long) -> Unit,
) {
    val wave = MaterialTheme.colorScheme.primary
    val played = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val mark = MaterialTheme.colorScheme.tertiary
    val head = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.surfaceVariant
    val span = (dur / zoom.coerceAtLeast(1)).coerceAtLeast(1)
    /** 見せる範囲の始まり(今の位置が真ん中。曲の頭と終わりでは端に寄せる) */
    fun startFor(p: Long) = (p - span / 2).coerceIn(0, (dur - span).coerceAtLeast(0))
    // なぞっている間は、範囲を止めておく(位置が動くたびに範囲も動くと、指の下の時刻がずれる)
    var dragStart by remember { mutableStateOf<Long?>(null) }
    // 位置は描くとき・触ったときにだけ読む(位置が動くたびに組み直さず、描き直すだけにする)
    val curSpan by androidx.compose.runtime.rememberUpdatedState(span)
    val curPosOf by androidx.compose.runtime.rememberUpdatedState(posOf)
    val curStartFor by androidx.compose.runtime.rememberUpdatedState(::startFor)
    fun at(x: Float, w: Int, from: Long) = from + (x / w * curSpan).toLong()
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(track.copy(alpha = 0.5f))) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { o -> onSeek(at(o.x, size.width, dragStart ?: curStartFor(curPosOf()))) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragStart = curStartFor(curPosOf()) },
                        onDragEnd = { dragStart = null },
                        onDragCancel = { dragStart = null },
                    ) { ch, _ -> onSeek(at(ch.position.x, size.width, dragStart ?: curStartFor(curPosOf()))) }
                },
        ) {
            val w = size.width
            val h = size.height
            val pk = peaks
            val pos = posOf()
            val start = dragStart ?: startFor(pos)
            val end = start + span
            fun xOf(t: Long) = (t - start).toFloat() / span * w
            if (pk != null && pk.isNotEmpty()) {
                // 1 列(2 ピクセル)ごとに、その範囲の山のいちばん高いものを描く(山が多くても、描く線の数は画面の幅ぶんだけ)
                val col = 2f
                val cols = (w / col).toInt().coerceAtLeast(1)
                val headX = xOf(pos)
                for (c in 0 until cols) {
                    val t0 = start + span * c / cols
                    val t1 = start + span * (c + 1) / cols
                    val b0 = (t0 * pk.size / dur).toInt().coerceIn(0, pk.size - 1)
                    val b1 = (t1 * pk.size / dur).toInt().coerceIn(b0 + 1, pk.size)
                    var m = 0f
                    for (b in b0 until b1) if (pk[b] > m) m = pk[b]
                    val v = m.coerceIn(0f, 1f) * h * 0.92f
                    if (v < 0.5f) continue
                    val x = c * col + col / 2
                    drawLine(if (x <= headX) played else wave, Offset(x, h / 2 - v / 2), Offset(x, h / 2 + v / 2), col * 0.8f)
                }
            } else {
                // 読み込み中: 読めた割合だけ線を引く
                drawLine(wave.copy(alpha = 0.5f), Offset(0f, h / 2), Offset(w * loadPct, h / 2), 2f)
            }
            lines.forEachIndexed { i, l ->
                val t = l.timeMs ?: return@forEachIndexed
                if (t < start || t > end) return@forEachIndexed
                val x = xOf(t)
                drawLine(if (i == current) head else mark.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, h * 0.2f), 2.5f)
            }
            if (pos in start..end) {
                val x = xOf(pos)
                drawLine(head, Offset(x, 0f), Offset(x, h), 3f)
            }
        }
    }
}
