package com.uyatame.ucrt.airplay

import com.uyatame.ucrt.player.AlacDecoder
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3 (UDP 受信 + 復号 + ALAC デコード + PcmDecoder) の単体テスト。 */
class AirPlayStreamTest {

    // ---------- RAOP 復号 ----------

    @Test
    fun decryptRaopRoundTrip() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (0xA0 + it).toByte() }
        val plain = ByteArray(53) { (it * 7 % 251).toByte() }
        // 先頭 48 バイトを CBC 暗号化し、端数 5 バイトは生のまま付ける (RAOP の形)。
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val wire = cipher.doFinal(plain, 0, 48) + plain.copyOfRange(48, 53)
        val out = AirPlayStream.decryptRaop(key, iv, wire)
        assertArrayEquals(plain, out)
    }

    @Test
    fun decryptRaopShortPayloadUnchanged() {
        val key = ByteArray(16) { 1 }
        val iv = ByteArray(16) { 2 }
        val payload = byteArrayOf(9, 8, 7)
        assertArrayEquals(payload, AirPlayStream.decryptRaop(key, iv, payload))
    }

    // ---------- タイミング応答 ----------

    @Test
    fun timingResponseShape() {
        val req = ByteArray(32)
        for (i in 0 until 8) req[24 + i] = (0x11 * (i + 1)).toByte()
        val res = AirPlayStream.buildTimingResponse(req, 32)!!
        assertEquals(0x80.toByte(), res[0])
        assertEquals(0xD3.toByte(), res[1])
        assertArrayEquals(req.copyOfRange(24, 32), res.copyOfRange(8, 16))
        assertNull(AirPlayStream.buildTimingResponse(req, 16))
    }

    // ---------- ALAC 生フレーム ----------

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var cur = 0
        private var nbits = 0

        fun bits(value: Int, n: Int) {
            for (i in n - 1 downTo 0) {
                cur = (cur shl 1) or ((value ushr i) and 1)
                if (++nbits == 8) {
                    out.write(cur)
                    cur = 0
                    nbits = 0
                }
            }
        }

        fun finish(): ByteArray {
            if (nbits > 0) out.write(cur shl (8 - nbits))
            return out.toByteArray()
        }
    }

    /** 読み取りをしない RandomInput (openRaw 専用)。 */
    private object NoInput : com.uyatame.ucrt.player.RandomInput {
        override val size: Long = 0
        override fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int = -1
    }

    @Test
    fun alacRawUncompressedFrame() {
        val left = intArrayOf(1000, -2000, 3000, -4000)
        val right = intArrayOf(500, -500, 250, -250)
        val w = BitWriter()
        w.bits(1, 3) // ID_CPE
        w.bits(0, 4) // elementInstanceTag
        w.bits(0, 12) // unused
        w.bits(1, 4) // header: partial=0, bytesShifted=0, escape=1 (無圧縮)
        for (i in 0 until 4) {
            w.bits(left[i] and 0xFFFF, 16)
            w.bits(right[i] and 0xFFFF, 16)
        }
        w.bits(7, 3) // ID_END
        val frame = w.finish()

        val dec = AlacDecoder(NoInput)
        dec.openRaw(frameLength = 4, bitDepth = 16, pb = 40, mb = 10, kb = 14, channels = 2, sampleRate = 44100)
        val n = dec.decodePacket(frame, 0, frame.size)
        assertEquals(4, n)
        assertEquals(4, dec.blockSize)
        for (i in 0 until 4) {
            assertEquals("L[$i]", left[i], dec.samples[0][i])
            assertEquals("R[$i]", right[i], dec.samples[1][i])
        }
    }

    // ---------- PcmDecoder アダプタ ----------

    @Test
    fun pcmDecoderDeinterleavesAndFillsSilence() {
        val queue = AirPlayStream.FrameQueue(4)
        val dec = AirPlayPcmDecoder(queue, frameSamples = 352)
        dec.open()
        // 2 サンプル分のフレーム (L=7,-9 / R=100,-100, 16bit LE インターリーブ)
        queue.offer(byteArrayOf(7, 0, 100, 0, -9, -1, -100, -1))
        assertTrue(dec.decodeNext())
        assertEquals(2, dec.blockSize)
        assertEquals(7, dec.samples[0][0])
        assertEquals(-9, dec.samples[0][1])
        assertEquals(100, dec.samples[1][0])
        assertEquals(-100, dec.samples[1][1])
        // 空のときは無音ブロックでつなぐ
        assertTrue(dec.decodeNext())
        assertEquals(352, dec.blockSize)
        assertEquals(0, dec.samples[0][0])
        assertEquals(2L + 352, dec.frameSample + dec.blockSize)
        dec.close()
        assertTrue(!dec.decodeNext())
    }
}
