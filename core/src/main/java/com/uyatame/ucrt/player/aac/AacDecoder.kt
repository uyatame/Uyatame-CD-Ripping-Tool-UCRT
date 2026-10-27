package com.uyatame.ucrt.player.aac

import com.uyatame.ucrt.player.Imdct
import java.io.IOException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** AAC のデータが壊れている・対応していない */
internal class AacException(msg: String) : IOException(msg)

/**
 * AAC(AAC-LC)のデコーダー。1 フレーム(raw_data_block)を 1024 サンプルの PCM(-1〜1 の小数)にする。
 * ISO/IEC 14496-3 のとおりに作ったもの。対応: 長い窓・短い窓、M/S・インテンシティステレオ、PNS、TNS、パルス、1〜8 チャンネル。
 * 対応しない(例外): AAC Main の予測・LTP・SSR・CCE(結合チャンネル)・960 サンプルのフレーム。
 * sbr が true なら、FIL の中の SBR(HE-AAC)のデータで高音を作り、2 倍のサンプリング周波数の 2048 サンプルにする。
 * ps が true なら(モノラルの HE-AAC)、PS(HE-AAC v2)でステレオにする(PS のデータが無ければ左右に同じ音)
 */
internal class AacDecoder(val config: AudioSpecificConfig, val sbr: Boolean = false, val ps: Boolean = false) {
    /** 1 チャンネル分の状態 */
    internal class Channel {
        // ---- ics_info(CPE で窓を共通にするときは、左から写す) ----
        var windowSequence = ONLY_LONG
        var windowShape = 0
        var prevWindowShape = 0
        var maxSfb = 0
        var numWindowGroups = 1
        val groupLen = IntArray(8)

        // ---- 帯域ごとの値([グループ × 16 + sfb]。短い窓の sfb は 15 まで、長い窓は 51 まで。長い窓はグループ 0 だけ) ----
        val bandType = IntArray(8 * 64)
        val sf = IntArray(8 * 64)

        // ---- TNS ----
        var tnsPresent = false
        val tnsNFilt = IntArray(8)
        val tnsLength = Array(8) { IntArray(4) }
        val tnsOrder = Array(8) { IntArray(4) }
        val tnsDirection = Array(8) { IntArray(4) }
        val tnsCoef = Array(8) { Array(4) { FloatArray(20) } }

        /** 量子化された値(パルスを足したあと、逆量子化する) */
        val quant = IntArray(1024)
        /** スペクトル(窓ごとに 128 ずつ並べる。長い窓は 1024) */
        val spec = FloatArray(1024)
        /** 次のフレームに足す後ろ半分 */
        val overlap = FloatArray(1024)
        /** 出力(SBR のときは 2048) */
        val out = FloatArray(2048)

        val isShort: Boolean get() = windowSequence == EIGHT_SHORT
        val numWindows: Int get() = if (isShort) 8 else 1
    }

    private val sfi = config.samplingIndex
    private val swbLong = AacTables.SWB_LONG[sfi]
    private val swbShort = AacTables.SWB_SHORT[sfi]
    private val numSwbLong = AacTables.NUM_SWB_LONG[sfi]
    private val numSwbShort = AacTables.NUM_SWB_SHORT[sfi]

    /** 出力するチャンネルの数(要素を並んだ順に割り当てる。PS ならモノラルからステレオ) */
    val channels = if (ps) 2 else config.channels
    /** 1 フレームの出力のサンプル数 */
    val frameLength = if (sbr) 2048 else 1024
    private val ch = Array(channels) { Channel() }
    /** 宣言より多い要素(壊れたファイルなど)を読み捨てるため */
    private val spare = arrayOf(Channel(), Channel())

    /** フレームの出力: output[チャンネル][0 until frameLength] */
    val output: Array<FloatArray> get() = outputs
    private val outputs = Array(channels) { ch[it].out }

    /** SBR のデータがあったか(SBR が書かれていないファイルで、SBR を使うかを決めるため) */
    var sbrSeen = false
        private set
    /** 要素ごとの SBR(要素の並びの番号) */
    private val sbrs = arrayOfNulls<Sbr>(MAX_ELEMENTS)
    /** このフレームの要素: 種類と、最初のチャンネル */
    private val elemId = IntArray(MAX_ELEMENTS)
    private val elemCh = IntArray(MAX_ELEMENTS)

    private val br = BitReader()

    /** 前のフレームとのつながりを切る(シークのあと) */
    fun reset() {
        for (c in ch) { c.overlap.fill(0f); c.prevWindowShape = 0; c.windowSequence = ONLY_LONG }
        for (c in spare) { c.overlap.fill(0f); c.prevWindowShape = 0 }
        // SBR は残す(見出しの設定はファイルの中で変わらないので、すぐに高音を作れる。重ね合わせの分は、呼び出し側が先読みして捨てる)
    }

    /**
     * フレーム(raw_data_block 1 つ。ADTS の見出しは除いたもの)をデコードする。出力は output に入る。
     * 壊れていれば AacException(出力は無音にする)
     */
    fun decodeFrame(data: ByteArray, off: Int, len: Int) {
        br.init(data, off, len)
        try {
            rawDataBlock()
        } catch (e: AacException) {
            silence()
            throw e
        } catch (e: RuntimeException) {
            // 配列の外を見たなど(壊れたデータ)
            silence()
            throw AacException("AAC: bad frame (${e.javaClass.simpleName})")
        }
    }

    private fun silence() {
        for (c in ch) { c.out.fill(0f); c.overlap.fill(0f) }
    }

    private fun sbrOf(e: Int, id: Int): Sbr {
        sbrs[e]?.let { return it }
        val s = Sbr(2 * config.sampleRate, if (ps && id == Sbr.ID_SCE && e == 0) Ps() else null)
        sbrs[e] = s
        return s
    }

    // ---- raw_data_block ----

    private var nextChannel = 0
    private var elementIndex = 0
    /** 直前の要素(SBR のデータはその要素のもの) */
    private var lastElementCh = -1
    private var lastElementId = -1
    private var lastElementIndex = -1

    private fun rawDataBlock() {
        nextChannel = 0
        elementIndex = 0
        lastElementCh = -1
        val done = BooleanArray(channels)
        while (true) {
            val id = br.bits(3)
            when (id) {
                ID_SCE, ID_LFE -> {
                    br.bits(4) // element_instance_tag
                    val c = take(1)
                    element(id)
                    ics(c[0], false)
                    finish(c[0])
                    if (nextChannel < channels) done[nextChannel] = true
                    nextChannel++
                }
                ID_CPE -> {
                    br.bits(4)
                    val c = take(2)
                    element(id)
                    cpe(c[0], c[1])
                    finish(c[0]); finish(c[1])
                    for (k in 0..1) if (nextChannel + k < channels) done[nextChannel + k] = true
                    nextChannel += 2
                }
                ID_CCE -> throw AacException("AAC: coupling channel not supported")
                ID_DSE -> {
                    br.bits(4)
                    val align = br.bit()
                    var cnt = br.bits(8)
                    if (cnt == 255) cnt += br.bits(8)
                    if (align == 1) br.align()
                    br.skip(cnt * 8)
                }
                ID_PCE -> ProgramConfig.read(br)
                ID_FIL -> {
                    var cnt = br.bits(4)
                    if (cnt == 15) cnt += br.bits(8) - 1
                    fill(cnt)
                }
                ID_END -> break
            }
        }
        // SBR(要素の FIL を全部読んでから)
        if (sbr) {
            for (e in 0 until minOf(elementIndex, MAX_ELEMENTS)) {
                val c0 = elemCh[e]
                if (c0 >= channels) continue
                val id = elemId[e]
                val r = when {
                    id == ID_CPE && c0 + 1 < channels -> ch[c0 + 1].out
                    ps && e == 0 && id == ID_SCE -> ch[1].out
                    else -> null
                }
                sbrOf(e, id).apply(id, ch[c0].out, r)
                if (ps && e == 0 && id == ID_SCE) done[1] = true
            }
        }
        // フレームに無かったチャンネルは無音
        for (i in 0 until channels) if (!done[i]) ch[i].out.fill(0f)
    }

    /** 要素の始まりを覚える(SBR のため) */
    private fun element(id: Int) {
        lastElementCh = nextChannel
        lastElementId = id
        lastElementIndex = elementIndex
        if (elementIndex < MAX_ELEMENTS) {
            elemId[elementIndex] = id
            elemCh[elementIndex] = nextChannel
        }
        elementIndex++
    }

    /** 次の n チャンネル(宣言より多ければ、読み捨て用) */
    private fun take(n: Int): Array<Channel> = Array(n) { k ->
        val i = nextChannel + k
        if (i < channels) ch[i] else spare[k]
    }

    /** FIL の中身(cnt バイト)。SBR のデータなら SBR に渡す */
    private fun fill(cnt: Int) {
        if (cnt <= 0) return
        val end = br.pos + cnt * 8
        val type = br.peek(4)
        if (type == EXT_SBR_DATA || type == EXT_SBR_DATA_CRC) {
            sbrSeen = true
            if (sbr && lastElementCh in 0 until channels && lastElementIndex < MAX_ELEMENTS) {
                br.skip(4)
                sbrOf(lastElementIndex, lastElementId).decodeExtension(br, cnt * 8 - 4, type == EXT_SBR_DATA_CRC, lastElementId)
            }
        }
        // SBR が読んだ量にかかわらず、FIL の終わりに合わせる
        br.pos = end
        if (br.pos > br.end) throw AacException("AAC: fill overrun")
    }

    // ---- 要素 ----

    private fun cpe(l: Channel, r: Channel) {
        val common = br.bit() == 1
        var msMask = 0
        val msUsed = msUsedBuf
        if (common) {
            icsInfo(l)
            copyInfo(l, r)
            msMask = br.bits(2)
            if (msMask == 3) throw AacException("AAC: bad ms_mask_present")
            if (msMask == 1) {
                for (g in 0 until l.numWindowGroups) for (sfb in 0 until l.maxSfb) msUsed[g * 64 + sfb] = br.bit() == 1
            }
        }
        ics(l, common)
        ics(r, common)
        if (common) {
            // M/S(両方とも普通の帯域のときだけ)
            if (msMask != 0) {
                ms(l, r, msMask, msUsed)
            }
            intensity(l, r, msMask, msUsed)
        }
    }

    private val msUsedBuf = BooleanArray(8 * 64)

    private fun copyInfo(a: Channel, b: Channel) {
        b.windowSequence = a.windowSequence
        b.windowShape = a.windowShape
        b.maxSfb = a.maxSfb
        b.numWindowGroups = a.numWindowGroups
        System.arraycopy(a.groupLen, 0, b.groupLen, 0, 8)
    }

    /** individual_channel_stream。スペクトル(逆量子化と PNS まで)を作る */
    private fun ics(c: Channel, common: Boolean) {
        val globalGain = br.bits(8)
        if (!common) icsInfo(c)
        sectionData(c)
        scaleFactors(c, globalGain)
        val pulse = br.bit() == 1
        var pulseCount = 0
        var pulseStart = 0
        if (pulse) {
            if (c.isShort) throw AacException("AAC: pulse in short window")
            pulseCount = br.bits(2) + 1
            pulseStart = br.bits(6)
            for (i in 0 until pulseCount) {
                pulseOffset[i] = br.bits(5)
                pulseAmp[i] = br.bits(4)
            }
        }
        c.tnsPresent = br.bit() == 1
        if (c.tnsPresent) tnsData(c)
        if (br.bit() == 1) throw AacException("AAC: gain control (SSR) not supported")
        spectralData(c)
        if (pulse) {
            if (pulseStart >= numSwbLong) throw AacException("AAC: bad pulse")
            var k = swbLong[pulseStart]
            for (i in 0 until pulseCount) {
                k += pulseOffset[i]
                if (k >= 1024) throw AacException("AAC: bad pulse offset")
                val q = c.quant[k]
                c.quant[k] = if (q > 0) q + pulseAmp[i] else q - pulseAmp[i]
            }
        }
        dequant(c)
    }

    private val pulseOffset = IntArray(4)
    private val pulseAmp = IntArray(4)

    private fun icsInfo(c: Channel) {
        if (br.bit() != 0) {
            // ics_reserved_bit。0 でなくても続ける(ffmpeg と同じ)
        }
        c.windowSequence = br.bits(2)
        c.windowShape = br.bit()
        if (c.isShort) {
            c.maxSfb = br.bits(4)
            val grouping = br.bits(7)
            c.numWindowGroups = 1
            c.groupLen.fill(0)
            c.groupLen[0] = 1
            for (i in 0 until 7) {
                if ((grouping shr (6 - i)) and 1 == 1) {
                    c.groupLen[c.numWindowGroups - 1]++
                } else {
                    c.numWindowGroups++
                    c.groupLen[c.numWindowGroups - 1] = 1
                }
            }
            if (c.maxSfb > numSwbShort) throw AacException("AAC: bad max_sfb")
        } else {
            c.maxSfb = br.bits(6)
            c.numWindowGroups = 1
            c.groupLen.fill(0)
            c.groupLen[0] = 1
            if (c.maxSfb > numSwbLong) throw AacException("AAC: bad max_sfb")
            // predictor_data_present(AAC Main の予測。LC では 0)
            if (br.bit() == 1) throw AacException("AAC: prediction not supported")
        }
    }

    private fun sectionData(c: Channel) {
        val bits = if (c.isShort) 3 else 5
        val esc = (1 shl bits) - 1
        for (g in 0 until c.numWindowGroups) {
            var k = 0
            while (k < c.maxSfb) {
                val cb = br.bits(4)
                if (cb == 12) throw AacException("AAC: reserved codebook")
                var len = 0
                while (true) {
                    val inc = br.bits(bits)
                    len += inc
                    if (inc != esc) break
                    if (br.pos > br.end) throw AacException("AAC: section overrun")
                }
                if (k + len > c.maxSfb) throw AacException("AAC: bad section length")
                for (sfb in k until k + len) c.bandType[g * 64 + sfb] = cb
                k += len
            }
        }
    }

    private fun scaleFactors(c: Channel, globalGain: Int) {
        var sf = globalGain
        var isPos = 0
        var noise = globalGain - 90
        var firstNoise = true
        for (g in 0 until c.numWindowGroups) for (sfb in 0 until c.maxSfb) {
            val i = g * 64 + sfb
            when (c.bandType[i]) {
                ZERO_HCB -> c.sf[i] = 0
                INTENSITY_HCB, INTENSITY_HCB2 -> {
                    isPos += sfHuff.decode(br) - 60
                    c.sf[i] = isPos
                }
                NOISE_HCB -> {
                    if (firstNoise) {
                        firstNoise = false
                        noise += br.bits(9) - 256
                    } else {
                        noise += sfHuff.decode(br) - 60
                    }
                    c.sf[i] = noise
                }
                else -> {
                    sf += sfHuff.decode(br) - 60
                    if (sf < 0 || sf > 255) throw AacException("AAC: bad scalefactor")
                    c.sf[i] = sf
                }
            }
        }
    }

    private fun tnsData(c: Channel) {
        val short = c.isShort
        for (w in 0 until c.numWindows) {
            val nFilt = br.bits(if (short) 1 else 2)
            c.tnsNFilt[w] = nFilt
            if (nFilt == 0) continue
            val coefRes = br.bit()
            for (f in 0 until nFilt) {
                c.tnsLength[w][f] = br.bits(if (short) 4 else 6)
                val order = br.bits(if (short) 3 else 5)
                if (order > (if (short) 7 else 12)) throw AacException("AAC: bad TNS order")
                c.tnsOrder[w][f] = order
                if (order > 0) {
                    c.tnsDirection[w][f] = br.bit()
                    val compress = br.bit()
                    val bits = coefRes + 3 - compress
                    val map = AacTables.TNS_MAP[2 * compress + coefRes]
                    for (i in 0 until order) c.tnsCoef[w][f][i] = map[br.bits(bits)]
                }
            }
        }
    }

    /** スペクトルの値(量子化された整数)。帯域は、グループの中で sfb ごとに窓を並べて送られてくる */
    private fun spectralData(c: Channel) {
        val q = c.quant
        java.util.Arrays.fill(q, 0)
        val short = c.isShort
        val swb = if (short) swbShort else swbLong
        var win = 0
        for (g in 0 until c.numWindowGroups) {
            val gl = c.groupLen[g]
            for (sfb in 0 until c.maxSfb) {
                val cb = c.bandType[g * 64 + sfb]
                val start = swb[sfb]
                val end = swb[sfb + 1]
                if (cb == ZERO_HCB || cb >= NOISE_HCB) continue
                val h = specHuff[cb - 1]
                for (w in 0 until gl) {
                    val base = (win + w) * 128
                    var k = start
                    when (cb) {
                        1, 2 -> while (k < end) {
                            // 4 つ組・符号付き(-1〜1)
                            val v = h.decode(br)
                            q[base + k] = v / 27 - 1
                            q[base + k + 1] = (v / 9) % 3 - 1
                            q[base + k + 2] = (v / 3) % 3 - 1
                            q[base + k + 3] = v % 3 - 1
                            k += 4
                        }
                        3, 4 -> while (k < end) {
                            // 4 つ組・符号なし(0〜2)と符号のビット
                            val v = h.decode(br)
                            q[base + k] = v / 27
                            q[base + k + 1] = (v / 9) % 3
                            q[base + k + 2] = (v / 3) % 3
                            q[base + k + 3] = v % 3
                            for (j in 0 until 4) if (q[base + k + j] != 0 && br.bit() == 1) q[base + k + j] = -q[base + k + j]
                            k += 4
                        }
                        5, 6 -> while (k < end) {
                            // 2 つ組・符号付き(-4〜4)
                            val v = h.decode(br)
                            q[base + k] = v / 9 - 4
                            q[base + k + 1] = v % 9 - 4
                            k += 2
                        }
                        else -> {
                            // 2 つ組・符号なし: 7・8 は 0〜7、9・10 は 0〜12、11 は 0〜16(16 はエスケープ)
                            val mod = when (cb) { 7, 8 -> 8; 9, 10 -> 13; else -> 17 }
                            while (k < end) {
                                val v = h.decode(br)
                                var y = v / mod
                                var z = v % mod
                                if (y != 0 && br.bit() == 1) y = -y
                                if (z != 0 && br.bit() == 1) z = -z
                                if (cb == 11) {
                                    if (y == 16 || y == -16) y = if (y > 0) escape() else -escape()
                                    if (z == 16 || z == -16) z = if (z > 0) escape() else -escape()
                                }
                                q[base + k] = y
                                q[base + k + 1] = z
                                k += 2
                            }
                        }
                    }
                }
            }
            win += gl
        }
    }

    /** エスケープ(16 以上の値): 1 が N 個と 0、そのあと N + 4 ビット */
    private fun escape(): Int {
        var n = 4
        while (br.bit() == 1) {
            n++
            if (n > 12) throw AacException("AAC: bad escape")
        }
        return (1 shl n) + br.bits(n)
    }

    /** 逆量子化(|q|^(4/3) × 2^((sf − 100)/4))と PNS(雑音の帯域) */
    private fun dequant(c: Channel) {
        val s = c.spec
        java.util.Arrays.fill(s, 0f)
        val short = c.isShort
        val swb = if (short) swbShort else swbLong
        var win = 0
        for (g in 0 until c.numWindowGroups) {
            val gl = c.groupLen[g]
            for (sfb in 0 until c.maxSfb) {
                val i = g * 64 + sfb
                val cb = c.bandType[i]
                val start = swb[sfb]
                val end = swb[sfb + 1]
                when {
                    cb == ZERO_HCB || cb == INTENSITY_HCB || cb == INTENSITY_HCB2 -> {}
                    cb == NOISE_HCB -> for (w in 0 until gl) noise(s, (win + w) * 128 + start, end - start, c.sf[i])
                    else -> {
                        val gain = pow2(c.sf[i] - 100).toFloat()
                        for (w in 0 until gl) {
                            val base = (win + w) * 128
                            for (k in start until end) {
                                val v = c.quant[base + k]
                                if (v != 0) s[base + k] = if (v > 0) POW43[v] * gain else -POW43[-v] * gain
                            }
                        }
                    }
                }
            }
            win += gl
        }
    }

    /** 雑音で帯域を埋める(帯域のエネルギーが 2^(sf/2) になるように) */
    private fun noise(s: FloatArray, at: Int, n: Int, sf: Int) {
        var e = 0f
        for (k in 0 until n) {
            rnd = rnd * 1664525 + 1013904223
            val v = (rnd shr 8).toFloat()
            s[at + k] = v
            e += v * v
        }
        if (e <= 0f) return
        // 出力を -1〜1 にするための 1/32768 も掛ける(普通の帯域の POW43 と同じ)
        val scale = (pow2(sf) / sqrt(e.toDouble()) / 32768.0).toFloat()
        for (k in 0 until n) s[at + k] *= scale
    }

    /**
     * PNS の雑音の乱数(ffmpeg と同じ式と初期値。ffmpeg のデコーダーと同じ音になるので、確かめやすい)。
     * 雑音なので、シークのあとは頭から続けたときと値が違ってもよい
     */
    private var rnd = 0x1f2e3d4c

    /** M/S ステレオ: 左 = M + S、右 = M − S */
    private fun ms(l: Channel, r: Channel, mask: Int, used: BooleanArray) {
        val swb = if (l.isShort) swbShort else swbLong
        var win = 0
        for (g in 0 until l.numWindowGroups) {
            val gl = l.groupLen[g]
            for (sfb in 0 until l.maxSfb) {
                val i = g * 64 + sfb
                if (mask == 1 && !used[i]) continue
                if (l.bandType[i] >= NOISE_HCB || r.bandType[i] >= NOISE_HCB) {
                    // 両方とも雑音なら、右は左と同じ雑音にする(規格の「相関のある雑音」)
                    if (l.bandType[i] == NOISE_HCB && r.bandType[i] == NOISE_HCB) {
                        for (w in 0 until gl) {
                            val base = (win + w) * 128
                            val n = swb[sfb + 1] - swb[sfb]
                            // 左の雑音はエネルギーが 2^(左の sf / 2)。右の sf に合わせて倍率を掛ける
                            val scale = (pow2(r.sf[i]) / pow2(l.sf[i])).toFloat()
                            for (k in base + swb[sfb] until base + swb[sfb] + n) r.spec[k] = l.spec[k] * scale
                        }
                    }
                    continue
                }
                for (w in 0 until gl) {
                    val base = (win + w) * 128
                    for (k in base + swb[sfb] until base + swb[sfb + 1]) {
                        val a = l.spec[k]
                        val b = r.spec[k]
                        l.spec[k] = a + b
                        r.spec[k] = a - b
                    }
                }
            }
            win += gl
        }
    }

    /** インテンシティステレオ: 右の帯域を、左に倍率を掛けて作る */
    private fun intensity(l: Channel, r: Channel, mask: Int, used: BooleanArray) {
        val swb = if (l.isShort) swbShort else swbLong
        var win = 0
        for (g in 0 until r.numWindowGroups) {
            val gl = r.groupLen[g]
            for (sfb in 0 until r.maxSfb) {
                val i = g * 64 + sfb
                val cb = r.bandType[i]
                if (cb != INTENSITY_HCB && cb != INTENSITY_HCB2) continue
                var sign = if (cb == INTENSITY_HCB) 1f else -1f
                if (mask == 2 || (mask == 1 && used[i])) sign = -sign
                val scale = sign * pow2(-r.sf[i]).toFloat()
                for (w in 0 until gl) {
                    val base = (win + w) * 128
                    for (k in base + swb[sfb] until base + swb[sfb + 1]) r.spec[k] = l.spec[k] * scale
                }
            }
            win += gl
        }
    }

    /** TNS・逆 MDCT・窓・重ね合わせ */
    private fun finish(c: Channel) {
        if (c.tnsPresent) tns(c)
        filterbank(c)
    }

    private val lpc = FloatArray(20)

    private fun tns(c: Channel) {
        val short = c.isShort
        val swb = if (short) swbShort else swbLong
        val numSwb = if (short) numSwbShort else numSwbLong
        val maxBands = minOf(if (short) AacTables.TNS_MAX_BANDS_SHORT[sfi] else AacTables.TNS_MAX_BANDS_LONG[sfi], c.maxSfb)
        if (maxBands == 0) return
        val s = c.spec
        for (w in 0 until c.numWindows) {
            var bottom = numSwb
            for (f in 0 until c.tnsNFilt[w]) {
                val top = bottom
                bottom = maxOf(0, top - c.tnsLength[w][f])
                val order = c.tnsOrder[w][f]
                if (order == 0) continue
                // 反射係数 → LPC の係数(ffmpeg の compute_lpc_coefs と同じ)
                val coef = c.tnsCoef[w][f]
                for (i in 0 until order) {
                    val r = -coef[i]
                    lpc[i] = r
                    for (j in 0 until ((i + 1) shr 1)) {
                        val a = lpc[j]
                        val b = lpc[i - 1 - j]
                        lpc[j] = a + r * b
                        lpc[i - 1 - j] = b + r * a
                    }
                }
                val start0 = swb[minOf(bottom, maxBands)]
                val end = swb[minOf(top, maxBands)]
                val size = end - start0
                if (size <= 0) continue
                val inc: Int
                var start: Int
                if (c.tnsDirection[w][f] == 1) {
                    inc = -1
                    start = end - 1
                } else {
                    inc = 1
                    start = start0
                }
                start += w * 128
                for (m in 0 until size) {
                    var v = s[start]
                    for (i in 1..minOf(m, order)) v -= s[start - i * inc] * lpc[i - 1]
                    s[start] = v
                    start += inc
                }
            }
        }
    }

    private val buf = FloatArray(2048)
    private val sbuf = FloatArray(256)

    private fun filterbank(c: Channel) {
        val out = c.out
        val ov = c.overlap
        val prevLong = if (c.prevWindowShape == 1) KBD_LONG else SINE_LONG
        val curLong = if (c.windowShape == 1) KBD_LONG else SINE_LONG
        val prevShort = if (c.prevWindowShape == 1) KBD_SHORT else SINE_SHORT
        val curShort = if (c.windowShape == 1) KBD_SHORT else SINE_SHORT
        val z = buf
        when (c.windowSequence) {
            EIGHT_SHORT -> {
                java.util.Arrays.fill(z, 0f)
                for (w in 0 until 8) {
                    IMDCT_SHORT.run(c.spec, w * 128, sbuf)
                    val rise = if (w == 0) prevShort else curShort
                    val at = 448 + 128 * w
                    for (n in 0 until 128) z[at + n] += sbuf[n] * rise[n]
                    for (n in 0 until 128) z[at + 128 + n] += sbuf[128 + n] * curShort[127 - n]
                }
            }
            else -> {
                IMDCT_LONG.run(c.spec, 0, z)
                // 前半(立ち上がり)
                if (c.windowSequence == LONG_STOP) {
                    for (n in 0 until 448) z[n] = 0f
                    for (n in 0 until 128) z[448 + n] *= prevShort[n]
                } else {
                    for (n in 0 until 1024) z[n] *= prevLong[n]
                }
                // 後半(立ち下がり)
                if (c.windowSequence == LONG_START) {
                    for (n in 0 until 128) z[1472 + n] *= curShort[127 - n]
                    for (n in 1600 until 2048) z[n] = 0f
                } else {
                    for (n in 0 until 1024) z[1024 + n] *= curLong[1023 - n]
                }
            }
        }
        for (n in 0 until 1024) out[n] = z[n] + ov[n]
        System.arraycopy(z, 1024, ov, 0, 1024)
        c.prevWindowShape = c.windowShape
    }

    companion object {
        const val ONLY_LONG = 0
        const val LONG_START = 1
        const val EIGHT_SHORT = 2
        const val LONG_STOP = 3

        const val ZERO_HCB = 0
        const val NOISE_HCB = 13
        const val INTENSITY_HCB2 = 14
        const val INTENSITY_HCB = 15

        private const val ID_SCE = 0
        private const val ID_CPE = 1
        private const val ID_CCE = 2
        private const val ID_LFE = 3
        private const val ID_DSE = 4
        private const val ID_PCE = 5
        private const val ID_FIL = 6
        private const val ID_END = 7

        private const val MAX_ELEMENTS = 16
        private const val EXT_SBR_DATA = 13
        private const val EXT_SBR_DATA_CRC = 14

        /** |q|^(4/3)(q は 0〜8191)。出力が -1〜1 になるよう 1/32768 を掛けておく */
        private val POW43 = FloatArray(8192) { (Math.pow(it.toDouble(), 4.0 / 3.0) / 32768.0).toFloat() }

        private val POW2 = DoubleArray(512) { Math.pow(2.0, (it - 256) / 4.0) }
        /** 2^(x/4) */
        private fun pow2(x: Int): Double = if (x in -256..255) POW2[x + 256] else Math.pow(2.0, x / 4.0)

        private val sfHuff = Huffman(AacTables.SF_CODES, AacTables.SF_BITS)
        private val specHuff = Array(11) { Huffman(AacTables.SPEC_CODES[it], AacTables.SPEC_BITS[it]) }

        // ---- 窓(立ち上がりの半分) ----
        private val SINE_LONG = FloatArray(1024) { sin(PI / 2048 * (it + 0.5)).toFloat() }
        private val SINE_SHORT = FloatArray(128) { sin(PI / 256 * (it + 0.5)).toFloat() }
        private val KBD_LONG = kbd(1024, 4.0)
        private val KBD_SHORT = kbd(128, 6.0)

        /** カイザー・ベッセル派生窓(長さ 2n の前半 n) */
        private fun kbd(n: Int, alpha: Double): FloatArray {
            val k = DoubleArray(n + 1)
            for (i in 0..n) {
                val x = 2.0 * i / n - 1.0
                k[i] = bessel0(PI * alpha * sqrt(1 - x * x))
            }
            val total = k.sum()
            val w = FloatArray(n)
            var acc = 0.0
            for (i in 0 until n) {
                acc += k[i]
                w[i] = sqrt(acc / total).toFloat()
            }
            return w
        }

        private fun bessel0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val h = x / 2
            for (k in 1 until 50) {
                term *= (h / k) * (h / k)
                sum += term
                if (term < sum * 1e-12) break
            }
            return sum
        }

        private val IMDCT_LONG = Imdct(2048)
        private val IMDCT_SHORT = Imdct(256)
    }
}

/**
 * ハフマン符号の表(2 段の表引き)。値 e が 0 以上なら [記号 << 5 | 長さ]、負なら 2 段目の表への [位置 << 5 | ビット数]
 */
internal class Huffman(codes: IntArray, lens: IntArray) {
    private val t: IntArray

    init {
        val root = 1 shl ROOT
        val list = ArrayList<Int>()
        repeat(root) { list.add(INVALID) }
        // 長い符号は、頭の ROOT ビットごとにまとめる
        val subBits = IntArray(root)
        for (i in codes.indices) {
            val l = lens[i]
            if (l > ROOT) {
                val p = codes[i] ushr (l - ROOT)
                subBits[p] = maxOf(subBits[p], l - ROOT)
            }
        }
        for (p in 0 until root) if (subBits[p] > 0) {
            val at = list.size
            repeat(1 shl subBits[p]) { list.add(INVALID) }
            list[p] = -((at shl 5) or subBits[p])
        }
        for (i in codes.indices) {
            val l = lens[i]
            val c = codes[i]
            if (l == 0) continue
            if (l <= ROOT) {
                val first = c shl (ROOT - l)
                for (j in 0 until (1 shl (ROOT - l))) list[first + j] = (i shl 5) or l
            } else {
                val p = c ushr (l - ROOT)
                val e = -list[p]
                val at = e ushr 5
                val sb = e and 31
                val rest = c and ((1 shl (l - ROOT)) - 1)
                val first = rest shl (sb - (l - ROOT))
                for (j in 0 until (1 shl (sb - (l - ROOT)))) list[at + first + j] = (i shl 5) or l
            }
        }
        t = list.toIntArray()
    }

    fun decode(b: BitReader): Int {
        val e = t[b.peek(ROOT)]
        if (e >= 0) {
            b.skip(e and 31)
            return e ushr 5
        }
        if (e == INVALID) throw AacException("AAC: bad huffman code")
        val p = -e
        val sb = p and 31
        val e2 = t[(p ushr 5) + (b.peek(ROOT + sb) and ((1 shl sb) - 1))]
        if (e2 == INVALID) throw AacException("AAC: bad huffman code")
        b.skip(e2 and 31)
        return e2 ushr 5
    }

    private companion object {
        const val ROOT = 9
        const val INVALID = Int.MIN_VALUE
    }
}

/** ビットの読み込み(上の桁から)。終わりを過ぎて読むと AacException */
internal class BitReader {
    private var b = ByteArray(0)
    private var base = 0
    /** 読む位置(ビット。base からの数) */
    var pos = 0
    /** 終わり(ビット) */
    var end = 0
        private set

    fun init(data: ByteArray, off: Int, len: Int) {
        b = data
        base = off
        pos = 0
        end = len * 8
    }

    /** n ビット(0〜25)を見る。位置は動かさない。終わりより後ろは 0 */
    fun peek(n: Int): Int {
        if (n == 0) return 0
        val i = pos ushr 3
        val byteEnd = end ushr 3
        var v = 0
        for (k in 0 until 4) {
            v = v shl 8
            if (i + k < byteEnd) v = v or (b[base + i + k].toInt() and 0xFF)
        }
        return ((v shl (pos and 7)) ushr (32 - n))
    }

    fun skip(n: Int) {
        pos += n
        if (pos > end) throw AacException("AAC: frame overrun")
    }

    fun bits(n: Int): Int {
        if (n == 0) return 0
        if (n > 25) return (bits(n - 16) shl 16) or bits(16)
        val v = peek(n)
        skip(n)
        return v
    }

    fun bit(): Int = bits(1)

    fun align() {
        val r = pos and 7
        if (r != 0) skip(8 - r)
    }

    fun left(): Int = end - pos
}
