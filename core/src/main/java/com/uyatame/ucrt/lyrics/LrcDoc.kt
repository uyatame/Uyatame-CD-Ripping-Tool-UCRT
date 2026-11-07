package com.uyatame.ucrt.lyrics

import java.util.Locale

/**
 * 歌詞の編集用の、LRC の 1 行(lyrics-editor-module の LrcCodec を、UCRT に合わせて作り直したもの)。
 * timeMs が null の行は、時刻が付いていない行
 */
class LrcLine(val id: Long, val timeMs: Long?, val text: String) {
    fun copy(timeMs: Long? = this.timeMs, text: String = this.text) = LrcLine(id, timeMs, text)
}

/** 歌詞の全体(行・ずれ・[ti:] などの情報) */
class LrcDocument(val lines: List<LrcLine>, val offsetMs: Long = 0, val metadata: Map<String, String> = emptyMap()) {
    /** 時刻の付いた行があるか */
    val synced: Boolean get() = lines.any { it.timeMs != null }
}

/** LRC の読み書きと、編集の操作(Android 4.0 でも動く。画面の部品は使わない) */
object LrcDoc {
    private val TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val META = Regex("^\\[([a-zA-Z]+):(.*)]$")
    private var nextId = 1L

    @Synchronized
    fun newId(): Long = nextId++

    /**
     * LRC(または普通の文章)を読む。1 行に時刻が複数あれば、その数だけ行に分ける。
     * 行の並びはファイルの順のまま(時刻だけの行がまとまっていても、勝手に並べ替えない)。
     * ただし 1 行に複数の時刻があった(繰り返しのサビなど)ときは、時刻順に並べ直す(時刻の無い行は、元の行の後ろに付いたまま)
     */
    fun parse(text: String): LrcDocument {
        val out = ArrayList<LrcLine>()
        val meta = LinkedHashMap<String, String>()
        var offset = 0L
        var multi = false
        for (raw0 in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val raw = raw0.trimEnd()
            val times = TAG.findAll(raw).toList()
            if (times.isNotEmpty()) {
                val lyric = TAG.replace(raw, "").trim()
                if (times.size > 1) multi = true
                for (m in times) out.add(LrcLine(newId(), toMs(m), lyric))
                continue
            }
            val mm = META.matchEntire(raw.trim())
            if (mm != null) {
                val key = mm.groupValues[1].lowercase(Locale.ROOT)
                val value = mm.groupValues[2].trim()
                if (key == "offset") offset = value.toLongOrNull() ?: 0L else meta[key] = value
                continue
            }
            if (raw.isNotBlank()) out.add(LrcLine(newId(), null, raw.trim()))
        }
        val lines = if (multi) sortTimed(out) else out
        return LrcDocument(lines, offset, meta)
    }

    private fun toMs(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val f = m.groupValues[3]
        val frac = when (f.length) {
            0 -> 0L
            1 -> f.toLong() * 100
            2 -> f.toLong() * 10
            else -> f.take(3).toLong()
        }
        return min * 60_000 + sec * 1000 + frac
    }

    /** 時刻の付いた行を時刻順に。時刻の無い行は、直前の時刻の付いた行の後ろに付いたまま動く */
    fun sortTimed(lines: List<LrcLine>): List<LrcLine> {
        class Group(val key: Long, val order: Int, val items: MutableList<LrcLine>)
        val groups = ArrayList<Group>()
        var cur: Group? = null
        lines.forEachIndexed { i, l ->
            val t = l.timeMs
            if (t != null || cur == null) {
                cur = Group(t ?: -1L, i, mutableListOf(l)).also { groups.add(it) }
            } else cur!!.items.add(l)
        }
        return groups.sortedWith(compareBy({ it.key }, { it.order })).flatMap { it.items }
    }

    /** 時刻を [mm:ss.xx] に(百分の一秒は四捨五入) */
    fun stamp(ms: Long): String {
        val cs = (ms.coerceAtLeast(0) + 5) / 10
        return String.format(Locale.US, "[%02d:%02d.%02d]", cs / 6000, (cs / 100) % 60, cs % 100)
    }

    /** LRC にする。時刻の無い行は、文章だけで書く */
    fun encode(doc: LrcDocument): String {
        val sb = StringBuilder()
        for ((k, v) in doc.metadata) sb.append('[').append(k).append(':').append(v).append("]\n")
        if (doc.offsetMs != 0L) sb.append("[offset:").append(doc.offsetMs).append("]\n")
        for (l in doc.lines) {
            l.timeMs?.let { sb.append(stamp(it)) }
            sb.append(l.text).append('\n')
        }
        return sb.toString()
    }

    /** 普通の文章(時刻なし)を行に分ける(歌詞を貼り付けたとき) */
    fun fromPlain(text: String): List<LrcLine> =
        text.replace("\r\n", "\n").split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map { LrcLine(newId(), null, it) }

    // ---- 編集の操作(元の一覧は変えず、新しい一覧を返す) ----

    fun setTime(lines: List<LrcLine>, i: Int, ms: Long?): List<LrcLine> =
        if (i !in lines.indices) lines else lines.toMutableList().also { it[i] = it[i].copy(timeMs = ms?.coerceAtLeast(0)) }

    fun nudge(lines: List<LrcLine>, i: Int, deltaMs: Long): List<LrcLine> {
        val t = lines.getOrNull(i)?.timeMs ?: return lines
        return setTime(lines, i, t + deltaMs)
    }

    fun setText(lines: List<LrcLine>, i: Int, text: String): List<LrcLine> =
        if (i !in lines.indices) lines else lines.toMutableList().also { it[i] = it[i].copy(text = text) }

    fun insertAfter(lines: List<LrcLine>, i: Int, text: String = ""): List<LrcLine> =
        lines.toMutableList().also { it.add((i + 1).coerceIn(0, it.size), LrcLine(newId(), null, text)) }

    fun delete(lines: List<LrcLine>, i: Int): List<LrcLine> =
        if (i !in lines.indices) lines else lines.toMutableList().also { it.removeAt(i) }

    /** 再生位置 posMs で歌っている行(時刻が posMs 以下で、いちばん遅い行)。無ければ -1 */
    fun indexAt(lines: List<LrcLine>, posMs: Long, offsetMs: Long = 0): Int {
        var best = -1
        var bestT = Long.MIN_VALUE
        lines.forEachIndexed { i, l ->
            val t = (l.timeMs ?: return@forEachIndexed) - offsetMs
            if (t <= posMs && t >= bestT) { best = i; bestT = t }
        }
        return best
    }

    /** i の次から、時刻の付いていない最初の行(無ければ i + 1。末尾なら -1) */
    fun nextUntimed(lines: List<LrcLine>, i: Int): Int {
        for (k in i + 1 until lines.size) if (lines[k].timeMs == null) return k
        return if (i + 1 < lines.size) i + 1 else -1
    }
}
