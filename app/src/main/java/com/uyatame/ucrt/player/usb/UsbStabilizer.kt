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
 * - CPU が眠らないようにする(画面が消えていても。設定がオフでもする。自前の転送は Android が起こしておいてくれないため)
 * - (設定がオンのとき)Android の音声の経路に、無音を流し続ける(音声の処理を動かしておく)
 * - (設定がオンのとき)画面の描画のタイミング(1 フレームごと)を受け取り続ける(画面を操作しているときと同じ状態にする)
 * (2026-10-29 に一度削除したが、音切れが再発したためユーザーの指示で戻した)
 */
object UsbStabilizer {
    /** 設定でオンにしているか(音切れ対策の無音・描画のタイミング。CPU を起こしておくのは、オフでもする) */
    @Volatile var enabled = true

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var running = false
    private var silence: Thread? = null
    private var track: AudioTrack? = null
    private var wake: PowerManager.WakeLock? = null
    private var app: Context? = null
    /** 無音を Android の音声の経路に最後に流していた時刻(SystemClock.elapsedRealtime。流していなければ 0) */
    @Volatile var lastPlayedAt = 0L
        private set

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start(ctx: Context) {
        synchronized(lock) {
            if (running) return
            running = true
            val app = ctx.applicationContext
            this.app = app
            wake = runCatching {
                androidx.core.content.ContextCompat.getSystemService(app, PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ucrt:usb-dac")
                    ?.apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
            }.getOrNull()
            if (!enabled) {
                AudioEngine.log("usb dac: wake lock on")
                return
            }
            silence = Thread({ playSilence() }, "usb-dac-keepalive").apply {
                isDaemon = true
                start()
            }
            main.post { if (running) Choreographer.getInstance().postFrameCallback(frame) }
            AudioEngine.log("usb dac: stabilizer on (silent track, wake lock, frame clock)")
        }
    }

    fun stop() = stopAndAwait(0L)

    /** 止める。timeoutMs > 0 なら、無音を流すスレッドが AudioTrack を手放すまで待つ(DAC を Android に返す前) */
    fun stopAndAwait(timeoutMs: Long = 800L) {
        val worker: Thread?
        synchronized(lock) {
            if (!running) return
            running = false
            if (track != null) lastPlayedAt = android.os.SystemClock.elapsedRealtime()
            runCatching { track?.pause(); track?.flush() }
            worker = silence
            worker?.interrupt()
            silence = null
            runCatching { wake?.takeIf { it.isHeld }?.release() }
            wake = null
            main.post { Choreographer.getInstance().removeFrameCallback(frame) }
        }
        if (timeoutMs > 0 && worker != null && worker !== Thread.currentThread()) runCatching { worker.join(timeoutMs) }
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
        // 無音は端末のスピーカーへ出す(USB DAC へ向かうと、UCRT が確保して切り離した DAC のカードを Android が開こうとして、
        // 直接出力の妨げになることがあるため。無音なので音は出ない)
        app?.let { ctx ->
            runCatching {
                val am = androidx.core.content.ContextCompat.getSystemService(ctx, android.media.AudioManager::class.java)
                am?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                    ?.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { t.setPreferredDevice(it) }
            }
        }
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
            lastPlayedAt = android.os.SystemClock.elapsedRealtime()
        }
    }
}
