package com.uyatame.ucrt.library

import com.uyatame.ucrt.T

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LibTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val track: Int,
    val disc: Int,
    val durationMs: Long,
    val folderId: String,
    val coverUri: String?,
    val modified: Long,
    val year: String = "",
    val genre: String = "",
    /** サンプリング周波数(DSD は 1 チャンネルあたりの DSD の周波数)。分からなければ 0 */
    val rate: Int = 0,
    /** 量子化ビット数(DSD は 1)。分からなければ 0 */
    val bits: Int = 0,
    /** CUE シートで分けた曲の、元のファイルの中での始まり(ミリ秒) */
    val startMs: Long = 0,
    /** CUE シートで分けた曲の終わり(0 ならファイルの終わりまで) */
    val endMs: Long = 0,
    /** CUE シートで曲に分けたため、一覧には出さない元のファイル */
    val hidden: Boolean = false,
    /** CUE シートで分けた曲の、CUE シートの URI */
    val sheet: String? = null,
    /** 拡張子だけでは分からない形式(MP4 の中身が MPEG-H なら "MPEG-H")。空なら拡張子から決める */
    val codec: String = "",
)

data class LibAlbum(val key: String, val title: String, val artist: String, val tracks: List<LibTrack>) {
    val first: LibTrack get() = tracks.first()
    // 以下は並べ替え・絞り込みのたびに何度も使うので、最初に使ったときに一度だけ計算する

    /** 発売年(曲ごとに違えば最初に見つかったもの) */
    val year: String by lazy { tracks.firstOrNull { it.year.isNotEmpty() }?.year.orEmpty() }
    /** いちばん新しく追加・更新された曲の時刻 */
    val added: Long by lazy { tracks.maxOf { it.modified } }
    /** 音質の区分(いちばん良い曲に合わせる) */
    internal val quality: QualityClass by lazy { Quality.albumClass(tracks) }
    /** 形式と音質の表記 */
    internal val badge: String by lazy { Quality.albumBadge(tracks) }
}

val UNKNOWN_ALBUM: String get() = T("不明なアルバム", "Unknown album")
val UNKNOWN_ARTIST: String get() = T("不明なアーティスト", "Unknown artist")

private val AUDIO_EXT = setOf("flac", "wav", "m4a", "mp3", "ogg", "opus", "aac", "dsf", "dff", "aif", "aiff", "aifc", "mp4", "wv")
private val COVER_NAMES = setOf("cover.jpg", "cover.png", "folder.jpg", "front.jpg")

/** 保存先フォルダ内の音楽ファイルを読み取り、アルバム単位にまとめる */
class Library(private val ctx: Context) {
    private val cacheFile = File(ctx.filesDir, "library.json")

    /**
     * 前回の版で作った一覧には、音質やジャンルの情報が入っていない。
     * その場合は true になり、次の読み込みで全曲を読み直す。
     */
    var needsFullRescan = false
        private set

    fun loadCache(): List<LibTrack> = try {
        if (!cacheFile.exists()) emptyList() else {
            val text = cacheFile.readText()
            val a: JSONArray
            if (text.trimStart().startsWith("[")) {
                // 以前の版の形式(音質・ジャンルなし)
                a = JSONArray(text)
                needsFullRescan = a.length() > 0
            } else {
                val root = JSONObject(text)
                a = root.optJSONArray("tracks") ?: JSONArray()
                needsFullRescan = root.optInt("v") < CACHE_VERSION && a.length() > 0
            }
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                LibTrack(
                    o.getString("u"), o.optString("t"), o.optString("a"), o.optString("al"), o.optString("aa"),
                    o.optInt("n"), o.optInt("d", 1), o.optLong("ms"), o.optString("f"),
                    if (o.has("c")) o.getString("c") else null, o.optLong("m"), o.optString("y"),
                    o.optString("g"), o.optInt("sr"), o.optInt("bt"),
                    o.optLong("ss"), o.optLong("se"), o.optBoolean("h"), if (o.has("cs")) o.getString("cs") else null,
                    o.optString("cd"),
                )
            }.distinctBy { docKey(it.uri) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveCache(list: List<LibTrack>) {
        val a = JSONArray()
        for (t in list) {
            val o = JSONObject()
            o.put("u", t.uri); o.put("t", t.title); o.put("a", t.artist); o.put("al", t.album); o.put("aa", t.albumArtist)
            o.put("n", t.track); o.put("d", t.disc); o.put("ms", t.durationMs); o.put("f", t.folderId); o.put("m", t.modified)
            if (t.coverUri != null) o.put("c", t.coverUri)
            o.put("y", t.year)
            if (t.genre.isNotEmpty()) o.put("g", t.genre)
            if (t.rate > 0) o.put("sr", t.rate)
            if (t.bits > 0) o.put("bt", t.bits)
            if (t.startMs > 0) o.put("ss", t.startMs)
            if (t.endMs > 0) o.put("se", t.endMs)
            if (t.hidden) o.put("h", true)
            if (t.sheet != null) o.put("cs", t.sheet)
            if (t.codec.isNotEmpty()) o.put("cd", t.codec)
            a.put(o)
        }
        val root = JSONObject().put("v", CACHE_VERSION).put("tracks", a)
        runCatching { cacheFile.writeText(root.toString()) }
        needsFullRescan = false
    }

    /** 同時に処理する数(フォルダの一覧取得・タグの読み込み) */
    private val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    /**
     * フォルダ内の曲を一覧にする。読み込み済み(前回から変わっていない)の曲はそのまま使い、
     * 新しく入った曲・書き換えられた曲だけタグを読む。消えた曲は一覧から外れる。
     * フォルダの一覧取得とタグの読み込みは、複数のスレッドで同時に行う。
     * progress は「タグを読む曲」の件数で数える。
     */
    fun scan(tree: Uri, old: List<LibTrack>, light: Boolean = false, progress: (Int, Int) -> Unit): List<LibTrack> {
        // 再生中は、音が途切れないよう少ないスレッド・低い優先度で読む
        val n = if (light) 2 else workers
        val pool = java.util.concurrent.Executors.newFixedThreadPool(n) { r ->
            Thread {
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                r.run()
            }.apply { isDaemon = true }
        }
        try {
            val found = java.util.Collections.synchronizedList(ArrayList<Triple<Uri, String, Long>>())
            val names = java.util.concurrent.ConcurrentHashMap<String, String>()
            val covers = java.util.concurrent.ConcurrentHashMap<String, String>()
            // CUE シート(フォルダの ID → CUE シートの URI)
            val cues = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
            // フォルダを階層ごとにまとめて、同時に一覧を取る
            var level = listOf(DocumentsContract.getTreeDocumentId(tree))
            var depth = 0
            while (level.isNotEmpty() && depth <= 8) {
                val next = java.util.Collections.synchronizedList(ArrayList<String>())
                pool.invokeAll(level.map { id ->
                    java.util.concurrent.Callable { list(tree, id, found, names, covers, cues, next) }
                })
                level = next.toList()
                depth++
            }
            // 同じファイルでも、別のフォルダの指定から見ると URI が変わるので、ドキュメント ID で照らし合わせる
            val oldMap = old.associateBy { docKey(it.uri) }
            val all = found.toList()
            val need = all.filter { (uri, _, mod) -> oldMap[docKey(uri.toString())]?.modified != mod }
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val read = java.util.concurrent.ConcurrentHashMap<String, LibTrack>()
            pool.invokeAll(need.map { (uri, folder, mod) ->
                java.util.concurrent.Callable {
                    val key = uri.toString()
                    read[key] = readTags(uri, names[key] ?: "", folder, covers[folder], mod)
                    val n = done.incrementAndGet()
                    // 表示の更新は間引く
                    if (n == need.size || n % 5 == 0) progress(n, need.size)
                    Unit
                }
            })
            val tracks = all.mapNotNull { (uri, folder, _) ->
                val key = uri.toString()
                read[key] ?: oldMap[docKey(key)]?.copy(uri = key, folderId = folder, coverUri = covers[folder], hidden = false)
            }
            return if (cues.isEmpty()) tracks else applyCues(tracks, cues, names)
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * CUE シートがあるフォルダでは、CUE シートが指す音声ファイルを曲ごとに分ける。
     * 元のファイルは一覧から隠し(次の読み込みで変わっていないか確かめるため、キャッシュには残す)、分けた曲を加える
     */
    private fun applyCues(tracks: List<LibTrack>, cues: Map<String, List<String>>, names: Map<String, String>): List<LibTrack> {
        val byFolder = tracks.groupBy { it.folderId }
        val hide = HashSet<String>()
        val added = ArrayList<LibTrack>()
        for ((folder, sheets) in cues) {
            val inFolder = byFolder[folder] ?: continue
            for (cueUri in sheets) {
                val sheet = runCatching {
                    StorageAccess.open(ctx, Uri.parse(cueUri))?.use { Cue.parse(Cue.decode(it.readBytes())) }
                }.getOrNull() ?: continue
                for (cf in sheet.files) {
                    // FILE の名前でファイルを探す(拡張子だけ違うもの(WAV → FLAC に変換した)も認める)
                    val want = cf.name.substringAfterLast('/').substringAfterLast('\\')
                    val base = inFolder.firstOrNull { names[it.uri].equals(want, ignoreCase = true) }
                        ?: inFolder.firstOrNull { names[it.uri]?.substringBeforeLast('.').equals(want.substringBeforeLast('.'), ignoreCase = true) }
                        ?: continue
                    if (base.uri in hide) continue
                    hide.add(base.uri)
                    cf.tracks.forEachIndexed { i, ct ->
                        val next = cf.tracks.getOrNull(i + 1)
                        val end = next?.startMs ?: 0L
                        val dur = (if (end > 0) end else base.durationMs) - ct.startMs
                        added.add(
                            base.copy(
                                uri = Cue.trackUri(base.uri, ct.number),
                                title = ct.title ?: T("トラック %02d", "Track %02d").format(ct.number),
                                artist = ct.performer ?: sheet.performer ?: base.artist,
                                album = sheet.title ?: base.album,
                                albumArtist = sheet.performer ?: base.albumArtist,
                                track = ct.number,
                                durationMs = dur.coerceAtLeast(0L),
                                year = sheet.date ?: base.year,
                                genre = sheet.genre ?: base.genre,
                                startMs = ct.startMs,
                                endMs = end,
                                hidden = false,
                                sheet = cueUri,
                            ),
                        )
                    }
                }
            }
        }
        return tracks.map { if (it.uri in hide) it.copy(hidden = true) else it } + added
    }

    /** 1 つのフォルダの中身を一覧にする(サブフォルダは next に入れる) */
    private fun list(
        tree: Uri,
        docId: String,
        found: MutableList<Triple<Uri, String, Long>>,
        names: MutableMap<String, String>,
        covers: MutableMap<String, String>,
        cues: java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>,
        next: MutableList<String>,
    ) {
        fun addCue(id: String) {
            cues.computeIfAbsent(docId) { java.util.Collections.synchronizedList(ArrayList()) }
                .add(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString())
        }
        // 「すべてのファイルへのアクセス」があれば、フォルダの中身をファイルから直接読む(URI は同じものを作る)
        if (StorageAccess.isExternalTree(tree)) {
            val dir = StorageAccess.fileOfDocId(docId)
            val files = dir?.takeIf { it.isDirectory }?.listFiles()
            if (files != null) {
                val prefix = if (docId.endsWith(":")) docId else "$docId/"
                for (f in files) {
                    val name = f.name
                    val id = prefix + name
                    if (f.isDirectory) { next.add(id); continue }
                    val lower = name.lowercase()
                    if (lower.substringAfterLast('.', "") in AUDIO_EXT) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        found.add(Triple(uri, docId, f.lastModified()))
                        names[uri.toString()] = name
                    } else if (lower in COVER_NAMES) {
                        covers[docId] = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                    } else if (lower.endsWith(".cue")) {
                        addCue(id)
                    }
                }
                return
            }
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        runCatching {
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: ""
                    val mime = c.getString(2) ?: ""
                    val mod = if (c.isNull(3)) 0L else c.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { next.add(id); continue }
                    val lower = name.lowercase()
                    if (lower.substringAfterLast('.', "") in AUDIO_EXT) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        found.add(Triple(uri, docId, mod))
                        names[uri.toString()] = name
                    } else if (lower in COVER_NAMES) {
                        covers[docId] = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                    } else if (lower.endsWith(".cue")) {
                        addCue(id)
                    }
                }
            }
        }
    }

    /** 足りない分を読み切る */
    private fun readFully(ins: java.io.InputStream, b: ByteArray): Int {
        var off = 0
        while (off < b.size) {
            val r = ins.read(b, off, b.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    /**
     * FLAC はファイルの先頭(STREAMINFO と VORBIS_COMMENT)だけを自前で読む。
     * Android 標準の読み取り機能より大幅に速い。読めなければ null。
     */
    private fun readFlacTags(uri: Uri, name: String, folder: String, cover: String?, mod: Long): LibTrack? {
        val raw = runCatching { StorageAccess.open(ctx, uri) }.getOrNull() ?: return null
        return runCatching {
            raw.use { r0 ->
                val ins = java.io.BufferedInputStream(r0, 1 shl 16)
                val head = ByteArray(4)
                if (readFully(ins, head) != 4 || String(head, Charsets.ISO_8859_1) != "fLaC") return null
                var rate = 0
                var bits = 0
                var samples = 0L
                val tags = HashMap<String, String>()
                while (true) {
                    val bh = ByteArray(4)
                    if (readFully(ins, bh) != 4) break
                    val last = (bh[0].toInt() and 0x80) != 0
                    val type = bh[0].toInt() and 0x7F
                    val len = ((bh[1].toInt() and 0xFF) shl 16) or ((bh[2].toInt() and 0xFF) shl 8) or (bh[3].toInt() and 0xFF)
                    if (type == 0 || type == 4) {
                        val b = ByteArray(len)
                        if (readFully(ins, b) != len) break
                        if (type == 0 && len >= 18) {
                            fun u(i: Int) = b[i].toLong() and 0xFF
                            rate = ((u(10) shl 12) or (u(11) shl 4) or (u(12) shr 4)).toInt()
                            bits = (((u(12) and 1) shl 4) or (u(13) shr 4)).toInt() + 1
                            samples = ((u(13) and 0x0F) shl 32) or (u(14) shl 24) or (u(15) shl 16) or (u(16) shl 8) or u(17)
                        } else if (type == 4) {
                            val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            val vlen = bb.int
                            bb.position(bb.position() + vlen)
                            val n = bb.int
                            for (k in 0 until n) {
                                if (bb.remaining() < 4) break
                                val l = bb.int
                                if (l < 0 || l > bb.remaining()) break
                                val sArr = ByteArray(l)
                                bb.get(sArr)
                                val c = String(sArr, Charsets.UTF_8)
                                val eq = c.indexOf('=')
                                if (eq > 0) {
                                    val key = c.substring(0, eq).uppercase()
                                    if (key !in tags) tags[key] = c.substring(eq + 1).trim()
                                }
                            }
                        }
                    } else {
                        var left = len.toLong()
                        while (left > 0) {
                            val sk = ins.skip(left)
                            if (sk <= 0) break
                            left -= sk
                        }
                    }
                    if (last) break
                }
                val base = name.substringBeforeLast('.')
                fun t(k: String) = tags[k]?.takeIf { it.isNotEmpty() }
                LibTrack(
                    uri.toString(),
                    t("TITLE") ?: base,
                    t("ARTIST") ?: UNKNOWN_ARTIST,
                    t("ALBUM") ?: UNKNOWN_ALBUM,
                    t("ALBUMARTIST") ?: t("ALBUM ARTIST") ?: "",
                    t("TRACKNUMBER")?.substringBefore('/')?.trim()?.toIntOrNull() ?: base.take(2).toIntOrNull() ?: 0,
                    t("DISCNUMBER")?.substringBefore('/')?.trim()?.toIntOrNull() ?: 1,
                    if (rate > 0) samples * 1000 / rate else 0L,
                    folder, cover, mod,
                    (t("DATE") ?: t("YEAR"))?.take(4) ?: "",
                    cleanGenre(t("GENRE")),
                    rate, bits,
                )
            }
        }.getOrNull()
    }

    /** DSD(DSF / DFF)は Android 標準の機能で読めないので、自前で読む */
    private fun readDsdTags(uri: Uri, name: String, folder: String, cover: String?, mod: Long): LibTrack {
        val base = name.substringBeforeLast('.')
        val r = com.uyatame.ucrt.player.dsd.DsdTags.read(ctx, uri)
        val t = r?.tags
        return LibTrack(
            uri.toString(),
            t?.title ?: base,
            t?.artist ?: UNKNOWN_ARTIST,
            t?.album ?: UNKNOWN_ALBUM,
            t?.albumArtist ?: "",
            t?.track ?: base.take(2).toIntOrNull() ?: 0,
            t?.disc ?: 1,
            r?.info?.durationMs ?: 0L,
            folder, cover, mod,
            t?.year ?: "",
            cleanGenre(t?.genre),
            r?.info?.rate ?: 0, if (r != null) 1 else 0,
        )
    }

    /** WAV は Android 標準の機能では曲情報を読めないので、自前で読む(LIST/INFO と id3 チャンク) */
    private fun readWavTags(uri: Uri, name: String, folder: String, cover: String?, mod: Long): LibTrack? {
        val w = runCatching { com.uyatame.ucrt.tags.WavTags.read(ctx, uri) }.getOrNull() ?: return null
        val base = name.substringBeforeLast('.')
        val t = w.tags
        return LibTrack(
            uri.toString(),
            t?.title ?: base,
            t?.artist ?: UNKNOWN_ARTIST,
            t?.album ?: UNKNOWN_ALBUM,
            t?.albumArtist ?: "",
            t?.track ?: base.take(2).toIntOrNull() ?: 0,
            t?.disc ?: 1,
            w.durationMs,
            folder, cover, mod,
            t?.year ?: "",
            cleanGenre(t?.genre),
            w.rate, w.bits,
        )
    }

    /** AIFF・WavPack・APE は Android 標準の機能では読めないので、アプリ内蔵のデコーダーとタグの読み込みで読む */
    private fun readBuiltinTags(uri: Uri, name: String, folder: String, cover: String?, mod: Long, kind: String): LibTrack? {
        val (info, t) = com.uyatame.ucrt.player.BuiltinCodecs.probe(ctx, uri, kind) ?: return null
        val base = name.substringBeforeLast('.')
        return LibTrack(
            uri.toString(),
            t?.title ?: base,
            t?.artist ?: UNKNOWN_ARTIST,
            t?.album ?: UNKNOWN_ALBUM,
            t?.albumArtist ?: "",
            t?.track ?: base.take(2).toIntOrNull() ?: 0,
            t?.disc ?: 1,
            info.durationMs,
            folder, cover, mod,
            t?.year ?: "",
            cleanGenre(t?.genre),
            info.rate, info.bits,
        )
    }

    private fun readTags(uri: Uri, name: String, folder: String, cover: String?, mod: Long): LibTrack {
        val kind = com.uyatame.ucrt.player.BuiltinCodecs.kindOf(name)
        if (kind == "aiff" || kind == "wavpack" || kind == "ape") readBuiltinTags(uri, name, folder, cover, mod, kind)?.let { return it }
        // MP4 / M4A: 中身の形式を見る
        if (kind == "alac") {
            val entry = com.uyatame.ucrt.player.BuiltinCodecs.openInput(ctx, uri)?.use { com.uyatame.ucrt.player.Mp4Probe.audioEntry(it) }
            // MPEG-H 3D Audio(360 Reality Audio など)なら、そう記録する
            if (com.uyatame.ucrt.player.Mp4Probe.isMpegH(entry)) return readTagsRetriever(uri, name, folder, cover, mod).copy(codec = "MPEG-H")
            val t = readTagsRetriever(uri, name, folder, cover, mod)
            // ALAC: Android 10 より前は周波数・ビット数を教えてくれないので、中身から読む(AAC なら 0 のまま)
            if (entry != "alac" || t.bits > 0) return t
            val info = com.uyatame.ucrt.player.BuiltinCodecs.openInput(ctx, uri)?.use { com.uyatame.ucrt.player.BuiltinCodecs.info("alac", it) }
                ?: return t
            return t.copy(rate = info.rate, bits = info.bits, durationMs = if (t.durationMs > 0) t.durationMs else info.durationMs)
        }
        return readTagsRetriever(uri, name, folder, cover, mod)
    }

    private fun readTagsRetriever(uri: Uri, name: String, folder: String, cover: String?, mod: Long): LibTrack {
        if (com.uyatame.ucrt.player.dsd.DsdFile.isDsd(name)) return readDsdTags(uri, name, folder, cover, mod)
        if (name.lowercase().endsWith(".wav")) readWavTags(uri, name, folder, cover, mod)?.let { return it }
        if (name.lowercase().endsWith(".flac")) readFlacTags(uri, name, folder, cover, mod)?.let { return it }
        val base = name.substringBeforeLast('.')
        val r = MediaMetadataRetriever()
        return try {
            // ファイル記述子を直接渡す方が速い
            val file = StorageAccess.fileOf(uri)
            if (file != null) {
                r.setDataSource(file.absolutePath)
            } else {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) pfd.use { r.setDataSource(it.fileDescriptor) } else r.setDataSource(ctx, uri)
            }
            fun m(k: Int): String? = r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
            val tn = m(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.toIntOrNull()
                ?: base.take(2).toIntOrNull() ?: 0
            LibTrack(
                uri.toString(),
                m(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: base,
                m(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: UNKNOWN_ARTIST,
                m(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: UNKNOWN_ALBUM,
                m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: "",
                tn,
                m(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.substringBefore('/')?.toIntOrNull() ?: 1,
                m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                folder, cover, mod,
                m(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: m(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4) ?: "",
                cleanGenre(m(MediaMetadataRetriever.METADATA_KEY_GENRE)),
                m(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0,
                m(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0,
            )
        } catch (e: Exception) {
            LibTrack(uri.toString(), base, UNKNOWN_ARTIST, UNKNOWN_ALBUM, "", base.take(2).toIntOrNull() ?: 0, 1, 0L, folder, cover, mod)
        } finally {
            runCatching { r.release() }
        }
    }

    /** ジャンル名の整理(ID3v1 の番号 "(17)" などは外す) */
    private fun cleanGenre(g: String?): String {
        val s = g?.trim().orEmpty()
        if (s.isEmpty()) return ""
        val m = Regex("^\\((\\d+)\\)(.*)$").find(s)
        if (m != null) return m.groupValues[2].trim().ifEmpty { m.groupValues[1].toIntOrNull()?.let { ID3V1_GENRES.getOrNull(it) } ?: "" }
        s.toIntOrNull()?.let { return ID3V1_GENRES.getOrNull(it) ?: "" }
        return s
    }

    companion object {
        /**
         * 同じファイルかを見分けるキー。
         * 保存先とライブラリのフォルダが重なっていると(例: 「Music」と「Music/cd」)、同じファイルが
         * 指定したフォルダごとに別の URI になるため、URI そのものではなく保存場所とドキュメント ID で比べる
         */
        fun docKey(uri: String): String = runCatching {
            val u = Uri.parse(uri)
            // CUE シートで分けた曲は、元のファイルと区別するため印(#cue=曲番号)も含める
            u.authority + "\u0000" + DocumentsContract.getDocumentId(u) + (u.fragment?.let { "#$it" } ?: "")
        }.getOrDefault(uri)

        /** 一覧の形式の版(音質・ジャンルを追加したので 2) */
        const val CACHE_VERSION = 3

        private val ID3V1_GENRES = listOf(
            "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
            "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
            "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
            "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
            "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
            "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
            "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
            "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
        )

        private fun isUnknownAlbum(a: String) = a == UNKNOWN_ALBUM || a == "不明なアルバム" || a == "Unknown album"

        /** いちばん多い値(空は数えない。無ければ null) */
        private fun mostCommon(values: List<String>): String? =
            values.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

        /**
         * 曲をアルバムにまとめる。
         * 同じフォルダにあって同じアルバム名の曲は、曲ごとのアーティスト(「feat. ○○」など)が違っても 1 枚のアルバムにする。
         * アルバムアーティストがあれば、別のフォルダ(Disc 1 / Disc 2 など)の曲も同じアルバムにまとめる
         */
        fun group(tracks: List<LibTrack>): List<LibAlbum> {
            // 1) フォルダとアルバム名でまとめる
            val byFolder = tracks.filter { !it.hidden }.groupBy { t ->
                if (isUnknownAlbum(t.album)) "f:" + t.folderId else t.folderId + "\u0000" + t.album
            }
            // 2) まとまりごとに、アルバムのキーを決める(同じキーのまとまりは 1 枚にする)
            val merged = LinkedHashMap<String, MutableList<LibTrack>>()
            for ((fk, list) in byFolder) {
                val first = list.first()
                val key = when {
                    isUnknownAlbum(first.album) -> fk
                    else -> {
                        val aa = mostCommon(list.map { it.albumArtist })
                        val artists = list.map { it.artist }.distinct()
                        when {
                            aa != null -> "a:" + aa + "\u0000" + first.album
                            // 全曲が同じアーティストなら、以前と同じキー(お気に入りなどがそのまま使える)
                            artists.size == 1 -> "a:" + artists[0] + "\u0000" + first.album
                            else -> "d:" + first.folderId + "\u0000" + first.album
                        }
                    }
                }
                merged.getOrPut(key) { ArrayList() }.addAll(list)
            }
            return merged.map { (k, list) ->
                val sorted = list.sortedWith(compareBy({ it.disc }, { it.track }, { it.title }))
                val f = sorted.first()
                val artist = mostCommon(sorted.map { it.albumArtist }) ?: run {
                    // アルバムアーティストが無ければ、半分以上の曲のアーティスト(feat. 付きの曲が混ざっていても)
                    val top = sorted.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }
                    if (top != null && top.value * 2 >= sorted.size) top.key else T("さまざまなアーティスト", "Various artists")
                }
                LibAlbum(k, f.album, artist, sorted)
            }.sortedBy { it.artist.lowercase() + "\u0000" + it.title.lowercase() }
        }
    }
}

/** ジャケット画像の読み込み(埋め込み画像 → フォルダのcover.jpg の順) */
object ArtLoader {
    private val cache = LruCache<String, ImageBitmap>(80)
    /** 同時に読み込む数を絞る(一覧を速くスクロールしたときに、再生の読み込みを邪魔しないように) */
    private val gate = java.util.concurrent.Semaphore(3)
    private val misses = HashSet<String>()

    /** 画像が差し替わったことを画面に知らせるための番号(増えると表示中の画像を読み直す) */
    var generation by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun peek(key: String?): ImageBitmap? = key?.let { cache.get(it) }

    fun load(ctx: Context, key: String, trackUri: String?, coverUri: String?, size: Int = 512): ImageBitmap? {
        cache.get(key)?.let { return it }
        synchronized(misses) { if (key in misses) return null }
        gate.acquire()
        try {
            cache.get(key)?.let { return it }
            return loadNow(ctx, key, trackUri, coverUri, size)
        } finally {
            gate.release()
        }
    }

    private fun loadNow(ctx: Context, key: String, trackUri0: String?, coverUri: String?, size: Int): ImageBitmap? {
        var bytes: ByteArray? = null
        // CUE シートで分けた曲は、元のファイルの画像を使う
        val trackUri = trackUri0?.let { Cue.fileUri(it) }
        if (trackUri != null && com.uyatame.ucrt.player.dsd.DsdFile.isDsd(Uri.decode(trackUri))) {
            bytes = runCatching { com.uyatame.ucrt.player.dsd.DsdTags.picture(ctx, Uri.parse(trackUri)) }.getOrNull()
        } else if (trackUri != null && Uri.decode(trackUri).lowercase().endsWith(".wav")) {
            bytes = runCatching { com.uyatame.ucrt.tags.WavTags.picture(ctx, Uri.parse(trackUri)) }.getOrNull()
        } else if (trackUri != null && com.uyatame.ucrt.player.BuiltinCodecs.kindOf(Uri.decode(trackUri)).let { it == "aiff" || it == "wavpack" || it == "ape" }) {
            val kind = com.uyatame.ucrt.player.BuiltinCodecs.kindOf(Uri.decode(trackUri))!!
            bytes = runCatching {
                com.uyatame.ucrt.player.BuiltinCodecs.openInput(ctx, Uri.parse(trackUri))?.use { com.uyatame.ucrt.tags.ExtraTags.read(kind, it)?.picture }
            }.getOrNull()
        } else if (trackUri != null) {
            val r = MediaMetadataRetriever()
            try {
                // 「すべてのファイルへのアクセス」があれば、ファイルから直接読む方が速い
                val uri = Uri.parse(trackUri)
                val file = StorageAccess.fileOf(uri)
                if (file != null) r.setDataSource(file.absolutePath) else r.setDataSource(ctx, uri)
                bytes = r.embeddedPicture
            } catch (e: Exception) {
            } finally {
                runCatching { r.release() }
            }
        }
        if (bytes == null && coverUri != null) {
            bytes = runCatching {
                ctx.contentResolver.openInputStream(Uri.parse(coverUri))?.use { it.readBytes() }
            }.getOrNull()
        }
        val img = bytes?.let { decode(it, size) }?.asImageBitmap()
        if (img == null) {
            synchronized(misses) { misses.add(key) }
            return null
        }
        cache.put(key, img)
        return img
    }

    fun forget(key: String) {
        cache.remove(key)
        synchronized(misses) { misses.remove(key) }
        generation++
    }

    fun clearMisses() {
        synchronized(misses) { misses.clear() }
        generation++
    }

    fun decode(b: ByteArray, size: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= size && o.outHeight / (s * 2) >= size) s *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    }
}
