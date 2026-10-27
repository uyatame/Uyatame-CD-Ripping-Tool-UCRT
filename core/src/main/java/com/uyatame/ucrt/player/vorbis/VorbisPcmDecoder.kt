package com.uyatame.ucrt.player.vorbis

import com.uyatame.ucrt.player.PcmDecoder
import com.uyatame.ucrt.player.RandomInput
import java.io.IOException

/**
 * Ogg Vorbis のファイル(.ogg / .oga)を、自前の Vorbis デコーダーで 16 ビットの PCM にする。
 * 位置はページの granule(そのページで終わるパケットまでのサンプル数)から決めるので、先頭・末尾の詰め物も正しく除く
 */
internal class VorbisPcmDecoder(private val src: RandomInput) : PcmDecoder {
    override var sampleRate = 0
        private set
    override var channels = 0
        private set
    override val bitsPerSample = 16
    override var totalSamples = 0L
        private set
    override var samples: Array<IntArray> = emptyArray()
        private set
    override var blockSize = 0
        private set
    override var frameSample = 0L
        private set

    private lateinit var dec: VorbisDecoder
    private val ogg = OggReader(src)
    /** 最初の音声のページの位置 */
    private var audioStart = 0L
    /** 出力の位置 0 にあたる granule(切り出したファイルなど、0 から始まらないことがある) */
    private var base = 0L
    /** 最後の granule(base を引く前)。分からなければ -1 */
    private var lastGranule = -1L

    /** デコードしたパケットの終わりの位置(base を引く前)。シークの直後で、まだ分からなければ null */
    private var pos: Long? = null
    /** 位置を決めるために先に読んだパケット */
    private val pending = ArrayDeque<OggReader.Packet>()

    override fun open() {
        ogg.seek(0)
        // next() の data は次の呼び出しで上書きされるので、すぐに写す
        val id = ogg.next()?.copy() ?: throw IOException("Ogg: no packet")
        if (id.len < 30 || id.data[0].toInt() != 1 || String(id.data, 1, 6, Charsets.ISO_8859_1) != "vorbis") {
            throw IOException("Ogg: not Vorbis")
        }
        ogg.next() ?: throw IOException("Ogg: no comment header")
        val setup = ogg.next()?.copy() ?: throw IOException("Ogg: no setup header")
        dec = VorbisDecoder(id.data, setup.data)
        sampleRate = dec.sampleRate
        channels = dec.channels
        audioStart = ogg.nextPageStart
        samples = Array(channels) { IntArray(dec.bs1 / 2) }
        lastGranule = ogg.lastGranule()
        // 先頭の位置を決める(最初のページの granule から、そのページのパケットの長さを引いて求める)
        startAt(audioStart, fromStart = true)
        val first = locate()
        base = if (first != null && first > 0) first else 0
        totalSamples = if (lastGranule >= 0) maxOf(0, lastGranule - base) else 0
    }

    /** 頭から読んでいるか(最初のパケットの終わりを 0 とみなせる) */
    private var fromStartNow = true
    /** シークした先から最後のページまで granule が無かった */
    private var eosAfterSeek = false

    /** off のページから読み直す。fromStart のときは、最初のパケットの前に続きの断片は無い */
    private fun startAt(off: Long, fromStart: Boolean) {
        fromStartNow = fromStart
        eosAfterSeek = false
        ogg.seek(off)
        if (!fromStart) ogg.skipContinued = true
        pending.clear()
        pos = null
        dec.reset()
    }

    /**
     * 先にパケットを読んで、granule のあるパケットまでの長さから、最初のパケットの終わりの位置を求める。
     * 求めた位置を返す(ファイルの終わりまで granule が無ければ null)
     */
    private fun locate(): Long? {
        while (true) {
            val p = ogg.next() ?: break
            pending.addLast(p.copy())
            if (p.granule >= 0) break
        }
        if (pending.isEmpty()) return null
        val last = pending.last()
        if (last.granule < 0) {
            // 最後まで granule が無い(壊れたファイル)。0 から数える
            pos = 0
            return 0
        }
        if (last.last) {
            // 最後のページの granule は、末尾の詰め物を除いた数なので、ここからは逆算できない。
            // ファイルの頭からなら 0 から始まるとみなし、シークの直後なら 1 ページ前から読み直す(seekSample)
            if (fromStartNow) {
                pos = 0
                return 0
            }
            eosAfterSeek = true
            return null
        }
        // 後ろから、パケットの間の長さ(前のブロックの 1/4 + 今のブロックの 1/4)を引いていく
        var end = last.granule
        var nextN = dec.blockSize(last.data, 0, last.len)
        for (i in pending.size - 2 downTo 0) {
            val p = pending.elementAt(i)
            val n = dec.blockSize(p.data, 0, p.len)
            if (n > 0 && nextN > 0) end -= n / 4 + nextN / 4
            if (n > 0) nextN = n
        }
        pos = end
        return end
    }

    override fun decodeNext(): Boolean {
        while (true) {
            if (pos == null && locate() == null) return false
            val p = if (pending.isNotEmpty()) pending.removeFirst() else ogg.next() ?: return false
            val start = pos!!
            val n = try {
                dec.decode(p.data, 0, p.len)
            } catch (e: RuntimeException) {
                // 壊れたパケット。つながりを切って続ける
                dec.reset()
                0
            }
            if (n == 0) continue
            pos = start + n
            // 出力の範囲: [start, start + n)。先頭(base より前)と、最後の granule より後ろは除く
            var first = 0
            var last = n
            if (start < base) first = minOf(n.toLong(), base - start).toInt()
            if (lastGranule >= 0 && start + last > lastGranule) last = maxOf(first.toLong(), lastGranule - start).toInt()
            if (last <= first) {
                if (lastGranule >= 0 && start >= lastGranule) return false
                continue
            }
            val map = if (channels in 3..8) CHANNEL_OFFSETS[channels - 1] else null
            for (c in 0 until channels) {
                val o = dec.pcm[c]
                val d = samples[map?.get(c) ?: c]
                for (k in first until last) {
                    val v = Math.round(o[k] * 32768f)
                    d[k - first] = if (v > 32767) 32767 else if (v < -32768) -32768 else v
                }
            }
            blockSize = last - first
            frameSample = start + first - base
            return true
        }
    }

    override fun seekSample(target: Long) {
        // 目的の位置より、長いブロックの半分以上前で終わるページの次から読む
        // (そこから最初のパケットは重ね合わせのためだけなので、出力は目的の位置より前から始まる)
        val goal = target + base - dec.bs1
        if (goal <= 0) {
            startAt(audioStart, fromStart = true)
            return
        }
        var g = goal
        while (true) {
            val at = ogg.pageAfterGranule(audioStart, g)
            if (at == null) {
                startAt(audioStart, fromStart = true)
                return
            }
            startAt(at, fromStart = false)
            // 先の位置を決められるか(最後のページしか無いと決められない)。だめなら、もっと前のページから
            if (locate() != null) return
            if (!eosAfterSeek) return
            val before = ogg.granuleBefore(audioStart, at) ?: run { startAt(audioStart, fromStart = true); return }
            g = before - 1
        }
    }

    private companion object {
        /**
         * Vorbis のチャンネルの順番 → 出力(WAV の順番)の位置。
         * Vorbis の並び: 3ch = L C R、5.1ch = L C R 後L 後R LFE、7.1ch = L C R 横L 横R 後L 後R LFE
         */
        val CHANNEL_OFFSETS = arrayOf(
            intArrayOf(0),
            intArrayOf(0, 1),
            intArrayOf(0, 2, 1),
            intArrayOf(0, 1, 2, 3),
            intArrayOf(0, 2, 1, 3, 4),
            intArrayOf(0, 2, 1, 4, 5, 3),
            intArrayOf(0, 2, 1, 5, 6, 4, 3),
            intArrayOf(0, 2, 1, 6, 7, 4, 5, 3),
        )
    }
}

/** Ogg のページとパケットの読み込み(最初の論理ストリームだけ) */
internal class OggReader(private val src: RandomInput) {
    /**
     * パケット。granule は、このパケットがページで最後に終わるパケットなら、そのページの granule(それ以外は -1)。
     * last はそのページがストリームの最後のページ(EOS)か
     */
    class Packet(val data: ByteArray, val len: Int, val granule: Long, val last: Boolean = false) {
        fun copy() = Packet(data.copyOf(len), len, granule, last)
    }

    private var serial = -1
    private var page = ByteArray(65536 + 282)
    /** 今のページの区切り(lacing)と、読んでいる位置 */
    private var segs = IntArray(255)
    private var segCount = 0
    private var segIndex = 0
    private var bodyPos = 0
    private var pageGranule = -1L
    private var pageEos = false
    /** 次に読むページの位置 */
    var nextPageStart = 0L
        private set
    /** 次のページの頭の「前のページからの続き」を捨てる(シークの直後) */
    var skipContinued = false
    private var pk = ByteArray(8192)
    private var pkLen = 0

    fun seek(off: Long) {
        nextPageStart = off
        segCount = 0
        segIndex = 0
        pkLen = 0
        skipContinued = false
    }

    /** ページを 1 つ読む。終わり・壊れていれば false */
    private fun readPage(): Boolean {
        while (true) {
            val at = nextPageStart
            val h = read(at, 27) ?: return false
            if (!(h[0] == 'O'.code.toByte() && h[1] == 'g'.code.toByte() && h[2] == 'g'.code.toByte() && h[3] == 'S'.code.toByte())) {
                // 同期がずれている: 次の "OggS" を探す
                val n = findSync(at + 1) ?: return false
                nextPageStart = n
                continue
            }
            val type = h[5].toInt() and 0xFF
            val gran = le64(h, 6)
            val ser = le32(h, 14)
            val n = h[26].toInt() and 0xFF
            val lace = read(at + 27, n) ?: return false
            var body = 0
            for (i in 0 until n) body += lace[i].toInt() and 0xFF
            nextPageStart = at + 27 + n + body
            if (serial == -1) serial = ser
            if (ser != serial) continue // ほかのストリーム(映像など)
            if (read(at + 27 + n, body, page) == null) return false
            for (i in 0 until n) segs[i] = lace[i].toInt() and 0xFF
            segCount = n
            segIndex = 0
            bodyPos = 0
            pageGranule = gran
            pageEos = (type and 4) != 0
            // 前のページからの続きで始まるページ: シークの直後なら、その断片は捨てる
            if ((type and 1) != 0 && skipContinued) {
                while (segIndex < segCount) {
                    val s = segs[segIndex++]
                    bodyPos += s
                    if (s < 255) break
                }
            } else if ((type and 1) == 0) {
                // 続きのはずのない所に途中のパケットがあれば捨てる
                pkLen = 0
            }
            skipContinued = false
            return true
        }
    }

    /** 次のパケット。ファイルの終わりなら null(返した data は次の呼び出しまで使える) */
    fun next(): Packet? {
        while (true) {
            if (segIndex >= segCount) {
                if (!readPage()) return null
                continue
            }
            val s = segs[segIndex++]
            if (pkLen + s > pk.size) pk = pk.copyOf(maxOf(pk.size * 2, pkLen + s))
            System.arraycopy(page, bodyPos, pk, pkLen, s)
            pkLen += s
            bodyPos += s
            if (s < 255) {
                // パケットが終わった。このページで最後に終わるパケットか
                var lastEnd = true
                for (k in segIndex until segCount) if (segs[k] < 255) { lastEnd = false; break }
                val len = pkLen
                pkLen = 0
                return Packet(pk, len, if (lastEnd) pageGranule else -1, lastEnd && pageEos)
            }
        }
    }

    /** 最後のページの granule(ファイルの後ろから探す)。無ければ -1 */
    fun lastGranule(): Long {
        val size = src.size
        var end = size
        val chunk = 65536
        while (end > 0) {
            val start = maxOf(0L, end - chunk)
            val b = read(start, (end - start).toInt()) ?: return -1
            for (i in b.size - 27 downTo 0) {
                if (b[i] == 'O'.code.toByte() && b[i + 1] == 'g'.code.toByte() && b[i + 2] == 'g'.code.toByte() && b[i + 3] == 'S'.code.toByte()) {
                    if (le32(b, i + 14) != serial) continue
                    val g = le64(b, i + 6)
                    if (g >= 0) return g
                }
            }
            if (start == 0L) break
            end = start + 27 // ページの頭がまたがっても見つかるように
        }
        return -1
    }

    /**
     * granule が goal 以下のページのうち最後のもの(Q)の、次のページの位置。
     * ページは granule の順に並んでいるので、二分探索で近くまで行ってから順に読む。最初のページから goal を超えていれば null
     */
    fun pageAfterGranule(start: Long, goal: Long): Long? {
        var lo = start
        var hi = src.size
        while (hi - lo > 65536) {
            val mid = (lo + hi) / 2
            val p = findSync(mid)
            val g = if (p != null && p < hi) granuleFrom(p, hi) else null
            if (g != null && g.second <= goal) lo = g.first else hi = mid
        }
        // lo から順に
        var at = lo
        var found: Long? = null
        while (true) {
            val h = read(at, 27) ?: break
            if (!isSync(h, 0)) {
                at = findSync(at + 1) ?: break
                continue
            }
            val n = h[26].toInt() and 0xFF
            val lace = read(at + 27, n) ?: break
            var body = 0
            for (i in 0 until n) body += lace[i].toInt() and 0xFF
            val next = at + 27 + n + body
            if (le32(h, 14) == serial) {
                val g = le64(h, 6)
                if (g >= 0) {
                    if (g <= goal) found = next else break
                }
            }
            at = next
        }
        return found
    }

    /** before のページより前で、granule のある最後のページの granule。無ければ null */
    fun granuleBefore(start: Long, before: Long): Long? {
        var at = start
        var g: Long? = null
        while (at < before) {
            val h = read(at, 27) ?: break
            if (!isSync(h, 0)) { at = findSync(at + 1) ?: break; continue }
            val n = h[26].toInt() and 0xFF
            val lace = read(at + 27, n) ?: break
            var body = 0
            for (i in 0 until n) body += lace[i].toInt() and 0xFF
            val next = at + 27 + n + body
            if (next > before) break
            if (le32(h, 14) == serial) le64(h, 6).takeIf { it >= 0 }?.let { g = it }
            at = next
        }
        return g
    }

    /** p から、granule のある最初のページ: [位置, granule] */
    private fun granuleFrom(p0: Long, limit: Long): Pair<Long, Long>? {
        var p = p0
        while (p < limit) {
            val h = read(p, 27) ?: return null
            if (!isSync(h, 0)) { p = findSync(p + 1) ?: return null; continue }
            val n = h[26].toInt() and 0xFF
            val lace = read(p + 27, n) ?: return null
            var body = 0
            for (i in 0 until n) body += lace[i].toInt() and 0xFF
            if (le32(h, 14) == serial) {
                val g = le64(h, 6)
                if (g >= 0) return p to g
            }
            p += 27 + n + body
        }
        return null
    }

    private fun findSync(from: Long): Long? {
        var p = from
        val size = src.size
        val buf = ByteArray(8192)
        while (p + 4 <= size) {
            val n = minOf(buf.size.toLong(), size - p).toInt()
            if (read(p, n, buf) == null) return null
            for (i in 0..n - 4) if (isSync(buf, i)) return p + i
            p += n - 3
        }
        return null
    }

    private fun isSync(b: ByteArray, i: Int) =
        b[i] == 'O'.code.toByte() && b[i + 1] == 'g'.code.toByte() && b[i + 2] == 'g'.code.toByte() && b[i + 3] == 'S'.code.toByte()

    private fun read(pos: Long, n: Int, into: ByteArray? = null): ByteArray? {
        if (pos < 0 || pos + n > src.size) return null
        val b = into ?: ByteArray(n)
        var off = 0
        while (off < n) {
            val r = src.read(pos + off, b, off, n - off)
            if (r <= 0) return null
            off += r
        }
        return b
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun le64(b: ByteArray, o: Int): Long = (le32(b, o).toLong() and 0xFFFFFFFFL) or (le32(b, o + 4).toLong() shl 32)
}
