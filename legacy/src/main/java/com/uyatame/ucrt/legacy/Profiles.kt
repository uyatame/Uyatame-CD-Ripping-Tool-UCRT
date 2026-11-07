package com.uyatame.ucrt.legacy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 設定のプロファイル(本家の SettingsProfiles と同じ形式のファイル)。設定をまとめて保存・切り替え、ファイルへの書き出し・読み込み。
 * この端末だけで意味のある設定(保存先・ライブラリのフォルダなど)は入れず、切り替えても今のまま残す
 */
object Profiles {
    private const val FORMAT = "ucrt-settings"
    private val DEVICE_ONLY = setOf("outputDir", "libraryFolders", "contactEmail", "usbVolume", "appVolumeStep")

    class Profile(val id: String, val name: String, val savedAt: Long)

    private fun dir(ctx: Context) = File(ctx.filesDir, "profiles").apply { mkdirs() }

    /** 今の設定を JSON にする */
    fun export(ctx: Context, name: String = ""): JSONObject {
        val values = JSONObject()
        for ((k, v) in LegacyApp.app.prefs.sp.all) {
            if (k in DEVICE_ONLY || v == null) continue
            val e = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is String -> JSONObject().put("t", "s").put("v", v)
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> null
            } ?: continue
            values.put(k, e)
        }
        return JSONObject().put("format", FORMAT).put("version", 1).put("name", name)
            .put("savedAt", System.currentTimeMillis()).put("settings", values)
    }

    /** JSON の設定に切り替える(この端末だけの設定は残し、ほかは一度消してから入れる)。形式が違えば false */
    fun apply(o: JSONObject): Boolean {
        if (o.optString("format") != FORMAT) return false
        val values = o.optJSONObject("settings") ?: return false
        val sp = LegacyApp.app.prefs.sp
        val e = sp.edit()
        for (k in sp.all.keys) if (k !in DEVICE_ONLY) e.remove(k)
        val names = values.keys()
        while (names.hasNext()) {
            val k = names.next()
            if (k in DEVICE_ONLY) continue
            val x = values.optJSONObject(k) ?: continue
            runCatching {
                when (x.optString("t")) {
                    "b" -> e.putBoolean(k, x.getBoolean("v"))
                    "i" -> e.putInt(k, x.getInt("v"))
                    "l" -> e.putLong(k, x.getLong("v"))
                    "f", "d" -> e.putFloat(k, x.getDouble("v").toFloat())
                    "s" -> e.putString(k, x.getString("v"))
                    "ss" -> {
                        val a = x.getJSONArray("v")
                        val set = HashSet<String>()
                        for (i in 0 until a.length()) set.add(a.getString(i))
                        e.putStringSet(k, set)
                    }
                }
            }
        }
        e.commit()
        Bus.emit(Bus.SETTINGS)
        return true
    }

    fun list(ctx: Context): List<Profile> =
        (dir(ctx).listFiles() ?: emptyArray()).filter { it.name.endsWith(".json") }.mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                Profile(f.name.removeSuffix(".json"), o.optString("name").ifEmpty { f.name.removeSuffix(".json") }, o.optLong("savedAt"))
            }.getOrNull()
        }.sortedWith(compareBy(java.text.Collator.getInstance()) { it.name })

    /** 今の設定をプロファイルとして保存する(id を渡せば上書き) */
    fun save(ctx: Context, name: String, id: String? = null) {
        val pid = id ?: java.lang.Long.toString(System.currentTimeMillis(), 36)
        val f = File(dir(ctx), "$pid.json")
        val tmp = File(dir(ctx), "$pid.json.tmp")
        tmp.writeText(export(ctx, name.trim()).toString(1))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun load(ctx: Context, id: String): Boolean =
        runCatching { apply(JSONObject(File(dir(ctx), "$id.json").readText())) }.getOrDefault(false)

    fun delete(ctx: Context, id: String) { File(dir(ctx), "$id.json").delete() }
}
