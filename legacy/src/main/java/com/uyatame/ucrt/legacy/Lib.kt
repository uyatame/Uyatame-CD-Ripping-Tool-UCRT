package com.uyatame.ucrt.legacy

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.uyatame.ucrt.FileUris
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.library.Cue
import com.uyatame.ucrt.library.LIB_AUDIO_EXT
import com.uyatame.ucrt.library.LIB_COVER_NAMES
import com.uyatame.ucrt.library.LibAlbum
import com.uyatame.ucrt.library.LibCore
import com.uyatame.ucrt.library.LibTrack
import com.uyatame.ucrt.library.TagScan
import com.uyatame.ucrt.library.UNKNOWN_ALBUM
import com.uyatame.ucrt.library.UNKNOWN_ARTIST
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * ライブラリ(本家の Library と MainViewModel のライブラリの部分)。
 * 曲はフォルダの場所(ファイル)から読む。曲の URI は file:。一覧は本家と同じ形式で保存する
 */
class Lib(private val ctx: Context, private val prefs: Prefs) {
    private val cacheFile = File(ctx.filesDir, "library.json")
    private val tags = TagScan(ctx, { ctx.contentResolver.openInputStream(it) }, { FileUris.fileOf(it) })

    @Volatile var albums: List<LibAlbum> = emptyList()
        private set
    @Volatile private var all: List<LibTrack> = emptyList()

    private class Index(val tracks: Map<String, LibTrack>, val albumOf: Map<String, String>, val albums: Map<String, LibAlbum>)
    @Volatile private var index = Index(emptyMap(), emptyMap(), emptyMap())

    val trackMap: Map<String, LibTrack> get() = index.tracks
    fun albumKeyOf(uri: String?): String? = uri?.let { index.albumOf[it] }
    fun albumByKey(key: String?): LibAlbum? = key?.let { index.albums[it] }

    // ---- 読み込みの状態 ----
    @Volatile var scanning = false; private set
    /** 全曲を読み直している */
    @Volatile var full = false; private set
    /** 新しく読む曲の数 */
    @Volatile var newCount = 0; private set
    @Volatile var progress = ""; private set
    /** 最初の一覧を読み込み終えた */
    @Volatile var loaded = false; private set
    /** 読めなかったフォルダ(許可が無いなど) */
    @Volatile var unreadable: List<String> = emptyList(); private set

    /** ライブラリに入れるフォルダ(取り込みの保存先と、追加したフォルダ) */
    fun folders(): List<String> = (listOfNotNull(prefs.outputDir) + prefs.libraryFolders.sorted()).distinct()

    private fun publish(list: List<LibTrack>) {
        all = list
        val g = LibCore.group(list)
        val t = HashMap<String, LibTrack>()
        val k = HashMap<String, String>()
        val a = HashMap<String, LibAlbum>()
        for (al in g) {
            a[al.key] = al
            for (tr in al.tracks) { t[tr.uri] = tr; k[tr.uri] = al.key }
        }
        index = Index(t, k, a)
        albums = g
        Bus.emit(Bus.LIBRARY)
    }

    /** 起動したとき: 保存した一覧を出してから、新しい曲を探す */
    fun start() {
        Thread({
            val (list, needFull) = LibCore.loadCache(cacheFile) { it }
            if (list.isNotEmpty()) publish(list)
            loaded = true
            Bus.emit(Bus.LIBRARY)
            refreshNow(needFull)
        }, "ucrt-lib").start()
    }

    /** 読み直す(fresh なら全曲のタグを読み直す) */
    fun refresh(fresh: Boolean) {
        if (scanning) return
        Thread({ refreshNow(fresh) }, "ucrt-lib").start()
    }

    @Synchronized
    private fun refreshNow(fresh: Boolean) {
        scanning = true
        full = fresh
        newCount = 0
        progress = ""
        Bus.emit(Bus.SCAN)
        try {
            val roots = folders()
            val bad = ArrayList<String>()
            val list = scan(roots.map { File(it) }, if (fresh) emptyList() else all, bad)
            unreadable = bad
            LibCore.saveCache(cacheFile, list)
            publish(list)
        } catch (e: Throwable) {
            progress = e.message ?: e.javaClass.simpleName
        } finally {
            scanning = false
            full = false
            Bus.emit(Bus.SCAN or Bus.LIBRARY)
        }
    }

    private class Found(val file: File, val folder: String, val mod: Long)

    private fun scan(roots: List<File>, old: List<LibTrack>, bad: MutableList<String>): List<LibTrack> {
        val n = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val pool = Executors.newFixedThreadPool(n) { r ->
            Thread {
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                r.run()
            }.apply { isDaemon = true }
        }
        try {
            val found = ArrayList<Found>()
            val covers = HashMap<String, String>()
            val cues = HashMap<String, MutableList<File>>()
            val seen = HashSet<String>()
            for (root in roots) {
                if (!root.isDirectory || root.listFiles() == null) { bad.add(root.path); continue }
                // フォルダを順にたどる(深さ 8 まで)
                var level = listOf(root)
                var depth = 0
                while (level.isNotEmpty() && depth <= 8) {
                    val next = ArrayList<File>()
                    for (dir in level) {
                        val canon = runCatching { dir.canonicalPath }.getOrDefault(dir.path)
                        if (!seen.add(canon)) continue
                        val files = dir.listFiles() ?: continue
                        val fid = folderIdOf(dir)
                        for (f in files) {
                            val name = f.name
                            if (name.startsWith(".")) continue
                            if (f.isDirectory) { next.add(f); continue }
                            val lower = name.lowercase()
                            when {
                                lower.substringAfterLast('.', "") in LIB_AUDIO_EXT -> found.add(Found(f, fid, f.lastModified()))
                                lower in LIB_COVER_NAMES -> covers[fid] = Uri.fromFile(f).toString()
                                lower.endsWith(".cue") -> cues.getOrPut(fid) { ArrayList() }.add(f)
                            }
                        }
                    }
                    level = next
                    depth++
                }
            }
            val oldMap = old.associateBy { it.uri }
            val need = found.filter { f -> oldMap[Uri.fromFile(f.file).toString()]?.modified != f.mod }
            newCount = need.size
            Bus.emit(Bus.SCAN)
            val done = AtomicInteger(0)
            val read = ConcurrentHashMap<String, LibTrack>()
            pool.invokeAll(need.map { f ->
                Callable {
                    val uri = Uri.fromFile(f.file)
                    val key = uri.toString()
                    val name = f.file.name
                    read[key] = try {
                        tags.readTags(uri, name, f.folder, covers[f.folder], f.mod)
                    } catch (e: Exception) {
                        val base = name.substringBeforeLast('.')
                        LibTrack(key, base, UNKNOWN_ARTIST, UNKNOWN_ALBUM, "", base.take(2).toIntOrNull() ?: 0, 1, 0L, f.folder, covers[f.folder], f.mod)
                    }
                    val c = done.incrementAndGet()
                    if (c == need.size || c % 5 == 0) {
                        progress = "$c / ${need.size}"
                        Bus.emit(Bus.SCAN)
                    }
                    Unit
                }
            })
            val tracks = found.mapNotNull { f ->
                val key = Uri.fromFile(f.file).toString()
                read[key] ?: oldMap[key]?.copy(folderId = f.folder, coverUri = covers[f.folder], hidden = false)
            }
            return if (cues.isEmpty()) tracks else applyCues(tracks, cues)
        } finally {
            pool.shutdownNow()
        }
    }

    /** CUE シートがあるフォルダでは、CUE シートが指す音声ファイルを曲ごとに分ける(本家と同じ) */
    private fun applyCues(tracks: List<LibTrack>, cues: Map<String, List<File>>): List<LibTrack> {
        val byFolder = tracks.groupBy { it.folderId }
        val hide = HashSet<String>()
        val added = ArrayList<LibTrack>()
        fun nameOf(t: LibTrack) = Uri.parse(t.uri).lastPathSegment.orEmpty()
        for ((folder, sheets) in cues) {
            val inFolder = byFolder[folder] ?: continue
            for (cueFile in sheets) {
                val sheet = runCatching { Cue.parse(Cue.decode(cueFile.readBytes())) }.getOrNull() ?: continue
                val cueUri = Uri.fromFile(cueFile).toString()
                for (cf in sheet.files) {
                    val want = cf.name.substringAfterLast('/').substringAfterLast('\\')
                    val base = inFolder.firstOrNull { nameOf(it).equals(want, ignoreCase = true) }
                        ?: inFolder.firstOrNull { nameOf(it).substringBeforeLast('.').equals(want.substringBeforeLast('.'), ignoreCase = true) }
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

    /** 曲を一覧から外す(ファイルを消したあと) */
    fun removeTracks(uris: Collection<String>) {
        val drop = uris.toHashSet()
        val list = all.filter { it.uri !in drop && (it.sheet == null || Cue.fileUri(it.uri) !in drop) }
        Thread({ LibCore.saveCache(cacheFile, list) }, "ucrt-lib-save").start()
        publish(list)
    }

    companion object {
        /**
         * フォルダの ID(本家の保存場所の ID と同じ形。"primary:Music/Artist" のように、保存場所の名前とその中の道筋)。
         * フォルダ別の一覧で、保存場所の中の道筋を出すのに使う
         */
        fun folderIdOf(dir: File): String {
            val p = dir.absolutePath
            val ext = Environment.getExternalStorageDirectory().absolutePath
            if (p == ext || p.startsWith("$ext/")) return "primary:" + p.removePrefix(ext).trimStart('/')
            val parts = p.trimStart('/').split('/')
            if (parts.size >= 2 && (parts[0] == "storage" || parts[0] == "mnt")) return parts[1] + ":" + parts.drop(2).joinToString("/")
            return "root:" + p.trimStart('/')
        }

        /** 曲の入っているフォルダの道筋(例: Music/Artist/Album) */
        fun trackFolder(t: LibTrack): String = Uri.decode(t.folderId).substringAfter(':', "")

        /** 曲のファイル(CUE シートで分けた曲は元のファイル) */
        fun fileOf(uri: String): File? = Uri.parse(Cue.fileUri(uri)).path?.let { File(it) }
    }
}
