package com.uyatame.ucrt

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import java.io.File

/**
 * UI のテーマ(見た目の仕上げ)。配置と操作は変えず、色・角の丸み・ボタンや情報の表示のしかただけを変える。
 * テーマは JSON のファイル(拡張子は .json または .ucrttheme)で、読み込んで使える。書き出したテーマをひな形にして、
 * 値を書き換えれば自分のテーマが作れる(書式はリポジトリの themes/README.md)
 */
data class UiTheme(
    /** 英小文字・数字・「-」「_」だけ。同じ id のテーマを読み込むと上書きする */
    val id: String,
    val name: String,
    val author: String = "",
    val description: String = "",
    val builtin: Boolean = false,
    /** 再生画面の差し色(ARGB。0 = 今までの色) */
    val accent: Int = 0,
    /** アプリ全体のテーマの色(ARGB。0 = 設定の色・ダイナミックカラーのまま) */
    val seed: Int = 0,
    /** アプリ全体の角の丸み(dp。-1 = 標準) */
    val corner: Int = -1,
    /** 再生画面の上の切り替え: underline(下線) / pill(丸い面) / segmented(区切りの枠) */
    val tabs: String = "underline",
    /** ジャケットの角の丸み(dp) */
    val artCorner: Int = 12,
    /** ジャケットのまわりに細い額縁 */
    val artFrame: Boolean = false,
    /** ジャケットのまわりを差し色でほんのり光らせる */
    val artGlow: Boolean = false,
    /** 形式・出力先・EQ の表示: outline(枠線) / filled(半透明の塗り) / dark(暗い塗り) / panel(1 枚の表示窓にまとめる) */
    val info: String = "outline",
    /** 前・再生・次のボタン: outline(丸い枠) / solid(再生だけ塗り) / capsule(1 本の帯) / square(角丸の四角) */
    val controls: String = "outline",
    /** 再生バー: default / thin(細いつまみ) / ticks(目盛り付き) */
    val seek: String = "default",
    /** 曲名の太さ(400〜900) */
    val titleWeight: Int = 700,
    /** 背景のぼかしの強さ(1〜10。0 = 設定のまま) */
    val blurLevel: Int = 0,
)

object UiThemes {
    const val FORMAT = "ucrt-theme"
    const val FORMAT_VERSION = 1
    const val DEFAULT_ID = "classic"

    private val TABS = setOf("underline", "pill", "segmented")
    private val INFO = setOf("outline", "filled", "dark", "panel")
    private val CONTROLS = setOf("outline", "solid", "capsule", "square")
    private val SEEK = setOf("default", "thin", "ticks")

    /** 読み込み・削除のたびに増える(画面がテーマを読み直す合図) */
    var revision by mutableIntStateOf(0)
        private set

    val classic = UiTheme(DEFAULT_ID, "Classic", builtin = true)

    /** 組み込みのテーマ(名前と説明は表示のときに文言のリソースから引く) */
    val builtins: List<UiTheme> = listOf(
        classic,
        UiTheme(
            "sumi", "Sumi", builtin = true, accent = 0xFFF3D58C.toInt(), corner = 16, tabs = "pill",
            artCorner = 18, info = "filled", controls = "solid", seek = "thin", titleWeight = 900,
        ),
        UiTheme(
            "stage", "Stage", builtin = true, accent = 0xFFBFE6FF.toInt(), corner = 20, tabs = "underline",
            artCorner = 24, artGlow = true, info = "dark", controls = "capsule", seek = "thin", titleWeight = 900, blurLevel = 7,
        ),
        UiTheme(
            "instrument", "Instrument", builtin = true, accent = 0xFFFFC46B.toInt(), corner = 6, tabs = "segmented",
            artCorner = 2, artFrame = true, info = "panel", controls = "square", seek = "ticks", titleWeight = 700,
        ),
    )

    fun builtinNameRes(id: String): Int? = when (id) {
        "classic" -> R.string.theme_name_classic
        "sumi" -> R.string.theme_name_sumi
        "stage" -> R.string.theme_name_stage
        "instrument" -> R.string.theme_name_instrument
        else -> null
    }

    fun builtinDescRes(id: String): Int? = when (id) {
        "classic" -> R.string.theme_desc_classic
        "sumi" -> R.string.theme_desc_sumi
        "stage" -> R.string.theme_desc_stage
        "instrument" -> R.string.theme_desc_instrument
        else -> null
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "themes")

    /** 読み込んだテーマ(名前の順) */
    fun userThemes(ctx: Context): List<UiTheme> =
        (dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }
            .sortedBy { it.name.lowercase() }

    fun all(ctx: Context): List<UiTheme> = builtins + userThemes(ctx)

    fun find(ctx: Context, id: String): UiTheme {
        builtins.firstOrNull { it.id == id }?.let { return it }
        val f = File(dir(ctx), "$id.json")
        return if (f.isFile) runCatching { parse(f.readText()) }.getOrNull() ?: classic else classic
    }

    /** ファイルの中身を読んで保存する。読めないファイルなら null */
    fun importTheme(ctx: Context, text: String): UiTheme? {
        val t = runCatching { parse(text) }.getOrNull() ?: return null
        // 組み込みのテーマと同じ id なら、別の id にする(組み込みは上書きしない)
        val id = if (builtins.any { it.id == t.id }) "user-" + t.id else t.id
        val saved = t.copy(id = id, builtin = false)
        val d = dir(ctx)
        d.mkdirs()
        File(d, "$id.json").writeText(toJson(saved).toString(2))
        revision++
        return saved
    }

    fun delete(ctx: Context, id: String) {
        if (builtins.any { it.id == id }) return
        File(dir(ctx), "$id.json").delete()
        revision++
    }

    private fun color(o: JSONObject, key: String): Int {
        val v = o.optString(key, "").trim()
        if (v.isEmpty() || v == "0" || v.equals("default", true)) return 0
        return runCatching { android.graphics.Color.parseColor(v) }.getOrDefault(0)
    }

    private fun hex(c: Int) = if (c == 0) "" else String.format("#%08X", c)

    private fun pick(o: JSONObject, key: String, allowed: Set<String>, def: String): String =
        o.optString(key, def).lowercase().takeIf { it in allowed } ?: def

    /** テーマのファイルを読む(書式が違えば例外) */
    fun parse(text: String): UiTheme {
        val o = JSONObject(text)
        require(o.optString("format") == FORMAT) { "not a theme" }
        val rawId = o.optString("id", "").lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }.take(40)
        val name = o.optString("name", "").trim().take(40)
        require(rawId.isNotEmpty() && name.isNotEmpty()) { "no id or name" }
        val d = UiTheme(rawId, name)
        val p = o.optJSONObject("player") ?: JSONObject()
        return UiTheme(
            id = rawId,
            name = name,
            author = o.optString("author", "").trim().take(40),
            description = o.optString("description", "").trim().take(200),
            accent = color(o, "accent"),
            seed = color(o, "seed"),
            corner = o.optInt("corner", d.corner).coerceIn(-1, 32),
            tabs = pick(p, "tabs", TABS, d.tabs),
            artCorner = p.optInt("artCorner", d.artCorner).coerceIn(0, 40),
            artFrame = p.optBoolean("artFrame", d.artFrame),
            artGlow = p.optBoolean("artGlow", d.artGlow),
            info = pick(p, "info", INFO, d.info),
            controls = pick(p, "controls", CONTROLS, d.controls),
            seek = pick(p, "seek", SEEK, d.seek),
            titleWeight = (p.optInt("titleWeight", d.titleWeight) / 100 * 100).coerceIn(400, 900),
            blurLevel = p.optInt("blurLevel", d.blurLevel).coerceIn(0, 10),
        )
    }

    fun toJson(t: UiTheme): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("formatVersion", FORMAT_VERSION)
        put("id", t.id)
        put("name", t.name)
        put("author", t.author)
        put("description", t.description)
        put("accent", hex(t.accent))
        put("seed", hex(t.seed))
        put("corner", t.corner)
        put("player", JSONObject().apply {
            put("tabs", t.tabs)
            put("artCorner", t.artCorner)
            put("artFrame", t.artFrame)
            put("artGlow", t.artGlow)
            put("info", t.info)
            put("controls", t.controls)
            put("seek", t.seek)
            put("titleWeight", t.titleWeight)
            put("blurLevel", t.blurLevel)
        })
    }

    /**
     * ひな形として書き出す中身。テーマそのものに、書き換えられる値の説明(_help)を添える。
     * 組み込みのテーマを書き出したときは、そのまま読み込むと別のテーマとして入るよう、id と名前を変えておく
     */
    fun template(t: UiTheme, displayName: String): String {
        val base = if (t.builtin) t.copy(id = "my-" + t.id, name = "$displayName (custom)", author = "") else t
        val o = toJson(base)
        o.put("_help", JSONObject().apply {
            put("about", "UCRT theme. Edit the values and import this file in Settings > Display > Theme. / UCRT のテーマ。値を書き換えて、設定 > 表示 > テーマ から読み込みます")
            put("id", "a-z 0-9 - _ (max 40). Same id overwrites. / 同じ id は上書き")
            put("accent", "Player accent color, #RRGGBB or #AARRGGBB. Empty = default / 再生画面の差し色。空 = 標準")
            put("seed", "App-wide theme color. Empty = keep your setting / アプリ全体の色。空 = 設定のまま")
            put("corner", "App-wide corner radius in dp, -1..32. -1 = standard / 角の丸み")
            put("player.tabs", "underline | pill | segmented")
            put("player.artCorner", "0..40 (dp)")
            put("player.artFrame", "true | false")
            put("player.artGlow", "true | false")
            put("player.info", "outline | filled | dark | panel")
            put("player.controls", "outline | solid | capsule | square")
            put("player.seek", "default | thin | ticks")
            put("player.titleWeight", "400..900")
            put("player.blurLevel", "0..10 (0 = your setting / 設定のまま)")
        })
        return o.toString(2)
    }
}

/** 設定で選んでいるテーマ(読み込み・削除のときは読み直す) */
@Composable
fun rememberUiTheme(id: String): UiTheme {
    val ctx = LocalContext.current.applicationContext
    val rev = UiThemes.revision
    return remember(id, rev) { UiThemes.find(ctx, id) }
}
