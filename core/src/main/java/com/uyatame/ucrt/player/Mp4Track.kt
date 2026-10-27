package com.uyatame.ucrt.player

import java.io.IOException

/**
 * MP4 / M4A の中の、最初の音声トラックのパケット(MP4 のサンプル)の一覧。ALAC・AAC の内蔵デコーダーで使う。
 * 箱(box)の見出しをたどり、stsd・stsz・stco・stsc・stts・elst と、iTunes の前後の無音の情報(iTunSMPB)を読む
 */
internal class Mp4Track private constructor(
    /** サンプルエントリーの 4 文字("alac"・"mp4a" など) */
    val entry: String,
    /** stsd の中身(全部) */
    val stsd: ByteArray,
    /** 時間の単位(1 秒あたり。mdhd) */
    val timescale: Long,
    /** 長さ(timescale の単位。mdhd。分からなければ 0) */
    val duration: Long,
    /** パケットの位置と大きさ */
    val offsets: LongArray,
    val sizes: IntArray,
    /** パケットの始まりの時刻(timescale の単位。stts。最後に終わりを足して 1 つ多い。stts が無ければ null) */
    val times: LongArray?,
    /** 編集リスト(elst)の再生の始まり(timescale の単位)。無ければ -1 */
    val editStart: Long,
    /** 編集リストの再生の長さ(timescale の単位)。無ければ -1 */
    val editLength: Long,
    /** iTunes の情報(iTunSMPB): [先頭の無音, 末尾の詰め物, 長さ](サンプル)。無ければ null */
    val itunesGapless: LongArray?,
) {
    val count: Int get() = sizes.size

    /** start の位置から size バイトの箱(サンプルエントリーの子など)を、名前で探す。見つかれば中身の始まりと終わり */
    fun findInEntry(type: String): IntRange? {
        // stsd: [バージョン 4][数 4] の次に、最初のサンプルエントリー [大きさ 4][名前 4][...]
        val entrySize = u32(stsd, 8).toInt().coerceAtMost(stsd.size - 8)
        val t = fourcc(type)
        var p = 8 + 8
        while (p + 8 <= 8 + entrySize) {
            if (i32(stsd, p + 4) == t) {
                val sz = u32(stsd, p).toInt()
                if (sz >= 8 && p + sz <= 8 + entrySize) return (p + 8) until (p + sz)
            }
            p++
        }
        return null
    }

    companion object {
        /**
         * 最初の音声トラックで、サンプルエントリーが accept に入るものを読む。
         * 見つからなければ IOException(音声はあるが形式が違うときは "MP4: audio track is <形式>")
         */
        fun open(src: RandomInput, accept: Set<String>): Mp4Track {
            val r = Reader(src)
            val top = r.children(0, src.size)
            val moov = top.find("moov") ?: throw IOException("MP4: no moov")
            val moovKids = r.children(moov.start, moov.end)
            var movieScale = 0L
            moovKids.find("mvhd")?.let { r.load(it) }?.let { m ->
                movieScale = if (m.isNotEmpty() && m[0].toInt() == 1 && m.size >= 24) u32(m, 20) else if (m.size >= 16) u32(m, 12) else 0
            }
            var other: String? = null
            for (trak in moovKids) {
                if (trak.type != fourcc("trak")) continue
                val trakKids = r.children(trak.start, trak.end)
                val mdia = trakKids.find("mdia") ?: continue
                val mdiaKids = r.children(mdia.start, mdia.end)
                val hdlr = mdiaKids.find("hdlr")?.let { r.load(it) } ?: continue
                if (hdlr.size < 12 || i32(hdlr, 8) != fourcc("soun")) continue
                val minf = mdiaKids.find("minf") ?: continue
                val stbl = r.children(minf.start, minf.end).find("stbl") ?: continue
                val st = r.children(stbl.start, stbl.end)
                val stsd = st.find("stsd")?.let { r.load(it) } ?: continue
                if (stsd.size < 16) continue
                val entry = String(stsd, 12, 4, Charsets.ISO_8859_1)
                if (entry !in accept) { other = other ?: entry; continue }
                val mdhd = mdiaKids.find("mdhd")?.let { r.load(it) }
                return read(r, src, entry, stsd, mdhd, st, trakKids, movieScale, moovKids)
            }
            throw IOException(if (other != null) "MP4: audio track is $other" else "MP4: no audio track")
        }

        private fun read(
            r: Reader, src: RandomInput, entry: String, stsd: ByteArray, mdhd: ByteArray?, st: List<Box>,
            trakKids: List<Box>, movieScale: Long, moovKids: List<Box>,
        ): Mp4Track {
            // ---- mdhd: 時間の単位と長さ ----
            var timescale = 0L
            var duration = 0L
            if (mdhd != null && mdhd.size >= 24) {
                if (mdhd[0].toInt() == 1 && mdhd.size >= 36) {
                    timescale = u32(mdhd, 20); duration = i64(mdhd, 24)
                } else {
                    timescale = u32(mdhd, 12); duration = u32(mdhd, 16)
                }
            }

            // ---- stsz / stz2: パケットの大きさ ----
            val sizes: IntArray
            val stsz = st.find("stsz")?.let { r.load(it) }
            if (stsz != null) {
                if (stsz.size < 12) throw IOException("MP4: short stsz")
                val constant = u32(stsz, 4).toInt()
                val n = u32(stsz, 8).toInt()
                // パケットはファイルの中に収まるので、数 × 大きさはファイルの大きさを超えない(壊れた値で大きな配列を作らない)
                if (n < 0 || (constant != 0 && n * u32(stsz, 4) > src.size)) throw IOException("MP4: bad stsz")
                sizes = if (constant != 0) IntArray(n) { constant } else {
                    if (stsz.size < 12 + 4L * n) throw IOException("MP4: short stsz")
                    IntArray(n) { u32(stsz, 12 + 4 * it).toInt() }
                }
            } else {
                val stz2 = st.find("stz2")?.let { r.load(it) } ?: throw IOException("MP4: no stsz")
                if (stz2.size < 12) throw IOException("MP4: bad stz2")
                val field = stz2[7].toInt() and 0xFF
                val n = u32(stz2, 8).toInt()
                if (n < 0 || field !in intArrayOf(4, 8, 16) || stz2.size < 12 + (n.toLong() * field + 7) / 8) throw IOException("MP4: bad stz2")
                sizes = IntArray(n) {
                    when (field) {
                        4 -> (stz2[12 + it / 2].toInt() ushr (if (it % 2 == 0) 4 else 0)) and 15
                        8 -> stz2[12 + it].toInt() and 0xFF
                        else -> u16(stz2, 12 + 2 * it)
                    }
                }
            }
            val n = sizes.size
            if (n == 0) throw IOException("MP4: no samples")

            // ---- stco / co64: チャンクの位置 ----
            val chunks: LongArray = st.find("stco")?.let { r.load(it) }?.takeIf { it.size >= 8 }?.let { b ->
                LongArray(u32(b, 4).coerceAtMost((b.size - 8) / 4L).toInt()) { u32(b, 8 + 4 * it) }
            } ?: st.find("co64")?.let { r.load(it) }?.takeIf { it.size >= 8 }?.let { b ->
                LongArray(u32(b, 4).coerceAtMost((b.size - 8) / 8L).toInt()) { i64(b, 8 + 8 * it) }
            } ?: throw IOException("MP4: no stco")

            // ---- stsc: チャンクごとのパケットの数 → パケットの位置 ----
            val stsc = st.find("stsc")?.let { r.load(it) }?.takeIf { it.size >= 8 } ?: throw IOException("MP4: no stsc")
            val scN = u32(stsc, 4).coerceAtMost((stsc.size - 8) / 12L).toInt()
            if (scN == 0) throw IOException("MP4: empty stsc")
            var offsets = LongArray(n)
            var k = 0
            for (e in 0 until scN) {
                // 番号は 1 から。壊れた値(0 や 2^31 以上)で配列の外を見ないように
                val first = (u32(stsc, 8 + 12 * e) - 1).coerceIn(0L, chunks.size.toLong()).toInt()
                val per = u32(stsc, 12 + 12 * e).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val last = if (e + 1 < scN) (u32(stsc, 8 + 12 * (e + 1)) - 1).coerceIn(0L, chunks.size.toLong()).toInt() else chunks.size
                var c = first
                while (c < last && c < chunks.size && k < n) {
                    var off = chunks[c]
                    for (s in 0 until per) {
                        if (k >= n) break
                        offsets[k] = off
                        off += sizes[k]
                        k++
                    }
                    c++
                }
            }
            val count = k // stsc が足りなければ、そこまで
            var sz = sizes
            if (count < n) { offsets = offsets.copyOf(count); sz = sizes.copyOf(count) }
            if (count == 0) throw IOException("MP4: no samples")

            // ---- stts: パケットの始まりの時刻 ----
            var times: LongArray? = null
            st.find("stts")?.let { r.load(it) }?.takeIf { it.size >= 8 }?.let { stts ->
                val tsN = u32(stts, 4).coerceAtMost((stts.size - 8) / 8L).toInt()
                val t = LongArray(count + 1)
                var pos = 0L
                var i = 0
                for (e in 0 until tsN) {
                    val cnt = u32(stts, 8 + 8 * e)
                    val delta = u32(stts, 12 + 8 * e)
                    var j = 0L
                    while (j < cnt && i < count) {
                        t[i++] = pos
                        pos += delta
                        j++
                    }
                }
                // 足りなければ最後の間隔で埋める
                val last = if (i >= 2) t[i - 1] - t[i - 2] else 0L
                while (i < count) { t[i++] = pos; pos += last }
                t[count] = pos
                times = t
            }

            // ---- elst: 再生する範囲(先頭の無音を飛ばすため) ----
            var editStart = -1L
            var editLength = -1L
            trakKids.find("edts")?.let { r.children(it.start, it.end).find("elst") }?.let { r.load(it) }?.takeIf { it.size >= 8 }?.let { e ->
                val v1 = e[0].toInt() == 1
                val num = u32(e, 4).toInt()
                val step = if (v1) 20 else 12
                for (i in 0 until num) {
                    val o = 8 + step * i
                    if (o + step > e.size) break
                    val segDur = if (v1) i64(e, o) else u32(e, o)
                    val mediaTime = if (v1) i64(e, o + 8) else i32(e, o + 4).toLong()
                    // -1 は空の編集(再生の前の無音)。飛ばして、最初の中身のある編集を使う
                    if (mediaTime < 0) continue
                    editStart = mediaTime
                    editLength = if (movieScale > 0 && timescale > 0 && segDur > 0) segDur * timescale / movieScale else -1
                    break
                }
            }

            return Mp4Track(entry, stsd, timescale, duration, offsets, sz, times, editStart, editLength, iTunSmpb(r, moovKids))
        }

        /** moov/udta/meta/ilst の「----」(iTunSMPB)から、先頭の無音・末尾の詰め物・長さを読む */
        private fun iTunSmpb(r: Reader, moovKids: List<Box>): LongArray? = runCatching {
            val udta = moovKids.find("udta") ?: return null
            val meta = r.children(udta.start, udta.end).find("meta") ?: return null
            // meta はバージョンとフラグ(4 バイト)のあとに子が並ぶ(QuickTime の形では無いこともある)
            val kids = r.children(meta.start + 4, meta.end).takeIf { l -> l.any { it.type == fourcc("ilst") } }
                ?: r.children(meta.start, meta.end)
            val ilst = kids.find("ilst") ?: return null
            for (item in r.children(ilst.start, ilst.end)) {
                if (item.type != fourcc("----")) continue
                val parts = r.children(item.start, item.end)
                val name = parts.find("name")?.let { r.load(it) }?.let { String(it, 4, it.size - 4, Charsets.UTF_8) } ?: continue
                if (name != "iTunSMPB") continue
                val data = parts.find("data")?.let { r.load(it) } ?: continue
                val text = String(data, 8, data.size - 8, Charsets.ISO_8859_1).trim()
                val f = text.split(' ').filter { it.isNotEmpty() }
                if (f.size < 4) return null
                return longArrayOf(f[1].toLong(16), f[2].toLong(16), f[3].toLong(16))
            }
            null
        }.getOrNull()

        internal fun fourcc(s: String): Int =
            (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code

        internal fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        internal fun i32(b: ByteArray, o: Int): Int = (u16(b, o) shl 16) or u16(b, o + 2)
        internal fun u32(b: ByteArray, o: Int): Long = i32(b, o).toLong() and 0xFFFFFFFFL
        internal fun i64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)
    }

    internal class Box(val type: Int, val start: Long, val end: Long)

    private class Reader(val src: RandomInput) {
        fun readAt(pos: Long, n: Int): ByteArray {
            val b = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = src.read(pos + off, b, off, n - off)
                if (r <= 0) throw IOException("MP4: unexpected end of file")
                off += r
            }
            return b
        }

        /** start〜end の中の箱を並べる。size == 1 は 64 ビットの大きさ、size == 0 は終わりまで */
        fun children(start: Long, end: Long): List<Box> {
            val list = ArrayList<Box>()
            var p = start
            while (p + 8 <= end) {
                val h = readAt(p, 8)
                var size = u32(h, 0)
                val type = i32(h, 4)
                var hdr = 8
                if (size == 1L) {
                    if (p + 16 > end) break
                    size = i64(readAt(p + 8, 8), 0)
                    hdr = 16
                } else if (size == 0L) {
                    size = end - p
                }
                if (size < hdr) break // 壊れている
                if (size > end - p) { // 途中で切れたファイルは終わりまで(大きすぎる値で位置が一周しないように)
                    list += Box(type, p + hdr, end)
                    break
                }
                list += Box(type, p + hdr, p + size)
                p += size
            }
            return list
        }

        fun load(b: Box): ByteArray {
            val n = b.end - b.start
            if (n > (64 shl 20)) throw IOException("MP4: box too large")
            return readAt(b.start, n.toInt())
        }
    }
}

private fun List<Mp4Track.Box>.find(type: String): Mp4Track.Box? = firstOrNull { it.type == Mp4Track.fourcc(type) }
