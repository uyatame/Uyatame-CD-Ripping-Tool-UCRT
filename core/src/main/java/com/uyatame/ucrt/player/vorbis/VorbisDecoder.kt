package com.uyatame.ucrt.player.vorbis

import com.uyatame.ucrt.player.Imdct
import java.io.IOException
import kotlin.math.PI
import kotlin.math.sin

/** Vorbis のデータが壊れている・対応していない */
internal class VorbisException(msg: String) : IOException(msg)

/**
 * Vorbis のデコーダー(Vorbis I の仕様のとおりに作ったもの。細かいところは stb_vorbis(パブリックドメイン)と同じにしてある)。
 * 3 つの見出し(識別・コメント・設定)を読んでから、音声のパケットを 1 つずつ PCM(-1〜1 の小数)にする。
 * 対応しない(例外): floor 0(2000 年ごろの古いエンコーダーだけが使う)
 */
internal class VorbisDecoder(id: ByteArray, setup: ByteArray) {
    val channels: Int
    val sampleRate: Int
    /** 短いブロックと長いブロックの大きさ */
    val bs0: Int
    val bs1: Int

    private val books: Array<Codebook>
    private val floors: Array<Floor1>
    private val residues: Array<Residue>
    private val mappings: Array<Mapping>
    private val modes: Array<Mode>
    private val modeBits: Int

    private val b = VBits()

    init {
        // ---- 識別の見出し ----
        b.init(id, 0, id.size)
        if (b.bits(8) != 1 || !isVorbis(b)) throw VorbisException("Vorbis: bad identification header")
        if (b.bits(32) != 0) throw VorbisException("Vorbis: unsupported version")
        channels = b.bits(8)
        sampleRate = b.bits(32)
        b.bits(32); b.bits(32); b.bits(32) // ビットレート(最大・公称・最小)
        val s = b.bits(8)
        bs0 = 1 shl (s and 15)
        bs1 = 1 shl (s ushr 4)
        if (channels <= 0 || sampleRate <= 0) throw VorbisException("Vorbis: bad identification header")
        if (bs0 < 64 || bs1 > 8192 || bs0 > bs1) throw VorbisException("Vorbis: bad block sizes")

        // ---- 設定の見出し ----
        b.init(setup, 0, setup.size)
        if (b.bits(8) != 5 || !isVorbis(b)) throw VorbisException("Vorbis: bad setup header")
        books = Array(b.bits(8) + 1) { Codebook.read(b) }
        // 時間領域の変換(使われていない。すべて 0)
        repeat(b.bits(6) + 1) { if (b.bits(16) != 0) throw VorbisException("Vorbis: bad time domain transform") }
        floors = Array(b.bits(6) + 1) {
            when (b.bits(16)) {
                1 -> Floor1.read(b, books)
                0 -> throw VorbisException("Vorbis: floor 0 not supported")
                else -> throw VorbisException("Vorbis: bad floor type")
            }
        }
        residues = Array(b.bits(6) + 1) { Residue.read(b, books) }
        mappings = Array(b.bits(6) + 1) { Mapping.read(b, channels, floors.size, residues.size) }
        modes = Array(b.bits(6) + 1) {
            val m = Mode(b.bits(1) == 1, b.bits(16), b.bits(16), b.bits(8))
            if (m.windowType != 0 || m.transformType != 0 || m.mapping >= mappings.size) throw VorbisException("Vorbis: bad mode")
            m
        }
        if (b.bits(1) != 1 || b.eop) throw VorbisException("Vorbis: bad setup header framing")
        modeBits = ilog(modes.size - 1)
    }

    // ---- 窓と逆 MDCT ----
    private val win0 = window(bs0 / 2)
    private val win1 = window(bs1 / 2)
    private val imdct0 = Imdct(bs0, 1.0)
    private val imdct1 = if (bs1 == bs0) imdct0 else Imdct(bs1, 1.0)

    // ---- 作業用 ----
    private val spec = Array(channels) { FloatArray(bs1 / 2) }
    private val time = Array(channels) { FloatArray(bs1) }
    private val prev = Array(channels) { FloatArray(bs1 / 2) }
    private var prevN = 0
    private val finalY = Array(channels) { IntArray(Floor1.MAX_VALUES) }
    private val step2 = BooleanArray(Floor1.MAX_VALUES)
    private val zero = BooleanArray(channels)
    private val noResidue = BooleanArray(channels)

    /** 出力: pcm[チャンネル][0 until 返した数] */
    val pcm = Array(channels) { FloatArray(bs1 / 2) }

    /** 前のパケットとのつながりを切る(シークのあと。次のパケットは音を出さない) */
    fun reset() {
        prevN = 0
    }

    /** 音声のパケットのブロックの大きさ。音声のパケットでなければ 0 */
    fun blockSize(p: ByteArray, off: Int, len: Int): Int {
        b.init(p, off, len)
        if (len == 0 || b.bits(1) != 0) return 0
        val m = b.bits(modeBits)
        if (b.eop || m >= modes.size) return 0
        return if (modes[m].blockFlag) bs1 else bs0
    }

    /**
     * 音声のパケットをデコードする。出したサンプルの数(1 チャンネルあたり)を返す。
     * 最初のパケット(とシークの直後のパケット)は、次と重ね合わせる分だけなので 0
     */
    fun decode(p: ByteArray, off: Int, len: Int): Int {
        b.init(p, off, len)
        if (len == 0 || b.bits(1) != 0) return 0 // 音声のパケットでない
        val mi = b.bits(modeBits)
        if (b.eop || mi >= modes.size) return 0
        val mode = modes[mi]
        val n = if (mode.blockFlag) bs1 else bs0
        var prevFlag = false
        var nextFlag = false
        if (mode.blockFlag) {
            prevFlag = b.bits(1) == 1
            nextFlag = b.bits(1) == 1
        }
        val map = mappings[mode.mapping]
        val n2 = n / 2

        // ---- floor ----
        for (c in 0 until channels) {
            val f = floors[map.submapFloor[map.mux[c]]]
            zero[c] = !f.decode(b, books, finalY[c], step2)
        }
        // 結合(カップリング)している組は、どちらかに音があれば両方の残差を読む
        for (c in 0 until channels) noResidue[c] = zero[c]
        for (i in 0 until map.couplingSteps) {
            val m = map.magnitude[i]
            val a = map.angle[i]
            if (!noResidue[m] || !noResidue[a]) { noResidue[m] = false; noResidue[a] = false }
        }

        // ---- 残差 ----
        for (c in 0 until channels) java.util.Arrays.fill(spec[c], 0, n2, 0f)
        for (s in 0 until map.submaps) {
            val chs = map.submapChannels[s]
            residues[map.submapResidue[s]].decode(b, books, spec, chs, noResidue, n2)
        }

        // ---- 結合を戻す(逆の順に) ----
        for (i in map.couplingSteps - 1 downTo 0) {
            val mv = spec[map.magnitude[i]]
            val av = spec[map.angle[i]]
            for (k in 0 until n2) {
                val m = mv[k]
                val a = av[k]
                if (m > 0) {
                    if (a > 0) { av[k] = m - a } else { av[k] = m; mv[k] = m + a }
                } else {
                    if (a > 0) { av[k] = m + a } else { av[k] = m; mv[k] = m - a }
                }
            }
        }

        // ---- floor の曲線を掛ける → 逆 MDCT → 窓 ----
        val im = if (mode.blockFlag) imdct1 else imdct0
        val leftLen = if (mode.blockFlag && !prevFlag) bs0 / 2 else n2
        val rightLen = if (mode.blockFlag && !nextFlag) bs0 / 2 else n2
        val leftWin = if (leftLen == bs0 / 2) win0 else win1
        val rightWin = if (rightLen == bs0 / 2) win0 else win1
        val leftStart = n / 4 - leftLen / 2
        val rightStart = n * 3 / 4 - rightLen / 2
        for (c in 0 until channels) {
            val sp = spec[c]
            if (zero[c]) {
                java.util.Arrays.fill(sp, 0, n2, 0f)
            } else {
                floors[map.submapFloor[map.mux[c]]].render(finalY[c], sp, n2)
            }
            val t = time[c]
            im.run(sp, 0, t)
            for (i in 0 until leftStart) t[i] = 0f
            for (i in 0 until leftLen) t[leftStart + i] *= leftWin[i]
            for (i in 0 until rightLen) t[rightStart + i] *= rightWin[rightLen - 1 - i]
            for (i in rightStart + rightLen until n) t[i] = 0f
        }

        // ---- 前のパケットの後ろ半分と重ねる ----
        var count = 0
        if (prevN > 0) {
            count = prevN / 4 + n / 4
            val shift = n / 4 - prevN / 4 // 今のパケットの前半での位置 = 出力の位置 + shift
            val ph = prevN / 2
            for (c in 0 until channels) {
                val o = pcm[c]
                val pv = prev[c]
                val t = time[c]
                for (j in 0 until count) {
                    var v = if (j < ph) pv[j] else 0f
                    val cj = j + shift
                    if (cj in 0 until n2) v += t[cj]
                    o[j] = v
                }
            }
        }
        for (c in 0 until channels) System.arraycopy(time[c], n2, prev[c], 0, n2)
        prevN = n
        return count
    }

    companion object {
        fun isVorbis(b: VBits): Boolean {
            val s = "vorbis"
            for (ch in s) if (b.bits(8) != ch.code) return false
            return true
        }

        /** 2 進で何桁か(0 → 0、1 → 1、2・3 → 2、…) */
        fun ilog(v: Int): Int {
            var n = 0
            var x = v
            while (x > 0) { n++; x = x ushr 1 }
            return n
        }

        /** Vorbis の窓の立ち上がり(長さ len) */
        private fun window(len: Int): FloatArray = FloatArray(len) {
            val x = sin((it + 0.5) / len * PI / 2)
            sin(PI / 2 * x * x).toFloat()
        }
    }
}

/** ビットの読み込み(下の桁から)。パケットの終わりを過ぎると eop になり、0 を返す */
internal class VBits {
    private var b = ByteArray(0)
    private var base = 0
    private var bitLen = 0
    var pos = 0
        private set
    var eop = false
        private set

    fun init(data: ByteArray, off: Int, len: Int) {
        b = data
        base = off
        bitLen = len * 8
        pos = 0
        eop = false
    }

    fun bits(n: Int): Int {
        if (n == 0) return 0
        if (pos + n > bitLen) {
            eop = true
            pos = bitLen
            return 0
        }
        val i = pos ushr 3
        val byteLen = bitLen ushr 3
        var acc = 0L
        val need = ((pos and 7) + n + 7) ushr 3
        for (k in 0 until need) if (i + k < byteLen) acc = acc or ((b[base + i + k].toLong() and 0xFF) shl (8 * k))
        val v = (acc ushr (pos and 7)) and ((1L shl n) - 1)
        pos += n
        return v.toInt()
    }

    /** 次の n ビット(1〜24)を見る。終わりより後ろは 0。位置は動かさない */
    fun peek(n: Int): Int {
        val i = pos ushr 3
        val byteLen = bitLen ushr 3
        var acc = 0
        for (k in 0 until 4) if (i + k < byteLen) acc = acc or ((b[base + i + k].toInt() and 0xFF) shl (8 * k))
        return (acc ushr (pos and 7)) and ((1 shl n) - 1)
    }

    /** 残りのビットの数 */
    fun left(): Int = bitLen - pos

    fun skip(n: Int) {
        if (pos + n > bitLen) { eop = true; pos = bitLen } else pos += n
    }
}

/** 符号帳(ハフマン符号と、ベクトルの値) */
internal class Codebook(
    val dim: Int,
    val entries: Int,
    private val lens: IntArray,
    /** 値の表([番号 × dim + i])。値を持たない符号帳は null */
    val values: FloatArray?,
) {
    /** 頭の FAST ビットで引く表: [番号 << 5 | 長さ]、無ければ -1 */
    private val fast = IntArray(1 shl FAST) { -1 }
    /** 長い符号のための木(節の [左, 右]。値が負なら葉: −(番号 + 1)) */
    private var tree = IntArray(0)
    /** 使われている符号が 1 つだけのときの番号(その符号は長さを読み飛ばす)。無ければ -1 */
    private var single = -1

    init {
        build()
    }

    private fun build() {
        val used = lens.count { it > 0 }
        if (used == 0) return
        if (used == 1) {
            single = lens.indexOfFirst { it > 0 }
            return
        }
        // 規格の割り当て(番号の順に、空いているいちばん左の枝)。stb_vorbis の compute_codewords と同じ
        val avail = LongArray(33)
        val codes = LongArray(entries)
        var first = true
        for (i in 0 until entries) {
            val l = lens[i]
            if (l == 0) continue
            if (first) {
                first = false
                codes[i] = 0
                for (k in 1..l) avail[k] = 1L shl (32 - k)
                continue
            }
            var z = l
            while (z > 0 && avail[z] == 0L) z--
            if (z == 0) throw VorbisException("Vorbis: bad codebook (overspecified)")
            val res = avail[z]
            avail[z] = 0
            codes[i] = res
            if (z != l) for (y in l downTo z + 1) avail[y] = res + (1L shl (32 - y))
        }
        // 木を作る(上の桁から)。速い表は、読む順(下の桁から)に並べる
        val t = ArrayList<Int>()
        t.add(0); t.add(0)
        for (i in 0 until entries) {
            val l = lens[i]
            if (l == 0) continue
            val c = (codes[i] ushr (32 - l)).toInt()
            var node = 0
            for (k in l - 1 downTo 0) {
                val bit = (c ushr k) and 1
                val slot = node * 2 + bit
                if (k == 0) {
                    t[slot] = -(i + 1)
                } else {
                    var nx = t[slot]
                    if (nx <= 0) {
                        if (nx < 0) throw VorbisException("Vorbis: bad codebook")
                        nx = t.size / 2
                        t.add(0); t.add(0)
                        t[slot] = nx
                    }
                    node = nx
                }
            }
            if (l <= FAST) {
                // 読む順に並べ替えた符号
                var r = 0
                for (k in 0 until l) r = r or (((c ushr (l - 1 - k)) and 1) shl k)
                var x = r
                while (x < (1 shl FAST)) {
                    fast[x] = (i shl 5) or l
                    x += 1 shl l
                }
            }
        }
        tree = t.toIntArray()
    }

    /** 符号を 1 つ読む。パケットの終わり・壊れた符号なら -1 */
    fun decode(b: VBits): Int {
        if (single >= 0) {
            b.skip(lens[single])
            return if (b.eop) -1 else single
        }
        if (tree.isEmpty()) return -1
        if (b.left() >= FAST) {
            val e = fast[b.peek(FAST)]
            if (e >= 0) {
                b.skip(e and 31)
                return e ushr 5
            }
        }
        var node = 0
        while (true) {
            val bit = b.bits(1)
            if (b.eop) return -1
            val nx = tree[node * 2 + bit]
            if (nx < 0) return -nx - 1
            if (nx == 0) return -1
            node = nx
        }
    }

    companion object {
        private const val FAST = 10

        fun read(b: VBits): Codebook {
            if (b.bits(24) != 0x564342) throw VorbisException("Vorbis: bad codebook sync")
            val dim = b.bits(16)
            val entries = b.bits(24)
            if (entries > (1 shl 20)) throw VorbisException("Vorbis: codebook too large")
            val lens = IntArray(entries)
            if (b.bits(1) == 0) {
                val sparse = b.bits(1) == 1
                for (i in 0 until entries) {
                    if (!sparse || b.bits(1) == 1) lens[i] = b.bits(5) + 1
                }
            } else {
                var cur = b.bits(5) + 1
                var i = 0
                while (i < entries) {
                    val num = b.bits(VorbisDecoder.ilog(entries - i))
                    if (i + num > entries || cur > 32) throw VorbisException("Vorbis: bad codebook lengths")
                    for (k in 0 until num) lens[i + k] = cur
                    i += num
                    cur++
                }
            }
            if (b.eop) throw VorbisException("Vorbis: short setup header")
            val type = b.bits(4)
            var values: FloatArray? = null
            if (type == 1 || type == 2) {
                val min = float32(b.bits(32))
                val delta = float32(b.bits(32))
                val valueBits = b.bits(4) + 1
                val seq = b.bits(1) == 1
                val lookup = if (type == 1) lookup1(entries, dim) else entries * dim
                if (lookup.toLong() * valueBits > b.left()) throw VorbisException("Vorbis: short setup header")
                val mult = IntArray(lookup) { b.bits(valueBits) }
                if (entries.toLong() * dim > (8 shl 20)) throw VorbisException("Vorbis: codebook too large")
                val v = FloatArray(entries * dim)
                for (e in 0 until entries) {
                    if (lens[e] == 0) continue
                    var last = 0f
                    var div = 1
                    for (i in 0 until dim) {
                        val off = if (type == 1) (e / div) % lookup else e * dim + i
                        val x = mult[off] * delta + min + last
                        v[e * dim + i] = x
                        if (seq) last = x
                        if (type == 1) div *= lookup
                    }
                }
                values = v
            } else if (type != 0) {
                throw VorbisException("Vorbis: bad codebook lookup type")
            }
            return Codebook(dim, entries, lens, values)
        }

        /** 仕様の float32_unpack */
        private fun float32(x: Int): Float {
            val mant = x and 0x1fffff
            val sign = x and 0x80000000.toInt()
            val exp = (x and 0x7fe00000) ushr 21
            val r = Math.scalb(mant.toDouble(), exp - 788)
            return (if (sign != 0) -r else r).toFloat()
        }

        /** r^dim ≦ entries となるいちばん大きい r */
        private fun lookup1(entries: Int, dim: Int): Int {
            if (dim == 0) return 0
            var r = Math.floor(Math.exp(Math.log(entries.toDouble()) / dim)).toInt()
            while (pow(r + 1, dim) <= entries) r++
            while (r > 0 && pow(r, dim) > entries) r--
            return r
        }

        private fun pow(a: Int, n: Int): Long {
            var p = 1L
            repeat(n) { p *= a; if (p > Int.MAX_VALUE) return p }
            return p
        }
    }
}

/** floor 1(スペクトルの大まかな形を、折れ線で表したもの) */
internal class Floor1(
    private val partClass: IntArray,
    private val classDim: IntArray,
    private val classSub: IntArray,
    private val classMaster: IntArray,
    private val subBooks: Array<IntArray>,
    private val multiplier: Int,
    private val x: IntArray,
) {
    private val values = x.size
    private val sorted: IntArray = (0 until values).sortedBy { x[it] }.toIntArray()
    private val low = IntArray(values)
    private val high = IntArray(values)

    init {
        // それぞれの点の、左と右のいちばん近い点(自分より前の点から)
        for (i in 2 until values) {
            var lo = 0
            var hi = 1
            var lx = -1
            var hx = Int.MAX_VALUE
            for (j in 0 until i) {
                if (x[j] < x[i] && x[j] > lx) { lx = x[j]; lo = j }
                if (x[j] > x[i] && x[j] < hx) { hx = x[j]; hi = j }
            }
            low[i] = lo
            high[i] = hi
        }
    }

    /** このチャンネルの floor を読む。音が無い(または読めない)なら false。曲線の点は y(使わない点は -1) */
    fun decode(b: VBits, books: Array<Codebook>, y: IntArray, step2: BooleanArray): Boolean {
        if (b.bits(1) == 0) return false
        val range = RANGES[multiplier - 1]
        val bits = VorbisDecoder.ilog(range - 1)
        y[0] = b.bits(bits)
        y[1] = b.bits(bits)
        var off = 2
        for (p in partClass.indices) {
            val cls = partClass[p]
            val cdim = classDim[cls]
            val cbits = classSub[cls]
            val csub = (1 shl cbits) - 1
            var cval = 0
            if (cbits > 0) {
                cval = books[classMaster[cls]].decode(b)
                if (cval < 0) return false
            }
            for (k in 0 until cdim) {
                val book = subBooks[cls][cval and csub]
                cval = cval ushr cbits
                if (book >= 0) {
                    val v = books[book].decode(b)
                    if (v < 0) return false
                    y[off++] = v
                } else {
                    y[off++] = 0
                }
            }
        }
        if (b.eop) return false
        // 予測からの差を、実際の値に直す
        step2[0] = true
        step2[1] = true
        for (j in 2 until values) {
            val lo = low[j]
            val hi = high[j]
            val pred = predict(x[j], x[lo], x[hi], y[lo], y[hi])
            val v = y[j]
            val highroom = range - pred
            val lowroom = pred
            val room = if (highroom < lowroom) highroom * 2 else lowroom * 2
            if (v != 0) {
                step2[lo] = true
                step2[hi] = true
                step2[j] = true
                y[j] = if (v >= room) {
                    if (highroom > lowroom) v - lowroom + pred else pred - v + highroom - 1
                } else {
                    if (v and 1 != 0) pred - ((v + 1) shr 1) else pred + (v shr 1)
                }
            } else {
                step2[j] = false
                y[j] = pred
            }
        }
        for (j in 0 until values) if (!step2[j]) y[j] = -1
        return true
    }

    /** 曲線を描いて、スペクトル(残差)に掛ける */
    fun render(y: IntArray, out: FloatArray, n: Int) {
        var lx = 0
        var ly = y[0] * multiplier
        for (q in 1 until values) {
            val j = sorted[q]
            if (y[j] >= 0) {
                val hy = y[j] * multiplier
                val hx = x[j]
                if (lx != hx) line(out, lx, ly, hx, hy, n)
                lx = hx
                ly = hy
            }
        }
        if (lx < n) {
            val v = DB[ly and 255]
            for (j in lx until n) out[j] *= v
        }
    }

    /** 仕様の render_line(整数のブレゼンハム。ビット単位で同じにする必要がある) */
    private fun line(out: FloatArray, x0: Int, y0: Int, x1In: Int, y1: Int, n: Int) {
        val dy = y1 - y0
        val adx = x1In - x0
        var ady = Math.abs(dy)
        val base = dy / adx
        val sy = if (dy < 0) base - 1 else base + 1
        ady -= Math.abs(base) * adx
        val x1 = if (x1In > n) n else x1In
        var xx = x0
        var yy = y0
        var err = 0
        if (xx < x1) {
            out[xx] *= DB[yy and 255]
            xx++
            while (xx < x1) {
                err += ady
                if (err >= adx) {
                    err -= adx
                    yy += sy
                } else {
                    yy += base
                }
                out[xx] *= DB[yy and 255]
                xx++
            }
        }
    }

    companion object {
        const val MAX_VALUES = 65
        private val RANGES = intArrayOf(256, 128, 86, 64)

        private fun predict(x: Int, x0: Int, x1: Int, y0: Int, y1: Int): Int {
            val dy = y1 - y0
            val adx = x1 - x0
            val err = Math.abs(dy) * (x - x0)
            val off = err / adx
            return if (dy < 0) y0 - off else y0 + off
        }

        fun read(b: VBits, books: Array<Codebook>): Floor1 {
            val parts = b.bits(5)
            val partClass = IntArray(parts) { b.bits(4) }
            val maxClass = (partClass.maxOrNull() ?: -1) + 1
            val classDim = IntArray(maxClass)
            val classSub = IntArray(maxClass)
            val classMaster = IntArray(maxClass)
            val subBooks = Array(maxClass) { IntArray(0) }
            for (c in 0 until maxClass) {
                classDim[c] = b.bits(3) + 1
                classSub[c] = b.bits(2)
                if (classSub[c] > 0) {
                    classMaster[c] = b.bits(8)
                    if (classMaster[c] >= books.size) throw VorbisException("Vorbis: bad floor book")
                }
                subBooks[c] = IntArray(1 shl classSub[c]) {
                    val k = b.bits(8) - 1
                    if (k >= books.size) throw VorbisException("Vorbis: bad floor book")
                    k
                }
            }
            val mult = b.bits(2) + 1
            val rangeBits = b.bits(4)
            val xs = ArrayList<Int>()
            xs.add(0)
            xs.add(1 shl rangeBits)
            for (p in 0 until parts) repeat(classDim[partClass[p]]) { xs.add(b.bits(rangeBits)) }
            if (xs.size > MAX_VALUES) throw VorbisException("Vorbis: too many floor points")
            if (xs.toSet().size != xs.size) throw VorbisException("Vorbis: duplicate floor points")
            return Floor1(partClass, classDim, classSub, classMaster, subBooks, mult, xs.toIntArray())
        }

        /** 仕様の floor1_inverse_dB_table */
        private val DB = floatArrayOf(
            1.0649863e-07f, 1.1341951e-07f, 1.2079015e-07f, 1.2863978e-07f, 1.3699951e-07f, 1.4590251e-07f, 1.5538408e-07f, 1.6548181e-07f,
            1.7623575e-07f, 1.8768855e-07f, 1.9988561e-07f, 2.1287530e-07f, 2.2670913e-07f, 2.4144197e-07f, 2.5713223e-07f, 2.7384213e-07f,
            2.9163793e-07f, 3.1059021e-07f, 3.3077411e-07f, 3.5226968e-07f, 3.7516214e-07f, 3.9954229e-07f, 4.2550680e-07f, 4.5315863e-07f,
            4.8260743e-07f, 5.1396998e-07f, 5.4737065e-07f, 5.8294187e-07f, 6.2082472e-07f, 6.6116941e-07f, 7.0413592e-07f, 7.4989464e-07f,
            7.9862701e-07f, 8.5052630e-07f, 9.0579828e-07f, 9.6466216e-07f, 1.0273513e-06f, 1.0941144e-06f, 1.1652161e-06f, 1.2409384e-06f,
            1.3215816e-06f, 1.4074654e-06f, 1.4989305e-06f, 1.5963394e-06f, 1.7000785e-06f, 1.8105592e-06f, 1.9282195e-06f, 2.0535261e-06f,
            2.1869758e-06f, 2.3290978e-06f, 2.4804557e-06f, 2.6416497e-06f, 2.8133190e-06f, 2.9961443e-06f, 3.1908506e-06f, 3.3982101e-06f,
            3.6190449e-06f, 3.8542308e-06f, 4.1047004e-06f, 4.3714470e-06f, 4.6555282e-06f, 4.9580707e-06f, 5.2802740e-06f, 5.6234160e-06f,
            5.9888572e-06f, 6.3780469e-06f, 6.7925283e-06f, 7.2339451e-06f, 7.7040476e-06f, 8.2047000e-06f, 8.7378876e-06f, 9.3057248e-06f,
            9.9104632e-06f, 1.0554501e-05f, 1.1240392e-05f, 1.1970856e-05f, 1.2748789e-05f, 1.3577278e-05f, 1.4459606e-05f, 1.5399272e-05f,
            1.6400004e-05f, 1.7465768e-05f, 1.8600792e-05f, 1.9809576e-05f, 2.1096914e-05f, 2.2467911e-05f, 2.3928002e-05f, 2.5482978e-05f,
            2.7139006e-05f, 2.8902651e-05f, 3.0780908e-05f, 3.2781225e-05f, 3.4911534e-05f, 3.7180282e-05f, 3.9596466e-05f, 4.2169667e-05f,
            4.4910090e-05f, 4.7828601e-05f, 5.0936773e-05f, 5.4246931e-05f, 5.7772202e-05f, 6.1526565e-05f, 6.5524908e-05f, 6.9783085e-05f,
            7.4317983e-05f, 7.9147585e-05f, 8.4291040e-05f, 8.9768747e-05f, 9.5602426e-05f, 0.00010181521f, 0.00010843174f, 0.00011547824f,
            0.00012298267f, 0.00013097477f, 0.00013948625f, 0.00014855085f, 0.00015820453f, 0.00016848555f, 0.00017943469f, 0.00019109536f,
            0.00020351382f, 0.00021673929f, 0.00023082423f, 0.00024582449f, 0.00026179955f, 0.00027881276f, 0.00029693158f, 0.00031622787f,
            0.00033677814f, 0.00035866388f, 0.00038197188f, 0.00040679456f, 0.00043323036f, 0.00046138411f, 0.00049136745f, 0.00052329927f,
            0.00055730621f, 0.00059352311f, 0.00063209358f, 0.00067317058f, 0.00071691700f, 0.00076350630f, 0.00081312324f, 0.00086596457f,
            0.00092223983f, 0.00098217216f, 0.0010459992f, 0.0011139742f, 0.0011863665f, 0.0012634633f, 0.0013455702f, 0.0014330129f,
            0.0015261382f, 0.0016253153f, 0.0017309374f, 0.0018434235f, 0.0019632195f, 0.0020908006f, 0.0022266726f, 0.0023713743f,
            0.0025254795f, 0.0026895994f, 0.0028643847f, 0.0030505286f, 0.0032487691f, 0.0034598925f, 0.0036847358f, 0.0039241906f,
            0.0041792066f, 0.0044507950f, 0.0047400328f, 0.0050480668f, 0.0053761186f, 0.0057254891f, 0.0060975636f, 0.0064938176f,
            0.0069158225f, 0.0073652516f, 0.0078438871f, 0.0083536271f, 0.0088964928f, 0.009474637f, 0.010090352f, 0.010746080f,
            0.011444421f, 0.012188144f, 0.012980198f, 0.013823725f, 0.014722068f, 0.015678791f, 0.016697687f, 0.017782797f,
            0.018938423f, 0.020169149f, 0.021479854f, 0.022875735f, 0.024362330f, 0.025945531f, 0.027631618f, 0.029427276f,
            0.031339626f, 0.033376252f, 0.035545228f, 0.037855157f, 0.040315199f, 0.042935108f, 0.045725273f, 0.048696758f,
            0.051861348f, 0.055231591f, 0.058820850f, 0.062643361f, 0.066714279f, 0.071049749f, 0.075666962f, 0.080584227f,
            0.085821044f, 0.091398179f, 0.097337747f, 0.10366330f, 0.11039993f, 0.11757434f, 0.12521498f, 0.13335215f,
            0.14201813f, 0.15124727f, 0.16107617f, 0.17154380f, 0.18269168f, 0.19456402f, 0.20720788f, 0.22067342f,
            0.23501402f, 0.25028656f, 0.26655159f, 0.28387361f, 0.30232132f, 0.32196786f, 0.34289114f, 0.36517414f,
            0.38890521f, 0.41417847f, 0.44109412f, 0.46975890f, 0.50028648f, 0.53279791f, 0.56742212f, 0.60429640f,
            0.64356699f, 0.68538959f, 0.72993007f, 0.77736504f, 0.82788260f, 0.88168307f, 0.9389798f, 1.0f,
        )
    }
}

/** 残差(floor で表しきれない細かいところ) */
internal class Residue(
    private val type: Int,
    private val begin: Int,
    private val end: Int,
    private val partSize: Int,
    private val classifications: Int,
    private val classbook: Int,
    /** [分類][段階(0〜7)] の符号帳(無ければ -1) */
    private val bookOf: Array<IntArray>,
) {
    private var cls = Array(0) { IntArray(0) }
    private var inter = FloatArray(0)

    fun decode(b: VBits, books: Array<Codebook>, spec: Array<FloatArray>, chs: IntArray, noResidue: BooleanArray, n2: Int) {
        if (type == 2) {
            // 全部のチャンネルを交互に並べた 1 本として読む(どれかに音があれば全部読む)
            if (chs.all { noResidue[it] }) return
            val ch = chs.size
            val total = n2 * ch
            if (inter.size < total) inter = FloatArray(total)
            java.util.Arrays.fill(inter, 0, total, 0f)
            decodeVectors(b, books, arrayOf(inter), intArrayOf(0), BooleanArray(1), total, 1)
            for (k in 0 until n2) for (j in 0 until ch) spec[chs[j]][k] = inter[k * ch + j]
        } else {
            decodeVectors(b, books, spec, chs, noResidue, n2, type)
        }
    }

    /** 分類を読み、分類ごとの符号帳でベクトルを足していく(8 段階) */
    private fun decodeVectors(b: VBits, books: Array<Codebook>, v: Array<FloatArray>, chs: IntArray, skip: BooleanArray, size: Int, t: Int) {
        val lb = minOf(begin, size)
        val le = minOf(end, size)
        val nRead = le - lb
        if (nRead <= 0) return
        val parts = nRead / partSize
        if (parts == 0) return
        val cb = books[classbook]
        val per = cb.dim
        if (per <= 0) return
        if (cls.size < chs.size || (cls.isNotEmpty() && cls[0].size < parts + per)) cls = Array(maxOf(chs.size, cls.size)) { IntArray(parts + per) }
        for (pass in 0 until 8) {
            var p = 0
            while (p < parts) {
                if (pass == 0) {
                    for (j in chs.indices) {
                        if (skip[chs[j]]) continue
                        var temp = cb.decode(b)
                        if (temp < 0) return
                        for (i in per - 1 downTo 0) {
                            cls[j][i + p] = temp % classifications
                            temp /= classifications
                        }
                    }
                }
                var i = 0
                while (i < per && p < parts) {
                    for (j in chs.indices) {
                        if (skip[chs[j]]) continue
                        val book = bookOf[cls[j][p]][pass]
                        if (book < 0) continue
                        val bk = books[book]
                        val vals = bk.values ?: continue
                        val dim = bk.dim
                        val target = v[chs[j]]
                        val off = lb + p * partSize
                        if (t == 0) {
                            val step = partSize / dim
                            for (k in 0 until step) {
                                val e = bk.decode(b)
                                if (e < 0) return
                                for (d in 0 until dim) target[off + k + d * step] += vals[e * dim + d]
                            }
                        } else {
                            var k = 0
                            while (k < partSize) {
                                val e = bk.decode(b)
                                if (e < 0) return
                                for (d in 0 until dim) if (k + d < partSize) target[off + k + d] += vals[e * dim + d]
                                k += dim
                            }
                        }
                    }
                    i++
                    p++
                }
            }
        }
    }

    companion object {
        fun read(b: VBits, books: Array<Codebook>): Residue {
            val type = b.bits(16)
            if (type > 2) throw VorbisException("Vorbis: bad residue type")
            val begin = b.bits(24)
            val end = b.bits(24)
            val partSize = b.bits(24) + 1
            val classes = b.bits(6) + 1
            val classbook = b.bits(8)
            if (classbook >= books.size) throw VorbisException("Vorbis: bad residue book")
            val cascade = IntArray(classes) {
                val low = b.bits(3)
                val high = if (b.bits(1) == 1) b.bits(5) else 0
                high * 8 + low
            }
            val bookOf = Array(classes) { c ->
                IntArray(8) { pass ->
                    if ((cascade[c] shr pass) and 1 == 1) {
                        val k = b.bits(8)
                        if (k >= books.size) throw VorbisException("Vorbis: bad residue book")
                        k
                    } else -1
                }
            }
            return Residue(type, begin, end, partSize, classes, classbook, bookOf)
        }
    }
}

/** チャンネルと floor・残差の組み合わせ */
internal class Mapping(
    val submaps: Int,
    val couplingSteps: Int,
    val magnitude: IntArray,
    val angle: IntArray,
    val mux: IntArray,
    val submapFloor: IntArray,
    val submapResidue: IntArray,
) {
    /** submap ごとのチャンネル */
    val submapChannels: Array<IntArray> = Array(submaps) { s -> mux.indices.filter { mux[it] == s }.toIntArray() }

    companion object {
        fun read(b: VBits, channels: Int, floors: Int, residues: Int): Mapping {
            if (b.bits(16) != 0) throw VorbisException("Vorbis: bad mapping type")
            val submaps = if (b.bits(1) == 1) b.bits(4) + 1 else 1
            var steps = 0
            var mag = IntArray(0)
            var ang = IntArray(0)
            if (b.bits(1) == 1) {
                steps = b.bits(8) + 1
                val bits = VorbisDecoder.ilog(channels - 1)
                mag = IntArray(steps)
                ang = IntArray(steps)
                for (i in 0 until steps) {
                    mag[i] = b.bits(bits)
                    ang[i] = b.bits(bits)
                    if (mag[i] == ang[i] || mag[i] >= channels || ang[i] >= channels) throw VorbisException("Vorbis: bad coupling")
                }
            }
            if (b.bits(2) != 0) throw VorbisException("Vorbis: bad mapping")
            val mux = IntArray(channels)
            if (submaps > 1) for (c in 0 until channels) {
                mux[c] = b.bits(4)
                if (mux[c] >= submaps) throw VorbisException("Vorbis: bad mapping mux")
            }
            val fl = IntArray(submaps)
            val re = IntArray(submaps)
            for (s in 0 until submaps) {
                b.bits(8) // 使われていない
                fl[s] = b.bits(8)
                re[s] = b.bits(8)
                if (fl[s] >= floors || re[s] >= residues) throw VorbisException("Vorbis: bad mapping submap")
            }
            return Mapping(submaps, steps, mag, ang, mux, fl, re)
        }
    }
}

internal class Mode(val blockFlag: Boolean, val windowType: Int, val transformType: Int, val mapping: Int)
