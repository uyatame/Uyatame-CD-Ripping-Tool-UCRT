package com.uyatame.ucrt.legacy

import android.content.Context
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
    val original: List<String> = uris,
)

/**
 * お気に入り・音質チェック・プレイリスト・再生履歴・前回の再生状態を端末に保存する(本家の PlayerStore と同じ形式)。
 * 書き換えはメインのスレッドから。変わったら Bus.STORE で画面に知らせる
 */
class Store(ctx: Context) {
    private val file = File(ctx.filesDir, "player_store.json")
    private val resumeFile = File(ctx.filesDir, "player_resume.json")
    private val writer = Executors.newSingleThreadExecutor()
    /**
     * リピート・シャッフルの設定(前回の続きとは別に、切り替えたらすぐ保存する)。
     * 以前は続き(player_resume.json)の中にだけ保存していたので、再生リストが空のときや、
     * 切り替えてすぐにアプリが終わったときに、初めの状態に戻ってしまっていた
     */
    private val modes = ctx.getSharedPreferences("player_modes", Context.MODE_PRIVATE)

    var favTracks: List<String> = emptyList(); private set
    private var favTrackSet: Set<String> = emptySet()
    var favAlbums: List<String> = emptyList(); private set
    private var favAlbumSet: Set<String> = emptySet()
    /** お気に入りのアーティスト(名前。新しく追加したものが先頭。本家と同じ) */
    var favArtists: List<String> = emptyList(); private set
    private var favArtistSet: Set<String> = emptySet()
    var playlists: List<Playlist> = emptyList(); private set
    var history: List<PlayRecord> = emptyList(); private set
    var checkTracks: List<String> = emptyList(); private set
    private var checkTrackSet: Set<String> = emptySet()
    var counts: Map<String, Int> = emptyMap(); private set

    /** 保存したものを読む(起動したときに 1 回。どのスレッドからでも) */
    fun load() {
        val o = readJson(file) ?: return
        fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
        val ft = strings(o.optJSONArray("favTracks"))
        val ct = strings(o.optJSONArray("checkTracks"))
        val fa = strings(o.optJSONArray("favAlbums"))
        val fr = strings(o.optJSONArray("favArtists"))
        val pl = o.optJSONArray("playlists")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val p = a.optJSONObject(i) ?: return@mapNotNull null
                Playlist(p.optString("id"), p.optString("name"), strings(p.optJSONArray("uris")))
            }
        } ?: emptyList()
        val hi = o.optJSONArray("history")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val h = a.optJSONObject(i) ?: return@mapNotNull null
                PlayRecord(h.optString("u"), h.optLong("t"))
            }
        } ?: emptyList()
        val cn = o.optJSONObject("counts")?.let { c ->
            val m = HashMap<String, Int>()
            val it = c.keys()
            while (it.hasNext()) { val k = it.next(); m[k] = c.optInt(k) }
            m
        } ?: emptyMap()
        synchronized(this) {
            favTracks = ft; favTrackSet = ft.toHashSet()
            checkTracks = ct; checkTrackSet = ct.toHashSet()
            favAlbums = fa; favAlbumSet = fa.toHashSet()
            favArtists = fr; favArtistSet = fr.toHashSet()
            playlists = pl; history = hi; counts = cn
        }
        Bus.emit(Bus.STORE)
    }

    private class Snapshot(
        val favTracks: List<String>, val favAlbums: List<String>, val checkTracks: List<String>,
        val favArtists: List<String>,
        val playlists: List<Playlist>, val history: List<PlayRecord>, val counts: Map<String, Int>,
    )

    @Volatile private var pending: Snapshot? = null
    private val saveQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun save() {
        Bus.emit(Bus.STORE)
        pending = Snapshot(favTracks, favAlbums, checkTracks, favArtists, playlists, history, counts)
        if (!saveQueued.compareAndSet(false, true)) return
        writer.execute {
            saveQueued.set(false)
            val s = pending ?: return@execute
            val o = JSONObject()
            o.put("favTracks", JSONArray(s.favTracks))
            o.put("favAlbums", JSONArray(s.favAlbums))
            o.put("checkTracks", JSONArray(s.checkTracks))
            o.put("favArtists", JSONArray(s.favArtists))
            o.put("playlists", JSONArray().apply {
                s.playlists.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("uris", JSONArray(p.uris))) }
            })
            o.put("history", JSONArray().apply { s.history.forEach { h -> put(JSONObject().put("u", h.uri).put("t", h.time)) } })
            o.put("counts", JSONObject().apply { s.counts.forEach { (k, v) -> put(k, v) } })
            writeAtomic(file, o.toString())
        }
    }

    fun flush(timeoutMs: Long = 2000) {
        runCatching { writer.submit {}.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    /**
     * 書き換える。一時ファイルに書いてディスクに書き込み終えてから、名前を変えて入れ替える(途中で止まっても前の中身が残る)。
     * 失敗したときはログに残し、前の中身はそのまま残す(以前は失敗を記録せず、名前を変えられないときは直接上書きしていた。
     * AtomicFile は Android 4.2 からなので使わない)
     */
    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        try {
            java.io.FileOutputStream(tmp).use { o ->
                o.write(text.toByteArray(Charsets.UTF_8))
                o.flush()
                o.fd.sync()
            }
            if (!tmp.renameTo(f)) throw java.io.IOException("rename failed")
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            AppLog.log("store: cannot write ${f.name} (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    /** 読む。無ければ null。壊れていて読めないときは、ログに残して「.corrupt」に移す(次からは無いものとして始める) */
    private fun readJson(f: File): JSONObject? {
        if (!f.exists()) return null
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            AppLog.log("store: cannot read ${f.name} (${e.javaClass.simpleName}: ${e.message}), moved to .corrupt")
            runCatching { f.renameTo(File(f.path + ".corrupt")) }
            null
        }
    }

    // ---------- リピート・シャッフル ----------

    /** リピート(0 = なし、1 = 全曲、2 = 1 曲)とシャッフルを保存する(すぐに。続きの保存とは別) */
    fun saveModes(repeat: Int, shuffle: Boolean) {
        modes.edit().putInt("repeat", repeat.coerceIn(0, 2)).putBoolean("shuffle", shuffle).apply()
    }

    /** 保存したリピートとシャッフル。まだ保存したことが無ければ null */
    fun loadModes(): Pair<Int, Boolean>? {
        if (!modes.contains("repeat")) return null
        return modes.getInt("repeat", 0).coerceIn(0, 2) to modes.getBoolean("shuffle", false)
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

    fun isFavArtist(name: String?) = name != null && name in favArtistSet

    fun toggleFavArtist(name: String) {
        favArtists = if (isFavArtist(name)) favArtists - name else listOf(name) + favArtists
        favArtistSet = favArtists.toHashSet()
        save()
    }

    // ---------- 音質チェック用の曲 ----------

    fun isCheck(uri: String?) = uri != null && uri in checkTrackSet

    fun setCheck(uris: List<String>, on: Boolean) {
        checkTracks = if (on) uris.filter { it !in checkTrackSet }.reversed() + checkTracks else checkTracks.filter { it !in uris }
        checkTrackSet = checkTracks.toHashSet()
        save()
    }

    fun toggleCheck(uri: String) = setCheck(listOf(uri), !isCheck(uri))

    fun moveCheck(from: Int, to: Int) {
        if (from !in checkTracks.indices || to !in checkTracks.indices) return
        checkTracks = checkTracks.toMutableList().apply { add(to, removeAt(from)) }
        checkTrackSet = checkTracks.toHashSet()
        save()
    }

    /** 音質チェックの曲を、key の順(名前順など)に並べ替える(並びはそのまま保存する) */
    fun sortCheck(key: (String) -> String) {
        checkTracks = checkTracks.sortedWith(compareBy(java.text.Collator.getInstance()) { key(it) })
        checkTrackSet = checkTracks.toHashSet()
        save()
    }

    /** ライブラリを読み直したあと、ライブラリに無くなった曲を音質チェックから外す(曲数に数えられたまま増え続けないように。本家と同じ) */
    fun reconcileCheck(library: Set<String>) {
        if (library.isEmpty() || checkTracks.isEmpty()) return
        val list = checkTracks.filter { it in library }
        if (list.size == checkTracks.size) return
        checkTracks = list
        checkTrackSet = list.toHashSet()
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

    /**
     * いくつかの曲をまとめて動かす(本家と同じ)。picked は並びの中の位置、how は -1 上へ・1 下へ・-2 先頭へ・2 末尾へ。
     * 動かしたあとの位置を返す(選んだままにするため)
     */
    fun movePlaylistItems(id: String, picked: Set<Int>, how: Int): Set<Int> {
        val p = playlists.firstOrNull { it.id == id } ?: return picked
        val n = p.uris.size
        val sel = BooleanArray(n) { it in picked }
        val list = p.uris.toMutableList()
        when (how) {
            -1 -> for (i in 1 until n) if (sel[i] && !sel[i - 1]) {
                java.util.Collections.swap(list, i, i - 1); sel[i - 1] = true; sel[i] = false
            }
            1 -> for (i in n - 2 downTo 0) if (sel[i] && !sel[i + 1]) {
                java.util.Collections.swap(list, i, i + 1); sel[i + 1] = true; sel[i] = false
            }
            -2, 2 -> {
                val a = list.filterIndexed { i, _ -> sel[i] }
                val b = list.filterIndexed { i, _ -> !sel[i] }
                list.clear()
                if (how == -2) { list.addAll(a); list.addAll(b) } else { list.addAll(b); list.addAll(a) }
                for (i in 0 until n) sel[i] = if (how == -2) i < a.size else i >= b.size
            }
        }
        playlists = playlists.map { if (it.id == id) it.copy(uris = list) else it }
        save()
        return (0 until n).filter { sel[it] }.toSet()
    }

    /** いくつかの曲をまとめて外す */
    fun removePlaylistItems(id: String, picked: Set<Int>) {
        playlists = playlists.map { p -> if (p.id == id) p.copy(uris = p.uris.filterIndexed { i, _ -> i !in picked }) else p }
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
        writer.execute {
            val o = JSONObject()
                .put("uris", JSONArray(r.uris))
                .put("index", r.index)
                .put("pos", r.positionMs)
                .put("shuffle", r.shuffle)
                .put("repeat", r.repeat)
                .put("orig", JSONArray(r.original))
            writeAtomic(resumeFile, o.toString())
        }
    }

    /** どのスレッドからでも(ファイルを読む) */
    fun loadResume(): ResumeState? {
        val o = readJson(resumeFile) ?: return null
        val a = o.optJSONArray("uris")
        if (a == null || a.length() == 0) {
            AppLog.log("store: player_resume.json has no queue, ignored")
            return null
        }
        val uris = (0 until a.length()).map { a.optString(it) }
        val orig = o.optJSONArray("orig")?.let { b -> (0 until b.length()).map { b.optString(it) } } ?: uris
        // 値がおかしいとき(壊れた・古い形式)は、使える範囲に直す(リピート・シャッフルは別に保存した値を優先して使う)
        return ResumeState(
            uris, o.optInt("index").coerceIn(0, uris.lastIndex), o.optLong("pos").coerceAtLeast(0L),
            o.optBoolean("shuffle"), o.optInt("repeat").coerceIn(0, 2), orig,
        )
    }

    companion object {
        const val HISTORY_MAX = 300
    }
}
