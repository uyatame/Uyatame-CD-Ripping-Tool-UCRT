package com.uyatame.ucrt.player.usb

/** 再生用の出力形式(AS インターフェースの代替設定 1 つ分) */
internal class UacAlt(
    val iface: Int,
    val alt: Int,
    val channels: Int,
    /** 1 サンプルが占めるバイト数(2 / 3 / 4) */
    val subslot: Int,
    /** 実際の有効ビット数 */
    val bits: Int,
    val terminalLink: Int,
    val epOut: Int,
    val epOutAttr: Int,
    val epOutMaxPacket: Int,
    val epOutInterval: Int,
    val epFb: Int,
    val epFbMaxPacket: Int,
    val epFbInterval: Int,
    /** UAC1 の場合の対応サンプリング周波数(空なら連続値・UAC2 はクロックから取得) */
    val rates: List<Int>,
    val rateMin: Int,
    val rateMax: Int,
    /** ネイティブ DSD 用(UAC2 の RAW_DATA 形式) */
    val raw: Boolean = false,
) {
    /** 1 = 非同期, 2 = アダプティブ, 3 = 同期 */
    val syncType: Int get() = (epOutAttr shr 2) and 3
    val maxPacketBytes: Int get() = (epOutMaxPacket and 0x7FF) * (1 + ((epOutMaxPacket shr 11) and 3))

    override fun toString(): String =
        "if$iface alt$alt ${channels}ch ${bits}bit/${subslot * 8} ep=0x%02x sync=$syncType max=$maxPacketBytes int=$epOutInterval".format(epOut) +
            (if (epFb >= 0) " fb=0x%02x".format(epFb) else " fb=none") + (if (rates.isNotEmpty()) " rates=$rates" else "") +
            (if (raw) " RAW(DSD)" else "")
}

internal class FeatureUnit(val id: Int, val source: Int, val masterVolume: Boolean, val channelVolume: List<Int>)

/** USB オーディオ機器の記述子を読み取った結果 */
internal class UacInfo(
    val uac2: Boolean,
    val acIface: Int,
    val alts: List<UacAlt>,
    /** UAC2: 端子 ID → クロック ID */
    val terminalClock: Map<Int, Int>,
    val clockSources: Set<Int>,
    /** クロックセレクター ID → 入力のクロック ID 一覧 */
    val clockSelectors: Map<Int, List<Int>>,
    val clockMultipliers: Map<Int, Int>,
    val features: List<FeatureUnit>,
    /** ユニット/端子 ID → 入力元 ID(音量ユニットを探すため) */
    val sourceOf: Map<Int, Int>,
    val outputTerminals: List<Int>,
    val streamingInputTerminals: Set<Int>,
)

internal object UacDescriptors {
    private fun u8(b: ByteArray, i: Int) = if (i < b.size) b[i].toInt() and 0xFF else 0
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u24(b: ByteArray, i: Int) = u16(b, i) or (u8(b, i + 2) shl 16)
    private fun u32(b: ByteArray, i: Int) = u24(b, i) or (u8(b, i + 3) shl 24)

    fun parse(raw: ByteArray): UacInfo? {
        var i = 0
        var configs = 0
        var curIf = -1; var curAlt = 0; var curClass = 0; var curSub = 0; var curProto = 0
        var uac2 = false
        var acIface = -1
        val terminalClock = HashMap<Int, Int>()
        val clockSources = HashSet<Int>()
        val clockSelectors = HashMap<Int, List<Int>>()
        val clockMultipliers = HashMap<Int, Int>()
        val features = ArrayList<FeatureUnit>()
        val sourceOf = HashMap<Int, Int>()
        val outputTerminals = ArrayList<Int>()
        val streamingIn = HashSet<Int>()
        val alts = ArrayList<UacAlt>()

        // 代替設定 1 つ分を組み立てるための一時変数
        var asLink = 0; var asPcm = false; var asRaw = false; var asCh = 0; var asSub = 0; var asBits = 0
        var asRates = ArrayList<Int>(); var asMin = 0; var asMax = 0
        var epOut = -1; var epOutAttr = 0; var epOutMax = 0; var epOutInt = 1; var epOutSynch = 0
        var epFb = -1; var epFbMax = 0; var epFbInt = 1

        fun flushAlt() {
            if (curClass == 1 && curSub == 2 && curAlt > 0 && (asPcm || asRaw) && epOut >= 0 && asCh > 0 && asSub in 1..4) {
                val fb = if (epFb >= 0) epFb else if (epOutSynch != 0) epOutSynch else -1
                alts += UacAlt(
                    curIf, curAlt, asCh, asSub, if (asBits > 0) asBits else asSub * 8, asLink,
                    epOut, epOutAttr, epOutMax, epOutInt, fb, if (fb >= 0) epFbMax.coerceAtLeast(3) else 0, epFbInt,
                    asRates.toList(), asMin, asMax, raw = asRaw && !asPcm,
                )
            }
            asLink = 0; asPcm = false; asRaw = false; asCh = 0; asSub = 0; asBits = 0
            asRates = ArrayList(); asMin = 0; asMax = 0
            epOut = -1; epOutAttr = 0; epOutMax = 0; epOutInt = 1; epOutSynch = 0
            epFb = -1; epFbMax = 0; epFbInt = 1
        }

        while (i + 2 <= raw.size) {
            val len = u8(raw, i)
            val type = u8(raw, i + 1)
            if (len < 2 || i + len > raw.size) break
            val d = raw.copyOfRange(i, i + len)
            // 2 つ目の構成(configuration)以降は読まない(今使われている最初の構成だけを見る)
            if (type == 2) {
                configs++
                if (configs > 1) break
            }
            when (type) {
                4 -> { // INTERFACE
                    flushAlt()
                    curIf = u8(d, 2); curAlt = u8(d, 3); curClass = u8(d, 5); curSub = u8(d, 6); curProto = u8(d, 7)
                    if (curClass == 1 && curSub == 1 && acIface < 0) {
                        acIface = curIf
                        uac2 = curProto == 0x20
                    }
                }
                0x24 -> if (curClass == 1 && curSub == 1) { // オーディオ制御
                    val st = u8(d, 2)
                    if (uac2) when (st) {
                        0x02 -> { // 入力端子
                            val id = u8(d, 3)
                            terminalClock[id] = u8(d, 7)
                            if (u16(d, 4) == 0x0101) streamingIn += id
                        }
                        0x03 -> { // 出力端子
                            val id = u8(d, 3)
                            sourceOf[id] = u8(d, 7)
                            terminalClock[id] = u8(d, 8)
                            if (u16(d, 4) != 0x0101) outputTerminals += id
                        }
                        0x04 -> sourceOf[u8(d, 3)] = u8(d, 5) // ミキサー(最初の入力)
                        0x05 -> sourceOf[u8(d, 3)] = u8(d, 5) // セレクター(最初の入力)
                        0x06 -> { // 機能ユニット
                            val id = u8(d, 3); val src = u8(d, 4)
                            sourceOf[id] = src
                            val n = (len - 6) / 4
                            val chs = ArrayList<Int>()
                            var master = false
                            for (c in 0 until n) {
                                val ctl = u32(d, 5 + c * 4)
                                val vol = (ctl shr 2) and 3
                                if (vol == 3) { if (c == 0) master = true else chs += c }
                            }
                            features += FeatureUnit(id, src, master, chs)
                        }
                        0x0A -> clockSources += u8(d, 3)
                        0x0B -> {
                            val id = u8(d, 3); val n = u8(d, 4)
                            clockSelectors[id] = (0 until n).map { u8(d, 5 + it) }
                        }
                        0x0C -> clockMultipliers[u8(d, 3)] = u8(d, 4)
                    } else when (st) {
                        0x02 -> { val id = u8(d, 3); if (u16(d, 4) == 0x0101) streamingIn += id }
                        0x03 -> { val id = u8(d, 3); sourceOf[id] = u8(d, 7); if (u16(d, 4) != 0x0101) outputTerminals += id }
                        0x04 -> sourceOf[u8(d, 3)] = u8(d, 5)
                        0x05 -> sourceOf[u8(d, 3)] = u8(d, 5)
                        0x06 -> {
                            val id = u8(d, 3); val src = u8(d, 4); val cs = u8(d, 5).coerceAtLeast(1)
                            sourceOf[id] = src
                            val n = (len - 7) / cs
                            val chs = ArrayList<Int>()
                            var master = false
                            for (c in 0 until n) {
                                val ctl = u8(d, 6 + c * cs)
                                if (ctl and 0x02 != 0) { if (c == 0) master = true else chs += c }
                            }
                            features += FeatureUnit(id, src, master, chs)
                        }
                    }
                } else if (curClass == 1 && curSub == 2) { // オーディオストリーミング
                    val st = u8(d, 2)
                    if (uac2) when (st) {
                        0x01 -> {
                            asLink = u8(d, 3)
                            val fmts = u32(d, 6)
                            asPcm = u8(d, 5) == 1 && (fmts and 1) != 0
                            asRaw = u8(d, 5) == 1 && (fmts ushr 31) == 1
                            asCh = u8(d, 10)
                        }
                        0x02 -> if (u8(d, 3) == 1) { asSub = u8(d, 4); asBits = u8(d, 5) }
                    } else when (st) {
                        0x01 -> { asLink = u8(d, 3); asPcm = u16(d, 5) == 1 }
                        0x02 -> if (u8(d, 3) == 1) {
                            asCh = u8(d, 4); asSub = u8(d, 5); asBits = u8(d, 6)
                            val nf = u8(d, 7)
                            if (nf == 0) { asMin = u24(d, 8); asMax = u24(d, 11) }
                            else for (k in 0 until nf) asRates.add(u24(d, 8 + k * 3))
                        }
                    }
                }
                5 -> if (curClass == 1 && curSub == 2) { // エンドポイント
                    val addr = u8(d, 2); val attr = u8(d, 3); val maxp = u16(d, 4); val interval = u8(d, 6)
                    if ((attr and 3) == 1) {
                        if (addr and 0x80 == 0) {
                            epOut = addr; epOutAttr = attr; epOutMax = maxp; epOutInt = interval.coerceAtLeast(1)
                            if (len >= 9) epOutSynch = u8(d, 8)
                        } else if (((attr shr 4) and 3) == 1 || epOutSynch == addr || (epOut >= 0 && ((epOutAttr shr 2) and 3) == 1)) {
                            epFb = addr; epFbMax = maxp and 0x7FF; epFbInt = interval.coerceAtLeast(1)
                        }
                    }
                }
            }
            i += len
        }
        flushAlt()
        if (acIface < 0 || alts.isEmpty()) return null
        return UacInfo(
            uac2, acIface, alts, terminalClock, clockSources, clockSelectors, clockMultipliers,
            features, sourceOf, outputTerminals, streamingIn,
        )
    }
}
