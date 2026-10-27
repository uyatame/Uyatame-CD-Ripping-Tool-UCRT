package com.uyatame.ucrt.rip

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface AudioSink {
    fun write(buf: ByteArray, off: Int, len: Int)
    fun finish()
    fun abort() {}
}

/**
 * 16bit/44.1kHz/2ch PCM を WAV として書き出す。総バイト数は事前に判明している。
 * meta は曲情報のチャンク(WavTags.metaChunks)。fmt と data の間に入れる
 */
class WavSink(private val out: OutputStream, dataBytes: Long, meta: ByteArray = ByteArray(0)) : AudioSink {
    init {
        val b = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + meta.size + dataBytes).toInt())
        b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray()); b.putInt(16)
        b.putShort(1); b.putShort(2); b.putInt(44100); b.putInt(176400)
        b.putShort(4); b.putShort(16)
        out.write(b.array())
        out.write(meta)
        val d = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        d.put("data".toByteArray()); d.putInt(dataBytes.toInt())
        out.write(d.array())
    }

    override fun write(buf: ByteArray, off: Int, len: Int) = out.write(buf, off, len)
    override fun finish() = out.flush()
}
