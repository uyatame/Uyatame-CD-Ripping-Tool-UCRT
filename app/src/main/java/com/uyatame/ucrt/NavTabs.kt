package com.uyatame.ucrt

/**
 * 下のメニュー(タブ)に出す画面の設定の書き方。Android の部品には頼らない(PC 上の単体テストで確かめられるように)。
 * 出す画面の名前を「,」でつなぐ(例: "library,disc,upsample,aging")。空 = 標準(エージングは出さない)。
 * 1 つも出さないときは "none"。設定は常に出す(どの画面にも、設定から行けるように)
 */
object NavTabs {
    const val LIBRARY = "library"
    const val DISC = "disc"
    const val STREAMING = "streaming"
    const val UPSAMPLE = "upsample"
    const val AGING = "aging"

    /** 選べる画面(この並びで出す) */
    val ALL = listOf(LIBRARY, DISC, STREAMING, UPSAMPLE, AGING)
    val DEFAULT = listOf(LIBRARY, DISC, STREAMING, UPSAMPLE)

    fun parse(s: String): Set<String> =
        if (s.isBlank()) DEFAULT.toSet() else s.split(',').map { it.trim() }.filter { it in ALL }.toSet()

    fun encode(shown: Set<String>): String = ALL.filter { it in shown }.joinToString(",").ifEmpty { "none" }

    /** 1 つの画面を出す・出さないにした設定 */
    fun toggle(s: String, id: String, show: Boolean): String {
        val cur = parse(s)
        return encode(if (show) cur + id else cur - id)
    }
}
