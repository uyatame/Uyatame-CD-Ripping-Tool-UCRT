package com.uyatame.ucrt.player.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Chromecast とやり取りするメッセージ(protobuf の CastMessage)の組み立てと読み取りを確かめる */
class CastChannelTest {
    @Test
    fun encodesLikeProtobuf() {
        val b = CastChannel.encode("s", "r", "n", "{}")
        // 1: version 0 / 2: "s" / 3: "r" / 4: "n" / 5: 文字列 / 6: "{}"
        val want = byteArrayOf(0x08, 0, 0x12, 1, 's'.code.toByte(), 0x1a, 1, 'r'.code.toByte(), 0x22, 1, 'n'.code.toByte(), 0x28, 0, 0x32, 2, '{'.code.toByte(), '}'.code.toByte())
        assertArrayEquals(want, b)
    }

    @Test
    fun roundTripWithLongPayload() {
        // 128 バイト以上の本文では、長さが 2 バイトの可変長整数になる
        val payload = "{\"type\":\"LOAD\",\"x\":\"" + "あ".repeat(100) + "\"}"
        val m = CastChannel.decode(CastChannel.encode(CastChannel.SENDER, "web-5", CastChannel.NS_MEDIA, payload))!!
        assertEquals(CastChannel.SENDER, m.source)
        assertEquals("web-5", m.destination)
        assertEquals(CastChannel.NS_MEDIA, m.namespace)
        assertEquals(payload, m.payload)
    }

    @Test
    fun brokenMessageIsIgnored() {
        assertNull(CastChannel.decode(byteArrayOf(0x12, 0x7F, 1, 2)))
    }
}
