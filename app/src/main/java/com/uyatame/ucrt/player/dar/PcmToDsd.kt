package com.uyatame.ucrt.player.dar

import com.uyatame.ucrt.player.Upsampler
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 1 チャンネル分のデルタシグマ変調(5 次)。直線補間で DSD の周波数まで上げながら 1bit にする。
 * f2(1 サンプルあたりのビット数)は整数でなくてもよい(48 kHz 系の音源を 44.1 kHz 系の DSD にするとき)。
 *
 * ノイズシェーピングは NTF = (1 - z^-1)^5 / D(z)(D は高域の利得が 1.5 になるよう選んだ極)。
 * 誤差を返すフィルター (NTF - 1) を転置直接形 II で計算する(状態は 5 つ。値の入れ替えが少なく速い)。
 */
internal class SdmChannel(private val f2d: Double) {
    /** f2 が整数ならその値(整数でなければ 0) */
    private val f2: Int = if (f2d == Math.rint(f2d)) f2d.toInt() else 0
    /** 整数でないとき: 次のビットの位置(前のサンプルからの距離。サンプルの間隔を 1 とする) */
    private var phase = 1.0 / f2d
    private var prev = 0f
    private var s1 = 0.0; private var s2 = 0.0; private var s3 = 0.0; private var s4 = 0.0; private var s5 = 0.0
    /** 作りかけのバイトとそのビット数 */
    private var acc = 0
    private var nb = 0

    /**
     * src の n1 サンプル(元の周波数 × f1)を変調して dst の先頭から書く。書いたバイト数を返す。
     * 左右で同じだけ進めれば、書くバイト数も左右で同じになる
     */
    fun modulate(src: FloatArray, n1: Int, gain: Float, dst: ByteArray): Int {
        val b1 = -0.807717848861496; val b2 = 2.9142085842917167; val b3 = -3.9703926164888994
        val b4 = 2.418792061036653; val b5 = -0.5555555559879046
        val d1 = -4.192282151138504; val d2 = 7.085791415708283; val d3 = -6.029607383511101
        val d4 = 2.581207938963347; val d5 = -0.4444444440120954
        var x1 = s1; var x2 = s2; var x3 = s3; var x4 = s4; var x5 = s5
        var a = acc
        var bits = nb
        var o = 0
        var p = prev
        // DSD の 0 dB(変調度 50%)に合わせて半分にし、変調器が不安定にならないよう ±0.5 で抑える
        val g = gain * 0.5f
        if (f2 == 0) {
            // 整数でないとき: ビットの位置を少しずつ進めて、前後のサンプルの間を直線補間する
            val inc = 1.0 / f2d
            var t = phase
            for (i in 0 until n1) {
                var cur = src[i] * g
                if (cur > 0.5f) cur = 0.5f else if (cur < -0.5f) cur = -0.5f else if (cur.isNaN()) cur = 0f
                val d = (cur - p).toDouble()
                while (t <= 1.0) {
                    val v0 = p + d * t
                    t += inc
                    val w = x1
                    val v = v0 + w
                    if (v > 8.0 || v < -8.0) {
                        x1 = 0.0; x2 = 0.0; x3 = 0.0; x4 = 0.0; x5 = 0.0
                        a = (a shl 1) or (if (v >= 0.0) 1 else 0)
                    } else {
                        val bit = v >= 0.0
                        val e = (if (bit) 1.0 else -1.0) - v
                        x1 = x2 + b1 * e - d1 * w
                        x2 = x3 + b2 * e - d2 * w
                        x3 = x4 + b3 * e - d3 * w
                        x4 = x5 + b4 * e - d4 * w
                        x5 = b5 * e - d5 * w
                        a = (a shl 1) or (if (bit) 1 else 0)
                    }
                    bits++
                    if (bits == 8) {
                        dst[o++] = a.toByte()
                        a = 0
                        bits = 0
                    }
                }
                t -= 1.0
                p = cur
            }
            phase = t
            prev = p
            s1 = x1; s2 = x2; s3 = x3; s4 = x4; s5 = x5
            acc = a
            nb = bits
            return o
        }
        val inv = 1f / f2
        if (f2 % 8 == 0 && bits == 0) {
            // ほとんどの場合: 1 サンプルからちょうど f2 / 8 バイトできるので、8 ビットずつまとめて作る(バイトの区切りの判定が要らない)
            val bytesPer = f2 / 8
            for (i in 0 until n1) {
                var cur = src[i] * g
                if (cur > 0.5f) cur = 0.5f else if (cur < -0.5f) cur = -0.5f else if (cur.isNaN()) cur = 0f
                val step = (cur - p) * inv
                var v0 = p
                for (q in 0 until bytesPer) {
                    var byte = 0
                    for (j in 0 until 8) {
                        v0 += step
                        val w = x1
                        val v = v0 + w
                        val bit = v >= 0.0
                        if (v > 8.0 || v < -8.0) {
                            x1 = 0.0; x2 = 0.0; x3 = 0.0; x4 = 0.0; x5 = 0.0
                        } else {
                            val e = (if (bit) 1.0 else -1.0) - v
                            x1 = x2 + b1 * e - d1 * w
                            x2 = x3 + b2 * e - d2 * w
                            x3 = x4 + b3 * e - d3 * w
                            x4 = x5 + b4 * e - d4 * w
                            x5 = b5 * e - d5 * w
                        }
                        byte = (byte shl 1) or (if (bit) 1 else 0)
                    }
                    dst[o++] = byte.toByte()
                }
                p = cur
            }
            prev = p
            s1 = x1; s2 = x2; s3 = x3; s4 = x4; s5 = x5
            return o
        }
        for (i in 0 until n1) {
            var cur = src[i] * g
            if (cur > 0.5f) cur = 0.5f else if (cur < -0.5f) cur = -0.5f else if (cur.isNaN()) cur = 0f
            val step = (cur - p) * inv
            var v0 = p
            for (j in 0 until f2) {
                v0 += step
                val w = x1
                val v = v0 + w
                if (v > 8.0 || v < -8.0) {
                    // 万一発散しかけたら状態を消して立て直す(音が一瞬途切れるだけで済むように)
                    x1 = 0.0; x2 = 0.0; x3 = 0.0; x4 = 0.0; x5 = 0.0
                    a = (a shl 1) or (if (v >= 0.0) 1 else 0)
                } else {
                    val bit = v >= 0.0
                    val e = (if (bit) 1.0 else -1.0) - v
                    x1 = x2 + b1 * e - d1 * w
                    x2 = x3 + b2 * e - d2 * w
                    x3 = x4 + b3 * e - d3 * w
                    x4 = x5 + b4 * e - d4 * w
                    x5 = b5 * e - d5 * w
                    a = (a shl 1) or (if (bit) 1 else 0)
                }
                bits++
                if (bits == 8) {
                    dst[o++] = a.toByte()
                    a = 0
                    bits = 0
                }
            }
            p = cur
        }
        prev = p
        s1 = x1; s2 = x2; s3 = x3; s4 = x4; s5 = x5
        acc = a
        nb = bits
        return o
    }
}

/**
 * ステレオの PCM を DSD(1bit)に変換する(DAR の DSD 変換)。複数のコアで流れ作業にする。
 *
 * 1. 多相 FIR で 352.8 / 384 kHz 付近まで整数倍にアップサンプリング(専用のスレッド)
 * 2. 左右それぞれ、直線補間で DSD の周波数まで上げて 5 次のデルタシグマ変調(左右で別々のスレッド)
 * 3. 左右をそろえ、出力先の単位(align バイト)にまとめて out を呼ぶ(専用のスレッド)
 *
 * dsdRate は DSD の周波数。音源の周波数の整数倍でなくてもよい(48 kHz 系の音源を 44.1 kHz 系の DSD にするとき。
 * そのときは 2 の直線補間の倍率が整数でなくなる)。out(左右のバイト列, バイト数)には先頭のビットが先、1 = プラスで渡す。
 * onLoad には、変調のスレッドの忙しさ(計算にかかった時間 ÷ 音の長さ。1 を超えると追いつかない)を時々知らせる。
 */
internal class PcmToDsd(
    private val srcRate: Int,
    private val dsdRate: Int,
    private val align: Int,
    private val out: (Array<ByteArray>, Int) -> Unit,
    private val onLoad: (Double) -> Unit = {},
) {
    /** 1 段目(FIR)の倍率。2 段目(直線補間)は dsdRate ÷ (srcRate × f1) */
    private val f1: Int
    private val f2: Double

    init {
        var f = 1
        if (dsdRate % srcRate == 0) {
            // 整数倍のとき: 2 段目も整数倍になるように選ぶ(これまでと同じ)
            val ratio = dsdRate / srcRate
            for (c in 1..ratio) {
                if (ratio % c == 0 && srcRate.toLong() * c <= 384_000L) f = c
            }
        } else {
            while (srcRate.toLong() * (f + 1) <= 384_000L) f++
        }
        f1 = f
        f2 = dsdRate.toDouble() / (srcRate.toLong() * f)
    }

    private class Buf(val home: BlockingQueue<Buf>) {
        var f = FloatArray(0)
        var b = ByteArray(0)
        var n = 0
        var gain = 1f
    }

    @Volatile private var closed = false
    private val depth = 4
    private fun pool(): BlockingQueue<Buf> = ArrayBlockingQueue<Buf>(depth).also { q -> repeat(depth) { q.put(Buf(q)) } }

    private val inFree = pool()
    private val inQ: BlockingQueue<Buf> = ArrayBlockingQueue(depth)
    private val upFree = Array(2) { pool() }
    private val upQ = Array<BlockingQueue<Buf>>(2) { ArrayBlockingQueue(depth) }
    private val bitFree = Array(2) { pool() }
    private val bitQ = Array<BlockingQueue<Buf>>(2) { ArrayBlockingQueue(depth) }

    private val threads = ArrayList<Thread>()

    /**
     * 列から取り出す。流れ作業のスレッド(stop なし)は届くまで眠って待ち(止めるときは close が起こす)、
     * 渡す側(stop あり)は stop を確かめながら待つ。止めたら null
     */
    private fun take(q: BlockingQueue<Buf>, stop: (() -> Boolean)? = null): Buf? {
        if (stop == null) return if (closed) null else q.take()
        while (!closed && !stop()) q.poll(50, TimeUnit.MILLISECONDS)?.let { return it }
        return null
    }

    /** 列に入れる(take と同じく、流れ作業のスレッドは空くまで眠って待つ)。止めたら false */
    private fun put(q: BlockingQueue<Buf>, b: Buf, stop: (() -> Boolean)? = null): Boolean {
        if (stop == null) { if (closed) return false; q.put(b); return true }
        while (!closed && !stop()) if (q.offer(b, 50, TimeUnit.MILLISECONDS)) return true
        return false
    }

    private fun start(name: String, body: () -> Unit) {
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            try {
                body()
            } catch (e: InterruptedException) {
                // 終了
            } catch (e: Exception) {
                com.uyatame.ucrt.player.AudioEngine.log("dsd convert error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, name).apply { isDaemon = true; threads.add(this) }.start()
    }

    init {
        // 1) アップサンプリング(ステレオをまとめて計算し、左右に分ける)
        start("dsd-upsample") {
            val up = if (f1 > 1) Upsampler(f1, 2) else null
            var tmp = FloatArray(0)
            while (!closed) {
                val b = take(inQ) ?: break
                val n1 = b.n * f1
                val inter: FloatArray
                if (up != null) {
                    if (tmp.size < n1 * 2) tmp = FloatArray(n1 * 2)
                    up.process(b.f, b.n, tmp)
                    inter = tmp
                } else {
                    inter = b.f
                }
                val l = take(upFree[0]) ?: break
                val r = take(upFree[1]) ?: break
                if (l.f.size < n1) l.f = FloatArray(n1)
                if (r.f.size < n1) r.f = FloatArray(n1)
                val lf = l.f
                val rf = r.f
                var k = 0
                for (i in 0 until n1) {
                    lf[i] = inter[k]
                    rf[i] = inter[k + 1]
                    k += 2
                }
                l.n = n1; r.n = n1
                l.gain = b.gain; r.gain = b.gain
                inFree.offer(b)
                if (!put(upQ[0], l)) break
                if (!put(upQ[1], r)) break
            }
        }
        // 2) 左右それぞれの変調
        for (c in 0..1) {
            start("dsd-sdm-${if (c == 0) "L" else "R"}") {
                val sdm = SdmChannel(f2)
                var busyNs = 0L
                var audioNs = 0L
                while (!closed) {
                    val b = take(upQ[c]) ?: break
                    val o = take(bitFree[c]) ?: break
                    val maxBytes = (b.n.toLong() * Math.ceil(f2).toLong() / 8 + 2).toInt()
                    if (o.b.size < maxBytes) o.b = ByteArray(maxBytes)
                    val t0 = System.nanoTime()
                    o.n = sdm.modulate(b.f, b.n, b.gain, o.b)
                    busyNs += System.nanoTime() - t0
                    audioNs += b.n * 1_000_000_000L / (srcRate.toLong() * f1)
                    upFree[c].offer(b)
                    if (!put(bitQ[c], o)) break
                    // 約 2 秒ごとに忙しさを知らせる
                    if (c == 0 && audioNs >= 2_000_000_000L) {
                        onLoad(busyNs.toDouble() / audioNs)
                        busyNs = 0L
                        audioNs = 0L
                    }
                }
            }
        }
        // 3) 左右をそろえて、出力先の単位にまとめて渡す
        start("dsd-out") {
            val carry = Array(2) { ByteArray(align) }
            var carryLen = 0
            var outB = Array(2) { ByteArray(0) }
            while (!closed) {
                val l = take(bitQ[0]) ?: break
                val r = take(bitQ[1]) ?: break
                val total = carryLen + l.n
                if (outB[0].size < total) outB = Array(2) { ByteArray(total + 64) }
                for (c in 0..1) {
                    System.arraycopy(carry[c], 0, outB[c], 0, carryLen)
                    val src = if (c == 0) l else r
                    System.arraycopy(src.b, 0, outB[c], carryLen, src.n)
                }
                bitFree[0].offer(l)
                bitFree[1].offer(r)
                val emit = total / align * align
                carryLen = total - emit
                for (c in 0..1) System.arraycopy(outB[c], emit, carry[c], 0, carryLen)
                if (emit > 0) out(outB, emit)
            }
        }
    }

    /**
     * チャンネル交互(2ch)の float を frames フレーム入れる。gain を掛けてから変換する。
     * 流れ作業が詰まっていれば空くまで待つ。stop() が true になったら false を返す
     */
    fun submit(input: FloatArray, frames: Int, gain: Float, stop: () -> Boolean): Boolean {
        if (frames <= 0) return true
        val b = take(inFree, stop) ?: return false
        if (b.f.size < frames * 2) b.f = FloatArray(frames * 2)
        System.arraycopy(input, 0, b.f, 0, frames * 2)
        b.n = frames
        b.gain = gain
        if (!put(inQ, b, stop)) { inFree.offer(b); return false }
        return true
    }

    /** 止める(待っているスレッドは起こして終わらせる) */
    fun close() {
        closed = true
        for (t in threads) t.interrupt()
    }

    val isClosed: Boolean get() = closed
}
