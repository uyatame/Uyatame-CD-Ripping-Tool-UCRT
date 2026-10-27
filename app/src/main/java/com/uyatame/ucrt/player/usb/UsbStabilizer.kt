package com.uyatame.ucrt.player.usb

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Choreographer
import com.uyatame.ucrt.player.AudioEngine

/**
 * USB DAC への直接出力が、端末の省電力のために途切れるのを防ぐ(試験的)。
 *
 * 一部の端末では、画面を操作していないと USB の等時転送が時々間に合わなくなり(パケットの EXDEV)、
 * 音がプツプツ途切れる。音質設定のシートを開いている間や、Android 側で別の音声を鳴らしている間は起きないことから、
 * 端末が「暇」と判断して速度を落とすのが原因と考えられるので、直接出力している間だけ次のことをする:
 * - Android の音声の経路に、無音を流し続ける(音声の処理を動かしておく)
 * - CPU が眠らないようにする(画面が消えていても)
 * - 画面の描画のタイミング(1 フレームごと)を受け取り続ける(画面を操作しているときと同じ状態にする)
 */
object UsbStabilizer {
    /** 設定でオンにしているか */
    @Volatile var enabled = true

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var running = false
    private var silence: Thread? = null
    private var track: AudioTrack? = null
    private var wake: PowerManager.WakeLock? = null

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start(ctx: Context) {
        synchronized(lock) {
            if (!enabled || running) return
            running = true
            val app = ctx.applicationContext
            wake = runCatching {
                androidx.core.content.ContextCompat.getSystemService(app, PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ucrt:usb-dac")
                    ?.apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
            }.getOrNull()
            silence = Thread({ playSilence() }, "usb-dac-keepalive").apply {
                isDaemon = true
                start()
            }
            main.post { if (running) Choreographer.getInstance().postFrameCallback(frame) }
            AudioEngine.log("usb dac: stabilizer on (silent track, wake lock, frame clock)")
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
            main.post { Choreographer.getInstance().removeFrameCallback(frame) }
        }
    }

    /** 無音を Android の音声の経路に流し続ける(止めるまで) */
    private fun playSilence() {
        val rate = 48000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return
        val t = runCatching {
            com.uyatame.ucrt.player.newAudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                min * 2,
            )
        }.getOrNull() ?: return
        synchronized(lock) {
            if (!running) {
                t.release()
                return
            }
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
