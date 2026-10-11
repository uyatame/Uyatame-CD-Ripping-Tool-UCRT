package com.uyatame.ucrt.meta

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一度決めた CD の曲情報とジャケットを、ディスク ID ごとに端末へ保存しておく。
 * 同じ CD を入れ直したときは、ネットに問い合わせずにすぐ表示できる。
 */
class DiscCache(ctx: Context) {
    private val dir = File(ctx.filesDir, "disc_cache").apply { mkdirs() }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun load(discId: String): AlbumMeta? {
        val f = File(dir, safe(discId) + ".json")
        if (!f.exists()) return null
        return runCatching { parse(JSONObject(f.readText())) }.getOrNull()
    }

    private fun parse(o: JSONObject): AlbumMeta {
        val tr = o.optJSONArray("tracks") ?: JSONArray()
        return AlbumMeta(
            title = o.optString("title"),
            artist = o.optString("artist"),
            date = o.optString("date"),
            releaseId = o.optString("releaseId").takeIf { it.isNotEmpty() },
            tracks = (0 until tr.length()).map { i ->
                val t = tr.getJSONObject(i)
                TrackMeta(t.optString("title"), t.optString("artist"))
            },
            discNo = o.optInt("discNo", 1),
            discTotal = o.optInt("discTotal", 1),
        )
    }

    fun save(discId: String, m: AlbumMeta) {
        runCatching {
            val o = JSONObject()
            o.put("title", m.title)
            o.put("artist", m.artist)
            o.put("date", m.date)
            o.put("releaseId", m.releaseId ?: "")
            o.put("discNo", m.discNo)
            o.put("discTotal", m.discTotal)
            val tr = JSONArray()
            m.tracks.forEach { t -> tr.put(JSONObject().put("title", t.title).put("artist", t.artist)) }
            o.put("tracks", tr)
            File(dir, safe(discId) + ".json").writeText(o.toString())
        }
    }

    fun loadCover(discId: String): ByteArray? = runCatching {
        File(dir, safe(discId) + ".img").takeIf { it.exists() }?.readBytes()
    }.getOrNull()

    fun saveCover(discId: String, b: ByteArray?) {
        runCatching {
            val f = File(dir, safe(discId) + ".img")
            if (b == null) f.delete() else f.writeBytes(b)
        }
    }

    fun forget(discId: String) {
        runCatching {
            File(dir, safe(discId) + ".json").delete()
            File(dir, safe(discId) + ".img").delete()
        }
    }
}
