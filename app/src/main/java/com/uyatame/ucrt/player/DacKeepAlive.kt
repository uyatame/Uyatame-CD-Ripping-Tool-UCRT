package com.uyatame.ucrt.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uyatame.ucrt.MainActivity
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.player.usb.UsbDac

/**
 * DAC を常に起こしておく(試験的。ほかのアプリの再生にも効く)。
 *
 * USB DAC やイヤホン端子(端末の内蔵 DAC)は、音が止まると省電力のために眠り、
 * 再生の始まりが欠けたり、プツプツ途切れたり、切り替えの雑音が出たりすることがある。
 * そこで、対象の機器がつながっている間、その機器に無音を流し続けて、音の経路を起こしたままにする。
 * Android の音声の経路を使うので、どのアプリの再生にも効く(このアプリが USB DAC を直接使っている間は、そちらの対策に任せる)。
 *
 * 端末のシステムの設定(内蔵 DAC の切り替えなど)を書き換えたり、ほかのアプリの音の出し方を変えたりはできない
 * (Android がほかのアプリに許していないため)。
 */
object DacKeepAlive {
    /** 対象: 0 = 自動(USB DAC があればそれ、なければイヤホン端子)、1 = USB DAC だけ、2 = イヤホン端子だけ */
    @Volatile var target = 0

    /** 画面表示用: 今起こしている機器の名前(待機中は空) */
    var activeDevice by mutableStateOf("")
        internal set
    var running by mutableStateOf(false)
        internal set

    fun start(ctx: Context) {
        // 出力先を選んで無音を流すので、Android 6.0 から
        if (Build.VERSION.SDK_INT < 23) return
        val i = Intent(ctx, DacKeepAliveService::class.java)
        runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
            .onFailure { AudioEngine.log("dac keep-alive: cannot start (${it.javaClass.simpleName})") }
    }

    fun stop(ctx: Context) {
        runCatching { ctx.stopService(Intent(ctx, DacKeepAliveService::class.java)) }
    }

    /** 起こしておく機器を選ぶ */
    internal fun pick(devices: Array<AudioDeviceInfo>): AudioDeviceInfo? {
        val usb = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        val wired = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
        return when (target) {
            1 -> usb
            2 -> wired
            else -> usb ?: wired
        }
    }
}

/** DAC を起こしておく前面サービス(通知を出し続ける) */
class DacKeepAliveService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var am: AudioManager
    private var device: AudioDeviceInfo? = null
    private var thread: Thread? = null
    @Volatile private var stopFlag = false
    private var wake: PowerManager.WakeLock? = null

    /** 抜き差しの知らせを止める関数 */
    private var unwatch: (() -> Unit)? = null

    /** UCRT が USB DAC を直接使い始めた・やめたときも、選び直す */
    private val poll = object : Runnable {
        override fun run() {
            update()
            main.postDelayed(this, 3000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        am = androidx.core.content.ContextCompat.getSystemService(this, AudioManager::class.java)!!
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < 23) {
            stopSelf()
            return START_NOT_STICKY
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(ID, notification())
        }.isSuccess
        if (!ok) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!DacKeepAlive.running) {
            DacKeepAlive.running = true
            unwatch = OutputDevices.watch(this, main) { update() }
            main.post(poll)
            AudioEngine.log("dac keep-alive: started")
        }
        update()
        // Android 17 からは、裏で(システムが)起こし直したサービスの音は消されるので、起こし直さない(アプリを開いたときにまた始める)
        return if (Build.VERSION.SDK_INT >= 37) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(poll)
        unwatch?.invoke()
        unwatch = null
        stopSilence()
        DacKeepAlive.running = false
        DacKeepAlive.activeDevice = ""
        AudioEngine.log("dac keep-alive: stopped")
        super.onDestroy()
    }

    /** 対象の機器を選び直して、無音を流す先を合わせる */
    private fun update() {
        // UCRT が USB DAC を直接使っている間は、Android からその DAC へは出せない(UCRT の音切れ対策に任せる)
        val want = if (UsbDac.active) null else DacKeepAlive.pick(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
        if (want?.id == device?.id && (want == null) == (thread == null)) return
        stopSilence()
        device = want
        if (want != null) startSilence(want)
        DacKeepAlive.activeDevice = want?.productName?.toString()?.trim().orEmpty().ifEmpty { if (want != null) typeName(want) else "" }
        runCatching { androidx.core.content.ContextCompat.getSystemService(this, NotificationManager::class.java)!!.notify(ID, notification()) }
    }

    private fun typeName(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB DAC"
        else -> S(R.string.headphone_jack)
    }

    private fun startSilence(d: AudioDeviceInfo) {
        stopFlag = false
        wake = runCatching {
            androidx.core.content.ContextCompat.getSystemService(this, PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ucrt:dac-keepalive")
                ?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        thread = Thread({ playSilence(d) }, "dac-keepalive").apply { isDaemon = true; start() }
        AudioEngine.log("dac keep-alive: keeping ${d.productName} (type ${d.type}) awake")
    }

    private fun stopSilence() {
        stopFlag = true
        thread?.interrupt()
        thread?.let { runCatching { it.join(500) } }
        thread = null
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
    }

    private fun playSilence(d: AudioDeviceInfo) {
        val rate = 48000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return
        val t = runCatching {
            newAudioTrack(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                min * 2,
            )
        }.getOrNull() ?: return
        val zeros = ByteArray(min)
        try {
            t.setPreferredDevice(d)
            t.play()
            while (!stopFlag && !Thread.currentThread().isInterrupted) {
                if (t.write(zeros, 0, zeros.size) < 0) break
            }
        } catch (_: Exception) {
        } finally {
            runCatching { t.stop() }
            t.release()
        }
    }

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            androidx.core.content.ContextCompat.getSystemService(this, NotificationManager::class.java)!!.createNotificationChannel(
                NotificationChannel("keepalive", S(R.string.dac_keepalive), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val name = DacKeepAlive.activeDevice
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "keepalive") else Notification.Builder(this)
        return b.setContentTitle(S(R.string.dac_keepalive))
            .setContentText(if (name.isNotEmpty()) S(R.string.dac_keepalive_active, name) else S(R.string.dac_keepalive_waiting))
            .setSmallIcon(R.drawable.ic_stat_ucrt)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ID = 3
    }
}
