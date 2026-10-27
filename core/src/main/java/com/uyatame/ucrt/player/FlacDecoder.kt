package com.uyatame.ucrt.player

import java.io.IOException

/** 位置を指定して読めるデータ(ファイル) */
internal interface RandomInput {
    val size: Long
    /** pos から最大 len バイト読む。読んだバイト数(終わりなら 0 以下) */
    fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int
}

/**
 * FLAC のデコーダー(アプリ内蔵)。
 *
 * 端末の OS のデコーダー(MediaCodec)に頼らずに FLAC を PCM にする。
 * 端末によっては OS のデコーダーがうまく動かない(Walkman の OMX.google.flac.decoder など)ので、
 * FLAC はこちらで読む。結果は元の PCM とビット単位で同じ(FLAC は可逆圧縮なので)。
 *
 * 対応: ネイティブ FLAC(.flac)、1〜8ch、4〜32bit、固定・可変ブロックサイズ。Ogg FLAC は非対応(OS のデコーダーに任せる)。
 */
internal class FlacDecoder(private val src: RandomInput) : PcmDecoder {
    override var sampleRate = 0
        private set
    override var channels = 0
        private set
    override var bitsPerSample = 0
        private set
    /** 全体のサンプル数(1 チャンネルあたり。分からなければ 0) */
    override var totalSamples = 0L
        private set
    private var minBlock = 0
    private var maxBlock = 0
    /** 最初のフレームの位置 */
    private var firstFrame = 0L

    /** デコードしたフレーム: samples[ch][0 until blockSize]、最初のサンプルの番号 */
    override var samples: Array<IntArray> = emptyArray()
        private set
    override var blockSize = 0
        private set
    override var frameSample = 0L
        private set

    // ---- バイトの読み込み ----
    private val buf = ByteArray(1 shl 16)
    private var bufPos = 0L
    private var bufLen = 0
    private var idx = 0
    // ---- ビットの読み込み(acc の下位 accBits ビットが未読) ----
    private var acc = 0L
    private var accBits = 0

    private fun nextByte(): Int {
        if (idx >= bufLen) {
            bufPos += bufLen
            bufLen = src.read(bufPos, buf, 0, buf.size).coerceAtLeast(0)
            idx = 0
            if (bufLen == 0) throw EOF
        }
        return buf[idx++].toInt() and 0xFF
    }

    /** 次に読むバイトの位置(バイトの境目にいるときだけ正しい) */
    private fun bytePos(): Long = bufPos + idx - accBits / 8

    private fun seekByte(pos: Long) {
        acc = 0
        accBits = 0
        // 読み込み済みの範囲なら、読み直さずに位置だけ動かす
        if (pos >= bufPos && pos < bufPos + bufLen) {
            idx = (pos - bufPos).toInt()
            return
        }
        bufPos = pos
        bufLen = 0
        idx = 0
    }

    private fun alignByte() {
        accBits -= accBits % 8
        acc = if (accBits == 0) 0 else acc and ((1L shl accBits) - 1)
    }

    /** n ビット(0〜32)を符号なしで読む */
    private fun bits(n: Int): Int {
        if (n == 0) return 0
        while (accBits < n) {
            // 読み込み済みの範囲なら、関数を呼ばずにその場で読む(1 サンプルごとに通るので)
            val b = if (idx < bufLen) buf[idx++].toInt() and 0xFF else nextByte()
            acc = (acc shl 8) or b.toLong()
            accBits += 8
        }
        accBits -= n
        val v = (acc ushr accBits).toInt() and (if (n == 32) -1 else (1 shl n) - 1)
        acc = acc and ((1L shl accBits) - 1)
        return v
    }

    /** n ビット(0〜64)を符号なしで読む */
    private fun bitsLong(n: Int): Long =
        if (n <= 32) bits(n).toLong() and 0xFFFFFFFFL
        else (bitsLong(n - 32) shl 32) or (bits(32).toLong() and 0xFFFFFFFFL)

    /** n ビットの符号付き整数 */
    private fun signed(n: Int): Long {
        if (n == 0) return 0
        val v = bitsLong(n)
        return (v shl (64 - n)) shr (64 - n)
    }

    /** 1 が出るまでの 0 の数 */
    private fun unary(): Int {
        var q = 0
        while (true) {
            if (accBits == 0) {
                acc = (if (idx < bufLen) buf[idx++].toInt() and 0xFF else nextByte()).toLong()
                accBits = 8
            }
            val v = acc and ((1L shl accBits) - 1)
            if (v == 0L) {
                q += accBits
                accBits = 0
                acc = 0
                continue
            }
            val width = 64 - java.lang.Long.numberOfLeadingZeros(v) // 1 のあるビットまでの幅
            q += accBits - width
            accBits = width - 1
            acc = v and ((1L shl accBits) - 1)
            return q
        }
    }

    // ---- ヘッダー ----

    /** ファイルの先頭を読む。FLAC でなければ例外 */
    override fun open() {
        seekByte(0)
        var start = 0L
        // 先頭に ID3v2 が付いていれば飛ばす
        val head = ByteArray(10)
        if (src.read(0, head, 0, 10) == 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val sz = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
            start = 10L + sz + (if ((head[5].toInt() and 0x10) != 0) 10 else 0)
        }
        seekByte(start)
        if (bits(32) != 0x664C6143) throw IOException("not a FLAC file") // "fLaC"
        var last = false
        var gotInfo = false
        while (!last) {
            last = bits(1) == 1
            val type = bits(7)
            val len = bits(24)
            val blockStart = bytePos()
            if (type == 0) {
                minBlock = bits(16)
                maxBlock = bits(16)
                bits(24); bits(24) // フレームの最小・最大の大きさ
                sampleRate = bits(20)
                channels = bits(3) + 1
                bitsPerSample = bits(5) + 1
                totalSamples = bitsLong(36)
                gotInfo = true
            }
            seekByte(blockStart + len)
        }
        if (!gotInfo || sampleRate <= 0) throw IOException("FLAC: no STREAMINFO")
        firstFrame = bytePos()
    }

    // ---- フレーム ----

    private class Header(val blockSize: Int, val rate: Int, val chAssign: Int, val bps: Int, val sample: Long)

    private val hdr = ByteArray(16)

    /**
     * いまの位置からフレームのヘッダーを読む(CRC-8 も確かめる)。ヘッダーでなければ null。
     * 読めたら位置はヘッダーの直後
     */
    private fun readHeader(): Header? {
        var n = 0
        fun b(): Int = nextByte().also { hdr[n++] = it.toByte() }
        val b0 = b()
        val b1 = b()
        if (b0 != 0xFF || (b1 and 0xFE) != 0xF8) return null
        val variable = (b1 and 1) == 1
        val b2 = b()
        val b3 = b()
        val bsCode = b2 ushr 4
        val srCode = b2 and 15
        val chAssign = b3 ushr 4
        val ssCode = (b3 ushr 1) and 7
        if (bsCode == 0 || srCode == 15 || chAssign > 10 || ssCode == 3 || (b3 and 1) != 0) return null
        // フレーム番号・サンプル番号(UTF-8 に似た可変長)
        val f0 = b()
        var num: Long
        var more: Int
        when {
            f0 < 0x80 -> { num = f0.toLong(); more = 0 }
            f0 >= 0xFE -> { num = 0; more = 6 }
            else -> {
                val ones = Integer.numberOfLeadingZeros(f0.inv() shl 24)
                if (ones < 2) return null
                num = (f0 and (0x7F ushr ones)).toLong()
                more = ones - 1
            }
        }
        repeat(more) {
            val c = b()
            if ((c and 0xC0) != 0x80) return null
            num = (num shl 6) or (c and 0x3F).toLong()
        }
        val bs = when (bsCode) {
            1 -> 192
            in 2..5 -> 576 shl (bsCode - 2)
            6 -> b() + 1
            7 -> ((b() shl 8) or b()) + 1
            else -> 256 shl (bsCode - 8)
        }
        val rate = when (srCode) {
            0 -> sampleRate
            1 -> 88200; 2 -> 176400; 3 -> 192000; 4 -> 8000; 5 -> 16000; 6 -> 22050
            7 -> 24000; 8 -> 32000; 9 -> 44100; 10 -> 48000; 11 -> 96000
            12 -> b() * 1000
            13 -> (b() shl 8) or b()
            else -> ((b() shl 8) or b()) * 10
        }
        val bps = when (ssCode) {
            0 -> bitsPerSample
            1 -> 8; 2 -> 12; 4 -> 16; 5 -> 20; 6 -> 24
            else -> 32
        }
        val crc = nextByte()
        if (crc8(hdr, n) != crc) return null
        val sample = if (variable) num else num * (if (minBlock == maxBlock && maxBlock > 0) maxBlock else bs)
        return Header(bs, rate, chAssign, bps, sample)
    }

    private fun crc8(b: ByteArray, n: Int): Int {
        var c = 0
        for (i in 0 until n) {
            c = c xor (b[i].toInt() and 0xFF)
            repeat(8) { c = if ((c and 0x80) != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        }
        return c
    }

    /** 次のフレームのヘッダーを探して読む(壊れたところは飛ばす)。ファイルの終わりなら null */
    private fun nextHeader(): Header? {
        alignByte()
        while (true) {
            val pos = bytePos()
            val h = try {
                readHeader()
            } catch (e: IOException) {
                if (e === EOF) return null
                throw e
            }
            if (h != null && h.chAssign.let { ca -> (if (ca < 8) ca + 1 else 2) == channels } && h.rate == sampleRate) {
                lastPos = pos
                return h
            }
            // ヘッダーでなかった: 1 バイト先から探し直す
            seekByte(pos + 1)
        }
    }

    /** 次のフレームをデコードする。終わりなら false */
    override fun decodeNext(): Boolean {
        var h: Header
        while (true) {
            h = nextHeader() ?: return false
            val bs = h.blockSize
            if (samples.size != channels || samples[0].size < bs) samples = Array(channels) { IntArray(maxOf(bs, maxBlock)) }
            try {
                for (ch in 0 until channels) {
                    // サイドチャンネルは 1 ビット多い
                    val side = (h.chAssign == 8 && ch == 1) || (h.chAssign == 9 && ch == 0) || (h.chAssign == 10 && ch == 1)
                    subframe(samples[ch], bs, h.bps + if (side) 1 else 0)
                }
                alignByte()
                bits(16) // CRC-16
                break
            } catch (e: BadFrame) {
                // 壊れたフレーム(またはヘッダーに見えただけの所): 1 バイト先から次のフレームを探す
                seekByte(lastPos + 1)
            } catch (e: IOException) {
                if (e === EOF) return false
                throw e
            }
        }
        val bs = h.blockSize
        val l = samples.getOrNull(0)
        val r = samples.getOrNull(1)
        when (h.chAssign) {
            8 -> for (i in 0 until bs) r!![i] = l!![i] - r[i] // 左・サイド
            9 -> for (i in 0 until bs) l!![i] += r!![i] // サイド・右
            10 -> for (i in 0 until bs) { // ミッド・サイド
                val s = r!![i]
                val m = (l!![i] shl 1) or (s and 1)
                l[i] = (m + s) shr 1
                r[i] = (m - s) shr 1
            }
        }
        blockSize = bs
        frameSample = h.sample
        return true
    }

    private fun subframe(out: IntArray, n: Int, bps0: Int) {
        bits(1)
        val type = bits(6)
        var wasted = 0
        if (bits(1) == 1) wasted = unary() + 1
        val bps = bps0 - wasted
        if (bps < 0) throw BadFrame("FLAC: bad wasted bits")
        when {
            type == 0 -> {
                val v = signed(bps).toInt()
                java.util.Arrays.fill(out, 0, n, v)
            }
            type == 1 -> for (i in 0 until n) out[i] = signed(bps).toInt()
            type in 8..12 -> {
                val order = type - 8
                if (order > n) throw BadFrame("FLAC: bad predictor order")
                for (i in 0 until order) out[i] = signed(bps).toInt()
                residual(out, n, order)
                fixed(out, n, order)
            }
            type >= 32 -> {
                val order = type - 31
                if (order > n) throw BadFrame("FLAC: bad predictor order")
                for (i in 0 until order) out[i] = signed(bps).toInt()
                val precision = bits(4) + 1
                if (precision == 16) throw BadFrame("FLAC: bad LPC precision")
                val shift = signed(5).toInt()
                if (shift < 0) throw BadFrame("FLAC: negative LPC shift")
                val coef = IntArray(order) { signed(precision).toInt() }
                residual(out, n, order)
                // 値の範囲が 32bit に収まるとき(libFLAC と同じ判定)は、32bit の整数で計算する(32bit の CPU では 64bit の計算が遅い)
                if (bps + precision + (31 - Integer.numberOfLeadingZeros(order)) <= 32) lpc32(out, n, order, coef, shift)
                else lpc(out, n, order, coef, shift)
            }
            else -> throw BadFrame("FLAC: reserved subframe $type")
        }
        if (wasted > 0) for (i in 0 until n) out[i] = out[i] shl wasted
    }

    private fun residual(out: IntArray, n: Int, order: Int) {
        val method = bits(2)
        if (method > 1) throw BadFrame("FLAC: reserved residual coding")
        val paramBits = if (method == 0) 4 else 5
        val escape = if (method == 0) 15 else 31
        val porder = bits(4)
        val parts = 1 shl porder
        // 壊れたデータで out の外に書かないように(libFLAC と同じ確かめ方)
        if (porder > 0 && ((n and (parts - 1)) != 0 || (n ushr porder) < order)) throw BadFrame("FLAC: bad partition order")
        var i = order
        for (p in 0 until parts) {
            val cnt = if (porder == 0) n - order else (n ushr porder) - (if (p == 0) order else 0)
            val k = bits(paramBits)
            if (k == escape) {
                val nb = bits(5)
                for (j in 0 until cnt) out[i++] = if (nb == 0) 0 else signed(nb).toInt()
            } else {
                for (j in 0 until cnt) {
                    val q = unary().toLong()
                    val v = (q shl k) or (bits(k).toLong() and 0xFFFFFFFFL)
                    out[i++] = ((v ushr 1) xor -(v and 1)).toInt()
                }
            }
        }
    }

    private fun fixed(x: IntArray, n: Int, order: Int) {
        when (order) {
            1 -> for (i in 1 until n) x[i] += x[i - 1]
            2 -> for (i in 2 until n) x[i] += 2 * x[i - 1] - x[i - 2]
            3 -> for (i in 3 until n) x[i] += 3 * (x[i - 1] - x[i - 2]) + x[i - 3]
            4 -> for (i in 4 until n) x[i] += 4 * (x[i - 1] + x[i - 3]) - 6 * x[i - 2] - x[i - 4]
        }
    }

    /** lpc と同じ計算を 32bit の整数で(合計が 32bit に収まると分かっているときだけ使う) */
    private fun lpc32(x: IntArray, n: Int, order: Int, c: IntArray, shift: Int) {
        for (i in order until n) {
            var sum = 0
            for (j in 0 until order) sum += c[j] * x[i - 1 - j]
            x[i] += sum shr shift
        }
    }

    private fun lpc(x: IntArray, n: Int, order: Int, c: IntArray, shift: Int) {
        for (i in order until n) {
            var sum = 0L
            for (j in 0 until order) sum += c[j].toLong() * x[i - 1 - j]
            x[i] += (sum shr shift).toInt()
        }
    }

    // ---- 途中から ----

    /**
     * target(サンプル番号)を含むフレームの手前に移る。
     * 次の decodeNext() からそのフレームが出る(frameSample で始まりが分かるので、手前の分は呼び出し側で捨てる)
     */
    override fun seekSample(target: Long) {
        if (target <= 0) { seekByte(firstFrame); return }
        var lo = firstFrame
        var hi = src.size
        // 位置で二分探索: 中ほどから次のフレームを探し、そのサンプル番号で狭める
        while (hi - lo > 64 * 1024) {
            val mid = lo + (hi - lo) / 2
            seekByte(mid)
            val h = nextHeader()
            if (h == null || h.sample > target) {
                hi = mid
            } else {
                // ヘッダーの位置(読み終えた位置からヘッダー分を戻すより、探し始めた位置を使う)
                lo = mid
            }
        }
        // lo から順にデコードし、target を含むフレームの頭に戻る
        seekByte(lo)
        lastPos = lo
        var retry = -1L
        while (true) {
            val ok = try {
                decodeNext()
            } catch (e: IOException) {
                // ヘッダーに見えただけの所だった: その 1 バイト先から探し直す
                // (読み込みのエラーが続くときなど、先へ進めなければあきらめる)
                if (lastPos + 1 <= retry) throw e
                retry = lastPos + 1
                seekByte(retry)
                continue
            }
            if (!ok) return
            if (frameSample + blockSize > target) {
                seekByte(lastPos)
                return
            }
        }
    }

    /** 最後に読んだフレームのヘッダーの位置 */
    private var lastPos = 0L

    /** フレームの中身が壊れている(読み込みのエラーとは分ける) */
    private class BadFrame(msg: String) : IOException(msg)

    companion object {
        private val EOF = IOException("end of FLAC data")
    }
}
