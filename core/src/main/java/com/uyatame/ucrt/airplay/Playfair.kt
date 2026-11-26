package com.uyatame.ucrt.airplay

import com.uyatame.ucrt.airplay.PlayfairTables.defaultSap
import com.uyatame.ucrt.airplay.PlayfairTables.indexMangle
import com.uyatame.ucrt.airplay.PlayfairTables.initialSessionKey
import com.uyatame.ucrt.airplay.PlayfairTables.messageIv
import com.uyatame.ucrt.airplay.PlayfairTables.messageKey
import com.uyatame.ucrt.airplay.PlayfairTables.staticSource1
import com.uyatame.ucrt.airplay.PlayfairTables.staticSource2
import com.uyatame.ucrt.airplay.PlayfairTables.tableS1
import com.uyatame.ucrt.airplay.PlayfairTables.tableS10
import com.uyatame.ucrt.airplay.PlayfairTables.tableS2
import com.uyatame.ucrt.airplay.PlayfairTables.tableS3
import com.uyatame.ucrt.airplay.PlayfairTables.tableS4
import com.uyatame.ucrt.airplay.PlayfairTables.tableS5
import com.uyatame.ucrt.airplay.PlayfairTables.tableS6
import com.uyatame.ucrt.airplay.PlayfairTables.tableS7
import com.uyatame.ucrt.airplay.PlayfairTables.tableS8
import com.uyatame.ucrt.airplay.PlayfairTables.tableS9
import com.uyatame.ucrt.airplay.PlayfairTables.tKey
import com.uyatame.ucrt.airplay.PlayfairTables.xKey
import com.uyatame.ucrt.airplay.PlayfairTables.zKey

/**
 * FairPlay "playfair" 鍵復号。GoAirPlay (自作・実機検証済み) の playfair.go を
 * Kotlin へ移植したもの。アルゴリズムはオープンソースの AirPlay 受信実装で
 * 公開されている手順に基づく。
 *
 * RTSP SETUP で届く 164 バイトの FairPlay ハンドシェイク (keymsg) と
 * 72 バイトの暗号化鍵 (ekey) から、音声ストリーム用の AES 鍵 16 バイトを復元する。
 *
 * 移植上の約束事 (Go 版の uint32 / byte 意味論の再現):
 * - 32 ビット演算は Int で行い、Go の uint32 と同じビット列として扱う。
 *   右シフトは ushr、割り算は符号なし (udiv) を使う。
 * - バイト列への 32 ビットアクセスはすべてリトルエンディアン。
 * - テーブル参照や演算に使うバイト値は u() で 0..255 に直してから使う。
 */
internal object Playfair {

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    private fun le32(b: ByteArray, off: Int): Int =
        u(b[off]) or (u(b[off + 1]) shl 8) or (u(b[off + 2]) shl 16) or (u(b[off + 3]) shl 24)

    private fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }

    private fun be32(b: ByteArray, off: Int): Int =
        (u(b[off]) shl 24) or (u(b[off + 1]) shl 16) or (u(b[off + 2]) shl 8) or u(b[off + 3])

    private fun rol32(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    /** 符号なし 32 ビット割り算 (Go の uint32 / と同じ)。 */
    private fun udiv(x: Int, d: Int): Int = ((x.toLong() and 0xFFFF_FFFFL) / d).toInt()

    /** 符号なし 32 ビット剰余 (Go の uint32 % と同じ)。 */
    private fun umod(x: Int, m: Int): Int = ((x.toLong() and 0xFFFF_FFFFL) % m).toInt()

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    /** AirPlay ストリーム鍵を復号する。keymsg: 164 バイト、ekey: 72 バイト。 */
    fun decrypt(keymsg: ByteArray, ekey: ByteArray): ByteArray {
        val chunk1 = ekey.copyOfRange(16, 32)
        val chunk2 = ekey.copyOfRange(56, 72)

        val sapKey = generateSessionKey(defaultSap, keymsg)

        val keySchedule = Array(11) { IntArray(4) }
        generateKeySchedule(sapKey, keySchedule)

        val blockIn = ByteArray(16)
        zXor(chunk2, blockIn, 1)
        cycle(blockIn, keySchedule)

        val keyOut = ByteArray(16)
        for (i in 0 until 16) {
            keyOut[i] = (u(blockIn[i]) xor u(chunk1[i])).toByte()
        }
        xXor(keyOut, keyOut, 1)
        zXor(keyOut, keyOut, 1)
        return keyOut
    }

    private fun xorBlocks(a: ByteArray, aOff: Int, bb: ByteArray, bOff: Int, out: ByteArray, oOff: Int) {
        for (i in 0 until 16) {
            out[oOff + i] = (u(a[aOff + i]) xor u(bb[bOff + i])).toByte()
        }
    }

    private fun zXor(inp: ByteArray, out: ByteArray, blocks: Int) {
        for (j in 0 until blocks) {
            for (i in 0 until 16) {
                out[j * 16 + i] = (u(inp[j * 16 + i]) xor u(zKey[i])).toByte()
            }
        }
    }

    private fun xXor(inp: ByteArray, out: ByteArray, blocks: Int) {
        for (j in 0 until blocks) {
            for (i in 0 until 16) {
                out[j * 16 + i] = (u(inp[j * 16 + i]) xor u(xKey[i])).toByte()
            }
        }
    }

    private fun tXor(inp: ByteArray, out: ByteArray) {
        for (i in 0 until 16) {
            out[i] = (u(inp[i]) xor u(tKey[i])).toByte()
        }
    }

    // テーブル内の 256 バイトサブテーブルの開始位置 (Go 版はスライスを返していた)。
    private fun tableIndexOff(i: Int): Int = ((31 * i) % 0x28) shl 8
    private fun messageTableIndexOff(i: Int): Int = (97 * i % 144) shl 8
    private fun permuteTable2Off(i: Int): Int = ((71 * i) % 144) shl 8

    private fun permuteBlock1(block: ByteArray) {
        block[0] = tableS3[u(block[0])]
        block[4] = tableS3[0x400 + u(block[4])]
        block[8] = tableS3[0x800 + u(block[8])]
        block[12] = tableS3[0xc00 + u(block[12])]

        var tmp = block[13]
        block[13] = tableS3[0x100 + u(block[9])]
        block[9] = tableS3[0xd00 + u(block[5])]
        block[5] = tableS3[0x900 + u(block[1])]
        block[1] = tableS3[0x500 + u(tmp)]

        tmp = block[2]
        block[2] = tableS3[0xa00 + u(block[10])]
        block[10] = tableS3[0x200 + u(tmp)]
        tmp = block[6]
        block[6] = tableS3[0xe00 + u(block[14])]
        block[14] = tableS3[0x600 + u(tmp)]

        tmp = block[3]
        block[3] = tableS3[0xf00 + u(block[7])]
        block[7] = tableS3[0x300 + u(block[11])]
        block[11] = tableS3[0x700 + u(block[15])]
        block[15] = tableS3[0xb00 + u(tmp)]
    }

    private fun permuteBlock2(block: ByteArray, round: Int) {
        fun t(idx: Int, v: Byte): Byte = tableS4[permuteTable2Off(round * 16 + idx) + u(v)]
        block[0] = t(0, block[0])
        block[4] = t(4, block[4])
        block[8] = t(8, block[8])
        block[12] = t(12, block[12])

        var tmp = block[13]
        block[13] = t(13, block[9])
        block[9] = t(9, block[5])
        block[5] = t(5, block[1])
        block[1] = t(1, tmp)

        tmp = block[2]
        block[2] = t(2, block[10])
        block[10] = t(10, tmp)
        tmp = block[6]
        block[6] = t(6, block[14])
        block[14] = t(14, tmp)

        tmp = block[3]
        block[3] = t(3, block[7])
        block[7] = t(7, block[11])
        block[11] = t(11, block[15])
        block[15] = t(15, tmp)
    }

    /** 16 バイトの鍵素材を 11 ラウンド分の鍵へ展開する。 */
    private fun generateKeySchedule(keyMaterial: ByteArray, keySchedule: Array<IntArray>) {
        val buf = ByteArray(16)
        tXor(keyMaterial, buf)

        var ti = 0
        for (round in 0 until 11) {
            keySchedule[round][0] = le32(buf, 0)

            val t1 = tableIndexOff(ti)
            val t2 = tableIndexOff(ti + 1)
            val t3 = tableIndexOff(ti + 2)
            val t4 = tableIndexOff(ti + 3)
            ti += 4

            buf[0] = (u(buf[0]) xor u(tableS1[t1 + u(buf[0x0d])]) xor u(indexMangle[round])).toByte()
            buf[1] = (u(buf[1]) xor u(tableS1[t2 + u(buf[0x0e])])).toByte()
            buf[2] = (u(buf[2]) xor u(tableS1[t3 + u(buf[0x0f])])).toByte()
            buf[3] = (u(buf[3]) xor u(tableS1[t4 + u(buf[0x0c])])).toByte()

            keySchedule[round][1] = le32(buf, 4)
            putLe32(buf, 4, le32(buf, 4) xor le32(buf, 0))

            keySchedule[round][2] = le32(buf, 8)
            putLe32(buf, 8, le32(buf, 8) xor le32(buf, 4))

            keySchedule[round][3] = le32(buf, 12)
            putLe32(buf, 12, le32(buf, 12) xor le32(buf, 8))
        }
    }

    /** 16 バイトブロックへの 9 ラウンド変換 (インプレース)。 */
    private fun cycle(block: ByteArray, keySchedule: Array<IntArray>) {
        putLe32(block, 0, le32(block, 0) xor keySchedule[10][0])
        putLe32(block, 4, le32(block, 4) xor keySchedule[10][1])
        putLe32(block, 8, le32(block, 8) xor keySchedule[10][2])
        putLe32(block, 12, le32(block, 12) xor keySchedule[10][3])

        permuteBlock1(block)

        for (round in 0 until 9) {
            val ks = keySchedule[9 - round]
            val k0 = ByteArray(4).also { putLe32(it, 0, ks[0]) }
            val k1 = ByteArray(4).also { putLe32(it, 0, ks[1]) }
            val k2 = ByteArray(4).also { putLe32(it, 0, ks[2]) }
            val k3 = ByteArray(4).also { putLe32(it, 0, ks[3]) }

            putLe32(block, 0, tableS5[u(block[3]) xor u(k0[3])] xor
                tableS6[u(block[2]) xor u(k0[2])] xor
                tableS8[u(block[0]) xor u(k0[0])] xor
                tableS7[u(block[1]) xor u(k0[1])])

            putLe32(block, 4, tableS5[u(block[7]) xor u(k1[3])] xor
                tableS6[u(block[6]) xor u(k1[2])] xor
                tableS7[u(block[5]) xor u(k1[1])] xor
                tableS8[u(block[4]) xor u(k1[0])])

            putLe32(block, 8, tableS5[u(block[11]) xor u(k2[3])] xor
                tableS6[u(block[10]) xor u(k2[2])] xor
                tableS7[u(block[9]) xor u(k2[1])] xor
                tableS8[u(block[8]) xor u(k2[0])])

            putLe32(block, 12, tableS5[u(block[15]) xor u(k3[3])] xor
                tableS6[u(block[14]) xor u(k3[2])] xor
                tableS7[u(block[13]) xor u(k3[1])] xor
                tableS8[u(block[12]) xor u(k3[0])])

            permuteBlock2(block, 8 - round)
        }

        putLe32(block, 0, le32(block, 0) xor keySchedule[0][0])
        putLe32(block, 4, le32(block, 4) xor keySchedule[0][1])
        putLe32(block, 8, le32(block, 8) xor keySchedule[0][2])
        putLe32(block, 12, le32(block, 12) xor keySchedule[0][3])
    }

    /**
     * 164 バイトのハンドシェイクメッセージ内の 128 バイトのペイロードを復号する
     * (先頭 16 バイトはヘッダー)。
     */
    private fun decryptMessage(messageIn: ByteArray, decryptedMessage: ByteArray) {
        val buffer = ByteArray(16)
        val mode = u(messageIn[12]) // 0, 1, 2 or 3

        for (i in 0 until 8) {
            // n 番目のブロックを取り込む (mode 3 はメッセージを後ろから歩く)。
            for (j in 0 until 16) {
                buffer[j] = if (mode == 3) {
                    messageIn[(0x80 - 0x10 * i) + j]
                } else {
                    messageIn[(0x10 * (i + 1)) + j]
                }
            }

            // テーブル置換 + tableS9 のワード混合を 9 ラウンド。
            for (j in 0 until 9) {
                val base = 0x80 - 0x10 * j
                fun mt(idx: Int, v: Byte): Int =
                    u(tableS2[messageTableIndexOff(base + idx) + u(v)]) xor u(messageKey[mode][base + idx])

                buffer[0x0] = mt(0x0, buffer[0x0]).toByte()
                buffer[0x4] = mt(0x4, buffer[0x4]).toByte()
                buffer[0x8] = mt(0x8, buffer[0x8]).toByte()
                buffer[0xc] = mt(0xc, buffer[0xc]).toByte()

                var tmp = buffer[0x0d]
                buffer[0xd] = mt(0xd, buffer[0x9]).toByte()
                buffer[0x9] = mt(0x9, buffer[0x5]).toByte()
                buffer[0x5] = mt(0x5, buffer[0x1]).toByte()
                buffer[0x1] = mt(0x1, tmp).toByte()

                tmp = buffer[0x02]
                buffer[0x2] = mt(0x2, buffer[0xa]).toByte()
                buffer[0xa] = mt(0xa, tmp).toByte()
                tmp = buffer[0x06]
                buffer[0x6] = mt(0x6, buffer[0xe]).toByte()
                buffer[0xe] = mt(0xe, tmp).toByte()

                tmp = buffer[0x3]
                buffer[0x3] = mt(0x3, buffer[0x7]).toByte()
                buffer[0x7] = mt(0x7, buffer[0xb]).toByte()
                buffer[0xb] = mt(0xb, buffer[0xf]).toByte()
                buffer[0xf] = mt(0xf, tmp).toByte()

                putLe32(buffer, 0, tableS9[0x000 + u(buffer[0x0])] xor
                    tableS9[0x100 + u(buffer[0x1])] xor
                    tableS9[0x200 + u(buffer[0x2])] xor
                    tableS9[0x300 + u(buffer[0x3])])
                putLe32(buffer, 4, tableS9[0x000 + u(buffer[0x4])] xor
                    tableS9[0x100 + u(buffer[0x5])] xor
                    tableS9[0x200 + u(buffer[0x6])] xor
                    tableS9[0x300 + u(buffer[0x7])])
                putLe32(buffer, 8, tableS9[0x000 + u(buffer[0x8])] xor
                    tableS9[0x100 + u(buffer[0x9])] xor
                    tableS9[0x200 + u(buffer[0xa])] xor
                    tableS9[0x300 + u(buffer[0xb])])
                putLe32(buffer, 12, tableS9[0x000 + u(buffer[0xc])] xor
                    tableS9[0x100 + u(buffer[0xd])] xor
                    tableS9[0x200 + u(buffer[0xe])] xor
                    tableS9[0x300 + u(buffer[0xf])])
            }

            // 最後の置換は tableS10。
            fun t10(idx: Int, v: Byte): Byte = tableS10[(idx shl 8) + u(v)]
            buffer[0x0] = t10(0x0, buffer[0x0])
            buffer[0x4] = t10(0x4, buffer[0x4])
            buffer[0x8] = t10(0x8, buffer[0x8])
            buffer[0xc] = t10(0xc, buffer[0xc])

            var tmp = buffer[0x0d]
            buffer[0xd] = t10(0xd, buffer[0x9])
            buffer[0x9] = t10(0x9, buffer[0x5])
            buffer[0x5] = t10(0x5, buffer[0x1])
            buffer[0x1] = t10(0x1, tmp)

            tmp = buffer[0x02]
            buffer[0x2] = t10(0x2, buffer[0xa])
            buffer[0xa] = t10(0xa, tmp)
            tmp = buffer[0x06]
            buffer[0x6] = t10(0x6, buffer[0xe])
            buffer[0xe] = t10(0xe, tmp)

            tmp = buffer[0x3]
            buffer[0x3] = t10(0x3, buffer[0x7])
            buffer[0x7] = t10(0x7, buffer[0xb])
            buffer[0xb] = t10(0xb, buffer[0xf])
            buffer[0xf] = t10(0xf, tmp)

            // 前のメッセージブロック (端では mode の IV) と XOR する。
            if (mode == 2 || mode == 1 || mode == 0) {
                if (i > 0) {
                    xorBlocks(buffer, 0, messageIn, 0x10 * i, decryptedMessage, 0x10 * i)
                } else {
                    xorBlocks(buffer, 0, messageIv[mode], 0, decryptedMessage, 0)
                }
            } else {
                val off = 0x70 - 0x10 * i
                if (i < 7) {
                    xorBlocks(buffer, 0, messageIn, off, decryptedMessage, off)
                } else {
                    xorBlocks(buffer, 0, messageIv[mode], 0, decryptedMessage, off)
                }
            }
        }
    }

    /** SAP 本体と (復号済み) ハンドシェイクメッセージから 16 バイトのセッション鍵を導く。 */
    private fun generateSessionKey(oldSap: ByteArray, messageIn: ByteArray): ByteArray {
        val decryptedMessage = ByteArray(128)
        decryptMessage(messageIn, decryptedMessage)

        val newSap = ByteArray(320)
        staticSource1.copyInto(newSap, 0, 0, 0x11)
        decryptedMessage.copyInto(newSap, 0x011, 0, 0x80)
        oldSap.copyInto(newSap, 0x091, 0x80, 0x80 + 0x80)
        staticSource2.copyInto(newSap, 0x111, 0, 0x2f)

        val sessionKey = ByteArray(16)
        initialSessionKey.copyInto(sessionKey)

        val md5Out = ByteArray(16)
        for (round in 0 until 5) {
            val base = newSap.copyOfRange(round * 64, round * 64 + 64)
            modifiedMD5(base, sessionKey, md5Out)
            sapHash(base, sessionKey)
            for (i in 0 until 4) {
                putLe32(sessionKey, 4 * i, le32(sessionKey, 4 * i) + le32(md5Out, 4 * i))
            }
        }

        // 各 4 バイトワード内のバイト順を反転する。
        for (i in 0 until 16 step 4) {
            var t = sessionKey[i]; sessionKey[i] = sessionKey[i + 3]; sessionKey[i + 3] = t
            t = sessionKey[i + 1]; sessionKey[i + 1] = sessionKey[i + 2]; sessionKey[i + 2] = t
        }

        for (i in 0 until 16) {
            sessionKey[i] = (u(sessionKey[i]) xor 121).toByte()
        }
        return sessionKey
    }

    // --- 変形 MD5 ---

    private val md5Shift = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    // md5K[i] = floor(2^32 * abs(sin(i+1))) — 標準 MD5 のサインテーブル。
    private val md5K = intArrayOf(
        0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db.toInt(), 0xc1bdceee.toInt(),
        0xf57c0faf.toInt(), 0x4787c62a.toInt(), 0xa8304613.toInt(), 0xfd469501.toInt(),
        0x698098d8.toInt(), 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
        0x6b901122.toInt(), 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821.toInt(),
        0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51.toInt(), 0xe9b6c7aa.toInt(),
        0xd62f105d.toInt(), 0x02441453.toInt(), 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
        0x21e1cde6.toInt(), 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed.toInt(),
        0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9.toInt(), 0x8d2a4c8a.toInt(),
        0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122.toInt(), 0xfde5380c.toInt(),
        0xa4beea44.toInt(), 0x4bdecfa9.toInt(), 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
        0x289b7ec6.toInt(), 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05.toInt(),
        0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8.toInt(), 0xc4ac5665.toInt(),
        0xf4292244.toInt(), 0x432aff97.toInt(), 0xab9423a7.toInt(), 0xfc93a039.toInt(),
        0x655b59c3.toInt(), 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
        0x6fa87e4f.toInt(), 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1.toInt(),
        0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb.toInt(), 0xeb86d391.toInt(),
    )

    private fun modifiedMD5(originalBlockIn: ByteArray, keyIn: ByteArray, keyOut: ByteArray) {
        val blockIn = ByteArray(64)
        originalBlockIn.copyInto(blockIn, 0, 0, 64)

        fun swapWords(a: Int, bb: Int) {
            val va = le32(blockIn, 4 * a)
            val vb = le32(blockIn, 4 * bb)
            putLe32(blockIn, 4 * a, vb)
            putLe32(blockIn, 4 * bb, va)
        }

        var a = le32(keyIn, 0)
        var bb = le32(keyIn, 4)
        var c = le32(keyIn, 8)
        var d = le32(keyIn, 12)
        for (i in 0 until 64) {
            val j = when {
                i < 16 -> i
                i < 32 -> (5 * i + 1) % 16
                i < 48 -> (3 * i + 5) % 16
                else -> (7 * i) % 16
            }

            // 注: メッセージワードは標準 MD5 と違いビッグエンディアンで組む。
            val input = be32(blockIn, 4 * j)
            var z = a + input + md5K[i]
            val f = when {
                i < 16 -> (bb and c) or (bb.inv() and d)
                i < 32 -> (bb and d) or (c and d.inv())
                i < 48 -> bb xor c xor d
                else -> c xor (bb or d.inv())
            }
            z = rol32(z + f, md5Shift[i])
            z += bb

            val tmp = d
            d = c
            c = bb
            bb = z
            a = tmp

            if (i == 31) {
                // ハッシュ途中のワード入れ替え (状態のニブルで決まる)。
                swapWords(a and 15, bb and 15)
                swapWords(c and 15, d and 15)
                swapWords((a and (15 shl 4)) ushr 4, (bb and (15 shl 4)) ushr 4)
                swapWords((a and (15 shl 8)) ushr 8, (bb and (15 shl 8)) ushr 8)
                swapWords((a and (15 shl 12)) ushr 12, (bb and (15 shl 12)) ushr 12)
            }
        }

        putLe32(keyOut, 0, le32(keyIn, 0) + a)
        putLe32(keyOut, 4, le32(keyIn, 4) + bb)
        putLe32(keyOut, 8, le32(keyIn, 8) + c)
        putLe32(keyOut, 12, le32(keyIn, 12) + d)
    }

    // --- sapHash と garble ヘルパー ---

    /** バイトを左へローテート (1..7)。 */
    private fun rol8(input: Byte, count: Int): Byte {
        val x = u(input)
        return (((x shl count) and 0xFF) or (x ushr (8 - count))).toByte()
    }

    /** マスクしない版: シフトアウトしたビットは 8 ビット目より上に残る。 */
    private fun rol8x(input: Byte, count: Int): Int {
        val x = u(input)
        return (x shl count) or (x ushr (8 - count))
    }

    private fun weirdRor8(input: Byte, count: Int): Int {
        val x = u(input)
        if (count == 0) return 0
        return ((x ushr count) and 0xFF) or ((x and 0xFF) shl (8 - count))
    }

    private fun weirdRol8(input: Byte, count: Int): Int {
        val x = u(input)
        if (count == 0) return 0
        return ((x shl count) and 0xFF) or ((x and 0xFF) ushr (8 - count))
    }

    private fun weirdRol32(input: Byte, count: Int): Int {
        val x = u(input)
        if (count == 0) return 0
        return (x shl count) xor (x ushr (8 - count))
    }

    /** 64 バイトブロックを 16 バイトの keyOut へハッシュする (インプレース)。 */
    private fun sapHash(blockIn: ByteArray, keyOut: ByteArray) {
        val buffer0 = b(0x96, 0x5F, 0xC6, 0x53, 0xF8, 0x46, 0xCC, 0x18, 0xDF, 0xBE, 0xB2, 0xF8, 0x38, 0xD7, 0xEC, 0x22, 0x03, 0xD1, 0x20, 0x8F)
        val buffer1 = ByteArray(210)
        val buffer2 = b(0x43, 0x54, 0x62, 0x7A, 0x18, 0xC3, 0xD6, 0xB3, 0x9A, 0x56, 0xF6, 0x1C, 0x14, 0x3F, 0x0C, 0x1D, 0x3B, 0x36, 0x83, 0xB1, 0x39, 0x51, 0x4A, 0xAA, 0x09, 0x3E, 0xFE, 0x44, 0xAF, 0xDE, 0xC3, 0x20, 0x9D, 0x42, 0x3A)
        val buffer3 = ByteArray(132)
        val buffer4 = b(0xED, 0x25, 0xD1, 0xBB, 0xBC, 0x27, 0x9F, 0x02, 0xA2, 0xA9, 0x11, 0x00, 0x0C, 0xB3, 0x52, 0xC0, 0xBD, 0xE3, 0x1B, 0x49, 0xC7)
        val i0Index = intArrayOf(18, 22, 23, 0, 5, 19, 32, 31, 10, 21, 30)

        // 入力を読み込む (4 バイトワードごとにバイト順を反転)。
        for (i in 0 until 210) {
            val inWord = le32(blockIn, 4 * ((i % 64) ushr 2))
            val inByte = (inWord ushr ((3 - (i % 4)) shl 3)) and 0xFF
            buffer1[i] = inByte.toByte()
        }

        // buffer1 をかき混ぜる (インデックスは符号なし 32 ビットで 210 の剰余)。
        for (i in 0 until 840) {
            val x = buffer1[umod(i - 155, 210)]
            val y = buffer1[umod(i - 57, 210)]
            val z = buffer1[umod(i - 13, 210)]
            val w = buffer1[umod(i, 210)]
            buffer1[i % 210] = (u(rol8(y, 5)) + (u(rol8(z, 3)) xor u(w)) - u(rol8(x, 7))).toByte()
        }

        garble(buffer0, buffer1, buffer2, buffer3, buffer4)

        for (i in 0 until 16) {
            keyOut[i] = 0xE1.toByte()
        }
        for (i in 0 until 11) {
            keyOut[i] = if (i == 3) {
                0x3d.toByte()
            } else {
                (u(keyOut[i]) + u(buffer3[i0Index[i] * 4])).toByte()
            }
        }
        for (i in 0 until 20) {
            keyOut[i % 16] = (u(keyOut[i % 16]) xor u(buffer0[i])).toByte()
        }
        for (i in 0 until 35) {
            keyOut[i % 16] = (u(keyOut[i % 16]) xor u(buffer2[i])).toByte()
        }
        for (i in 0 until 210) {
            keyOut[i % 16] = (u(keyOut[i % 16]) xor u(buffer1[i])).toByte()
        }

        // 逆方向のかき混ぜ。
        for (j in 0 until 16) {
            for (i in 0 until 16) {
                val x = keyOut[umod(i - 7, 16)]
                val y = keyOut[i % 16]
                val z = keyOut[umod(i - 37, 16)]
                val w = keyOut[umod(i - 177, 16)]
                keyOut[i] = (u(rol8(x, 1)) xor u(y) xor u(rol8(z, 6)) xor u(rol8(w, 5))).toByte()
            }
        }
    }

    /**
     * sapHash の 5 つのバッファをインプレースで撹拌する。すべての演算は Int
     * (Go 版の uint32 相当) で行い、代入のたびにバイトへ切り捨てる。C の
     * unsigned char 代入と一致する。
     */
    private fun garble(buffer0: ByteArray, buffer1: ByteArray, buffer2: ByteArray, buffer3: ByteArray, buffer4: ByteArray) {
        var tmp = 0
        var tmp2 = 0
        var tmp3 = 0
        var A = 0
        var B = 0
        var C = 0
        var D = 0
        var E = 0
        var M = 0
        var J = 0
        var G = 0
        var F = 0
        var H = 0
        var K = 0
        var R = 0
        var S = 0
        var T = 0
        var U = 0
        var V = 0
        var W = 0
        var X = 0
        var Y = 0
        var Z = 0

        buffer2[12] = (0x14 + (((u(buffer1[64]) and 92) or ((u(buffer1[99]) / 3) and 35)) and u(buffer4[rol8x(buffer4[u(buffer1[206]) % 21], 4) % 21]))).toByte()

        buffer1[4] = ((u(buffer1[99]) / 5) * (u(buffer1[99]) / 5) * 2).toByte()

        buffer2[34] = 0xb8.toByte()

        buffer1[153] = (u(buffer1[153]) xor (u(buffer2[u(buffer1[203]) % 35]) * u(buffer2[u(buffer1[203]) % 35]) * u(buffer1[190]))).toByte()

        buffer0[3] = (u(buffer0[3]) - (((u(buffer4[u(buffer1[205]) % 21]) ushr 1) and 80) or 0xe6440)).toByte()

        buffer0[16] = 0x93.toByte()
        buffer0[13] = 0x62.toByte()

        buffer1[33] = (u(buffer1[33]) - (u(buffer4[u(buffer1[36]) % 21]) and 0xf6)).toByte()

        tmp2 = u(buffer2[u(buffer1[67]) % 35])
        buffer2[12] = 0x07.toByte()

        tmp = u(buffer0[u(buffer1[181]) % 20])
        buffer1[2] = (u(buffer1[2]) - 3136).toByte()

        buffer0[19] = buffer4[u(buffer1[58]) % 21]

        buffer3[0] = (92 - u(buffer2[u(buffer1[32]) % 35])).toByte()

        buffer3[4] = (u(buffer2[u(buffer1[15]) % 35]) + 0x9e).toByte()

        buffer1[34] = (u(buffer1[34]) + (u(buffer4[((u(buffer2[u(buffer1[15]) % 35]) + 0x9e) and 0xFF) % 21]) / 5)).toByte()

        buffer0[19] = (u(buffer0[19]) + 0xffffffe6.toInt() - ((u(buffer0[u(buffer3[4]) % 20]) ushr 1) and 102)).toByte()

        run {
            val v = u(buffer4[u(buffer1[190]) % 21])
            val shift = (7 - (v - 1)) and 7 // C: (7 - (buffer4[...]-1) & 7)、'-' が '&' より強い
            val t = 3 * (((u(buffer1[72]) ushr (v and 7)) xor (u(buffer1[72]) shl shift)) - (3 * u(buffer4[u(buffer1[126]) % 21])))
            buffer1[15] = (t xor u(buffer1[15])).toByte()
        }

        buffer0[15] = (u(buffer0[15]) xor (u(buffer2[u(buffer1[181]) % 35]) * u(buffer2[u(buffer1[181]) % 35]) * u(buffer2[u(buffer1[181]) % 35]))).toByte()

        buffer2[4] = (u(buffer2[4]) xor (u(buffer1[202]) / 3)).toByte()

        A = 92 - u(buffer0[u(buffer3[0]) % 20])
        E = (A and 0xc6) or (u(buffer1[105]).inv() and 0xc6) or (A and u(buffer1[105]).inv())
        buffer2[1] = (u(buffer2[1]) + (E * E * E)).toByte()

        buffer0[19] = (u(buffer0[19]) xor (((224 or (u(buffer4[u(buffer1[92]) % 21]) and 27)) * u(buffer2[u(buffer1[41]) % 35])) / 3)).toByte()

        buffer1[140] = (u(buffer1[140]) + weirdRor8(92.toByte(), u(buffer1[5]) and 7)).toByte()

        buffer2[12] = (u(buffer2[12]) + ((((u(buffer1[4]).inv() xor u(buffer2[u(buffer1[12]) % 35])) or u(buffer1[182])) and 192) or ((u(buffer1[4]).inv() xor u(buffer2[u(buffer1[12]) % 35])) and u(buffer1[182])))).toByte()

        buffer1[36] = (u(buffer1[36]) + 125).toByte()

        run {
            val p = (74 and u(buffer1[138])) or ((74 or u(buffer1[138])) and u(buffer0[15]))
            val q = u(buffer0[u(buffer1[43]) % 20])
            buffer1[124] = rol8(((p and q) or ((p or q) and 95)).toByte(), 4)
        }

        buffer3[8] = (((((u(buffer0[u(buffer3[4]) % 20]) and 95)) and ((u(buffer4[u(buffer1[68]) % 21]) and 46) shl 1)) or 16) xor 92).toByte()

        A = u(buffer1[177]) + u(buffer4[u(buffer1[79]) % 21])
        D = (((A ushr 1) or ((3 * u(buffer1[148])) / 5)) and u(buffer2[1])) or ((A ushr 1) and ((3 * u(buffer1[148])) / 5))
        buffer3[12] = (-(34 + D)).toByte()

        A = 8 - (u(buffer2[22]) and 7)
        B = u(buffer1[33]) ushr (A and 7)
        C = u(buffer1[33]) shl (u(buffer2[22]) and 7)
        buffer2[16] = (u(buffer2[16]) + (((u(buffer2[u(buffer3[0]) % 35]) and 159) or u(buffer0[u(buffer3[4]) % 20]) or 8) - ((B xor C) or 128))).toByte()

        buffer0[14] = (u(buffer0[14]) xor u(buffer2[u(buffer3[12]) % 35])).toByte()

        A = weirdRol8(buffer4[u(buffer0[u(buffer1[201]) % 20]) % 21], (u(buffer2[u(buffer1[112]) % 35]) shl 1) and 7)
        D = (u(buffer0[u(buffer1[208]) % 20]) and 131) or (u(buffer0[u(buffer1[164]) % 20]) and 124)
        buffer1[19] = (u(buffer1[19]) + ((A and (D / 5)) or ((A or (D / 5)) and 37))).toByte()

        buffer2[8] = weirdRor8(140.toByte(), ((u(buffer4[u(buffer1[45]) % 21]) + 92) * (u(buffer4[u(buffer1[45]) % 21]) + 92)) and 7).toByte()

        buffer1[190] = 56.toByte()

        buffer2[8] = (u(buffer2[8]) xor u(buffer3[0])).toByte()

        buffer1[53] = ((u(buffer0[u(buffer1[83]) % 20]) or 204) / 5).inv().toByte()

        buffer0[13] = (u(buffer0[13]) + u(buffer0[u(buffer1[41]) % 20])).toByte()

        buffer0[10] = (((u(buffer2[u(buffer3[0]) % 35]) and u(buffer1[2])) or ((u(buffer2[u(buffer3[0]) % 35]) or u(buffer1[2])) and u(buffer3[12]))) / 15).toByte()

        A = (((56 or (u(buffer4[u(buffer1[2]) % 21]) and 68)) or u(buffer2[u(buffer3[8]) % 35])) and 42) or (((u(buffer4[u(buffer1[2]) % 21]) and 68) or 56) and u(buffer2[u(buffer3[8]) % 35]))
        buffer3[16] = ((A * A) + 110).toByte()

        buffer3[20] = (202 - u(buffer3[16])).toByte()

        buffer3[24] = buffer1[151]

        buffer2[13] = (u(buffer2[13]) xor u(buffer4[u(buffer3[0]) % 21])).toByte()

        B = ((u(buffer2[u(buffer1[179]) % 35]) - 38) and 177) or (u(buffer3[12]) and 177)
        C = ((u(buffer2[u(buffer1[179]) % 35]) - 38)) and u(buffer3[12])
        buffer3[28] = (30 + ((B or C) * (B or C))).toByte()

        buffer3[32] = (u(buffer3[28]) + 62).toByte()

        A = ((u(buffer3[20]) + (u(buffer3[0]) and 74)) or (u(buffer4[u(buffer3[0]) % 21]).inv())) and 121
        B = ((u(buffer3[20]) + (u(buffer3[0]) and 74))) and (u(buffer4[u(buffer3[0]) % 21]).inv())
        tmp3 = (A or B)
        C = ((((A or B) xor 0xffffffa6.toInt()) or u(buffer3[0])) and 4) or (((A or B) xor 0xffffffa6.toInt()) and u(buffer3[0]))
        buffer1[47] = ((u(buffer2[u(buffer1[89]) % 35]) + C) xor u(buffer1[47])).toByte()

        buffer3[36] = (((u(rol8(((tmp and 179) + 68).toByte(), 2)) and u(buffer0[3])) or (tmp2 and u(buffer0[3]).inv())) - 15).toByte()

        buffer1[123] = (u(buffer1[123]) xor 221).toByte()

        A = (udiv(u(buffer4[u(buffer3[0]) % 21]), 3)) - u(buffer2[u(buffer3[4]) % 35])
        C = (((u(buffer3[0]) and 163) + 92) and 246) or (u(buffer3[0]) and 92)
        E = ((C or u(buffer3[24])) and 54) or (C and u(buffer3[24]))
        buffer3[40] = (A - E).toByte()

        buffer3[44] = (tmp3 xor 81 xor (((u(buffer3[0]) ushr 1) and 101) + 26)).toByte()

        buffer3[48] = (u(buffer2[u(buffer3[4]) % 35]) and 27).toByte()
        buffer3[52] = 27.toByte()
        buffer3[56] = 199.toByte()

        run {
            val a = ((u(buffer3[40]) or u(buffer3[24])) and 177) or (u(buffer3[40]) and u(buffer3[24]))
            val bb = ((u(buffer4[u(buffer3[0]) % 20]) and 177) or 176) or (u(buffer4[u(buffer3[0]) % 21]) and 3.inv())
            val lhs = a and bb
            val c1 = ((u(buffer3[40]) and u(buffer3[24])) or ((u(buffer3[40]) or u(buffer3[24])) and 177)) and 199
            val c2 = (((u(buffer4[u(buffer3[0]) % 21]) and 1) + 176) or (u(buffer4[u(buffer3[0]) % 21]) and 3.inv())) and u(buffer3[56])
            val mid = lhs or (c1 or c2)
            buffer3[64] = (u(buffer3[4]) + ((mid and u(buffer3[52]).inv()) or u(buffer3[48]))).toByte()
        }

        buffer2[33] = (u(buffer2[33]) xor u(buffer1[26])).toByte()

        buffer1[106] = (u(buffer1[106]) xor u(buffer3[20]) xor 133).toByte()

        buffer2[30] = (((u(buffer3[64]) / 3) - (275 or (u(buffer3[0]) and 247))) xor u(buffer0[u(buffer1[122]) % 20])).toByte()

        buffer1[22] = ((u(buffer2[u(buffer1[90]) % 35]) and 95) or 68).toByte()

        A = (u(buffer4[u(buffer3[36]) % 21]) and 184) or (u(buffer2[u(buffer3[44]) % 35]) and 184.inv())
        buffer2[18] = (u(buffer2[18]) + ((A * A * A) ushr 1)).toByte()

        buffer2[5] = (u(buffer2[5]) - u(buffer4[u(buffer1[92]) % 21])).toByte()

        A = (((u(buffer1[41]) and 24.inv()) or (u(buffer2[u(buffer1[183]) % 35]) and 24)) and (u(buffer3[16]) + 53)) or (u(buffer3[20]) and u(buffer2[u(buffer3[20]) % 35]))
        B = (u(buffer1[17]) and u(buffer3[44]).inv()) or (u(buffer0[u(buffer1[59]) % 20]) and u(buffer3[44]))
        buffer2[18] = (u(buffer2[18]) xor (A * B)).toByte()

        A = weirdRor8(buffer1[11], u(buffer2[u(buffer1[28]) % 35]) and 7) and 7
        B = (((u(buffer0[u(buffer1[93]) % 20]) and u(buffer0[14]).inv()) or (u(buffer0[14]) and 150)) and 28.inv()) or (u(buffer1[7]) and 28)
        buffer2[22] = (((((B or weirdRol8(buffer2[u(buffer3[0]) % 35], A)) and u(buffer2[33])) or (B and weirdRol8(buffer2[u(buffer3[0]) % 35], A))) + 74) and 0xFF).toByte()

        A = u(buffer4[(u(buffer0[u(buffer1[39]) % 20]) xor 217) % 21])
        buffer0[15] = (u(buffer0[15]) - (((((u(buffer3[20]) or u(buffer3[0])) and 214) or (u(buffer3[20]) and u(buffer3[0]))) and A) or ((((u(buffer3[20]) or u(buffer3[0])) and 214) or (u(buffer3[20]) and u(buffer3[0])) or A) and u(buffer3[32])))).toByte()

        B = (((u(buffer2[u(buffer1[57]) % 35]) and u(buffer0[u(buffer3[64]) % 20])) or ((u(buffer0[u(buffer3[64]) % 20]) or u(buffer2[u(buffer1[57]) % 35])) and 95) or (u(buffer3[64]) and 45) or 82) and 32)
        C = ((u(buffer2[u(buffer1[57]) % 35]) and u(buffer0[u(buffer3[64]) % 20])) or ((u(buffer2[u(buffer1[57]) % 35]) or u(buffer0[u(buffer3[64]) % 20])) and 95)) and ((u(buffer3[64]) and 45) or 82)
        D = (((u(buffer3[0]) / 3) - (u(buffer3[64]) or u(buffer1[22]))) xor (u(buffer3[28]) + 62) xor (B or C))
        T = u(buffer0[(D and 0xFF) % 20])

        buffer3[68] = ((u(buffer0[u(buffer1[99]) % 20]) * u(buffer0[u(buffer1[99]) % 20]) * u(buffer0[u(buffer1[99]) % 20]) * u(buffer0[u(buffer1[99]) % 20])) or u(buffer2[u(buffer3[64]) % 35])).toByte()

        U = u(buffer0[u(buffer1[50]) % 20])
        W = u(buffer2[u(buffer1[138]) % 35])
        X = u(buffer4[u(buffer1[39]) % 21])
        Y = u(buffer0[u(buffer1[4]) % 20])
        Z = u(buffer4[u(buffer1[202]) % 21])
        V = u(buffer0[u(buffer1[151]) % 20])
        S = u(buffer2[u(buffer1[14]) % 35])
        R = u(buffer0[u(buffer1[145]) % 20])

        A = (u(buffer2[u(buffer3[68]) % 35]) and u(buffer0[u(buffer1[209]) % 20])) or ((u(buffer2[u(buffer3[68]) % 35]) or u(buffer0[u(buffer1[209]) % 20])) and 24)
        B = weirdRol8(buffer4[u(buffer1[127]) % 21], u(buffer2[u(buffer3[68]) % 35]) and 7)
        C = (A and u(buffer0[10])) or (B and u(buffer0[10]).inv())
        D = 7 xor (u(buffer4[u(buffer2[u(buffer3[36]) % 35]) % 21]) shl 1)
        buffer3[72] = ((C and 71) or (D and 71.inv())).toByte()

        buffer2[2] = (u(buffer2[2]) + ((((u(buffer0[u(buffer3[20]) % 20]) shl 1) and 159) or (u(buffer4[u(buffer1[190]) % 21]) and 159.inv())) and ((((u(buffer4[u(buffer3[64]) % 21]) and 110) or (u(buffer0[u(buffer1[25]) % 20]) and 110.inv())) and 150.inv()) or (u(buffer1[25]) and 150)))).toByte()

        buffer2[14] = (u(buffer2[14]) - (((u(buffer2[u(buffer3[20]) % 35]) and (u(buffer3[72]) xor u(buffer2[u(buffer1[100]) % 35]))) and 34.inv()) or (u(buffer1[97]) and 34))).toByte()

        buffer0[17] = 115.toByte()

        run {
            val p = ((u(buffer4[u(buffer1[17]) % 21]) or u(buffer0[u(buffer3[20]) % 20])) and u(buffer3[72])) or (u(buffer4[u(buffer1[17]) % 21]) and u(buffer0[u(buffer3[20]) % 20]))
            val q = u(buffer1[50]) / 3
            buffer1[23] = (u(buffer1[23]) xor (((p and q) or ((p or q) and 246)) shl 1)).toByte()
        }

        buffer0[13] = ((((((u(buffer0[u(buffer3[40]) % 20]) or u(buffer1[10])) and 82) or (u(buffer0[u(buffer3[40]) % 20]) and u(buffer1[10]))) and 209) or ((u(buffer0[u(buffer1[39]) % 20]) shl 1) and 46)) ushr 1).toByte()

        buffer2[33] = (u(buffer2[33]) - (u(buffer1[113]) and 9)).toByte()

        buffer2[28] = (u(buffer2[28]) - ((((2 or (u(buffer1[110]) and 222)) ushr 1) and 223.inv()) or (u(buffer3[20]) and 223))).toByte()

        J = weirdRol8((V or Z).toByte(), U and 7)
        A = (u(buffer2[16]) and T) or (W and u(buffer2[16]).inv())
        B = (u(buffer1[33]) and 17) or (X and 17.inv())
        E = ((Y or ((A + B) / 5)) and 147) or (Y and ((A + B) / 5))
        M = (u(buffer3[40]) and u(buffer4[((u(buffer3[8]) + J + E) and 0xFF) % 21])) or ((u(buffer3[40]) or u(buffer4[((u(buffer3[8]) + J + E) and 0xFF) % 21])) and u(buffer2[23]))

        buffer0[15] = ((((u(buffer4[u(buffer3[20]) % 21]) - 48) and u(buffer1[184]).inv()) or ((u(buffer4[u(buffer3[20]) % 21]) - 48) and 189) or (189 and u(buffer1[184]).inv())) and (M * M * M)).toByte()

        buffer2[22] = (u(buffer2[22]) + u(buffer1[183])).toByte()

        buffer3[76] = ((3 * u(buffer4[u(buffer1[1]) % 21])) xor u(buffer3[0])).toByte()

        A = u(buffer2[((u(buffer3[8]) + (J + E)) and 0xFF) % 35])
        F = (((u(buffer4[u(buffer1[178]) % 21]) and A) or ((u(buffer4[u(buffer1[178]) % 21]) or A) and 209)) * u(buffer0[u(buffer1[13]) % 20])) * (u(buffer4[u(buffer1[26]) % 21]) ushr 1)
        G = (F + 0x733ffff9) * 198 - (((F + 0x733ffff9) * 396 + 212) and 212) + 85
        buffer3[80] = (u(buffer3[36]) + (G xor 148) + ((G xor 107) shl 1) - 127).toByte()

        buffer3[84] = ((u(buffer2[u(buffer3[64]) % 35]) and 245) or (u(buffer2[u(buffer3[20]) % 35]) and 10)).toByte()

        A = u(buffer0[u(buffer3[68]) % 20]) or 81
        buffer2[18] = (u(buffer2[18]) - (((A * A * A) and u(buffer0[15]).inv()) or ((u(buffer3[80]) / 15) and u(buffer0[15])))).toByte()

        buffer3[88] = (u(buffer3[8]) + J + E - u(buffer0[u(buffer1[160]) % 20]) + (u(buffer4[u(buffer0[((u(buffer3[8]) + J + E) and 255) % 20]) % 21]) / 3)).toByte()

        B = ((R xor u(buffer3[72])) and 198.inv()) or ((S * S) and 198)
        F = (u(buffer4[u(buffer1[69]) % 21]) and u(buffer1[172])) or ((u(buffer4[u(buffer1[69]) % 21]) or u(buffer1[172])) and ((u(buffer3[12]) - B) + 77))
        buffer0[16] = (147 - ((u(buffer3[72]) and ((F and 251) or 1)) or (((F and 250) or u(buffer3[72])) and 198))).toByte()

        C = (u(buffer4[u(buffer1[168]) % 21]) and u(buffer0[u(buffer1[29]) % 20]) and 7) or ((u(buffer4[u(buffer1[168]) % 21]) or u(buffer0[u(buffer1[29]) % 20])) and 6)
        F = (u(buffer4[u(buffer1[155]) % 21]) and u(buffer1[105])) or ((u(buffer4[u(buffer1[155]) % 21]) or u(buffer1[105])) and 141)
        buffer0[3] = (u(buffer0[3]) - u(buffer4[weirdRol32(F.toByte(), C) % 21])).toByte()

        buffer1[5] = (weirdRor8(buffer0[12], (u(buffer0[u(buffer1[61]) % 20]) / 5) and 7) xor udiv(u(buffer2[u(buffer3[84]) % 35]).inv(), 5)).toByte()

        buffer1[198] = (u(buffer1[198]) + u(buffer1[3])).toByte()

        A = (162 or u(buffer2[u(buffer3[64]) % 35]))
        buffer1[164] = (u(buffer1[164]) + ((A * A) / 5)).toByte()

        G = weirdRor8(139.toByte(), u(buffer3[80]) and 7)
        C = ((u(buffer4[u(buffer3[64]) % 21]) * u(buffer4[u(buffer3[64]) % 21]) * u(buffer4[u(buffer3[64]) % 21])) and 95) or (u(buffer0[u(buffer3[40]) % 20]) and 95.inv())
        buffer3[92] = ((G and 12) or (u(buffer0[u(buffer3[20]) % 20]) and 12) or (G and u(buffer0[u(buffer3[20]) % 20])) or C).toByte()

        buffer2[12] = (u(buffer2[12]) + (((u(buffer1[103]) and 32) or (u(buffer3[92]) and (u(buffer1[103]) or 60)) or 16) / 3)).toByte()

        buffer3[96] = buffer1[143]
        buffer3[100] = 27.toByte()

        buffer3[104] = ((((u(buffer3[40]) and u(buffer2[8]).inv()) or (u(buffer1[35]) and u(buffer2[8]))) and u(buffer3[64])) xor 119).toByte()

        buffer3[108] = (238 and ((((u(buffer3[40]) and u(buffer2[8]).inv()) or (u(buffer1[35]) and u(buffer2[8]))) and u(buffer3[64])) shl 1)).toByte()

        buffer3[112] = ((u(buffer3[64]).inv() and (u(buffer3[84]) / 3)) xor 49).toByte()

        buffer3[116] = (98 and ((u(buffer3[64]).inv() and (u(buffer3[84]) / 3)) shl 1)).toByte()

        A = (u(buffer1[35]) and u(buffer2[8])) or (u(buffer3[40]) and u(buffer2[8]).inv())
        B = (A and u(buffer3[64])) or (((u(buffer3[84]) / 3)) and u(buffer3[64]).inv())
        buffer1[143] = (u(buffer3[96]) - ((B and (86 + ((u(buffer1[172]) and 64) ushr 1))) or (((((u(buffer1[172]) and 65) ushr 1) xor 86) or ((u(buffer3[64]).inv() and (u(buffer3[84]) / 3)) or (((u(buffer3[40]) and u(buffer2[8]).inv()) or (u(buffer1[35]) and u(buffer2[8]))) and u(buffer3[64])))) and u(buffer3[100])))).toByte()

        buffer2[29] = 162.toByte()

        A = (((u(buffer4[u(buffer3[88]) % 21]) and 160) or (u(buffer0[u(buffer1[125]) % 20]) and 95)) ushr 1)
        B = u(buffer2[u(buffer1[149]) % 35]) xor (u(buffer1[43]) * u(buffer1[43]))
        buffer0[15] = (u(buffer0[15]) + ((B and A) or ((A or B) and 115))).toByte()

        buffer3[120] = (u(buffer3[64]) - u(buffer0[u(buffer3[40]) % 20])).toByte()

        buffer1[95] = buffer4[u(buffer3[20]) % 21]

        A = weirdRor8(buffer2[u(buffer3[80]) % 35], (u(buffer2[u(buffer1[17]) % 35]) * u(buffer2[u(buffer1[17]) % 35]) * u(buffer2[u(buffer1[17]) % 35])) and 7)
        buffer0[7] = (u(buffer0[7]) - (A * A)).toByte()

        buffer2[8] = (u(buffer2[8]) - u(buffer1[184]) + (u(buffer4[u(buffer1[202]) % 21]) * u(buffer4[u(buffer1[202]) % 21]) * u(buffer4[u(buffer1[202]) % 21]))).toByte()

        buffer0[16] = ((u(buffer2[u(buffer1[102]) % 35]) shl 1) and 132).toByte()

        buffer3[124] = ((u(buffer4[u(buffer3[40]) % 21]) ushr 1) xor u(buffer3[68])).toByte()

        buffer0[7] = (u(buffer0[7]) - (u(buffer0[u(buffer1[191]) % 20]) - (((u(buffer4[u(buffer1[80]) % 21]) shl 1) and 177.inv()) or (u(buffer4[u(buffer4[u(buffer3[88]) % 21]) % 21]) and 177)))).toByte()

        buffer0[6] = buffer0[u(buffer1[119]) % 20]

        A = (u(buffer4[u(buffer1[190]) % 21]) and 209.inv()) or (u(buffer1[118]) and 209)
        B = u(buffer0[u(buffer3[120]) % 20]) * u(buffer0[u(buffer3[120]) % 20])
        buffer0[12] = ((u(buffer0[u(buffer3[84]) % 20]) xor (u(buffer2[u(buffer1[71]) % 35]) + u(buffer2[u(buffer1[15]) % 35]))) and ((A and B) or ((A or B) and 27))).toByte()

        B = (u(buffer1[32]) and u(buffer2[u(buffer3[88]) % 35])) or ((u(buffer1[32]) or u(buffer2[u(buffer3[88]) % 35])) and 23)
        D = (((u(buffer4[u(buffer1[57]) % 21]) * 231) and 169) or (B and 86))
        F = (((u(buffer0[u(buffer1[82]) % 20]) and 29.inv()) or (u(buffer4[u(buffer3[124]) % 21]) and 29)) and 190) or (u(buffer4[udiv(D, 5) % 21]) and 190.inv())
        H = u(buffer0[u(buffer3[40]) % 20]) * u(buffer0[u(buffer3[40]) % 20]) * u(buffer0[u(buffer3[40]) % 20])
        K = (H and u(buffer1[82])) or (H and 92) or (u(buffer1[82]) and 92)
        buffer3[128] = (((F and K) or ((F or K) and 192)) xor udiv(D, 5)).toByte()

        buffer2[25] = (u(buffer2[25]) xor (((u(buffer0[u(buffer3[120]) % 20]) shl 1) * u(buffer1[5])) - (weirdRol8(buffer3[76], u(buffer4[u(buffer3[124]) % 21]) and 7) and (u(buffer3[20]) + 110)))).toByte()
    }
}
