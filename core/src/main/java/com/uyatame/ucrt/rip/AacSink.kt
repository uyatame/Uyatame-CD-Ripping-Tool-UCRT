package com.uyatame.ucrt.rip

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer

/**
 * Android OS内蔵のAACエンコーダ(MediaCodec)でm4aを書き出す。
 * コーデック本体はOS側の機能を利用し、アプリには同梱しない。
 */
class AacSink(path: String, bitrate: Int) : AudioSink {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    // 作れなかったときは、先に作ったエンコーダーを解放する(放っておくと OS のコーデックが使えるまま残る)
    private val muxer = try {
        MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    } catch (e: Exception) {
        runCatching { codec.release() }
        throw e
    }
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var started = false
    private var eos = false
    private var fed = 0L

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        try {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            runCatching { codec.release() }
            runCatching { muxer.release() }
            throw e
        }
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        var o = off
        var left = len
        while (left > 0) {
            val idx = codec.dequeueInputBuffer(5_000)
            if (idx >= 0) {
                val b = CodecCompat.input(codec, idx)
                b.clear()
                val n = minOf(b.capacity(), left)
                b.put(buf, o, n)
                codec.queueInputBuffer(idx, 0, n, fed * 1_000_000L / 176_400L, 0)
                fed += n
                o += n
                left -= n
            }
            drain(0)
        }
    }

    private fun drain(timeoutUs: Long) {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                i >= 0 -> {
                    val b = CodecCompat.output(codec, i, info)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (info.size > 0 && started) {
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        muxer.writeSampleData(track, b, info)
                    }
                    codec.releaseOutputBuffer(i, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { eos = true; return }
                }
            }
        }
    }

    override fun finish() {
        var queued = false
        while (!queued) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, fed * 1_000_000L / 176_400L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                queued = true
            } else drain(0)
        }
        var guard = 0
        while (!eos && guard++ < 2000) drain(10_000)
        release(true)
    }

    override fun abort() = release(false)

    private fun release(ok: Boolean) {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (started && ok) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }
}
