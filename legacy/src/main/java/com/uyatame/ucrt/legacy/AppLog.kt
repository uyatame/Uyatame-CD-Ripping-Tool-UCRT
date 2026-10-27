package com.uyatame.ucrt.legacy

/** アプリのログ(設定 → ログで見る。本家の logText) */
object AppLog {
    private val sb = StringBuilder()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    val text: String get() = synchronized(sb) { sb.toString() }

    fun log(msg: String) {
        synchronized(sb) {
            sb.append(fmt.format(java.util.Date())).append("  ").append(msg).append('\n')
            // 長くなりすぎたら古い分を捨てる
            if (sb.length > 200_000) sb.delete(0, sb.length - 150_000)
        }
        Bus.emit(Bus.SETTINGS)
    }
}
