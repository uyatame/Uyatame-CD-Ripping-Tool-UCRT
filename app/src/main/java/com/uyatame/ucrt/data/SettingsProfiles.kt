package com.uyatame.ucrt.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 設定のプロファイル(設定をまとめて保存・切り替える)と、ファイルへの書き出し・読み込み(バックアップ・機種変更・共有)。
 *
 * この端末だけで意味のある設定(保存先・ライブラリのフォルダ・出力先の機器など)と音量は、プロファイルに入れない
 * (音量は、切り替えたとたんに大きな音にならないように。聴覚の保護)。
 * (ほかの端末で読み込むと、存在しない場所を指してしまうため)。切り替えたときも、それらは今のまま残す。
 * Spotify のクライアント ID などの秘密の情報は、もともとこの設定とは別の場所にあるので入らない。
 */
object SettingsProfiles {
    const val FORMAT = "ucrt-settings"
    private const val VERSION = 1

    /** プロファイルに入れない(この端末だけの)設定 */
    private val DEVICE_ONLY = setOf("outputUri", "libraryFolders", "upOutUri", "outputDevice", "contactEmail", "usbVolume", "appVolumeStep")

    class Profile(val id: String, val name: String, val savedAt: Long)

    private fun dir(ctx: Context) = File(ctx.filesDir, "profiles").apply { mkdirs() }

    /** 今の設定を JSON にする */
    suspend fun export(ctx: Context, name: String = ""): JSONObject {
        val prefs = ctx.dataStore.data.first()
        val values = JSONObject()
        for ((k, v) in prefs.asMap()) {
            if (k.name in DEVICE_ONLY) continue
            val e = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Double -> JSONObject().put("t", "d").put("v", v)
                is String -> JSONObject().put("t", "s").put("v", v)
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> null
            } ?: continue
            values.put(k.name, e)
        }
        return JSONObject().put("format", FORMAT).put("version", VERSION).put("name", name)
            .put("savedAt", System.currentTimeMillis()).put("settings", values)
    }

    /**
     * JSON の設定にまとめて切り替える。この端末だけの設定は今のまま、ほかの設定は一度消して(初期値に戻して)から入れる
     * (プロファイルに無い設定が、前のプロファイルの値のまま残らないように)。形式が違えば false
     */
    suspend fun apply(ctx: Context, o: JSONObject): Boolean {
        if (o.optString("format") != FORMAT) return false
        val values = o.optJSONObject("settings") ?: return false
        ctx.dataStore.edit { p ->
            @Suppress("UNCHECKED_CAST")
            for (k in p.asMap().keys.toList()) if (k.name !in DEVICE_ONLY) p.remove(k as Preferences.Key<Any>)
            val names = values.keys()
            while (names.hasNext()) {
                val name = names.next()
                if (name in DEVICE_ONLY) continue
                val e = values.optJSONObject(name) ?: continue
                runCatching {
                    when (e.optString("t")) {
                        "b" -> p[booleanPreferencesKey(name)] = e.getBoolean("v")
                        "i" -> p[intPreferencesKey(name)] = e.getInt("v")
                        "l" -> p[longPreferencesKey(name)] = e.getLong("v")
                        "f" -> p[floatPreferencesKey(name)] = e.getDouble("v").toFloat()
                        "d" -> p[doublePreferencesKey(name)] = e.getDouble("v")
                        "s" -> p[stringPreferencesKey(name)] = e.getString("v")
                        "ss" -> {
                            val a = e.getJSONArray("v")
                            p[stringSetPreferencesKey(name)] = (0 until a.length()).map { i -> a.getString(i) }.toSet()
                        }
                    }
                }
            }
        }
        return true
    }

    // ---- 端末の中のプロファイル ----

    suspend fun list(ctx: Context): List<Profile> = withContext(Dispatchers.IO) {
        (dir(ctx).listFiles() ?: emptyArray()).filter { it.name.endsWith(".json") }.mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                Profile(f.name.removeSuffix(".json"), o.optString("name").ifEmpty { f.name.removeSuffix(".json") }, o.optLong("savedAt"))
            }.getOrNull()
        }.sortedWith(compareBy(java.text.Collator.getInstance()) { it.name })
    }

    /** 今の設定をプロファイルとして保存する(id を渡せば、そのプロファイルを上書き) */
    suspend fun save(ctx: Context, name: String, id: String? = null) {
        val o = export(ctx, name.trim())
        val pid = id ?: (java.lang.Long.toString(System.currentTimeMillis(), 36))
        withContext(Dispatchers.IO) { writeAtomic(File(dir(ctx), "$pid.json"), o.toString(1)) }
    }

    suspend fun load(ctx: Context, id: String): Boolean {
        val o = withContext(Dispatchers.IO) { runCatching { JSONObject(File(dir(ctx), "$id.json").readText()) }.getOrNull() } ?: return false
        return apply(ctx, o)
    }

    suspend fun delete(ctx: Context, id: String) = withContext(Dispatchers.IO) { File(dir(ctx), "$id.json").delete() }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
