package com.uyatame.ucrt.meta

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** GitHub で公開されている最新のリリース */
data class ReleaseInfo(
    /** "0.5.1" のような版(先頭の v は除く) */
    val version: String,
    val title: String,
    /** 変更点(リリースの説明文) */
    val notes: String,
    /** リリースのページ(APK と変更点が載っている) */
    val pageUrl: String,
)

/**
 * 新しい版が出ていないかを、GitHub のリリースで確かめる。
 * 送るのはアプリの名前と版だけ(GitHub の API の決まりで User-Agent が必要なため)
 */
object UpdateCheck {
    private const val REPO = "uyatame/Uyatame-CD-Ripping-Tool-UCRT"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    /** 最新のリリース。リリースが無い・つながらないときは null */
    fun latest(appVersion: String): ReleaseInfo? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "UCRT/$appVersion")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 10000
        c.readTimeout = 15000
        try {
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            if (j.optBoolean("draft") || j.optBoolean("prerelease")) return null
            val tag = j.optString("tag_name").trim()
            // UCRT Legacy(legacy-v)・FFmpeg などのリリースが「最新」になっていても、本家の版として扱わない
            if (tag.isEmpty() || !(tag.startsWith("v") || tag.startsWith("V"))) return null
            return ReleaseInfo(
                version = tag.removePrefix("v").removePrefix("V"),
                title = j.optString("name").ifBlank { tag },
                notes = j.optString("body").trim(),
                pageUrl = j.optString("html_url").ifBlank { RELEASES_PAGE },
            )
        } finally {
            c.disconnect()
        }
    }

    /**
     * UCRT Legacy の最新のリリース。v0.9.0 からは、本家と同じリリース(タグ v<版>)に Legacy の APK(名前が UCRT_Legacy_ で始まる)も置く。
     * それより前の Legacy だけのリリース(タグ legacy-v<版>。α版などのプレリリースも含む)も見て、いちばん新しい版を返す。
     * 無い・つながらないときは null
     */
    fun latestLegacy(appVersion: String): ReleaseInfo? {
        val c = URL("https://api.github.com/repos/$REPO/releases?per_page=30").openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "UCRT-Legacy/$appVersion")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 10000
        c.readTimeout = 15000
        try {
            if (c.responseCode != 200) return null
            return pickLegacy(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    /** リリースの一覧(GitHub の API の JSON)から、UCRT Legacy の最新のリリースを選ぶ(下書きは除く) */
    fun pickLegacy(json: String): ReleaseInfo? {
        val arr = org.json.JSONArray(json)
        var best: ReleaseInfo? = null
        for (k in 0 until arr.length()) {
            val j = arr.optJSONObject(k) ?: continue
            if (j.optBoolean("draft")) continue
            val tag = j.optString("tag_name").trim()
            val version = when {
                tag.startsWith("legacy-v") -> tag.removePrefix("legacy-v")
                // 本家と一緒のリリース: Legacy の APK が置いてあるものだけ(プレリリースは除く)
                (tag.startsWith("v") || tag.startsWith("V")) && !j.optBoolean("prerelease") && hasLegacyApk(j) ->
                    tag.removePrefix("v").removePrefix("V")
                else -> continue
            }
            if (best == null || compare(version, best.version) > 0) {
                best = ReleaseInfo(
                    version = version,
                    title = j.optString("name").ifBlank { tag },
                    notes = j.optString("body").trim(),
                    pageUrl = j.optString("html_url").ifBlank { "https://github.com/$REPO/releases" },
                )
            }
        }
        return best
    }

    private fun hasLegacyApk(j: JSONObject): Boolean {
        val assets = j.optJSONArray("assets") ?: return false
        for (i in 0 until assets.length()) {
            val n = assets.optJSONObject(i)?.optString("name").orEmpty()
            if (n.startsWith("UCRT_Legacy_", ignoreCase = true) && n.endsWith(".apk", ignoreCase = true)) return true
        }
        return false
    }

    /**
     * release が、いま使っている版 current より新しいか。
     * テスト用のビルド("0.5.0.test0.0.2")は、同じ番号のリリース("0.5.0")より古いものとして扱う
     */
    fun isNewer(release: String, current: String): Boolean {
        val i = current.indexOf(".test")
        val base = if (i >= 0) current.substring(0, i) else current
        val c = compare(release, base)
        return c > 0 || (c == 0 && i >= 0)
    }

    /** "1.2.3" どうしを数字で比べる(足りない桁は 0) */
    fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (k in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(k) { 0 }.compareTo(y.getOrElse(k) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
