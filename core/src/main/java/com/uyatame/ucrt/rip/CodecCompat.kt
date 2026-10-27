package com.uyatame.ucrt.rip

import android.media.MediaCodec
import android.os.Build
import java.nio.ByteBuffer

/** MediaCodec の入出力の場所(getInputBuffer・getOutputBuffer は Android 5.0 から。それより前は一覧から取る) */
object CodecCompat {
    @Suppress("DEPRECATION")
    fun input(c: MediaCodec, i: Int): ByteBuffer =
        if (Build.VERSION.SDK_INT >= 21) Codec21.input(c, i) else c.inputBuffers[i].also { it.clear() }

    @Suppress("DEPRECATION")
    fun output(c: MediaCodec, i: Int, info: MediaCodec.BufferInfo): ByteBuffer =
        if (Build.VERSION.SDK_INT >= 21) Codec21.output(c, i) else c.outputBuffers[i].also { it.position(info.offset); it.limit(info.offset + info.size) }
}

/** Android 5.0 からの MediaCodec の API */
object Codec21 {
    fun input(c: MediaCodec, i: Int): ByteBuffer = c.getInputBuffer(i)!!
    fun output(c: MediaCodec, i: Int): ByteBuffer = c.getOutputBuffer(i)!!
}
