package com.uyatame.ucrt.legacy.compat

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** Android 5.0 から: float(32bit)の AudioTrack。24bit の曲を 16bit に丸めずに渡す */
object Api21Track {
    /** 作れなければ null(16bit に戻す) */
    fun create(rate: Int, cfg: Int): AudioTrack? = runCatching {
        val min = AudioTrack.getMinBufferSize(rate, cfg, AudioFormat.ENCODING_PCM_FLOAT)
        if (min <= 0) return null
        val ch = if (cfg == AudioFormat.CHANNEL_OUT_MONO) 1 else 2
        val size = maxOf(min * 2, rate / 4 * ch * 4)
        @Suppress("DEPRECATION")
        val t = AudioTrack(AudioManager.STREAM_MUSIC, rate, cfg, AudioFormat.ENCODING_PCM_FLOAT, size, AudioTrack.MODE_STREAM)
        if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); null } else t
    }.getOrNull()

    fun isFloat(t: AudioTrack): Boolean = t.audioFormat == AudioFormat.ENCODING_PCM_FLOAT

    fun write(t: AudioTrack, b: FloatArray, off: Int, len: Int): Int = t.write(b, off, len, AudioTrack.WRITE_BLOCKING)
}
