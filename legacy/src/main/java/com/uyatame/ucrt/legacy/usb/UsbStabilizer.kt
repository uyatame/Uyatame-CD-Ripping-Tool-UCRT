package com.uyatame.ucrt.legacy.usb

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.PowerManager

/**
 * USB DAC への直接出力が、端末の省電力のために途切れるのを防ぐ(本家の UsbStabilizer と同じ考え)。
 * 直接出力している間だけ、Android の音声の経路に無音を流し、CPU が眠らないようにし、
 * 画面の描画のタイミングを受け取り続ける(Android 4.1 から)
 */
object UsbStabilizer {
    @Volatile var enabled = true

    private val lock = Any()
    private var running = false
    private var silence: Thread? = null
    private var track: AudioTrack? = null
    private var wake: PowerManager.WakeLock? = null

    fun start(ctx: Context) {
        synchronized(lock) {
            if (!enabled || running) return
            running = true
            val app = ctx.applicationContext
            wake = runCatching {
                (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ucrt:usb-dac")
                    .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
            }.getOrNull()
            silence = Thread({ playSilence() }, "usb-dac-keepalive").apply {
                isDaemon = true
                start()
            }
            if (Build.VERSION.SDK_INT >= 16) Api16Frames.start { running }
            com.uyatame.ucrt.legacy.AppLog.log("usb dac: stabilizer on (silent track, wake lock, frame clock)")
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            runCatching { track?.pause(); track?.flush() }
            silence?.interrupt()
            silence = null
            runCatching { wake?.takeIf { it.isHeld }?.release() }
            wake = null
            if (Build.VERSION.SDK_INT >= 16) Api16Frames.stop()
        }
    }

    private fun playSilence() {
        val rate = 44100
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return
        @Suppress("DEPRECATION")
        val t = runCatching {
            AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, min * 2, AudioTrack.MODE_STREAM)
        }.getOrNull() ?: return
        synchronized(lock) {
            if (!running) { t.release(); return }
            track = t
        }
        val zeros = ByteArray(min)
        try {
            t.play()
            while (running && !Thread.currentThread().isInterrupted) {
                if (t.write(zeros, 0, zeros.size) < 0) break
            }
        } catch (_: Exception) {
        } finally {
            synchronized(lock) { if (track === t) track = null }
            runCatching { t.stop() }
            t.release()
        }
    }
}

/** 画面の描画のタイミング(1 フレームごと)を受け取り続ける(Android 4.1 から) */
internal object Api16Frames {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var alive: () -> Boolean = { false }
    private val frame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (alive()) android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start(running: () -> Boolean) {
        alive = running
        main.post { if (alive()) android.view.Choreographer.getInstance().postFrameCallback(frame) }
    }

    fun stop() {
        main.post { android.view.Choreographer.getInstance().removeFrameCallback(frame) }
    }
}
