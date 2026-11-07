package com.uyatame.ucrt.network

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.uyatame.ucrt.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * ネットワーク上の場所(NAS など)を、端末のファイルと同じ「ドキュメント」として見せる提供元。
 * こうすると、ライブラリの読み込み・再生・ジャケット・タグの読み取りが、今のままネットワーク上のファイルに使える。
 *
 * ドキュメント ID は「接続先の ID:パス」(例: "k3x9a:/FLAC/Album/01.flac")。読むだけで、書き込みはできない。
 */
class NetworkProvider : DocumentsProvider() {
    private val lists = HashMap<String, Pair<Long, List<NetEntry>>>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    override fun onCreate(): Boolean {
        // 前回の途中で終わった取り込みの残りを消す
        runCatching { File(context?.cacheDir, "net-cache").listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() } }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_COLS)
        val ctx = context ?: return c
        for (s in NetSourceStore.all(ctx)) {
            c.newRow()
                .add(Root.COLUMN_ROOT_ID, s.id)
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                .add(Root.COLUMN_TITLE, s.name.ifEmpty { s.host })
                .add(Root.COLUMN_SUMMARY, s.summary())
                .add(Root.COLUMN_DOCUMENT_ID, docId(s.id, "/"))
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLS)
        val (id, path) = parse(documentId)
        if (path == "/") { addDir(c, documentId, rootName(id)); return c }
        val e = try { cachedEntry(id, path) } catch (ex: IOException) { throw FileNotFoundException(ex.message) }
            ?: throw FileNotFoundException(documentId)
        addEntry(c, id, e)
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = ErrorCursor(projection ?: DOC_COLS)
        val (id, path) = parse(parentDocumentId)
        try {
            for (e in listCached(id, path)) addEntry(c, id, e)
        } catch (ex: Exception) {
            NetHub.invalidate(id)
            c.error = ex.message ?: ex.javaClass.simpleName
            com.uyatame.ucrt.player.AudioEngine.log("network: list $parentDocumentId failed: ${ex.javaClass.simpleName}: ${ex.message}")
        }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (pi, pp) = parse(parentDocumentId)
        val (ci, cp) = parse(documentId)
        return pi == ci && (pp == "/" || cp == pp || cp.startsWith("$pp/"))
    }

    /**
     * 読むときは、いったん端末のキャッシュに全部取り込んで、ふつうのファイルとして渡す。
     * ネットワーク待ちの読み込みを、OS の代理ファイル記述子(ProxyFileDescriptor)越しにメディアの処理へ渡すと、
     * 読み込みが遅れたときにメディアの処理ごと固まり、端末が落ちることがあったため(実ファイルなら、遅れてもほかに響かない)。
     */
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode.any { it == 'w' || it == 'a' || it == 't' }) throw FileNotFoundException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_read_only))
        val ctx = context ?: throw FileNotFoundException()
        val (id, path) = parse(documentId)
        val fs = try { NetHub.fs(ctx, id) } catch (e: Exception) { throw FileNotFoundException(e.message) }
        val entry = try { cachedEntry(id, path) } catch (e: Exception) { NetHub.invalidate(id); throw FileNotFoundException(e.message) }
            ?: throw FileNotFoundException(documentId)
        if (entry.isDir) throw FileNotFoundException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_is_folder))
        val dir = File(ctx.cacheDir, "net-cache").apply { mkdirs() }
        val key = java.security.MessageDigest.getInstance("SHA-1").digest(documentId.toByteArray()).joinToString("") { "%02x".format(it) }
        val ext = path.substringAfterLast('.', "").filter { it.isLetterOrDigit() }.take(8)
        val file = File(dir, "$key-${entry.size}" + if (ext.isNotEmpty()) ".$ext" else "")
        val lock = locks.getOrPut(key) { Any() }
        synchronized(lock) {
            if (!(file.exists() && file.length() == entry.size)) {
                makeRoom(dir, entry.size)
                val part = File(dir, "$key.part")
                try {
                    fs.open(path).use { nf ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(1 shl 20)
                            var pos = 0L
                            while (pos < entry.size) {
                                if (signal?.isCanceled == true) throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_canceled))
                                val n = nf.read(pos, buf, 0, buf.size)
                                if (n <= 0) throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_read_cut))
                                out.write(buf, 0, n)
                                pos += n
                            }
                        }
                    }
                    file.delete()
                    if (!part.renameTo(file)) throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_cache_failed))
                } catch (e: Exception) {
                    part.delete()
                    NetHub.invalidate(id)
                    com.uyatame.ucrt.player.AudioEngine.log("network: open $documentId failed: ${e.javaClass.simpleName}: ${e.message}")
                    throw FileNotFoundException(e.message)
                }
            }
            file.setLastModified(System.currentTimeMillis())
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** キャッシュが上限(2GB)や空き容量を超えないよう、古いものから消す */
    private fun makeRoom(dir: File, need: Long) {
        val files = (dir.listFiles() ?: return).sortedBy { it.lastModified() }.toMutableList()
        var total = files.sumOf { it.length() }
        val limit = 2L shl 30
        if (need > limit) throw FileNotFoundException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_too_large))
        val now = System.currentTimeMillis()
        for (f in files) {
            if (total + need <= limit && dir.usableSpace > need + (256L shl 20)) break
            // ほかの曲を取り込んでいる途中のもの(.part)は消さない(取り込みが失敗してしまう)
            if (f.name.endsWith(".part") && now - f.lastModified() < 10 * 60_000L) continue
            total -= f.length()
            f.delete()
        }
        if (dir.usableSpace < need + (64L shl 20)) throw FileNotFoundException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_no_space))
    }

    // ---- 一覧の保存(短い間だけ。同じフォルダを何度も取りに行かないため) ----

    private fun listCached(id: String, path: String): List<NetEntry> {
        val key = "$id:$path"
        val now = System.currentTimeMillis()
        synchronized(lists) {
            lists[key]?.let { if (now - it.first < TTL) return it.second }
        }
        val ctx = context ?: throw IOException("no context")
        val l = NetHub.fs(ctx, id).list(path).sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        synchronized(lists) {
            if (lists.size > 200) lists.clear()
            lists[key] = now to l
        }
        return l
    }

    private fun cachedEntry(id: String, path: String): NetEntry? {
        val parent = NetPaths.parent(path)
        val hit = synchronized(lists) { lists["$id:$parent"]?.takeIf { System.currentTimeMillis() - it.first < TTL }?.second }
        hit?.firstOrNull { it.path == path }?.let { return it }
        val ctx = context ?: return null
        return NetHub.fs(ctx, id).stat(path)
    }

    private fun rootName(id: String): String =
        context?.let { c -> NetSourceStore.get(c, id)?.let { it.name.ifEmpty { it.host } } } ?: id

    private fun addDir(c: MatrixCursor, docId: String, name: String) {
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, docId).add(Document.COLUMN_DISPLAY_NAME, name)
            .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR).add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, null).add(Document.COLUMN_LAST_MODIFIED, null)
    }

    private fun addEntry(c: MatrixCursor, id: String, e: NetEntry) {
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, docId(id, e.path)).add(Document.COLUMN_DISPLAY_NAME, e.name)
            .add(Document.COLUMN_MIME_TYPE, if (e.isDir) Document.MIME_TYPE_DIR else mimeOf(e.name)).add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, if (e.isDir) null else e.size).add(Document.COLUMN_LAST_MODIFIED, if (e.modified > 0) e.modified else null)
    }

    /** 失敗の理由を、システムの画面に伝えるための入れ物(エラーでもアプリを落とさず、空の一覧と理由を返す) */
    private class ErrorCursor(cols: Array<out String>) : MatrixCursor(cols) {
        var error: String? = null
        override fun getExtras(): Bundle = Bundle().apply { error?.let { putString(DocumentsContract.EXTRA_ERROR, it) } }
    }

    companion object {
        private const val TTL = 30_000L
        private val ROOT_COLS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID,
        )
        private val DOC_COLS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )

        fun authority(ctx: android.content.Context) = ctx.packageName + ".network"

        fun docId(sourceId: String, path: String) = sourceId + ":" + NetPaths.norm(path)

        /** ドキュメント ID → (接続先の ID, パス) */
        fun parse(docId: String): Pair<String, String> {
            val i = docId.indexOf(':')
            return if (i < 0) docId to "/" else docId.substring(0, i) to NetPaths.norm(docId.substring(i + 1))
        }

        /** その接続先の一番上を、ライブラリのフォルダとして登録するときの URI */
        fun treeUri(ctx: android.content.Context, sourceId: String, path: String = "/"): android.net.Uri =
            DocumentsContract.buildTreeDocumentUri(authority(ctx), docId(sourceId, path))

        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "flac" -> "audio/flac"
                "dsf" -> "audio/x-dsf"
                "dff" -> "audio/x-dff"
                "wv" -> "audio/x-wavpack"
                "ape" -> "audio/x-ape"
                "m4a" -> "audio/mp4"
                "lrc" -> "application/octet-stream"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            }
        }
    }
}
