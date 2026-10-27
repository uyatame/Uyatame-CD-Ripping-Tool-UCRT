package com.uyatame.ucrt.player.mp3

import com.uyatame.ucrt.player.PcmDecoder
import com.uyatame.ucrt.player.RandomInput

/**
 * MP3(MPEG Layer I・II・III)のファイルを、アプリ内蔵のデコーダー(Mp3Decoder)で読む。
 * ファイルの前後のタグ(ID3v2・ID3v1・APEv2)を飛ばし、LAME などの情報のフレームがあれば、
 * 先頭の無音と末尾の詰め物を取り除く(曲間の無音をなくす。minimp3_ex と同じ)。
 * 出力は 16bit(OS の MP3 デコーダーと同じ。minimp3 の規格の試験と同じ丸め方)
 */
internal class Mp3PcmDecoder(private val src: RandomInput) : PcmDecoder {
    override var sampleRate = 0
        private set
    override var channels = 0
        private set
    override val bitsPerSample = 16
    override var totalSamples = 0L
        private set
    override val samples = Array(2) { IntArray(1152) }
    override var blockSize = 0
        private set
    override var frameSample = 0L
        private set

    private val dec = Mp3Decoder()
    private val info = Mp3Decoder.FrameInfo()
    private val pcm = FloatArray(Mp3Decoder.MAX_SAMPLES_PER_FRAME)

    /** 音のデータの範囲(タグを除く) */
    private var start = 0L
    private var end = 0L

    // ファイルを読む窓
    private val buf = ByteArray(1 shl 16)
    private var bufFile = 0L
    private var bufPos = 0
    private var bufLen = 0

    /** 先頭の無音(取り除くサンプル数)と、取り除いたあとの全体の長さ(分かれば) */
    private var delay = 0
    private var detected = 0L
    /** 取り除く前の、次に出すサンプルの番号(先頭の無音を含む) */
    private var raw = 0L
    /** 情報のフレーム(音ではない)の位置。読み直すときに飛ばす */
    private var tagFrameAt = -1L
    /** 最初のフレームの層(I・II・III) */
    private var layer = 0
    /** 音が無いファイル */
    private var empty = false

    override fun open() {
        val size = src.size
        start = 0
        // 先頭の ID3v2(複数あることもある)
        while (true) {
            val h = read(start, 10) ?: break
            if (!(h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte())) break
            if ((h[5].toInt() and 15) != 0 || h.drop(6).any { (it.toInt() and 0x80) != 0 }) break
            var n = (((h[6].toInt() and 0x7f) shl 21) or ((h[7].toInt() and 0x7f) shl 14) or ((h[8].toInt() and 0x7f) shl 7) or (h[9].toInt() and 0x7f)) + 10L
            if ((h[5].toInt() and 16) != 0) n += 10
            start += n
        }
        // 末尾の ID3v1・APEv2
        end = size
        read(end - 128, 3)?.let { if (String(it, Charsets.ISO_8859_1) == "TAG") end -= 128 }
        if (end - start > 227) read(end - 227, 4)?.let { if (String(it, Charsets.ISO_8859_1) == "TAG+") end -= 227 }
        if (end - start > 32) read(end - 32, 32)?.let { t ->
            if (String(t, 0, 8, Charsets.ISO_8859_1) == "APETAGEX") {
                val tagSize = (t[12].toLong() and 0xFF) or ((t[13].toLong() and 0xFF) shl 8) or ((t[14].toLong() and 0xFF) shl 16) or ((t[15].toLong() and 0xFF) shl 24)
                end -= 32
                if (end - start >= tagSize) end -= tagSize
            }
        }
        if (end <= start) throw java.io.IOException("MP3: no audio")
        // 最初のフレーム(形式を知るため)
        seekByte(start)
        fill()
        val n = dec.decodeFrame(buf, bufPos, bufLen - bufPos, null, info)
        if (info.frameBytes == 0 || n == 0) throw java.io.IOException("MP3: no frame")
        sampleRate = info.hz
        channels = info.channels
        layer = info.layer
        val frameAt = bufPos + info.frameOffset
        val frameSize = info.frameBytes - info.frameOffset
        val spf = n
        val tag = if (info.layer == 3) dec.vbrTag(buf, frameAt, frameSize) else null
        if (tag != null) tagFrameAt = bufFile + frameAt
        if (tag != null && tag[0] >= 0) {
            delay = tag[1]
            var total = spf.toLong() * (tag[0].toLong() and 0xFFFFFFFFL)
            if (total >= delay) total -= delay
            if (tag[2] > 0 && total >= tag[2]) total -= tag[2]
            detected = total
            totalSamples = total
            // 情報のフレームに「長さ 0」と書かれている(音が無い)
            empty = total == 0L
        } else {
            // 長さは、平均のビットレートから見積もる(固定ビットレートならほぼ正確)
            totalSamples = if (info.bitrateKbps > 0) (end - start) * 8 * sampleRate / (info.bitrateKbps * 1000L) else 0
        }
        dec.reset()
        seekByte(start)
        raw = 0
    }

    private fun read(pos: Long, n: Int): ByteArray? {
        if (pos < 0 || pos + n > src.size) return null
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = src.read(pos + off, b, off, n - off)
            if (r <= 0) return null
            off += r
        }
        return b
    }

    private fun seekByte(pos: Long) {
        bufFile = pos
        bufPos = 0
        bufLen = 0
    }

    /** 窓の残りが少なければ、後ろを読み足す(フレームが窓の途中で切れないように) */
    private fun fill() {
        if (bufLen - bufPos >= 8192) return
        if (bufPos > 0) {
            System.arraycopy(buf, bufPos, buf, 0, bufLen - bufPos)
            bufFile += bufPos
            bufLen -= bufPos
            bufPos = 0
        }
        while (bufLen < buf.size) {
            val pos = bufFile + bufLen
            if (pos >= end) break
            val r = src.read(pos, buf, bufLen, minOf((buf.size - bufLen).toLong(), end - pos).toInt())
            if (r <= 0) break
            bufLen += r
        }
    }

    override fun decodeNext(): Boolean {
        if (empty) return false
        while (true) {
            fill()
            if (bufLen - bufPos <= 0) return false
            val at = bufFile + bufPos
            val n = dec.decodeFrame(buf, bufPos, bufLen - bufPos, pcm, info)
            if (info.frameBytes == 0) return false
            val frameAt = at + info.frameOffset
            bufPos += info.frameBytes
            // 情報のフレームは音ではない
            if (frameAt == tagFrameAt) continue
            // 途中でサンプリング周波数や層が変わったら、そこで終わりにする(minimp3_ex と同じ。別の曲がつながっている)
            if (info.hz != sampleRate || info.layer != layer) return false
            // デコードできなかったフレーム(前のフレームの続きが無いときなど)は出さない(minimp3 と同じ)。
            // 位置(シークの一覧と合わせるため)だけ進める
            if (n == 0) {
                raw += info.frameSamples
                continue
            }
            val ch = info.channels
            // 先頭の無音・末尾の詰め物を除いた範囲だけ出す
            var first = 0
            var last = n
            val s0 = raw - delay
            if (s0 < 0) first = minOf(n.toLong(), -s0).toInt()
            if (detected > 0 && s0 + last > detected) last = maxOf(first.toLong(), detected - s0).toInt()
            raw += n
            if (last <= first) {
                if (detected > 0 && s0 >= detected) return false
                continue
            }
            for (i in first until last) {
                val l = s16(pcm[i * ch])
                val r = if (ch == 2) s16(pcm[i * ch + 1]) else l
                samples[0][i - first] = l
                if (channels == 2) samples[1][i - first] = r
            }
            blockSize = last - first
            frameSample = s0 + first
            return true
        }
    }

    /** minimp3 の mp3dec_f32_to_s16 と同じ丸め(0 から遠い方へ) */
    private fun s16(f: Float): Int {
        val s = f * 32768f
        if (s >= 32766.5f) return 32767
        if (s <= -32767.5f) return -32768
        var v = (s + .5f).toInt().toShort().toInt()
        if (v < 0) v -= 1
        return v
    }

    // ---- シーク: フレームの位置の一覧(初めてシークするときに作る) ----
    private var index: LongArray? = null
    private var indexSamples: IntArray? = null
    private var indexCount = 0

    private fun buildIndex() {
        val pos = ArrayList<Long>()
        val spf = ArrayList<Int>()
        val d = Mp3Decoder()
        val fi = Mp3Decoder.FrameInfo()
        seekByte(start)
        while (true) {
            fill()
            if (bufLen - bufPos <= 0) break
            val at = bufFile + bufPos
            val n = d.decodeFrame(buf, bufPos, bufLen - bufPos, null, fi)
            if (fi.frameBytes == 0) break
            bufPos += fi.frameBytes
            if (n == 0) continue
            val frameAt = at + fi.frameOffset
            if (frameAt == tagFrameAt) continue
            pos.add(frameAt)
            spf.add(n)
        }
        index = pos.toLongArray()
        indexSamples = spf.toIntArray()
        indexCount = pos.size
        if (detected == 0L) totalSamples = spf.sumOf { it.toLong() }
    }

    override fun seekSample(target: Long) {
        if (index == null) buildIndex()
        val idx = index!!
        val sp = indexSamples!!
        // target(先頭の無音を除いた番号)を含むフレーム
        val want = target + delay
        var i = 0
        var s = 0L
        while (i < indexCount && s + sp[i] <= want) {
            s += sp[i]
            i++
        }
        // 前のフレームから読み始める(その分の音は捨てる)。層 III は、フレームの中身が前のフレームに最大 511 バイト
        // 入り込む(ビットリザーバー)ので、目的のフレームの 1 つ前を正しく作れるだけ(その前に 512 バイト以上)さかのぼる。
        // 1 つ前が要るのは、フレームのつなぎ目の重なり(MDCT・合成フィルター)のため
        var j = i
        if (j > 0) { j--; s -= sp[j] }
        if (layer == 3) {
            var bytes = 0L
            while (j > 0 && bytes < 512) {
                j--
                s -= sp[j]
                bytes += idx[j + 1] - idx[j]
            }
        }
        dec.reset()
        raw = s
        if (j >= indexCount) {
            seekByte(end)
        } else {
            seekByte(idx[j])
        }
    }
}
