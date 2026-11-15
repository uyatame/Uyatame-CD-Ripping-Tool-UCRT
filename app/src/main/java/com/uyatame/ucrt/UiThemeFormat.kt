package com.uyatame.ucrt

import org.json.JSONObject

// テーマのデータと、ファイル(JSON)の読み書き。Android の部品には頼らない(PC 上の単体テストで動かせるように、
// 画面・保存場所の処理は UiThemes に分けてある)

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
    // ---- アプリ全体(ライブラリ・ディスク・アップサンプル・ストリーミング・設定など、すべての画面) ----
    /** 下のタブ: bar(標準) / pill(選んだタブを横長の丸い面に) / floating(浮いた丸いバー) / line(平らな面と上の線) */
    val nav: String = "bar",
    /** カード(設定の区切り・アップサンプル・ディスクの情報など): filled(塗り) / outlined(枠線) / flat(面なし・区切り線) / panel(枠付きの表示窓) */
    val card: String = "filled",
    /** カードの見出し: accent(差し色) / caps(小さく字間を空ける) / bold(大きく太く) */
    val sectionTitle: String = "accent",
    /** 一覧の行(曲・アルバム・アーティストなど): plain(標準) / tinted(行ごとに薄い面) / divided(区切り線) */
    val listRow: String = "plain",
    /** 一覧などのジャケットの角の丸み(dp。-1 = 画面ごとの標準) */
    val coverCorner: Int = -1,
    /** ミニプレーヤー・取り込み中などの帯: bar(標準) / floating(浮いた丸いカード) / line(平らな面と上の線) */
    val bars: String = "bar",
    /** 画面の見出しの太さ(400〜900) */
    val headlineWeight: Int = 400,
    /** 画面の背景: plain / tint(上の方をテーマの色で薄く染める) */
    val background: String = "plain",
    /** 画面の見出し: plain(標準) / large(大きく太く、下に差し色の短い線) / caps(字間を空け、下に細い線) */
    val header: String = "plain",
    /** ボタンの形: round(丸い端) / soft(角丸) / square(小さな角) */
    val button: String = "round",
    /** アイコンの台(ライブラリのカテゴリー・設定の項目・頭文字の丸など): default(画面ごとの標準) / circle / soft / square */
    val tile: String = "default",
    /** 進み具合のバー(取り込み・変換・ミニプレーヤー): default / thick(太く丸い) / blocks(区切った四角) */
    val progress: String = "default",
    /** リキッドグラス(下のタブ・ミニプレーヤーなどを、中身の上に浮かぶガラスにする)。設定の「見た目」をリキッドグラスにするのと同じ */
    val glass: Boolean = false,
)

/** テーマのファイルの書式(読む・書き出す・ひな形)。書式はリポジトリの themes/README.md */
object UiThemeFormat {
    const val FORMAT = "ucrt-theme"
    const val FORMAT_VERSION = 1

    /** id に使える形(英小文字・数字・「-」「_」。ファイル名にもなるので、「/」や「.」は入れない) */
    private val ID_FORM = Regex("[a-z0-9_-]{1,48}")

    fun validId(id: String): Boolean = ID_FORM.matches(id)

    private val TABS = setOf("underline", "pill", "segmented")
    private val INFO = setOf("outline", "filled", "dark", "panel")
    private val CONTROLS = setOf("outline", "solid", "capsule", "square")
    private val SEEK = setOf("default", "thin", "ticks")
    private val NAV = setOf("bar", "pill", "floating", "line")
    private val CARD = setOf("filled", "outlined", "flat", "panel")
    private val SECTION_TITLE = setOf("accent", "caps", "bold")
    private val LIST_ROW = setOf("plain", "tinted", "divided")
    private val BARS = setOf("bar", "floating", "line")
    private val BACKGROUND = setOf("plain", "tint")
    private val HEADER = setOf("plain", "large", "caps")
    private val BUTTON = setOf("round", "soft", "square")
    private val TILE = setOf("default", "circle", "soft", "square")
    private val PROGRESS = setOf("default", "thick", "blocks")

    /** 読み込むファイルの大きさの上限(バイト)。テーマの書式は小さいので、これを超えるものはテーマではない */
    const val MAX_BYTES = 256 * 1024

    /**
     * 色の文字(#RRGGBB か #AARRGGBB)を ARGB の数にする。#RRGGBB は不透明。空・「0」・default・読めない文字は 0(= 指定なし)。
     * (Android の Color.parseColor は色の名前も通すが、書式の説明のとおり 2 つの形だけにする)
     */
    fun parseColor(text: String): Int {
        val v = text.trim()
        if (v.isEmpty() || v == "0" || v.equals("default", true)) return 0
        if (!v.startsWith("#")) return 0
        val h = v.substring(1)
        if (h.length != 6 && h.length != 8) return 0
        if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return 0
        val n = h.toLong(16)
        return (if (h.length == 6) 0xFF000000L or n else n).toInt()
    }

    private fun color(o: JSONObject, key: String): Int = parseColor(o.optString(key, ""))

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
        val a = o.optJSONObject("app") ?: JSONObject()
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
            nav = pick(a, "nav", NAV, d.nav),
            card = pick(a, "card", CARD, d.card),
            sectionTitle = pick(a, "sectionTitle", SECTION_TITLE, d.sectionTitle),
            listRow = pick(a, "listRow", LIST_ROW, d.listRow),
            coverCorner = a.optInt("coverCorner", d.coverCorner).coerceIn(-1, 40),
            bars = pick(a, "bars", BARS, d.bars),
            headlineWeight = (a.optInt("headlineWeight", d.headlineWeight) / 100 * 100).coerceIn(400, 900),
            background = pick(a, "background", BACKGROUND, d.background),
            header = pick(a, "header", HEADER, d.header),
            button = pick(a, "button", BUTTON, d.button),
            tile = pick(a, "tile", TILE, d.tile),
            progress = pick(a, "progress", PROGRESS, d.progress),
            glass = a.optBoolean("glass", d.glass),
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
        put("app", JSONObject().apply {
            put("nav", t.nav)
            put("card", t.card)
            put("sectionTitle", t.sectionTitle)
            put("listRow", t.listRow)
            put("coverCorner", t.coverCorner)
            put("bars", t.bars)
            put("headlineWeight", t.headlineWeight)
            put("background", t.background)
            put("header", t.header)
            put("button", t.button)
            put("tile", t.tile)
            put("progress", t.progress)
            put("glass", t.glass)
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
            put("app.nav", "bar | pill | floating | line  (bottom tabs / 下のタブ)")
            put("app.card", "filled | outlined | flat | panel  (cards in settings, disc, upsample... / カード)")
            put("app.sectionTitle", "accent | caps | bold  (card titles / カードの見出し)")
            put("app.listRow", "plain | tinted | divided  (song, album, artist rows / 一覧の行)")
            put("app.coverCorner", "-1..40 (dp, -1 = standard)  (artwork in lists / 一覧のジャケット)")
            put("app.bars", "bar | floating | line  (mini player, ripping bar / ミニプレーヤーなどの帯)")
            put("app.headlineWeight", "400..900  (screen titles / 画面の見出し)")
            put("app.background", "plain | tint")
            put("app.header", "plain | large | caps  (screen titles / 画面の見出し)")
            put("app.button", "round | soft | square  (button shape / ボタンの形)")
            put("app.tile", "default | circle | soft | square  (icon tiles, avatars / アイコンの台)")
            put("app.progress", "default | thick | blocks  (progress bars / 進み具合のバー)")
            put("app.glass", "true | false  (liquid glass bars / リキッドグラス)")
        })
        return o.toString(2)
    }
}
