package com.uyatame.ucrt.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Playfair 送信側 (逆変換一式) の往復テスト。
 * buildKeymsg + playfairEncrypt で作った (keymsg, ekey) を受信側の
 * Playfair.decrypt に通し、狙った鍵 keyOut がそのまま戻ることを確認する。
 * decrypt は Go 実装とビット単位で一致するゴールデンテスト済みなので、
 * これが通れば送信側の逆変換 (GF(2) 混合反転・逆置換・cycle 逆変換) も
 * ビット単位で正しいことになる。
 */
class PlayfairSendTest {

    @Test
    fun roundTripAllModes() {
        val random = SecureRandom()
        repeat(3) {
            for (mode in 0 until 4) {
                val plain = ByteArray(128) { i -> ((i * 31 + mode * 17 + it * 7) % 256).toByte() }
                val keymsg = PlayfairSend.buildKeymsg(plain, mode, random)
                assertTrue(keymsg.size == 164)
                assertTrue(keymsg[4] == 0x03.toByte())
                assertTrue(keymsg[12] == mode.toByte())
                val keyOut = ByteArray(16) { i -> ((i * 13 + mode * 41 + it * 3) % 256).toByte() }
                val ekey = PlayfairSend.playfairEncrypt(keymsg, keyOut, random)
                assertTrue(ekey.size == 72)
                val back = Playfair.decrypt(keymsg, ekey)
                assertArrayEquals("mode $mode round $it", keyOut, back)
            }
        }
    }

    @Test
    fun selfTestPasses() {
        assertTrue(PlayfairSend.selfTest(SecureRandom()))
    }
}
