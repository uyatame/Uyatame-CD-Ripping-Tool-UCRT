package com.uyatame.ucrt.legacy

import android.net.Uri
import android.os.Build
import android.os.Environment
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.library.PlaylistFiles
import java.io.File

/**
 * プレイリストのファイルの読み込み・書き出し(本家の importPlaylists・exportPlaylist と同じ)。
 * ほかのアプリの M3U・M3U8・PLS・XSPF・WPL を読み、ファイル名とフォルダ名(見つからなければ曲名とアーティスト)でライブラリの曲に結び付ける
 */
object PlaylistIO {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** ファイルの名前(分からなければ場所の最後) */
    private fun nameOf(act: LegacyActivity, u: Uri): String {
        if (u.scheme == "content") {
            runCatching {
                act.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.let { c ->
                    try { if (c.moveToFirst()) return c.getString(0) ?: "" } finally { c.close() }
                }
            }
        }
        return u.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    fun import(act: LegacyActivity, uris: List<Uri>) {
        val app = act.app
        Thread({
            val all = app.lib.albums.flatMap { it.tracks }
            val made = ArrayList<Pair<String, List<String>>>()
            var missing = 0
            for (u in uris) {
                runCatching {
                    val name = nameOf(act, u)
                    // プレイリストは小さいので、4MB を超えるものは読まない(間違えて選んだ大きなファイル)
                    val bytes = act.contentResolver.openInputStream(u)?.use { ins ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(1 shl 14)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > 4 shl 20) error("too large")
                        }
                        out.toByteArray()
                    } ?: return@runCatching
                    // 文字コードは UTF-8、だめなら Shift_JIS
                    val text = runCatching {
                        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    }.getOrElse { String(bytes, charset("windows-31j")) }
                    val p = PlaylistFiles.parse(text, name.substringAfterLast('.', ""))
                    val (found, miss) = PlaylistFiles.match(p.entries, all)
                    missing += miss
                    if (found.isNotEmpty()) made.add((p.name ?: name.substringBeforeLast('.')) to found)
                }.onFailure { AppLog.log("playlist import: ${it.message}") }
            }
            main.post {
                made.forEach { (n, list) -> app.store.createPlaylist(n, list) }
                Bus.emit(Bus.STORE)
                act.snack(
                    if (made.isEmpty()) S(R.string.playlist_import_none)
                    else S(R.string.playlist_imported, made.size, made.sumOf { it.second.size }) +
                        (if (missing > 0) S(R.string.playlist_import_missing, missing) else ""),
                )
            }
        }, "ucrt-playlist-import").start()
    }

    /**
     * M3U8 で書き出す(曲の場所はファイルの絶対パス)。Android 4.4 からは保存先を選んでもらい、
     * それより前は「Music」フォルダに書く
     */
    fun export(act: LegacyActivity, id: String) {
        val app = act.app
        val p = app.store.playlists.firstOrNull { it.id == id } ?: return
        val map = app.lib.trackMap
        val text = PlaylistFiles.toM3u8(p.name, p.uris.mapNotNull { map[it] }) { u ->
            Uri.parse(u).takeIf { it.scheme == "file" }?.path
        }
        val file = p.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "playlist" } + ".m3u8"
        fun done(r: Result<Unit>) = main.post {
            act.snack(r.fold({ S(R.string.playlist_exported, p.name) }, { S(R.string.could_not_save, it.message) }))
        }
        if (Build.VERSION.SDK_INT >= 19) {
            act.createFile(file, "audio/x-mpegurl") { to ->
                Thread({
                    done(runCatching { act.contentResolver.openOutputStream(to, "wt")?.use { it.write(text.toByteArray()) } ?: error("open") })
                }, "ucrt-playlist-export").start()
            }
        } else {
            Thread({
                done(runCatching {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    dir.mkdirs()
                    File(dir, file).writeBytes(text.toByteArray())
                })
            }, "ucrt-playlist-export").start()
        }
    }
}
