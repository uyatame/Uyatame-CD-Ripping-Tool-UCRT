package com.uyatame.ucrt.library

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** CUE シートの 1 曲 */
class CueTrack(
    val number: Int,
    val title: String?,
    val performer: String?,
    /** ファイルの先頭からの位置(INDEX 01) */
    val startMs: Long,
)

/** CUE シートの中の 1 ファイル(FILE 行)と、その中の曲 */
class CueFile(val name: String, val tracks: List<CueTrack>)

class CueSheet(
    val title: String?,
    val performer: String?,
    val date: String?,
    val genre: String?,
    val files: List<CueFile>,
)

/**
 * CUE シート(1 つの音声ファイルを曲ごとに分ける目次)を読む。
 * 日本の CUE シートは Shift_JIS のことが多いので、UTF-8 として読めなければ Shift_JIS として読む。
 */
object Cue {
    /** 曲を指す URI に付ける印(元のファイルの URI + "#cue=曲番号") */
    private const val MARK = "#cue="

    fun trackUri(fileUri: String, number: Int): String = fileUri + MARK + number

    /** CUE シートで分けた曲か */
    fun isCue(uri: String): Boolean = MARK in uri

    /** 曲の URI から、元の音声ファイルの URI(CUE シートの曲でなければそのまま) */
    fun fileUri(uri: String): String = uri.substringBefore(MARK)

    private val sjis: Charset? = runCatching { Charset.forName("windows-31j") }
        .recoverCatching { Charset.forName("Shift_JIS") }.getOrNull()

    fun decode(b: ByteArray): String {
        var off = 0
        // UTF-8 の BOM
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) off = 3
        fun strict(cs: Charset): String? = runCatching {
            cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, off, b.size - off)).toString()
        }.getOrNull()
        return strict(Charsets.UTF_8) ?: sjis?.let { strict(it) } ?: String(b, off, b.size - off, Charsets.ISO_8859_1)
    }

    /** 行の残りを 1 つの値として取り出す(ダブルクォートで囲まれていれば外す) */
    private fun value(rest: String): String {
        val s = rest.trim()
        if (s.startsWith("\"")) {
            val end = s.indexOf('"', 1)
            return if (end > 0) s.substring(1, end) else s.substring(1)
        }
        return s
    }

    /** "mm:ss:ff"(ff は 1/75 秒)をミリ秒に */
    private fun msf(s: String): Long? {
        val p = s.trim().split(':')
        if (p.size != 3) return null
        val m = p[0].toLongOrNull() ?: return null
        val sec = p[1].toLongOrNull() ?: return null
        val f = p[2].toLongOrNull() ?: return null
        return (m * 60 + sec) * 1000 + f * 1000 / 75
    }

    fun parse(text: String): CueSheet? {
        var title: String? = null
        var performer: String? = null
        var date: String? = null
        var genre: String? = null
        val files = ArrayList<CueFile>()
        var fileName: String? = null
        var tracks = ArrayList<CueTrack>()
        // 読み途中の曲
        var tNum = -1
        var tTitle: String? = null
        var tPerf: String? = null
        var tStart: Long? = null
        var tIndex0: Long? = null

        fun endTrack() {
            if (tNum > 0) {
                val st = tStart ?: tIndex0
                if (st != null) tracks.add(CueTrack(tNum, tTitle, tPerf, st))
            }
            tNum = -1; tTitle = null; tPerf = null; tStart = null; tIndex0 = null
        }
        fun endFile() {
            endTrack()
            val n = fileName
            if (n != null && tracks.isNotEmpty()) files.add(CueFile(n, tracks))
            tracks = ArrayList()
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val sp = line.indexOf(' ')
            val cmd = (if (sp < 0) line else line.substring(0, sp)).uppercase()
            val rest = if (sp < 0) "" else line.substring(sp + 1)
            when (cmd) {
                "FILE" -> {
                    // INDEX 01 がまだの曲(曲間を前のファイルの終わりに付けた CUE シート)は、次のファイルの曲にする
                    val carry = tNum > 0 && tStart == null
                    val cNum = tNum; val cTitle = tTitle; val cPerf = tPerf
                    if (carry) tNum = -1
                    endFile()
                    if (carry) { tNum = cNum; tTitle = cTitle; tPerf = cPerf }
                    // FILE "名前" WAVE: 最後の語(種類)を除いた部分が名前
                    val r = rest.trim()
                    fileName = if (r.startsWith("\"")) value(r) else r.substringBeforeLast(' ').trim().ifEmpty { r }
                }
                "TRACK" -> {
                    endTrack()
                    val parts = rest.trim().split(Regex("\\s+"))
                    // 音声以外(データ)の曲は扱わない
                    if (parts.getOrNull(1)?.uppercase() == "AUDIO") tNum = parts[0].toIntOrNull() ?: -1
                }
                "TITLE" -> if (tNum > 0) tTitle = value(rest) else if (fileName == null || files.isEmpty() && tracks.isEmpty()) title = value(rest)
                "PERFORMER" -> if (tNum > 0) tPerf = value(rest) else if (fileName == null || files.isEmpty() && tracks.isEmpty()) performer = value(rest)
                "INDEX" -> if (tNum > 0) {
                    val parts = rest.trim().split(Regex("\\s+"))
                    val t = parts.getOrNull(1)?.let { msf(it) }
                    when (parts.getOrNull(0)?.toIntOrNull()) {
                        1 -> tStart = t
                        0 -> tIndex0 = t
                    }
                }
                "REM" -> {
                    val r = rest.trim()
                    val key = r.substringBefore(' ').uppercase()
                    val v = value(r.substringAfter(' ', ""))
                    if (v.isNotEmpty()) when (key) {
                        "DATE" -> date = v.take(4)
                        "GENRE" -> genre = v
                    }
                }
            }
        }
        endFile()
        if (files.isEmpty()) return null
        return CueSheet(title?.ifBlank { null }, performer?.ifBlank { null }, date, genre?.ifBlank { null }, files)
    }
}
