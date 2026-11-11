package com.uyatame.ucrt

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.File

object UiThemes {
    const val DEFAULT_ID = "classic"

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
            nav = "pill", card = "filled", sectionTitle = "bold", listRow = "plain", coverCorner = 10, bars = "floating",
            headlineWeight = 800, header = "large", button = "round", tile = "circle", progress = "thick",
        ),
        UiTheme(
            "stage", "Stage", builtin = true, accent = 0xFFBFE6FF.toInt(), corner = 20, tabs = "underline",
            artCorner = 24, artGlow = true, info = "dark", controls = "capsule", seek = "thin", titleWeight = 900, blurLevel = 7,
            nav = "floating", card = "filled", sectionTitle = "accent", listRow = "tinted", coverCorner = 16, bars = "floating",
            headlineWeight = 900, background = "tint", header = "large", button = "round", tile = "soft", progress = "thick",
        ),
        UiTheme(
            "instrument", "Instrument", builtin = true, accent = 0xFFFFC46B.toInt(), corner = 6, tabs = "segmented",
            artCorner = 2, artFrame = true, info = "panel", controls = "square", seek = "ticks", titleWeight = 700,
            nav = "line", card = "panel", sectionTitle = "caps", listRow = "divided", coverCorner = 2, bars = "line",
            headlineWeight = 700, header = "caps", button = "square", tile = "square", progress = "blocks",
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
            .mapNotNull { f -> readTheme(f) }
            .sortedBy { it.name.lowercase() }

    /** テーマのファイルを読む。大きすぎる・書式が違うファイルは null */
    private fun readTheme(f: File): UiTheme? =
        if (f.length() > UiThemeFormat.MAX_BYTES) null else runCatching { parse(f.readText()) }.getOrNull()

    fun all(ctx: Context): List<UiTheme> = builtins + userThemes(ctx)

    fun find(ctx: Context, id: String): UiTheme {
        builtins.firstOrNull { it.id == id }?.let { return it }
        // id は設定のファイル(プロファイル)からも入るので、ファイル名として安全な形だけ通す(「../」などを開かない)
        if (!UiThemeFormat.validId(id)) return classic
        val f = File(dir(ctx), "$id.json")
        return if (f.isFile) readTheme(f) ?: classic else classic
    }

    /** ファイルの中身を読んで保存する。読めないファイルなら null */
    fun importTheme(ctx: Context, text: String): UiTheme? {
        if (text.length > UiThemeFormat.MAX_BYTES) return null
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
        if (builtins.any { it.id == id } || !UiThemeFormat.validId(id)) return
        File(dir(ctx), "$id.json").delete()
        revision++
    }

    /** テーマのファイルを読む(書式が違えば例外) */
    fun parse(text: String): UiTheme = UiThemeFormat.parse(text)

    fun toJson(t: UiTheme): org.json.JSONObject = UiThemeFormat.toJson(t)

    /** ひな形として書き出す中身(書き換えられる値の説明つき) */
    fun template(t: UiTheme, displayName: String): String = UiThemeFormat.template(t, displayName)

}

/** 設定で選んでいるテーマ(読み込み・削除のときは読み直す) */
@Composable
fun rememberUiTheme(id: String): UiTheme {
    val ctx = LocalContext.current.applicationContext
    val rev = UiThemes.revision
    return remember(id, rev) { UiThemes.find(ctx, id) }
}

/** 今のテーマ(AppTheme の中で渡す。共通の部品がこれを読んで形を変える) */
val LocalUiTheme = androidx.compose.runtime.staticCompositionLocalOf { UiThemes.classic }
