package com.uyatame.ucrt.aging

import org.json.JSONObject

/**
 * エージングの時間の管理(実際に鳴った時間を数え、休憩をはさむ)。Android の部品には頼らない(PC 上の単体テストで確かめられるように)。
 * 鳴らしている間(play)は、音が実際に出ているときだけ数える。休憩(rest)は、音に関係なく時間で進む
 */
class AgingClock(
    /** 鳴らす時間の合計(ミリ秒) */
    val targetMs: Long,
    /** 休憩をはさむか */
    val interval: Boolean,
    /** 続けて鳴らす時間(ミリ秒) */
    val onMs: Long = ON_MS,
    /** 休憩の時間(ミリ秒) */
    val offMs: Long = OFF_MS,
) {
    enum class Event { REST_START, REST_END, DONE }

    /** 鳴った時間の合計 */
    var playedMs = 0L
        private set
    /** 今の区切り(前の休憩のあと)で鳴った時間 */
    var segmentMs = 0L
        private set
    var resting = false
        private set
    /** 休憩の残り */
    var restLeftMs = 0L
        private set

    val done: Boolean get() = playedMs >= targetMs
    val progress: Float get() = if (targetMs <= 0) 1f else (playedMs.toFloat() / targetMs).coerceIn(0f, 1f)
    val remainingMs: Long get() = (targetMs - playedMs).coerceAtLeast(0)

    /** 次の休憩までの時間(休憩なし・休憩中・もう休憩が来ないなら -1) */
    val untilRestMs: Long get() {
        if (!interval || resting || done) return -1
        val left = onMs - segmentMs
        return if (left >= remainingMs) -1 else left
    }

    /** 終わるまでの時間(休憩も含めた見込み) */
    fun remainingWallMs(): Long {
        val play = remainingMs
        if (!interval || play == 0L) return play + if (resting) restLeftMs else 0
        // これから入る休憩の数(最後の区切りの後には休憩しない)
        val firstLeft = if (resting) onMs else onMs - segmentMs
        val rests = if (play <= firstLeft) 0 else ((play - firstLeft - 1) / onMs + 1)
        return play + rests * offMs + if (resting) restLeftMs else 0
    }

    /**
     * 時間を進める。sounding は、この間に音が実際に出ていたか(鳴らしている区切りで、止まっていたら数えない)。
     * 区切りをまたいだら、残りの時間は次の区切り(休憩・鳴らす)に回す
     */
    fun advance(dtMs: Long, sounding: Boolean): List<Event> {
        val ev = ArrayList<Event>(2)
        var dt = dtMs.coerceAtLeast(0)
        while (dt > 0 && !done) {
            if (resting) {
                val use = minOf(dt, restLeftMs)
                restLeftMs -= use
                dt -= use
                if (restLeftMs <= 0) {
                    resting = false
                    segmentMs = 0
                    ev += Event.REST_END
                }
            } else {
                if (!sounding) break
                var use = minOf(dt, targetMs - playedMs)
                if (interval) use = minOf(use, onMs - segmentMs)
                playedMs += use
                segmentMs += use
                dt -= use
                if (done) {
                    ev += Event.DONE
                } else if (interval && segmentMs >= onMs) {
                    resting = true
                    restLeftMs = offMs
                    ev += Event.REST_START
                }
            }
        }
        return ev
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("targetMs", targetMs)
        put("interval", interval)
        put("onMs", onMs)
        put("offMs", offMs)
        put("playedMs", playedMs)
        put("segmentMs", segmentMs)
        put("resting", resting)
        put("restLeftMs", restLeftMs)
    }

    companion object {
        /** 選べる時間(時間) */
        val HOURS = listOf(25, 50, 100, 200)
        /** 50 分鳴らして 10 分休む(連続で鳴らし続けて、ドライバーや端末が熱を持たないように) */
        const val ON_MS = 50 * 60_000L
        const val OFF_MS = 10 * 60_000L

        fun ofHours(hours: Int, interval: Boolean) = AgingClock(hours * 3_600_000L, interval)

        /** 保存したものから戻す(読めなければ null) */
        fun fromJson(o: JSONObject): AgingClock? {
            val target = o.optLong("targetMs", 0)
            if (target <= 0) return null
            val c = AgingClock(target, o.optBoolean("interval"), o.optLong("onMs", ON_MS).coerceAtLeast(60_000), o.optLong("offMs", OFF_MS).coerceAtLeast(0))
            c.playedMs = o.optLong("playedMs", 0).coerceIn(0, target)
            c.segmentMs = o.optLong("segmentMs", 0).coerceIn(0, c.onMs)
            c.resting = c.interval && o.optBoolean("resting")
            c.restLeftMs = if (c.resting) o.optLong("restLeftMs", 0).coerceIn(0, c.offMs) else 0
            return c
        }
    }
}
