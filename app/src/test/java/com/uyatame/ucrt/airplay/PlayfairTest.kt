package com.uyatame.ucrt.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Playfair 復号のゴールデンテスト。
 * 参照出力は GoAirPlay (実機で iPhone / Mac からの受信を検証済みの Go 実装) を
 * 同じダミー入力で直接叩いて作ったもの。Kotlin 移植が 1 ビットでもずれれば落ちる。
 */
class PlayfairTest {

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun header(keymsg: ByteArray) {
        val h = byteArrayOf(0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x04)
        h.copyInto(keymsg)
    }

    @Test
    fun deterministicVector() {
        val keymsg = ByteArray(164) { ((it * 7) % 251).toByte() }
        header(keymsg)
        keymsg[12] = 0x01
        val ekey = ByteArray(72) { ((it * 13 + 5) % 256).toByte() }

        val out = Playfair.decrypt(keymsg, ekey)
        assertEquals(16, out.size)
        assertEquals("97ae7e9620bf168a8337fd10f9245cfb", hex(out))
    }

    @Test
    fun allModes() {
        val expected = arrayOf(
            "4aec2ba4ff6eccce9b41e6c64702eeed",
            "f09c66e20c24a73b3e51f119477adbc5",
            "34452767ec30122a64abe7cd4f507b7e",
            "0de168e8aac187bcfcc7200bb03d657f",
        )
        val ekey = ByteArray(72) { (0xa5 xor it).toByte() }
        for (mode in 0 until 4) {
            val keymsg = ByteArray(164) { ((it * 31 + mode * 17) % 256).toByte() }
            header(keymsg)
            keymsg[12] = mode.toByte()
            val out = Playfair.decrypt(keymsg, ekey)
            assertEquals("mode $mode", expected[mode], hex(out))
        }
    }
}
