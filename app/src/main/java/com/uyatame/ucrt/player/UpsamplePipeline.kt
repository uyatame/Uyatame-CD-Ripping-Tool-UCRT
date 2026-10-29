package com.uyatame.ucrt.player

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

/**
 * ステレオのアップサンプリングを、複数のコアで流れ作業にする。
 *
 * アップサンプリングの段 × 左右ごとに専用のスレッドを持ち(×8 なら 3 段 × 2 = 6 スレッド)、
 * 各スレッドは前のスレッドから受け取ったかたまりを計算して次のスレッドへ渡すだけなので、
 * 段ごとに全員を待ち合わせる必要がない。最後のスレッドが左右を合わせて out を呼ぶ。
 * 各段・各チャンネルの FIR は 1 スレッドのときと同じものを同じ順に通すので、結果もまったく同じ。
 *
 * out(チャンネル交互の float, フレーム数, 音量の倍率) は、出力用のスレッドから順に呼ばれる。
 */
internal class UpsamplePipeline(factor: Int, private val out: (FloatArray, Int, Float) -> Unit) {
    /** スレッドのあいだで受け渡すかたまり。使い終わったら home(持ち主の空きの列)に返す */
    private class Buf(val home: BlockingQueue<Buf>) {
        var data = FloatArray(0)
        var frames = 0
        var gain = 1f
    }

    @Volatile private var closed = false
    private val threads = ArrayList<Thread>()

    /** 各つなぎ目に置けるかたまりの数 */
    private val depth = 4

    private val inFree = Array(2) { newFree() }
    private val inQ = Array<BlockingQueue<Buf>>(2) { ArrayBlockingQueue(depth) }

    /** 使っているスレッドの数(ログ用) */
    val threadCount: Int get() = threads.size

    private fun newFree(): BlockingQueue<Buf> {
        val q = ArrayBlockingQueue<Buf>(depth)
        repeat(depth) { q.put(Buf(q)) }
        return q
    }

    init {
        // 左右それぞれに、同じ並びの段を作る(状態は別々)
        val chains = Array(2) { Upsampler.plan(factor, 1) }
        var q: Array<BlockingQueue<Buf>> = arrayOf(inQ[0], inQ[1])
        for (si in chains[0].indices) {
            val next = Array<BlockingQueue<Buf>>(2) { ArrayBlockingQueue(depth) }
            for (c in 0..1) {
                val st = chains[c][si]
                val src = q[c]
                val dst = next[c]
                val free = newFree()
                start("upsample-${si + 1}${if (c == 0) "L" else "R"}") {
                    while (!closed) {
                        // 止めるまで待つ(前のスレッドから届けばすぐ起きる。届かない間は起きない)
                        val b = src.take()
                        val o = free.take()
                        val n = b.frames * st.factor
                        if (o.data.size < n) o.data = FloatArray(n)
                        st.process(b.data, b.frames, o.data)
                        o.frames = n
                        o.gain = b.gain
                        b.home.offer(b)
                        dst.put(o)
                    }
                }
            }
            q = next
        }
        val last = q
        start("upsample-out") {
            var buf = FloatArray(0)
            while (!closed) {
                val l = last[0].take()
                val r = last[1].take()
                val n = l.frames
                if (buf.size < n * 2) buf = FloatArray(n * 2)
                val ld = l.data
                val rd = r.data
                var k = 0
                for (i in 0 until n) {
                    buf[k] = ld[i]
                    buf[k + 1] = rd[i]
                    k += 2
                }
                val g = l.gain
                l.home.offer(l)
                r.home.offer(r)
                out(buf, n, g)
            }
        }
    }

    private fun start(name: String, body: () -> Unit) {
        val t = Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            try {
                body()
            } catch (e: InterruptedException) {
                // 終了
            } catch (e: Exception) {
                AudioEngine.log("upsample pipeline error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, name)
        t.isDaemon = true
        threads.add(t)
        t.start()
    }

    /** 列から取り出す(止めるまで待つ。止めたら null) */
    private fun take(q: BlockingQueue<Buf>, stop: () -> Boolean = { false }): Buf? {
        while (!closed && !stop()) {
            q.poll(50, TimeUnit.MILLISECONDS)?.let { return it }
        }
        return null
    }

    /** 列に入れる(空くまで待つ。止めたら false) */
    private fun put(q: BlockingQueue<Buf>, b: Buf, stop: () -> Boolean = { false }): Boolean {
        while (!closed && !stop()) {
            if (q.offer(b, 50, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    /**
     * チャンネル交互(2ch)の float を frames フレーム入れる。流れ作業が詰まっていれば空くまで待つ。
     * stop() が true になったら途中でやめて false を返す
     */
    fun submit(input: FloatArray, frames: Int, gain: Float, stop: () -> Boolean): Boolean {
        val l = take(inFree[0], stop) ?: return false
        val r = take(inFree[1], stop) ?: run { l.home.offer(l); return false }
        if (l.data.size < frames) l.data = FloatArray(frames)
        if (r.data.size < frames) r.data = FloatArray(frames)
        val ld = l.data
        val rd = r.data
        var k = 0
        for (i in 0 until frames) {
            ld[i] = input[k]
            rd[i] = input[k + 1]
            k += 2
        }
        l.frames = frames; r.frames = frames
        l.gain = gain; r.gain = gain
        if (!put(inQ[0], l, stop)) { l.home.offer(l); r.home.offer(r); return false }
        // 左を入れたら、右も必ず入れる(左右のかたまりの順番がずれないように)
        if (!put(inQ[1], r)) return false
        return true
    }

    /** 止める(待っているスレッドは起こして終わらせる) */
    fun close() {
        closed = true
        for (t in threads) t.interrupt()
    }

    val isClosed: Boolean get() = closed
}
