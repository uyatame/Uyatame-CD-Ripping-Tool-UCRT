package com.uyatame.ucrt.airplay

import com.uyatame.ucrt.player.AlacDecoder
import com.uyatame.ucrt.player.RandomInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * 送信側ストリーミング素材のテスト。
 * 無圧縮 ALAC エンコードを受信側の AlacDecoder で復号して PCM が一致する
 * こと、RAOP 暗号化が受信側の decryptRaop で元に戻ることを確認する。
 */
class AirPlayStreamerTest {

    private object NoInput : RandomInput {
        override val size: Long = 0
        override fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int = -1
    }

    @Test
    fun alacEncodeMatchesDecoder() {
        val pcm = ShortArray(352 * 2) { i ->
            val frame = i / 2
            if (i % 2 == 0) (frame * 37 % 20000 - 10000).toShort()
            else (10000 - frame * 53 % 20000).toShort()
        }
        val frame = AirPlayStreamer.encodeALACUncompressed(pcm)
        assertEquals(1411, frame.size)
        assertEquals(0x20.toByte(), frame[0])

        val dec = AlacDecoder(NoInput)
        dec.openRaw(frameLength = 352, bitDepth = 16, pb = 40, mb = 10, kb = 14, channels = 2, sampleRate = 44100)
        val n = dec.decodePacket(frame, 0, frame.size)
        assertEquals(352, n)
        for (i in 0 until 352) {
            assertEquals("L[$i]", pcm[2 * i].toInt(), dec.samples[0][i])
            assertEquals("R[$i]", pcm[2 * i + 1].toInt(), dec.samples[1][i])
        }
    }

    @Test
    fun raopEncryptRoundTrip() {
        val key = ByteArray(16) { (it * 7 + 1).toByte() }
        val iv = ByteArray(16) { (it * 11 + 3).toByte() }
        val payload = ByteArray(1411) { (it * 31 % 251).toByte() }
        val enc = AirPlayStreamer.encryptRAOP(key, iv, payload)
        assertEquals(payload.size, enc.size)
        assertTrue(!enc.copyOfRange(0, 1408).contentEquals(payload.copyOfRange(0, 1408)))
        // 末尾の端数 (3 バイト) は平文のまま。
        assertArrayEquals(payload.copyOfRange(1408, 1411), enc.copyOfRange(1408, 1411))
        val back = AirPlayStream.decryptRaop(key, iv, enc)
        assertArrayEquals(payload, back)
    }
}
