package com.uyatame.ucrt.library

import com.uyatame.ucrt.R
import com.uyatame.ucrt.S

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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 保存先フォルダ内の音楽ファイルを読み取り、アルバム単位にまとめる */
class Library(private val ctx: Context) {
    private val cacheFile = File(ctx.filesDir, "library.json")

    /**
     * 前回の版で作った一覧には、音質やジャンルの情報が入っていない。
     * その場合は true になり、次の読み込みで全曲を読み直す。
     */
    var needsFullRescan = false
        private set

    fun loadCache(): List<LibTrack> {
        val (list, full) = LibCore.loadCache(cacheFile) { docKey(it) }
        needsFullRescan = full
        return list
    }

    fun saveCache(list: List<LibTrack>) {
        LibCore.saveCache(cacheFile, list)
        needsFullRescan = false
    }

    /** 曲情報の読み込み(保存場所の URI から開く) */
    private val tags = TagScan(ctx, { StorageAccess.open(ctx, it) }, { StorageAccess.fileOf(it) })

    private fun readTags(uri: Uri, name: String, folder: String, cover: String?, mod: Long): LibTrack =
        tags.readTags(uri, name, folder, cover, mod)

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
                    val name = names[key] ?: ""
                    // 読み込みで例外が出ても、曲が一覧から消えないよう名前だけの曲にする
                    read[key] = try {
                        readTags(uri, name, folder, covers[folder], mod)
                    } catch (e: Exception) {
                        val base = name.substringBeforeLast('.')
                        LibTrack(key, base, UNKNOWN_ARTIST, UNKNOWN_ALBUM, "", base.take(2).toIntOrNull() ?: 0, 1, 0L, folder, covers[folder], mod)
                    }
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
                                title = ct.title ?: S(R.string.track_2).format(ct.number),
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
            // (computeIfAbsent は Android 7.0 からなので使わない)
            val list = cues[docId] ?: java.util.Collections.synchronizedList(ArrayList<String>()).let { cues.putIfAbsent(docId, it) ?: it }
            list.add(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString())
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
                    if (lower.substringAfterLast('.', "") in LIB_AUDIO_EXT) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        found.add(Triple(uri, docId, f.lastModified()))
                        names[uri.toString()] = name
                    } else if (lower in LIB_COVER_NAMES) {
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
                    if (lower.substringAfterLast('.', "") in LIB_AUDIO_EXT) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        found.add(Triple(uri, docId, mod))
                        names[uri.toString()] = name
                    } else if (lower in LIB_COVER_NAMES) {
                        covers[docId] = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                    } else if (lower.endsWith(".cue")) {
                        addCue(id)
                    }
                }
            }
        }
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

        /** 一覧の形式の版 */
        const val CACHE_VERSION = LibCore.CACHE_VERSION

        /** 曲をアルバムにまとめる(LibCore.group) */
        fun group(tracks: List<LibTrack>): List<LibAlbum> = LibCore.group(tracks)
    }
}

/**
 * ジャケット画像の読み込み(埋め込み画像 → フォルダの cover.jpg などの順)。
 *
 * 表示する大きさに合わせて読む(一覧の小さな画像は小さく、再生画面は元の大きさで)。
 * 一覧用の小さな画像は端末に保存しておき、次からは曲のファイルを開かずに読む(性能の低い端末でも軽いように)
 */
object ArtLoader {
    /** 読み込む大きさの段階(px)。表示する大きさ以上で、いちばん近いものを使う。最後は再生画面用(元の大きさまで) */
    private val BUCKETS = intArrayOf(128, 256, 512, 2048)
    /** 端末に保存する大きさの上限(これより大きい画像は保存しない) */
    private const val DISK_MAX = 512

    /** 画像の置き場所(メモリーの量で上限を決める) */
    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(8L shl 20, 96L shl 20).toInt(),
    ) {
        // 一覧用の小さな画像は 1 画素 2 バイト(RGB_565)なので、実際の大きさで数える(4 バイトで数えると半分しか置けない)
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            runCatching { value.asAndroidBitmap().byteCount }.getOrDefault(value.width * value.height * 4)
    }

    /**
     * 再生画面用(いちばん大きい段階)で読む大きさの上限(px)。画面の長い辺より大きく読んでも見た目は変わらず、
     * メモリーを大きく使う(3000px の画像は 36MB)ので、画面の大きさまでにする
     */
    @Volatile private var bigMax = 2048

    private fun target(b: Int): Int = if (b > DISK_MAX) minOf(b, bigMax) else b
    /** 同時に読み込む数を絞る(一覧を速くスクロールしたときに、再生の読み込みを邪魔しないように) */
    private val gate = java.util.concurrent.Semaphore(3)
    /** 画像が無いと分かった曲・アルバム(読み込みに失敗しただけのものは入れない) */
    private val misses = HashSet<String>()
    private var diskDir: File? = null

    /** 画像が差し替わったことを画面に知らせるための番号(増えると表示中の画像を読み直す) */
    var generation by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun bucketOf(px: Int): Int = BUCKETS.firstOrNull { it >= px } ?: BUCKETS.last()

    /** 読み込み済みの画像(求める大きさ以上のものがあればそれ、なければ小さいものでもよい) */
    fun peek(key: String?, px: Int = BUCKETS.last()): ImageBitmap? {
        if (key == null) return null
        val b = bucketOf(px)
        for (x in BUCKETS) if (x >= b) cache.get("$key@$x")?.let { return it }
        for (x in BUCKETS.reversed()) if (x < b) cache.get("$key@$x")?.let { return it }
        return null
    }

    /**
     * fallbackUri: 曲ごとのジャケットを読むとき、その曲に画像が埋め込まれていなければ代わりに使う曲(アルバムの最初の曲)。
     * フォルダの画像より先に試す
     */
    fun load(ctx: Context, key: String, trackUri: String?, coverUri: String?, size: Int = BUCKETS.last(), fallbackUri: String? = null): ImageBitmap? {
        val b = bucketOf(size)
        for (x in BUCKETS) if (x >= b) cache.get("$key@$x")?.let { return it }
        synchronized(misses) { if (key in misses) return null }
        // 再生画面の大きな画像だけを読んだあとでも、forget で保存した小さな画像を消せるように
        if (diskDir == null) {
            diskDir = File(ctx.cacheDir, "art").also { it.mkdirs() }
            val dm = ctx.resources.displayMetrics
            bigMax = maxOf(dm.widthPixels, dm.heightPixels).coerceIn(DISK_MAX * 2, 2048)
        }
        gate.acquire()
        try {
            cache.get("$key@$b")?.let { return it }
            return loadNow(ctx, key, trackUri, coverUri, b, fallbackUri)
        } finally {
            gate.release()
        }
    }

    private fun diskFile(ctx: Context, key: String, b: Int): File {
        val dir = diskDir ?: File(ctx.cacheDir, "art").also { it.mkdirs(); diskDir = it }
        val h = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "${h}_$b.jpg")
    }

    private fun loadNow(ctx: Context, key: String, trackUri0: String?, coverUri: String?, b: Int, fallbackUri: String? = null): ImageBitmap? {
        // 一覧用の小さな画像は、保存したものがあればそれを使う
        if (b <= DISK_MAX) {
            val f = diskFile(ctx, key, b)
            if (f.exists()) {
                val bmp = runCatching { BitmapFactory.decodeFile(f.path, thumbOptions()) }.getOrNull()
                if (bmp != null) return bmp.asImageBitmap().also { cache.put("$key@$b", it) }
                f.delete()
            }
        }
        var failed = false
        val bytes = try {
            sourceBytes(ctx, trackUri0, null) ?: fallbackUri?.takeIf { it != trackUri0 }?.let { sourceBytes(ctx, it, null) }
                ?: sourceBytes(ctx, null, coverUri)
        } catch (e: Exception) {
            failed = true
            null
        } catch (e: OutOfMemoryError) {
            // とても大きな画像ファイル(フォルダの画像など)
            failed = true
            null
        }
        val bmp = bytes?.let { runCatching { decode(it, target(b)) }.getOrNull() }
        if (bmp == null) {
            // 画像が無いと分かったときだけ覚える(読み込みに失敗しただけなら、次にまた試す)
            if (!failed && bytes == null) synchronized(misses) { misses.add(key) }
            return null
        }
        // 小さな画像は保存しておく
        if (b <= DISK_MAX) {
            runCatching {
                val f = diskFile(ctx, key, b)
                // 同じ画像を同時に読んだときに書き込みが混ざらないよう、スレッドごとに別の一時ファイルにする
                val tmp = File(f.path + "." + Thread.currentThread().id + ".tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                if (!tmp.renameTo(f)) tmp.delete()
            }
        }
        val img = bmp.asImageBitmap()
        cache.put("$key@$b", img)
        return img
    }

    private fun thumbOptions() = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

    /** 元の画像のバイト列(縮めていないもの。MusicBrainz にジャケットを登録するときに使う)。無い・読めなければ null */
    fun originalBytes(ctx: Context, trackUri: String?, coverUri: String?): ByteArray? =
        runCatching { sourceBytes(ctx, trackUri, coverUri) }.getOrNull()

    /** 画像のバイト列(曲に埋め込まれたもの → フォルダの画像)。無ければ null。読めなければ例外 */
    private fun sourceBytes(ctx: Context, trackUri0: String?, coverUri: String?): ByteArray? = ArtSource.bytes(ctx, trackUri0, coverUri)

    fun forget(key: String) {
        for (x in BUCKETS) cache.remove("$key@$x")
        // 保存した小さな画像も消す(曲情報の編集でジャケットを変えたとき)
        diskDir?.let { for (x in BUCKETS) if (x <= DISK_MAX) runCatching { File(it, diskFile0(key, x)).delete() } }
        synchronized(misses) { misses.remove(key) }
        generation++
    }

    fun clearMisses() {
        synchronized(misses) { misses.clear() }
        generation++
    }

    private fun diskFile0(key: String, b: Int): String {
        val h = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return "${h}_$b.jpg"
    }

    /**
     * 画像を size(px)前後の大きさで読む。大きな画像は、まず 2 のべき乗で間引いて読み、それでも大きければ縮める。
     * 小さな画像(一覧用)は、色数を減らしてメモリーを半分にする
     */
    fun decode(b: ByteArray, size: Int): Bitmap? = ArtSource.decode(b, size, size <= DISK_MAX)
}
