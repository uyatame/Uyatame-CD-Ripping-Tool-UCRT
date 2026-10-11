package com.uyatame.ucrt.rip

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface AudioSink {
    fun write(buf: ByteArray, off: Int, len: Int)
    fun finish()
    fun abort() {}
}

/** 16bit/44.1kHz/2ch PCM を WAV として書き出す。総バイト数は事前に判明している。 */
class WavSink(private val out: OutputStream, dataBytes: Long) : AudioSink {
    init {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt())
        b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray()); b.putInt(16)
        b.putShort(1); b.putShort(2); b.putInt(44100); b.putInt(176400)
        b.putShort(4); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
        out.write(b.array())
    }

    override fun write(buf: ByteArray, off: Int, len: Int) = out.write(buf, off, len)
    override fun finish() = out.flush()
}
