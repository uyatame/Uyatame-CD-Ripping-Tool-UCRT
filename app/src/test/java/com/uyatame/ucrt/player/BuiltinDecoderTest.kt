package com.uyatame.ucrt.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 内蔵デコーダー(FLAC・ALAC・WavPack・APE・AIFF)が、元の PCM とビット単位で同じ音を出すかを確かめる。
 *
 * テスト用のファイル(src/test/resources/audio)は、同じ 0.3 秒のステレオ音源(44.1 kHz)を
 * 各形式で保存したもの。元の PCM(リトルエンディアンで並べたもの)の MD5 と比べる。
 */
class BuiltinDecoderTest {
    private class FileInput(f: File) : RandomInput, AutoCloseable {
        private val r = RandomAccessFile(f, "r")
        override val size: Long = r.length()
        override fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int {
            r.seek(pos)
            return r.read(b, off, len)
        }
        override fun close() = r.close()
    }

    private fun fixture(name: String): File =
        File(requireNotNull(javaClass.getResource("/audio/$name")) { "テスト用のファイルがありません: $name" }.toURI())

    /** 全部デコードして、PCM の MD5 とサンプル数を返す */
    private fun decodeAll(d: PcmDecoder): Pair<String, Long> {
        val md5 = MessageDigest.getInstance("MD5")
        val bytes = d.bitsPerSample / 8
        val buf = ByteArray(bytes)
        var pos = 0L
        while (d.decodeNext()) {
            assertEquals("ブロックの位置", pos, d.frameSample)
            for (i in 0 until d.blockSize) for (c in 0 until d.channels) {
                val v = d.samples[c][i]
                for (k in 0 until bytes) buf[k] = (v shr (8 * k)).toByte()
                md5.update(buf)
            }
            pos += d.blockSize
        }
        return md5.digest().joinToString("") { "%02x".format(it) } to pos
    }

    private fun check(name: String, kind: String, bits: Int, expectedMd5: String) {
        FileInput(fixture(name)).use { input ->
            assertEquals("中身から形式を見分ける", kind, BuiltinCodecs.sniff(input))
            val d = BuiltinCodecs.create(kind, input)
            d.open()
            assertEquals(44100, d.sampleRate)
            assertEquals(2, d.channels)
            assertEquals(bits, d.bitsPerSample)
            val (md5, samples) = decodeAll(d)
            assertEquals("サンプル数", FRAMES, samples)
            assertEquals("デコードした PCM", expectedMd5, md5)
        }
        // 途中から再生する: 移った先のブロックに、全体をデコードしたときと同じ値が出る
        val full = FileInput(fixture(name)).use { input ->
            val d = BuiltinCodecs.create(kind, input).also { it.open() }
            val all = Array(2) { IntArray(FRAMES.toInt()) }
            while (d.decodeNext()) for (c in 0 until 2) System.arraycopy(d.samples[c], 0, all[c], d.frameSample.toInt(), d.blockSize)
            all
        }
        for (target in longArrayOf(1, FRAMES / 3, FRAMES / 2, FRAMES - 10)) {
            FileInput(fixture(name)).use { input ->
                val d = BuiltinCodecs.create(kind, input).also { it.open() }
                d.seekSample(target)
                assertTrue("移動後にデコードできる ($target)", d.decodeNext())
                assertTrue("移った先のブロックに $target が含まれる", target >= d.frameSample && target < d.frameSample + d.blockSize)
                val i = (target - d.frameSample).toInt()
                for (c in 0 until 2) assertEquals("移動後の値 ($target, ch$c)", full[c][target.toInt()], d.samples[c][i])
            }
        }
    }

    @Test fun flac16() = check("stereo16.flac", "flac", 16, MD5_16)
    @Test fun flac24() = check("stereo24.flac", "flac", 24, MD5_24)
    @Test fun alac16() = check("stereo16.m4a", "alac", 16, MD5_16)
    @Test fun alac24() = check("stereo24.m4a", "alac", 24, MD5_24)
    @Test fun wavPack16() = check("stereo16.wv", "wavpack", 16, MD5_16)
    @Test fun ape16() = check("stereo16.ape", "ape", 16, MD5_16)
    @Test fun aiff16() = check("stereo16.aiff", "aiff", 16, MD5_16)
    @Test fun aiff24() = check("stereo24.aiff", "aiff", 24, MD5_24)

    private companion object {
        /** 0.3 秒 × 44.1 kHz */
        const val FRAMES = 13230L
        /** 元の PCM(16 bit / 24 bit、リトルエンディアン)の MD5 */
        const val MD5_16 = "00d63a544b09ba7c4b6c0d4fe2b51150"
        const val MD5_24 = "679baa1536db0ab0afa6f2bce5ac114d"
    }
}
