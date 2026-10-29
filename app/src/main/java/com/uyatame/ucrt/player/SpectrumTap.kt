package com.uyatame.ucrt.player

import android.media.AudioFormat as AFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * スペクトラムアナライザーのための音の写し(再生の画面に表示するだけ。再生の処理は待たせない)。
 *
 * 出力に書き込んだ音を、左右を混ぜた 1 チャンネルにして輪のバッファーに入れておき、画面は「今鳴っている位置」
 * (出力の再生位置。USB DAC 直接出力でも Android の出力でも同じ数え方)までの音を取り出して周波数に分ける。
 * 書き込んだ時点ではなく鳴った時点の音を見るので、出力のバッファーが長くても表示がずれない。
 * 高い周波数の音源は、平均を取って 40 kHz 前後に落として入れる(表示は 20 kHz まで)。
 * 表示していない間(設定がオフ・再生の画面を開いていない)は何もしない
 */
object SpectrumTap {
    /** 画面に表示しているか(表示している間だけ写す) */
    @Volatile var enabled = false

    /** 表示している画面の数(画面の切り替えの途中などで 2 つ重なっても、両方が消えるまで写し続ける) */
    private var users = 0
    private val usersLock = Any()

    /** 表示を始めた画面から呼ぶ */
    fun acquire() = synchronized(usersLock) { users++; enabled = true }

    /** 表示をやめた画面から呼ぶ */
    fun release() = synchronized(usersLock) {
        users = (users - 1).coerceAtLeast(0)
        enabled = users > 0
    }

    private const val RING = 1 shl 17
    private const val MASK = RING - 1
    const val FFT_SIZE = 2048
    const val BANDS = 48

    private val ring = FloatArray(RING)
    private val lock = Any()
    /** 写している出力(変わったら最初から)と、その再生位置(入れたときの数え方で) */
    private var owner: Any? = null
    private var headOf: (() -> Long)? = null
    /** 輪のバッファーに入れたサンプルの数(間引いたあと) */
    private var stored = 0L
    /** 間引く割合(入力の何フレームを 1 つにまとめるか) */
    private var decim = 1
    private var acc = 0f
    private var accN = 0
    /** 入れ始めた位置(出力のフレームで数える。間引く前) */
    private var baseFrame = 0L
    private var rate = 44100

    /**
     * 出力に書き込む前に呼ぶ。src は書き込む音(位置は動かさない)、startFrame はその先頭の、出力の中での位置
     */
    internal fun submit(out: PcmOut, src: ByteBuffer, enc: Int, frames: Int, channels: Int, sampleRate: Int, startFrame: Long) {
        if (!enabled || frames <= 0 || channels <= 0) return
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bps = pcmBytes(enc).takeIf { it > 0 } ?: return
        synchronized(lock) {
            if (owner !== out || sampleRate != rate || startFrame != baseFrame + stored * decim + accN) {
                owner = out
                headOf = { out.headFrames() }
                rate = sampleRate
                decim = (sampleRate / 40000).coerceAtLeast(1)
                stored = 0
                acc = 0f; accN = 0
                baseFrame = startFrame
            }
            val use = min(channels, 2)
            val skip = (channels - use) * bps
            val inv = 1f / use
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until use) sum += sample(b, enc)
                if (skip > 0) b.position(b.position() + skip)
                acc += sum * inv
                if (++accN == decim) {
                    ring[(stored and MASK.toLong()).toInt()] = acc / decim
                    stored++
                    acc = 0f; accN = 0
                }
            }
        }
    }

    private fun sample(b: ByteBuffer, enc: Int): Float = when (enc) {
        AFormat.ENCODING_PCM_16BIT -> b.short / 32768f
        AFormat.ENCODING_PCM_FLOAT -> b.float
        AFormat.ENCODING_PCM_24BIT_PACKED -> {
            val b0 = b.get().toInt() and 0xFF
            val b1 = b.get().toInt() and 0xFF
            val b2 = b.get().toInt()
            ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
        }
        AFormat.ENCODING_PCM_32BIT -> b.int / 2147483648f
        AFormat.ENCODING_PCM_8BIT -> ((b.get().toInt() and 0xFF) - 128) / 128f
        else -> 0f
    }

    /** 1 バイトの 1 の数 → -1〜1 */
    private val ONES = FloatArray(256) { (Integer.bitCount(it) * 2 - 8) / 8f }

    /**
     * DSD のまま出すとき(ネイティブ DSD・DoP)に、DSD のバイト列を写す。1 の割合を平均して 44.1 kHz 前後の PCM にする
     * (表示用の簡単な変換。DSD の 0 dB をフルスケールに合わせて 2 倍する)。
     * startByte はその先頭の、1 チャンネルあたりのバイトの位置。head は今鳴っている位置(同じくバイト)
     */
    internal fun submitDsd(owner: Any, head: () -> Long, input: Array<ByteArray>, n: Int, channels: Int, dsdRate: Int, startByte: Long) {
        if (!enabled || n <= 0 || channels <= 0) return
        val byteRate = dsdRate / 8
        synchronized(lock) {
            if (this.owner !== owner || byteRate != rate || startByte != baseFrame + stored * decim + accN) {
                this.owner = owner
                headOf = head
                rate = byteRate
                decim = (byteRate / 44100).coerceAtLeast(1)
                stored = 0
                acc = 0f; accN = 0
                baseFrame = startByte
            }
            val use = min(channels, 2)
            val g = 2f / use
            for (i in 0 until n) {
                var sum = 0f
                for (c in 0 until use) sum += ONES[input[c][i].toInt() and 0xFF]
                acc += sum * g
                if (++accN == decim) {
                    ring[(stored and MASK.toLong()).toInt()] = acc / decim
                    stored++
                    acc = 0f; accN = 0
                }
            }
        }
    }

    /** 出力をやめたとき(その出力の音はもう見せない) */
    internal fun forget(out: Any) {
        synchronized(lock) { if (owner === out) { owner = null; headOf = null; stored = 0 } }
    }

    /**
     * 今鳴っている位置までの FFT_SIZE 個の音を dst に入れる。delayMs だけ前の音にする(Bluetooth などの遅れの補正)。
     * 音が無ければ 0(入れた周波数を返す)
     */
    /** 再生位置が最後に進んだ時刻(止まっているかを見る。window を呼ぶスレッドだけが使う) */
    private var lastHead = -1L
    private var lastHeadOwner: Any? = null
    private var headMovedAt = 0L

    fun window(dst: FloatArray, delayMs: Int): Int {
        val out: Any
        val h: () -> Long
        val r: Int
        synchronized(lock) { out = owner ?: return 0; h = headOf ?: return 0; r = rate }
        // 再生位置は出力から読む(ロックの外で。AudioTrack に問い合わせるため)
        val raw = runCatching { h() }.getOrDefault(0L)
        // 再生位置が少しの間進まなければ(一時停止・停止)、音は鳴っていないので 0 にする(バーがすっと下がる)。
        // 位置はおおよそ数 ms〜数十 ms ごとにしか進まないので、少し待ってから決める
        val now = System.nanoTime() / 1_000_000L
        if (raw != lastHead || out !== lastHeadOwner) { lastHead = raw; lastHeadOwner = out; headMovedAt = now }
        else if (now - headMovedAt > 150) return 0
        val head = raw - delayMs.toLong() * r / 1000
        synchronized(lock) {
            if (owner !== out || stored == 0L) return 0
            val end = ((head - baseFrame) / decim).coerceIn(0L, stored)
            val start = end - dst.size
            for (i in dst.indices) {
                val k = start + i
                // まだ届いていない・もう上書きした部分は 0
                dst[i] = if (k < 0 || k < stored - RING) 0f else ring[(k and MASK.toLong()).toInt()]
            }
            return r / decim
        }
    }
}

/**
 * 周波数の帯ごとの強さ(0〜1)を求める(画面のスレッドとは別に、決まった間隔で呼ぶ)。
 * 35 Hz〜20 kHz を対数で BANDS に分け、-72〜-6 dBFS を 0〜1 にする
 */
class SpectrumAnalyzer {
    private val buf = FloatArray(SpectrumTap.FFT_SIZE)
    private val re = FloatArray(SpectrumTap.FFT_SIZE)
    private val im = FloatArray(SpectrumTap.FFT_SIZE)
    private val win = FloatArray(SpectrumTap.FFT_SIZE) { (0.5 - 0.5 * cos(2.0 * PI * it / (SpectrumTap.FFT_SIZE - 1))).toFloat() }
    /** 波形(表示用に 128 点) */
    val wave = FloatArray(128)
    val bands = FloatArray(SpectrumTap.BANDS)

    /** 計算する。音が無ければ false(bands は 0 にする) */
    fun update(delayMs: Int): Boolean {
        val r = SpectrumTap.window(buf, delayMs)
        if (r <= 0) {
            bands.fill(0f); wave.fill(0f)
            return false
        }
        val n = buf.size
        for (i in 0 until n) { re[i] = buf[i] * win[i]; im[i] = 0f }
        fft(re, im)
        val minHz = 35.0
        val maxHz = min(20000.0, r * 0.5)
        for (band in bands.indices) {
            val f0 = minHz * Math.pow(maxHz / minHz, band.toDouble() / bands.size)
            val f1 = minHz * Math.pow(maxHz / minHz, (band + 1.0) / bands.size)
            val k0 = max(1, (f0 * n / r).toInt())
            val k1 = max(k0 + 1, min(n / 2, (f1 * n / r).toInt()))
            var m = 0f
            for (k in k0 until k1) m = max(m, re[k] * re[k] + im[k] * im[k])
            // ハン窓の利得(約 0.5)と FFT の大きさで割って、フルスケールに対する振幅にする
            val amp = (4.0 * sqrt(m.toDouble()) / n).coerceAtLeast(1e-9)
            bands[band] = ((20.0 * log10(amp) + 72.0) / 66.0).toFloat().coerceIn(0f, 1f)
        }
        val step = n / wave.size
        for (i in wave.indices) wave[i] = buf[n - wave.size * step + i * step]
        return true
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = real[i]; real[i] = real[j]; real[j] = t }
        }
        var len = 2
        while (len <= n) {
            val a = -2.0 * PI / len
            val wr0 = cos(a).toFloat(); val wi0 = sin(a).toFloat()
            val half = len / 2
            var base = 0
            while (base < n) {
                var wr = 1f; var wi = 0f
                for (k in 0 until half) {
                    val p = base + k; val q = p + half
                    val vr = real[q] * wr - imag[q] * wi
                    val vi = real[q] * wi + imag[q] * wr
                    real[q] = real[p] - vr; imag[q] = imag[p] - vi
                    real[p] += vr; imag[p] += vi
                    val t = wr * wr0 - wi * wi0
                    wi = wr * wi0 + wi * wr0; wr = t
                }
                base += len
            }
            len = len shl 1
        }
    }
}
