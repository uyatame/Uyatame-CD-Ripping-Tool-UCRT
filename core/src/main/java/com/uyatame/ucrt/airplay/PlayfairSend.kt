package com.uyatame.ucrt.airplay

import com.uyatame.ucrt.airplay.PlayfairTables.defaultSap
import com.uyatame.ucrt.airplay.PlayfairTables.messageIv
import com.uyatame.ucrt.airplay.PlayfairTables.messageKey
import com.uyatame.ucrt.airplay.PlayfairTables.tableS10
import com.uyatame.ucrt.airplay.PlayfairTables.tableS2
import com.uyatame.ucrt.airplay.PlayfairTables.tableS3
import com.uyatame.ucrt.airplay.PlayfairTables.tableS4
import com.uyatame.ucrt.airplay.PlayfairTables.tableS5
import com.uyatame.ucrt.airplay.PlayfairTables.tableS6
import com.uyatame.ucrt.airplay.PlayfairTables.tableS7
import com.uyatame.ucrt.airplay.PlayfairTables.tableS8
import com.uyatame.ucrt.airplay.PlayfairTables.tableS9
import java.security.SecureRandom

/**
 * FairPlay 送信側: Playfair (受信側) の変換の厳密な逆変換一式。
 * GoAirPlay の playfair_send.go (実機で Shairport-Sync に検証済み) の移植。
 *
 * 逆変換が可能な理由 (Go 版で実証済み):
 * - バイト置換テーブル (S2/S3/S4/S10 の各セグメント) はすべて全単射なので
 *   逆テーブルが存在する。
 * - 32 ビット混合テーブル (S5..S8、S9 の各セグメント) は差分集合
 *   {T[x]^T[0]} が GF(2) 上 8 次元の線形空間になり、4 つの部分空間が
 *   直和 (ランク 32) をなすので、ワード混合は GF(2) 線形代数で厳密に
 *   逆変換できる。
 *
 * 32 ビット演算は Playfair と同じく Int で行い、uint32 のビット列として扱う。
 */
internal object PlayfairSend {

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    private fun le32(b: ByteArray, off: Int): Int =
        u(b[off]) or (u(b[off + 1]) shl 8) or (u(b[off + 2]) shl 16) or (u(b[off + 3]) shl 24)

    private fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }

    private fun highestBit(w: Int): Int = 31 - w.countLeadingZeroBits()

    // --- GF(2) 線形代数 (uint32 ベクトル、加法は XOR) ---

    /** 32 本の独立な基底ベクトルへの係数分解を行うソルバ。 */
    private class GF2Solver(basis: IntArray) {
        private val pivotVal = IntArray(32)
        private val pivotCoeff = IntArray(32)
        private val hasPivot = BooleanArray(32)

        init {
            require(basis.size == 32) { "need 32 basis vectors, got ${basis.size}" }
            val vals = basis.copyOf()
            val coeffs = IntArray(32) { 1 shl it }
            for (i in 0 until 32) {
                var best = -1
                var bestBit = -1
                for (j in i until 32) {
                    if (vals[j] == 0) continue
                    val b = highestBit(vals[j])
                    if (b > bestBit) {
                        bestBit = b
                        best = j
                    }
                }
                check(best >= 0) { "basis is rank deficient at step $i" }
                val tv = vals[i]; vals[i] = vals[best]; vals[best] = tv
                val tc = coeffs[i]; coeffs[i] = coeffs[best]; coeffs[best] = tc
                for (j in i + 1 until 32) {
                    if (vals[j] and (1 shl bestBit) != 0) {
                        vals[j] = vals[j] xor vals[i]
                        coeffs[j] = coeffs[j] xor coeffs[i]
                    }
                }
                pivotVal[bestBit] = vals[i]
                pivotCoeff[bestBit] = coeffs[i]
                hasPivot[bestBit] = true
            }
        }

        /** z を基底の XOR で表す係数マスクを返す。 */
        fun solve(z0: Int): Int {
            var z = z0
            var coeff = 0
            while (z != 0) {
                val b = highestBit(z)
                if (!hasPivot[b]) return 0 // span の外 (ランク 32 では起きない)
                z = z xor pivotVal[b]
                coeff = coeff xor pivotCoeff[b]
            }
            return coeff
        }
    }

    /**
     * ワード混合 y = T0[w0] ^ T1[w1] ^ T2[w2] ^ T3[w3] の逆変換。
     * 各 Tp はバイト位置 p の 256 エントリ uint32 テーブル。
     */
    private class MixInverter(
        private val tables: Array<IntArray>,
    ) {
        private val basis: Array<IntArray> = Array(4) { IntArray(0) }
        private val solver: GF2Solver

        init {
            val combined = ArrayList<Int>(32)
            for (p in 0 until 4) {
                val t = tables[p]
                val indep = ArrayList<Int>(8)
                val piv = IntArray(32)
                val has = BooleanArray(32)
                var x = 0
                while (x < 256 && indep.size < 8) {
                    val v = t[x] xor t[0]
                    var w = v
                    while (w != 0) {
                        val b = highestBit(w)
                        if (has[b]) {
                            w = w xor piv[b]
                        } else {
                            piv[b] = w
                            has[b] = true
                            indep.add(v)
                            break
                        }
                    }
                    x++
                }
                check(indep.size == 8) { "table $p difference rank is ${indep.size}, want 8" }
                basis[p] = indep.toIntArray()
                combined.addAll(indep)
            }
            solver = GF2Solver(combined.toIntArray())
        }

        /** y = Tp[wp ^ kp] (p=0..3) の混合を、各バイトに鍵バイトを XOR した形で逆変換する。 */
        fun invertWithKeys(y: Int, key: Int): Int {
            val keyed = Array(4) { IntArray(256) }
            val maps = Array(4) { HashMap<Int, Byte>(256) }
            var f0 = 0
            for (p in 0 until 4) {
                val kb = (key ushr (8 * p)) and 0xFF
                val kt = keyed[p]
                for (x in 0 until 256) {
                    kt[x] = tables[p][x xor kb]
                }
                f0 = f0 xor kt[0]
                val mp = maps[p]
                for (x in 0 until 256) {
                    mp[kt[x] xor kt[0]] = x.toByte()
                }
            }
            val coeff = solver.solve(y xor f0)
            var w = 0
            for (p in 0 until 4) {
                var v = 0
                for (j in 0 until 8) {
                    if (coeff and (1 shl (p * 8 + j)) != 0) {
                        v = v xor basis[p][j]
                    }
                }
                w = w or ((maps[p][v]!!.toInt() and 0xFF) shl (8 * p))
            }
            return w
        }

        fun invert(y: Int): Int = invertWithKeys(y, 0)
    }

    // --- 逆バイト置換テーブル (遅延初期化) ---

    private fun invertByteTable(t: ByteArray, off: Int): ByteArray {
        val inv = ByteArray(256)
        for (i in 0 until 256) {
            inv[u(t[off + i])] = i.toByte()
        }
        return inv
    }

    private fun messageTableIndexOff(i: Int): Int = (97 * i % 144) shl 8
    private fun permuteTable2Off(i: Int): Int = ((71 * i) % 144) shl 8

    private val invS3: Array<ByteArray> by lazy {
        Array(16) { seg -> invertByteTable(tableS3, seg * 256) }
    }
    private val invS10: Array<ByteArray> by lazy {
        Array(16) { seg -> invertByteTable(tableS10, seg * 256) }
    }
    private val invS2ByIx: Array<ByteArray> by lazy {
        Array(144) { i -> invertByteTable(tableS2, messageTableIndexOff(i)) }
    }
    private val invS4ByIx: Array<ByteArray> by lazy {
        Array(144) { i -> invertByteTable(tableS4, permuteTable2Off(i)) }
    }

    private val cycleMixInv: MixInverter by lazy {
        // バイト位置順: 位置0=S8, 1=S7, 2=S6, 3=S5 (Playfair.cycle の混合順)
        MixInverter(arrayOf(tableS8, tableS7, tableS6, tableS5))
    }
    private val msgMixInv: MixInverter by lazy {
        MixInverter(
            arrayOf(
                tableS9.copyOfRange(0x000, 0x100),
                tableS9.copyOfRange(0x100, 0x200),
                tableS9.copyOfRange(0x200, 0x300),
                tableS9.copyOfRange(0x300, 0x400),
            )
        )
    }

    // --- 逆置換ステップ ---
    // すべての置換層は共通の位置置換 sigma を持つ (Go 版コメント参照)。

    /** target 位置に対する source 位置。 */
    private val substSource = intArrayOf(
        0, 13, 10, 7,
        4, 1, 14, 11,
        8, 5, 2, 15,
        12, 9, 6, 3,
    )

    /**
     * 置換層の逆変換。inv[t] は target t に適用された S ボックスの逆テーブル。
     * key が非 null のときは、前方向の末尾 XOR をundo するため先に XOR する。
     */
    private fun invertSubst(block: ByteArray, inv: Array<ByteArray>, key: ByteArray?) {
        val out = block.copyOf()
        for (t in 0 until 16) {
            var v = out[t]
            if (key != null) {
                v = (u(v) xor u(key[t])).toByte()
            }
            block[substSource[t]] = inv[t][u(v)]
        }
    }

    /** permuteBlock1 の逆変換。target -> segment の対応は Playfair 側と対。 */
    private val permute1Seg = intArrayOf(
        0, 5, 10, 15,
        4, 9, 14, 3,
        8, 13, 2, 7,
        12, 1, 6, 11,
    )

    private fun permuteBlock1Inverse(block: ByteArray) {
        val inv = Array(16) { t -> invS3[permute1Seg[t]] }
        invertSubst(block, inv, null)
    }

    private fun permuteBlock2Inverse(block: ByteArray, round: Int) {
        val inv = Array(16) { t -> invS4ByIx[round * 16 + t] }
        invertSubst(block, inv, null)
    }

    // --- cycle の逆変換 ---

    /** Playfair.cycle の逆変換 (インプレース)。 */
    private fun cycleInverse(block: ByteArray, keySchedule: Array<IntArray>) {
        fun xorKey(ks: IntArray) {
            for (k in 0 until 4) {
                putLe32(block, 4 * k, le32(block, 4 * k) xor ks[k])
            }
        }
        xorKey(keySchedule[0])
        for (r in 8 downTo 0) {
            permuteBlock2Inverse(block, 8 - r)
            val ks = keySchedule[9 - r]
            for (k in 0 until 4) {
                val w = le32(block, 4 * k)
                putLe32(block, 4 * k, cycleMixInv.invertWithKeys(w, ks[k]))
            }
        }
        permuteBlock1Inverse(block)
        xorKey(keySchedule[10])
    }

    /**
     * Playfair.decrypt の逆: keymsg と、復号結果として得たい 16 バイト鍵
     * keyOut から、72 バイトの ekey を作る。
     */
    fun playfairEncrypt(keymsg: ByteArray, keyOut: ByteArray, random: SecureRandom): ByteArray {
        val sapKey = Playfair.generateSessionKey(defaultSap, keymsg)
        val keySchedule = Array(11) { IntArray(4) }
        Playfair.generateKeySchedule(sapKey, keySchedule)

        // 外側の XOR 層をundo (zXor と xXor は対合で可換)。
        val tmp = keyOut.copyOf()
        Playfair.zXor(tmp, tmp, 1)
        Playfair.xXor(tmp, tmp, 1)

        val chunk1 = ByteArray(16).also { random.nextBytes(it) }
        val block = ByteArray(16)
        for (i in 0 until 16) {
            block[i] = (u(tmp[i]) xor u(chunk1[i])).toByte()
        }
        cycleInverse(block, keySchedule)
        val chunk2 = ByteArray(16)
        Playfair.zXor(block, chunk2, 1)

        val ekey = ByteArray(72).also { random.nextBytes(it) }
        chunk1.copyInto(ekey, 16)
        chunk2.copyInto(ekey, 56)
        return ekey
    }

    // --- decryptMessage の逆変換 (encryptMessage) ---

    private fun encryptBlockCore(block: ByteArray, mode: Int) {
        // 最後の S10 置換を先にundo。
        val inv = Array(16) { t -> invS10[t] }
        invertSubst(block, inv, null)
        // 9 ラウンドを逆順に: 逆 S9 混合 → 逆置換。
        for (j in 8 downTo 0) {
            for (k in 0 until 4) {
                val w = le32(block, 4 * k)
                putLe32(block, 4 * k, msgMixInv.invert(w))
            }
            val base = 0x80 - 0x10 * j
            val invR = Array(16) { t -> invS2ByIx[base + t] }
            val key = ByteArray(16) { t -> messageKey[mode][base + t] }
            invertSubst(block, invR, key)
        }
    }

    /** 128 バイト平文を、keymsg に載る 128 バイトの暗号文へ変換する (CBC)。 */
    fun encryptMessage(plain: ByteArray, mode: Int): ByteArray {
        val out = ByteArray(128)
        var prev = messageIv[mode]
        for (i in 0 until 8) {
            val block = ByteArray(16)
            for (j in 0 until 16) {
                block[j] = (u(plain[16 * i + j]) xor u(prev[j])).toByte()
            }
            encryptBlockCore(block, mode)
            block.copyInto(out, 16 * i)
            prev = out.copyOfRange(16 * i, 16 * i + 16)
        }
        return out
    }

    /**
     * 164 バイトの FairPlay ハンドシェイクメッセージを組み立てる。
     * バイト 4 = 0x03、バイト 12 = mode (受信側 fp-setup の要求仕様)。
     */
    fun buildKeymsg(plain: ByteArray, mode: Int, random: SecureRandom): ByteArray {
        val msg = ByteArray(164).also { random.nextBytes(it) }
        msg[4] = 0x03
        msg[12] = mode.toByte()
        encryptMessage(plain, mode).copyInto(msg, 16)
        return msg
    }

    /**
     * 開発時自己診断: 全 mode で buildKeymsg + playfairEncrypt したものを
     * 受信側の Playfair.decrypt に通し、元の鍵に戻るか確認する。
     */
    fun selfTest(random: SecureRandom): Boolean {
        for (mode in 0 until 4) {
            val plain = ByteArray(128).also { random.nextBytes(it) }
            val keymsg = buildKeymsg(plain, mode, random)
            val keyOut = ByteArray(16).also { random.nextBytes(it) }
            val ekey = playfairEncrypt(keymsg, keyOut, random)
            val back = Playfair.decrypt(keymsg, ekey)
            if (!back.contentEquals(keyOut)) return false
        }
        return true
    }
}
