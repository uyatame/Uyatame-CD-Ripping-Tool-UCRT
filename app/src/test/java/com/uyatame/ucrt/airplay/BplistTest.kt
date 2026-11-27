package com.uyatame.ucrt.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bplist の往復テスト (書き出したものを読み戻して一致するか)。 */
class BplistTest {

    @Test
    fun roundTripInfoShape() {
        val info = linkedMapOf<String, Any?>(
            "deviceID" to "aa:bb:cc:dd:ee:ff",
            "pk" to ByteArray(32) { it.toByte() },
            "features" to (0x5A7FFFF7L or (0x1EL shl 32)),
            "vv" to 2L,
            "keepAliveLowPower" to true,
            "initialVolume" to 0.0,
            "refreshRate" to 1.0 / 60.0,
            "audioLatencies" to listOf(
                linkedMapOf<String, Any?>("type" to 100L, "outputLatencyMicros" to false),
            ),
            "audioFormats" to listOf(
                linkedMapOf<String, Any?>("type" to 101L, "audioInputFormats" to 0x3fffffcL),
            ),
        )
        val bytes = Bplist.write(info)
        assertEquals("bplist00", String(bytes, 0, 8, Charsets.US_ASCII))

        val back = Bplist.read(bytes) as Map<*, *>
        assertEquals("aa:bb:cc:dd:ee:ff", back["deviceID"])
        assertTrue((back["pk"] as ByteArray).contentEquals(ByteArray(32) { it.toByte() }))
        assertEquals(0x5A7FFFF7L or (0x1EL shl 32), back["features"])
        assertEquals(2L, back["vv"])
        assertEquals(true, back["keepAliveLowPower"])
        assertEquals(0.0, back["initialVolume"])
        assertEquals(1.0 / 60.0, back["refreshRate"])
        val lat = (back["audioLatencies"] as List<*>)[0] as Map<*, *>
        assertEquals(100L, lat["type"])
        assertEquals(false, lat["outputLatencyMicros"])
        val fmt = (back["audioFormats"] as List<*>)[0] as Map<*, *>
        assertEquals(0x3fffffcL, fmt["audioInputFormats"])
    }

    @Test
    fun roundTripSetupShape() {
        // SETUP 要求の形: data (ekey/eiv) と streams のリストが往復すること。
        val req = linkedMapOf<String, Any?>(
            "ekey" to ByteArray(72) { (it * 3).toByte() },
            "eiv" to ByteArray(16) { (it + 9).toByte() },
            "streams" to listOf(
                linkedMapOf<String, Any?>("type" to 96L, "ct" to 2L, "spf" to 352L, "audioFormat" to 262144L),
            ),
        )
        val back = Bplist.read(Bplist.write(req)) as Map<*, *>
        assertTrue((back["ekey"] as ByteArray).contentEquals(ByteArray(72) { (it * 3).toByte() }))
        assertTrue((back["eiv"] as ByteArray).contentEquals(ByteArray(16) { (it + 9).toByte() }))
        val st = (back["streams"] as List<*>)[0] as Map<*, *>
        assertEquals(96L, st["type"])
        assertEquals(352L, st["spf"])
    }

    @Test
    fun longStringAndDataLengths() {
        // 15 以上の長さ (拡張長形式) の往復。
        val m = mapOf<String, Any?>(
            "s" to "x".repeat(300),
            "d" to ByteArray(100) { 0x55 },
            "list" to (0 until 40).map { it.toLong() },
        )
        val back = Bplist.read(Bplist.write(m)) as Map<*, *>
        assertEquals("x".repeat(300), back["s"])
        assertEquals(100, (back["d"] as ByteArray).size)
        assertEquals(40, (back["list"] as List<*>).size)
        assertEquals(39L, (back["list"] as List<*>)[39])
    }
}
