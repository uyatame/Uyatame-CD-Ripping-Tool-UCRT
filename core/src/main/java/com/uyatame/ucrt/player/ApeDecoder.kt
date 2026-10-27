package com.uyatame.ucrt.player

import java.io.IOException

/**
 * Monkey's Audio(.ape)のデコーダー(アプリ内蔵)。
 *
 * 端末の OS には APE のデコーダーがないので、こちらで PCM にする。結果は元の PCM とビット単位で同じ(可逆圧縮なので)。
 * 作りは公式の MAC SDK(3.99)と ffmpeg の apedec.c に合わせている(整数の桁あふれの扱いも同じ)。
 *
 * 対応: ファイルの版 3.95 以降(3.95〜3.99、4.x 以降の今の Monkey's Audio が作るもの)、
 * 圧縮の度合い Fast / Normal / High / Extra High / Insane、1〜2ch、8 / 16 / 24bit。
 * 3.95 より古い版は非対応(例外)。32bit・浮動小数点の APE も非対応。
 *
 * 1 フレーム(blocksPerFrame サンプル。3.99 では Fast〜High が 73728、Extra High が ×4、Insane が ×16)を、
 * CHUNK サンプルずつに分けて出す。APE はフレームの途中から読めないので、途中へ移るときはフレームの頭からデコードして読み飛ばす。
 *
 * 注意: NN フィルターの丸め(内積 + 2^(shift-1))は MAC と同じく 32bit で一周させる。ffmpeg(6.1 など)はここを 64bit で
 * 計算するため、24bit の大きな音の Insane などで MAC・元の PCM と結果が違うことがある(こちらは MAC と同じ)。
 */
internal class ApeDecoder(private val src: RandomInput) : PcmDecoder {
    override var sampleRate = 0
        private set
    override var channels = 0
        private set
    override var bitsPerSample = 0
        private set
    /** 全体のサンプル数(1 チャンネルあたり) */
    override var totalSamples = 0L
        private set

    /** デコードしたブロック: samples[ch][0 until blockSize]、最初のサンプルの番号 */
    override var samples: Array<IntArray> = emptyArray()
        private set
    override var blockSize = 0
        private set
    override var frameSample = 0L
        private set

    // ---- ファイルの情報 ----
    private var version = 0
    private var level = 0
    private var blocksPerFrame = 0
    private var finalFrameBlocks = 0
    private var totalFrames = 0
    /** 各フレームの始まりの位置(ファイルの先頭から) */
    private var seekTable = LongArray(0)
    /** フレームのデータの終わり(分からなければファイルの終わり) */
    private var dataEnd = 0L

    // ---- フレームのデータ(32bit ごとにバイトを入れ替えたもの) ----
    private var fb = ByteArray(0)
    private var fbLen = 0
    private var fp = 0

    // ---- いまのフレーム ----
    /** 次に読むフレームの番号 */
    private var nextFrame = 0
    /** いまのフレームの番号(読んでいなければ -1) */
    private var curFrame = -1
    private var curBlocks = 0
    /** いまのフレームで、出し終えたサンプル数 */
    private var curOut = 0
    private var curStart = 0L
    private var specialCodes = 0
    private var storedCrc = 0
    /** いまのフレームを、まとめてデコードした結果(fbuf)から出しているか */
    private var fromBuf = false
    /** フレーム全体のデコード結果(24bit で計算の幅を確かめるときだけ使う) */
    private var fbuf: Array<IntArray> = emptyArray()
    /** seekSample のあと、このサンプルを含むブロックまで読み飛ばす(-1 なら飛ばさない) */
    private var skipTo = -1L

    /**
     * 24bit の「interim mode」(予測の積の和を 64bit で計算する)。
     * MAC 3.99 などは 32bit(桁あふれで一周)、最近の版の 24bit は 64bit で符号化しているものがあり、ファイルからは区別できない。
     * ふだんは両方の結果を比べながら今の幅で読み、違いが出たフレームだけ、まとめてデコードして CRC で確かめる
     * (合わなければもう一方の幅で読み直す)。一度 CRC で確かめられたら、その幅に決める
     */
    private var interim = false
    private var modeConfirmed = false

    // ---- 予測・フィルター(チャンネルごと: 0 = X、1 = Y) ----
    private val pred = arrayOf(Predictor(), Predictor())
    private var lastX = 0

    // ---- レンジデコーダー(low・range は 32bit の符号なし。Long で持つ) ----
    private var rcLow = 0L
    private var rcRange = 0L
    private var rcBuffer = 0
    private val riceK = IntArray(2)
    private val riceKSum = IntArray(2)

    // ---- ヘッダー ----

    private fun readFully(pos: Long, b: ByteArray, len: Int): Int {
        var n = 0
        while (n < len) {
            val r = src.read(pos + n, b, n, len - n)
            if (r <= 0) break
            n += r
        }
        return n
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = (le16(b, o).toLong()) or (le16(b, o + 2).toLong() shl 16)

    /** ファイルの先頭を読む。APE でない・対応していない版なら例外 */
    override fun open() {
        // 先頭に ID3v2 が付いていれば飛ばす
        var start = 0L
        val head = ByteArray(10)
        if (readFully(0, head, 10) == 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val sz = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
            start = 10L + sz + (if ((head[5].toInt() and 0x10) != 0) 10 else 0)
        }
        // "MAC " を探す(ID3v2 の後ろの詰め物などを飛ばす。1MB まで)
        val scan = ByteArray(1 shl 16)
        var found = -1L
        var p = start
        while (found < 0 && p < start + (1 shl 20)) {
            val n = readFully(p, scan, scan.size)
            if (n < 4) break
            for (i in 0..n - 4) {
                if (scan[i] == 'M'.code.toByte() && scan[i + 1] == 'A'.code.toByte() && scan[i + 2] == 'C'.code.toByte() && scan[i + 3] == ' '.code.toByte()) {
                    found = p + i
                    break
                }
            }
            p += n - 3
        }
        if (found < 0) throw IOException("not an APE file")
        val base = found
        val h = ByteArray(64)
        if (readFully(base, h, 64) < 32) throw IOException("APE: header too short")
        version = le16(h, 4)
        if (version < 3950) throw IOException("unsupported APE version $version")
        var bps: Int
        var seekPos: Long
        var seekCount: Int
        if (version >= 3980) {
            // 今の形: APE_DESCRIPTOR(版の後ろに 2 バイトの詰め物)+ APE_HEADER
            val descBytes = le32(h, 8)
            val headerBytes = le32(h, 12)
            val seekBytes = le32(h, 16)
            val frameBytes = le32(h, 24) or (le32(h, 28) shl 32)
            val ah = ByteArray(24)
            if (readFully(base + descBytes, ah, 24) < 24) throw IOException("APE: header too short")
            level = le16(ah, 0)
            blocksPerFrame = le32(ah, 4).toInt()
            finalFrameBlocks = le32(ah, 8).toInt()
            totalFrames = le32(ah, 12).toInt()
            bps = le16(ah, 16)
            channels = le16(ah, 18)
            sampleRate = le32(ah, 20).toInt()
            seekPos = base + descBytes + headerBytes
            seekCount = (seekBytes / 4).toInt()
            dataEnd = if (frameBytes > 0) -frameBytes else 0 // 最初のフレームの位置が分かってから決める
        } else {
            // 古い形(3.95〜3.97): APE_HEADER_OLD
            level = le16(h, 6)
            val flags = le16(h, 8)
            channels = le16(h, 10)
            sampleRate = le32(h, 12).toInt()
            val wavHeaderBytes = le32(h, 16)
            totalFrames = le32(h, 24).toInt()
            finalFrameBlocks = le32(h, 28).toInt()
            blocksPerFrame = 73728 * 4
            bps = if ((flags and 1) != 0) 8 else if ((flags and 8) != 0) 24 else 16
            var q = base + 32
            if ((flags and 4) != 0) q += 4 // ピークの値
            seekCount = totalFrames
            if ((flags and 16) != 0) {
                val b4 = ByteArray(4)
                readFully(q, b4, 4)
                seekCount = le32(b4, 0).toInt()
                q += 4
            }
            if ((flags and 32) == 0) q += wavHeaderBytes // 元の WAV のヘッダー
            seekPos = q
            dataEnd = 0
        }
        bitsPerSample = bps
        if (channels !in 1..2) throw IOException("APE: unsupported channels $channels")
        if (bps != 8 && bps != 16 && bps != 24) throw IOException("APE: unsupported bits $bps")
        if (level !in intArrayOf(1000, 2000, 3000, 4000, 5000)) throw IOException("APE: unsupported compression level $level")
        if (totalFrames <= 0 || blocksPerFrame <= 0 || finalFrameBlocks !in 1..blocksPerFrame || sampleRate <= 0) throw IOException("APE: bad header")
        if (seekCount < totalFrames) throw IOException("APE: seek table too short")
        // 壊れたヘッダーで大きな配列を作らないように(Insane でも 73728 × 16。位置の表はファイルの中にある)
        if (blocksPerFrame > MAX_BLOCKS_PER_FRAME || totalFrames.toLong() * 4 > src.size) throw IOException("APE: bad header")
        totalSamples = (totalFrames - 1).toLong() * blocksPerFrame + finalFrameBlocks

        // 位置の表(32bit。"MAC " より前の部分(ID3v2 など)の長さを足す。
        // 4GB を超えるファイルでは途中で一周するので、戻ったら 2^32 を足す)
        val st = ByteArray(totalFrames * 4)
        if (readFully(seekPos, st, st.size) < st.size) throw IOException("APE: seek table truncated")
        seekTable = LongArray(totalFrames)
        var add = base
        for (i in 0 until totalFrames) {
            var v = le32(st, i * 4) + add
            if (i > 0 && v < seekTable[i - 1]) { add += 1L shl 32; v += 1L shl 32 }
            seekTable[i] = v
        }
        dataEnd = if (dataEnd < 0) seekTable[0] - dataEnd else src.size
        if (dataEnd > src.size) dataEnd = src.size

        // フィルターの段数・次数は圧縮の度合いで決まる
        for (pr in pred) pr.setup(level, version, bps == 24)
        fbuf = emptyArray()
        interim = false
        modeConfirmed = false
        samples = Array(channels) { IntArray(CHUNK) }
        nextFrame = 0
        curFrame = -1
        skipTo = -1
    }

    // ---- フレームの読み込み ----

    /** フレーム n のデータを読み込み、32bit ごとにバイトを入れ替える(MAC は 32bit の LE の語の上位ビットから読む) */
    private fun loadFrame(n: Int) {
        val first = seekTable[0]
        val pos = seekTable[n]
        val skip = ((pos - first) and 3).toInt()
        val aligned = pos - skip
        // 終わり: 次のフレームの始まり(最後のフレームはデータの終わり)。レンジデコーダーが少し先まで読むので余分に読む
        val end = (if (n + 1 < totalFrames) seekTable[n + 1] else dataEnd) + 16
        val len0 = (minOf(end, src.size) - aligned).coerceAtLeast(0)
        // 位置の表が壊れていると、とても大きくなることがある
        if (len0 > MAX_FRAME_BYTES) throw IOException("APE: frame too large")
        var len = len0.toInt()
        len = (len + 3) and 3.inv()
        if (fb.size < len + 16) fb = ByteArray(len + 16 + (len shr 3))
        val raw = fb
        val got = readFully(aligned, raw, len)
        java.util.Arrays.fill(raw, got, len + 16, 0)
        // 4 バイトごとに逆順
        var i = 0
        while (i < len) {
            val b0 = raw[i]; val b1 = raw[i + 1]
            raw[i] = raw[i + 3]; raw[i + 1] = raw[i + 2]
            raw[i + 2] = b1; raw[i + 3] = b0
            i += 4
        }
        fbLen = len
        fp = skip
    }

    private fun byte(): Int = if (fp < fbLen) fb[fp++].toInt() and 0xFF else { fp++; 0 }

    private fun be32(): Int = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()

    /** フレームの始まり: CRC・特別な印を読み、レンジデコーダーと予測を初期化する */
    private fun startFrame() {
        var crc = be32()
        specialCodes = 0
        if (version > 3820) {
            if (crc < 0) specialCodes = be32()
            crc = crc and 0x7FFFFFFF
        }
        storedCrc = crc
        pred[0].flush()
        pred[1].flush()
        lastX = 0
        for (c in 0..1) {
            riceK[c] = 10
            riceKSum[c] = (1 shl 10) * 16
        }
        // 最初の 1 バイトは使わない
        fp++
        rcBuffer = byte()
        rcLow = (rcBuffer ushr 1).toLong()
        rcRange = 1L shl 7
    }

    // ---- レンジデコーダー ----

    private fun normalize() {
        while (rcRange <= BOTTOM_VALUE) {
            rcBuffer = (rcBuffer shl 8) or byte()
            rcLow = ((rcLow shl 8) or ((rcBuffer ushr 1) and 0xFF).toLong()) and 0xFFFFFFFFL
            rcRange = rcRange shl 8
        }
    }

    /** shift ビットぶんの値を読んで、そのぶん進める */
    private fun decodeBits(shift: Int): Int {
        normalize()
        rcRange = rcRange ushr shift
        val v = rcLow / rcRange
        rcLow -= rcRange * v
        return v.toInt()
    }

    /** 0 until total の一様な値を読む */
    private fun decodeUniform(total: Int): Int {
        normalize()
        rcRange /= total
        val v = rcLow / rcRange
        rcLow -= rcRange * v
        return v.toInt()
    }

    /** 残差を 1 つ読む(c: 0 = X、1 = Y) */
    private fun decodeValue(c: Int): Int {
        var value: Int
        if (version >= 3990) {
            val ksum = riceKSum[c]
            val pivot = maxOf(ksum ushr 5, 1)
            // 桁あふれの部分(モデルで符号化)
            normalize()
            rcRange = rcRange ushr 16
            val rt = (rcLow / rcRange).toInt()
            var ov = 0
            while (ov < MODEL_ELEMENTS - 1 && rt >= RANGE_TOTAL_2[ov + 1]) ov++
            rcLow -= rcRange * RANGE_TOTAL_2[ov]
            rcRange *= RANGE_WIDTH_2[ov]
            if (ov == MODEL_ELEMENTS - 1) {
                ov = decodeBits(16) shl 16
                ov = ov or decodeBits(16)
            }
            // 残りの部分(0 until pivot の一様な値)
            val b = if (pivot >= (1 shl 16)) {
                val pbits = 32 - Integer.numberOfLeadingZeros(pivot)
                val split = 1 shl (pbits - 16)
                val a = decodeUniform(pivot / split + 1)
                val bb = decodeUniform(split)
                a * split + bb
            } else {
                decodeUniform(pivot)
            }
            value = b + ov * pivot
        } else {
            // 3.95〜3.98
            normalize()
            rcRange = rcRange ushr 16
            val rt = (rcLow / rcRange).toInt()
            var ov = 0
            while (ov < MODEL_ELEMENTS - 1 && rt >= RANGE_TOTAL_1[ov + 1]) ov++
            rcLow -= rcRange * RANGE_TOTAL_1[ov]
            rcRange *= RANGE_WIDTH_1[ov]
            val k: Int
            if (ov == MODEL_ELEMENTS - 1) {
                k = decodeBits(5)
                ov = 0
            } else {
                k = if (riceK[c] < 1) 0 else riceK[c] - 1
            }
            value = if (k <= 16 || version < 3910) decodeBits(k)
            else {
                val x1 = decodeBits(16)
                val x2 = decodeBits(k - 16)
                x1 or (x2 shl 16)
            }
            value += ov shl k
        }
        // k・ksum を更新(ksum は 32bit の符号なし)
        val ks = riceKSum[c] + ((value + 1) / 2) - ((riceKSum[c] + 16) ushr 5)
        riceKSum[c] = ks
        var k = riceK[c]
        if (Integer.compareUnsigned(ks, K_SUM_MIN_BOUNDARY[k]) < 0) k--
        else if (k < 31 && Integer.compareUnsigned(ks, K_SUM_MIN_BOUNDARY[k + 1]) >= 0) k++
        riceK[c] = k
        // 符号付きに戻す
        return if ((value and 1) != 0) (value shr 1) + 1 else -(value shr 1)
    }

    // ---- デコード ----

    /** いまのフレームの続きの n サンプルを dst[ch][0 until n] にデコードする */
    private fun decodeRange(dst: Array<IntArray>, n: Int) {
        val shiftOut = 32 - bitsPerSample
        val b0 = dst[0]
        val from = 0
        val end = n
        if (channels == 2) {
            val b1 = dst[1]
            if ((specialCodes and SPECIAL_LEFT_SILENCE) != 0 && (specialCodes and SPECIAL_RIGHT_SILENCE) != 0) {
                java.util.Arrays.fill(b0, from, end, 0)
                java.util.Arrays.fill(b1, from, end, 0)
            } else if ((specialCodes and SPECIAL_PSEUDO_STEREO) != 0) {
                val px = pred[0]
                for (i in from until end) {
                    val x = px.decompress(decodeValue(0), 0, interim)
                    val v = (x shl shiftOut) shr shiftOut
                    b0[i] = v
                    b1[i] = v
                }
            } else {
                val px = pred[0]
                val py = pred[1]
                var lx = lastX
                for (i in from until end) {
                    val ny = decodeValue(1)
                    val nx = decodeValue(0)
                    val y = py.decompress(ny, lx, interim)
                    val x = px.decompress(nx, y, interim)
                    lx = x
                    // (X, Y) → (L, R)
                    val l = x - (y / 2)
                    val r = l + y
                    b0[i] = (l shl shiftOut) shr shiftOut
                    b1[i] = (r shl shiftOut) shr shiftOut
                }
                lastX = lx
            }
        } else {
            if ((specialCodes and SPECIAL_MONO_SILENCE) != 0) {
                java.util.Arrays.fill(b0, from, end, 0)
            } else {
                val px = pred[0]
                for (i in from until end) {
                    val x = px.decompress(decodeValue(0), 0, interim)
                    b0[i] = (x shl shiftOut) shr shiftOut
                }
            }
        }
    }

    /** いまのフレームの CRC(元の PCM のバイト列の CRC32 を 1 ビット右にずらしたもの) */
    private fun frameCrc(buf: Array<IntArray>): Int {
        var crc = -1
        val bytes = bitsPerSample / 8
        for (i in 0 until curBlocks) {
            for (c in 0 until channels) {
                var v = buf[c][i]
                if (bytes == 1) v += 128
                for (k in 0 until bytes) {
                    crc = (crc ushr 8) xor CRC_TABLE[(crc xor (v ushr (8 * k))) and 0xFF]
                }
            }
        }
        return (crc xor -1) ushr 1
    }

    /** フレーム n を始める */
    private fun beginFrame(n: Int) {
        curFrame = n
        curBlocks = if (n == totalFrames - 1) finalFrameBlocks else blocksPerFrame
        curStart = n.toLong() * blocksPerFrame
        curOut = 0
        fromBuf = false
        loadFrame(n)
        startFrame()
    }

    /**
     * 32bit と 64bit で結果が違うところが出たフレームを、頭からまとめてデコードし直して CRC で確かめる。
     * 合わなければもう一方の幅で読み直す。以後このフレームは fbuf から出す
     */
    private fun resolveFrame() {
        if (fbuf.isEmpty()) fbuf = Array(channels) { IntArray(blocksPerFrame) }
        for (attempt in 0..1) {
            loadFrame(curFrame)
            startFrame()
            decodeRange(fbuf, curBlocks)
            if (frameCrc(fbuf) == storedCrc) {
                modeConfirmed = true
                fromBuf = true
                return
            }
            interim = !interim
        }
        // どちらも合わない(壊れている): 元の幅の結果にしておく
        loadFrame(curFrame)
        startFrame()
        decodeRange(fbuf, curBlocks)
        fromBuf = true
    }

    /** 次のブロックをデコードする。終わりなら false */
    override fun decodeNext(): Boolean {
        while (true) {
            if (curFrame < 0 || curOut >= curBlocks) {
                if (nextFrame >= totalFrames) {
                    blockSize = 0
                    return false
                }
                beginFrame(nextFrame)
                nextFrame++
            }
            val n = minOf(CHUNK, curBlocks - curOut)
            val start = curStart + curOut
            if (fromBuf) {
                for (c in 0 until channels) System.arraycopy(fbuf[c], curOut, samples[c], 0, n)
            } else {
                decodeRange(samples, n)
                if (!modeConfirmed && (pred[0].diverged || pred[1].diverged)) {
                    resolveFrame()
                    for (c in 0 until channels) System.arraycopy(fbuf[c], curOut, samples[c], 0, n)
                }
            }
            curOut += n
            // 読み飛ばし中で、このブロックに目的のサンプルがなければ、出さずに進める
            if (skipTo >= 0 && start + n <= skipTo) continue
            blockSize = n
            frameSample = start
            skipTo = -1
            return true
        }
    }

    /**
     * target(サンプル番号)を含むブロックの手前に移る。
     * 次の decodeNext() で、target を含むブロックが出る(フレームの頭からデコードして読み飛ばす)
     */
    override fun seekSample(target: Long) {
        val t = target.coerceIn(0L, (totalSamples - 1).coerceAtLeast(0))
        nextFrame = (t / blocksPerFrame).toInt().coerceIn(0, totalFrames)
        curFrame = -1
        curOut = 0
        curBlocks = 0
        skipTo = t
    }

    // ---- NN フィルター ----

    /** 適応 FIR フィルター(係数・入力は 16bit。MAC の CNNFilter と同じ。積の和は 32bit で一周する) */
    private class NNFilter(val order: Int, val shift: Int, val ver: Int) {
        private val m = ShortArray(order)
        private val inp = ShortArray(NN_WINDOW + order)
        private val dm = ShortArray(NN_WINDOW + order)
        private var pos = order
        private var avg = 0
        private val round = 1 shl (shift - 1)

        fun flush() {
            java.util.Arrays.fill(m, 0)
            java.util.Arrays.fill(inp, 0)
            java.util.Arrays.fill(dm, 0)
            pos = order
            avg = 0
        }

        fun decompress(x: Int): Int {
            val b = pos - order
            val m = m
            val inp = inp
            val dm = dm
            var dot = 0
            // 内積を取りながら、係数を残差の符号の向きに動かす(内積は動かす前の係数で)
            if (x > 0) {
                for (i in 0 until order) {
                    val mi = m[i]
                    dot += mi * inp[b + i]
                    m[i] = (mi - dm[b + i]).toShort()
                }
            } else if (x < 0) {
                for (i in 0 until order) {
                    val mi = m[i]
                    dot += mi * inp[b + i]
                    m[i] = (mi + dm[b + i]).toShort()
                }
            } else {
                for (i in 0 until order) dot += m[i] * inp[b + i]
            }
            val out = x + ((dot + round) shr shift)
            // 16bit に飽和させて入力の履歴へ
            inp[pos] = (if (out == out.toShort().toInt()) out else (out shr 31) xor 0x7FFF).toShort()
            if (ver >= 3980) {
                val a = Math.abs(out)
                dm[pos] = when {
                    a > avg * 3 -> ((out shr 25) and 64) - 32
                    a > (avg * 4) / 3 -> ((out shr 26) and 32) - 16
                    a > 0 -> ((out shr 27) and 16) - 8
                    else -> 0
                }.toShort()
                avg += (a - avg) / 16
                dm[pos - 1] = (dm[pos - 1].toInt() shr 1).toShort()
                dm[pos - 2] = (dm[pos - 2].toInt() shr 1).toShort()
                dm[pos - 8] = (dm[pos - 8].toInt() shr 1).toShort()
            } else {
                dm[pos] = (if (out == 0) 0 else ((out shr 28) and 8) - 4).toShort()
                dm[pos - 4] = (dm[pos - 4].toInt() shr 1).toShort()
                dm[pos - 8] = (dm[pos - 8].toInt() shr 1).toShort()
            }
            pos++
            if (pos == NN_WINDOW + order) {
                // 履歴を前に詰める
                System.arraycopy(inp, NN_WINDOW, inp, 0, order)
                System.arraycopy(dm, NN_WINDOW, dm, 0, order)
                pos = order
            }
            return out
        }
    }

    // ---- 予測(3.95 以降の「新しい」予測。MAC の CPredictorDecompress3950toCurrent と同じ) ----

    private class Predictor {
        private var filters: Array<NNFilter> = emptyArray()
        private val bufA = IntArray(WINDOW + HIST)
        private val bufB = IntArray(WINDOW + HIST)
        private val adA = IntArray(WINDOW + HIST)
        private val adB = IntArray(WINDOW + HIST)
        private var p = HIST
        private var ma0 = 0; private var ma1 = 0; private var ma2 = 0; private var ma3 = 0
        private var mb0 = 0; private var mb1 = 0; private var mb2 = 0; private var mb3 = 0; private var mb4 = 0
        private var lastA = 0
        private var stageA = 0
        private var stageB = 0

        /** 24bit: 32bit と 64bit の両方で計算して、違いが出たかを見る */
        private var wide = false
        /** このフレームで、32bit と 64bit の結果が違うところがあったか */
        var diverged = false
            private set

        fun setup(level: Int, version: Int, wide: Boolean) {
            this.wide = wide
            // 内側(先にかける)から順に。Insane は MAC 3.99 と同じく、ファイルの版によらず 3.98 以降の動き
            filters = when (level) {
                2000 -> arrayOf(NNFilter(16, 11, version))
                3000 -> arrayOf(NNFilter(64, 11, version))
                4000 -> arrayOf(NNFilter(32, 10, version), NNFilter(256, 13, version))
                5000 -> arrayOf(NNFilter(16, 11, 3990), NNFilter(256, 13, 3990), NNFilter(1280, 15, 3990))
                else -> emptyArray()
            }
        }

        fun flush() {
            for (f in filters) f.flush()
            java.util.Arrays.fill(bufA, 0)
            java.util.Arrays.fill(bufB, 0)
            java.util.Arrays.fill(adA, 0)
            java.util.Arrays.fill(adB, 0)
            p = HIST
            ma0 = 360; ma1 = 317; ma2 = -109; ma3 = 98
            mb0 = 0; mb1 = 0; mb2 = 0; mb3 = 0; mb4 = 0
            lastA = 0
            stageA = 0
            stageB = 0
            diverged = false
        }

        /** -符号(0 なら 0) */
        private fun adapt(v: Int): Int = if (v != 0) ((v shr 30) and 2) - 1 else 0

        fun decompress(input: Int, nB: Int, interim: Boolean): Int {
            if (p == WINDOW + HIST) {
                System.arraycopy(bufA, WINDOW, bufA, 0, HIST)
                System.arraycopy(bufB, WINDOW, bufB, 0, HIST)
                System.arraycopy(adA, WINDOW, adA, 0, HIST)
                System.arraycopy(adB, WINDOW, adB, 0, HIST)
                p = HIST
            }
            // 第 2 段: NN フィルター
            var nA = input
            for (f in filters) nA = f.decompress(nA)

            // 第 1 段: 予測(A: 自分の過去、B: もう一方のチャンネル)
            val p = p
            bufA[p] = lastA
            bufA[p - 1] = bufA[p] - bufA[p - 1]
            // B は 1 次のフィルター(31/32)をかけた値
            bufB[p] = nB - ((stageB * 31) shr 5)
            stageB = nB
            bufB[p - 1] = bufB[p] - bufB[p - 1]

            // 予測の積の和(32bit で一周する)
            val pa = bufA[p] * ma0 + bufA[p - 1] * ma1 + bufA[p - 2] * ma2 + bufA[p - 3] * ma3
            val pb = bufB[p] * mb0 + bufB[p - 1] * mb1 + bufB[p - 2] * mb2 + bufB[p - 3] * mb3 + bufB[p - 4] * mb4
            var cur = nA + ((pa + (pb shr 1)) shr 10)
            if (wide) {
                // 64bit で計算したもの(interim mode)
                val la = bufA[p].toLong() * ma0 + bufA[p - 1].toLong() * ma1 + bufA[p - 2].toLong() * ma2 + bufA[p - 3].toLong() * ma3
                val lb = bufB[p].toLong() * mb0 + bufB[p - 1].toLong() * mb1 + bufB[p - 2].toLong() * mb2 +
                    bufB[p - 3].toLong() * mb3 + bufB[p - 4].toLong() * mb4
                val curL = nA + ((la + (lb shr 1)) shr 10).toInt()
                if (curL != cur) {
                    diverged = true
                    if (interim) cur = curL
                }
            }

            adA[p] = adapt(bufA[p])
            adA[p - 1] = adapt(bufA[p - 1])
            adB[p] = adapt(bufB[p])
            adB[p - 1] = adapt(bufB[p - 1])

            if (nA > 0) {
                ma0 -= adA[p]; ma1 -= adA[p - 1]; ma2 -= adA[p - 2]; ma3 -= adA[p - 3]
                mb0 -= adB[p]; mb1 -= adB[p - 1]; mb2 -= adB[p - 2]; mb3 -= adB[p - 3]; mb4 -= adB[p - 4]
            } else if (nA < 0) {
                ma0 += adA[p]; ma1 += adA[p - 1]; ma2 += adA[p - 2]; ma3 += adA[p - 3]
                mb0 += adB[p]; mb1 += adB[p - 1]; mb2 += adB[p - 2]; mb3 += adB[p - 3]; mb4 += adB[p - 4]
            }

            // 1 次のフィルター(31/32)を戻す
            stageA = cur + ((stageA * 31) shr 5)
            lastA = cur
            this.p = p + 1
            return stageA
        }
    }

    companion object {
        /** 1 回の decodeNext() で出すサンプル数(フレームを分けて出す) */
        private const val CHUNK = 4608
        /** 1 フレームのサンプル数・バイト数の上限(壊れたファイルで大きな配列を作らない) */
        private const val MAX_BLOCKS_PER_FRAME = 73728 * 32
        private const val MAX_FRAME_BYTES = 64L shl 20

        private const val WINDOW = 512
        private const val HIST = 8
        private const val NN_WINDOW = 512

        private const val SPECIAL_MONO_SILENCE = 1
        private const val SPECIAL_LEFT_SILENCE = 1
        private const val SPECIAL_RIGHT_SILENCE = 2
        private const val SPECIAL_PSEUDO_STEREO = 4

        private const val BOTTOM_VALUE = 1L shl 23
        private const val MODEL_ELEMENTS = 64

        private val K_SUM_MIN_BOUNDARY = intArrayOf(
            0, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288,
            1048576, 2097152, 4194304, 8388608, 16777216, 33554432, 67108864, 134217728, 268435456, 536870912,
            1073741824, Int.MIN_VALUE, 0, 0, 0, 0, 0,
        )

        private val RANGE_TOTAL_1 = longArrayOf(
            0, 14824, 28224, 39348, 47855, 53994, 58171, 60926, 62682, 63786, 64463, 64878, 65126, 65276, 65365, 65419,
            65450, 65469, 65480, 65487, 65491, 65493, 65494, 65495, 65496, 65497, 65498, 65499, 65500, 65501, 65502, 65503,
            65504, 65505, 65506, 65507, 65508, 65509, 65510, 65511, 65512, 65513, 65514, 65515, 65516, 65517, 65518, 65519,
            65520, 65521, 65522, 65523, 65524, 65525, 65526, 65527, 65528, 65529, 65530, 65531, 65532, 65533, 65534, 65535, 65536,
        )
        private val RANGE_WIDTH_1 = longArrayOf(
            14824, 13400, 11124, 8507, 6139, 4177, 2755, 1756, 1104, 677, 415, 248, 150, 89, 54, 31,
            19, 11, 7, 4, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        )
        private val RANGE_TOTAL_2 = longArrayOf(
            0, 19578, 36160, 48417, 56323, 60899, 63265, 64435, 64971, 65232, 65351, 65416, 65447, 65466, 65476, 65482,
            65485, 65488, 65490, 65491, 65492, 65493, 65494, 65495, 65496, 65497, 65498, 65499, 65500, 65501, 65502, 65503,
            65504, 65505, 65506, 65507, 65508, 65509, 65510, 65511, 65512, 65513, 65514, 65515, 65516, 65517, 65518, 65519,
            65520, 65521, 65522, 65523, 65524, 65525, 65526, 65527, 65528, 65529, 65530, 65531, 65532, 65533, 65534, 65535, 65536,
        )
        private val RANGE_WIDTH_2 = longArrayOf(
            19578, 16582, 12257, 7906, 4576, 2366, 1170, 536, 261, 119, 65, 31, 19, 10, 6, 3,
            3, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        )

        /** CRC32(反転した多項式 0xEDB88320) */
        private val CRC_TABLE = IntArray(256).also { t ->
            for (n in 0 until 256) {
                var c = n
                repeat(8) { c = if ((c and 1) != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
                t[n] = c
            }
        }
    }
}
