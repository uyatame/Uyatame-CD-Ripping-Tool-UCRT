package com.uyatame.ucrt.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** プレイリスト(曲はファイルの URI で持つ) */
data class Playlist(val id: String, val name: String, val uris: List<String>)

/** 履歴 1 件(最後に再生した時刻) */
data class PlayRecord(val uri: String, val time: Long)

/** 前回の再生状態(アプリを開き直したときに続きから再生するため) */
class ResumeState(
    val uris: List<String>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeat: Int,
    /** シャッフル前の並び(シャッフルしていなければ uris と同じ) */
    val original: List<String> = uris,
)

/**
 * お気に入り・プレイリスト・再生履歴・前回の再生状態を端末に保存する。
 * 画面から直接見られるよう、値は Compose の状態として持つ(書き換えはメインスレッドから)。
 */
class PlayerStore(ctx: Context) {
    private val file = File(ctx.filesDir, "player_store.json")
    private val resumeFile = File(ctx.filesDir, "player_resume.json")
    /** 書き込みは 1 本のスレッドで順番に行う */
    private val writer = Executors.newSingleThreadExecutor()

    /** お気に入りの曲(新しく追加したものが先頭) */
    var favTracks by mutableStateOf<List<String>>(emptyList())
        private set
    var favTrackSet by mutableStateOf<Set<String>>(emptySet())
        private set
    /** お気に入りのアルバム(LibAlbum.key) */
    var favAlbums by mutableStateOf<List<String>>(emptyList())
        private set
    var favAlbumSet by mutableStateOf<Set<String>>(emptySet())
        private set
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set
    /** 再生履歴(新しいものが先頭。同じ曲は 1 件にまとめる) */
    var history by mutableStateOf<List<PlayRecord>>(emptyList())
        private set
    /** 曲ごとの再生回数 */
    var counts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    suspend fun load() {
        val o = withContext(Dispatchers.IO) {
            runCatching { if (file.exists()) JSONObject(file.readText()) else null }.getOrNull()
        } ?: return
        fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
        favTracks = strings(o.optJSONArray("favTracks"))
        favTrackSet = favTracks.toHashSet()
        favAlbums = strings(o.optJSONArray("favAlbums"))
        favAlbumSet = favAlbums.toHashSet()
        playlists = o.optJSONArray("playlists")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val p = a.optJSONObject(i) ?: return@mapNotNull null
                Playlist(p.optString("id"), p.optString("name"), strings(p.optJSONArray("uris")))
            }
        } ?: emptyList()
        history = o.optJSONArray("history")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val h = a.optJSONObject(i) ?: return@mapNotNull null
                PlayRecord(h.optString("u"), h.optLong("t"))
            }
        } ?: emptyList()
        counts = o.optJSONObject("counts")?.let { c ->
            val m = HashMap<String, Int>()
            c.keys().forEach { k -> m[k] = c.optInt(k) }
            m
        } ?: emptyMap()
    }

    private fun save() {
        val o = JSONObject()
        o.put("favTracks", JSONArray(favTracks))
        o.put("favAlbums", JSONArray(favAlbums))
        o.put("playlists", JSONArray().apply {
            playlists.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("uris", JSONArray(p.uris))) }
        })
        o.put("history", JSONArray().apply { history.forEach { h -> put(JSONObject().put("u", h.uri).put("t", h.time)) } })
        o.put("counts", JSONObject().apply { counts.forEach { (k, v) -> put(k, v) } })
        val text = o.toString()
        writer.execute { writeAtomic(file, text) }
    }

    private fun writeAtomic(f: File, text: String) {
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
        }
    }

    // ---------- お気に入り ----------

    fun isFav(uri: String?) = uri != null && uri in favTrackSet
    fun isFavAlbum(key: String?) = key != null && key in favAlbumSet

    fun setFav(uris: List<String>, on: Boolean) {
        favTracks = if (on) uris.filter { it !in favTrackSet }.reversed() + favTracks else favTracks.filter { it !in uris }
        favTrackSet = favTracks.toHashSet()
        save()
    }

    fun toggleFav(uri: String) = setFav(listOf(uri), !isFav(uri))

    fun toggleFavAlbum(key: String) {
        favAlbums = if (isFavAlbum(key)) favAlbums - key else listOf(key) + favAlbums
        favAlbumSet = favAlbums.toHashSet()
        save()
    }

    fun moveFav(from: Int, to: Int) {
        if (from !in favTracks.indices || to !in favTracks.indices) return
        favTracks = favTracks.toMutableList().apply { add(to, removeAt(from)) }
        save()
    }

    // ---------- プレイリスト ----------

    fun createPlaylist(name: String, uris: List<String>): String {
        val id = System.currentTimeMillis().toString(36) + (0..999).random()
        playlists = playlists + Playlist(id, name.trim().ifEmpty { "Playlist" }, uris)
        save()
        return id
    }

    fun renamePlaylist(id: String, name: String) {
        playlists = playlists.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it }
        save()
    }

    fun deletePlaylist(id: String) {
        playlists = playlists.filter { it.id != id }
        save()
    }

    fun addToPlaylist(id: String, uris: List<String>) {
        playlists = playlists.map { if (it.id == id) it.copy(uris = it.uris + uris) else it }
        save()
    }

    fun removeFromPlaylist(id: String, index: Int) {
        playlists = playlists.map { p ->
            if (p.id == id && index in p.uris.indices) p.copy(uris = p.uris.toMutableList().apply { removeAt(index) }) else p
        }
        save()
    }

    fun movePlaylistItem(id: String, from: Int, to: Int) {
        playlists = playlists.map { p ->
            if (p.id == id && from in p.uris.indices && to in p.uris.indices) {
                p.copy(uris = p.uris.toMutableList().apply { add(to, removeAt(from)) })
            } else p
        }
        save()
    }

    // ---------- 履歴 ----------

    fun recordPlay(uri: String) {
        history = (listOf(PlayRecord(uri, System.currentTimeMillis())) + history.filter { it.uri != uri }).take(HISTORY_MAX)
        counts = counts + (uri to (counts[uri] ?: 0) + 1)
        save()
    }

    fun clearHistory() {
        history = emptyList()
        counts = emptyMap()
        save()
    }

    // ---------- 前回の再生状態 ----------

    fun saveResume(r: ResumeState?) {
        if (r == null) {
            writer.execute { runCatching { resumeFile.delete() } }
            return
        }
        val o = JSONObject()
            .put("uris", JSONArray(r.uris))
            .put("index", r.index)
            .put("pos", r.positionMs)
            .put("shuffle", r.shuffle)
            .put("repeat", r.repeat)
            .put("orig", JSONArray(r.original))
        val text = o.toString()
        writer.execute { writeAtomic(resumeFile, text) }
    }

    suspend fun loadResume(): ResumeState? = withContext(Dispatchers.IO) {
        runCatching {
            if (!resumeFile.exists()) return@runCatching null
            val o = JSONObject(resumeFile.readText())
            val a = o.optJSONArray("uris") ?: return@runCatching null
            val uris = (0 until a.length()).map { a.optString(it) }
            val orig = o.optJSONArray("orig")?.let { b -> (0 until b.length()).map { b.optString(it) } } ?: uris
            ResumeState(uris, o.optInt("index"), o.optLong("pos"), o.optBoolean("shuffle"), o.optInt("repeat"), orig)
        }.getOrNull()
    }

    companion object {
        const val HISTORY_MAX = 300
    }
}
