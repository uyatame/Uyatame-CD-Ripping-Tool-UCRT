package com.uyatame.ucrt.player.dsd

import android.media.AudioFormat
import com.uyatame.ucrt.player.AudioEngine
import com.uyatame.ucrt.player.AudioOutput
import com.uyatame.ucrt.player.usb.DacFormat
import com.uyatame.ucrt.player.usb.UsbDac
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** DSD の出力先(ネイティブ DSD / DoP / PCM 変換)を同じ形で扱うための入り口 */
interface DsdSink {
    /** 出力の周波数(位置の計算に使う) */
    val sampleRate: Int
    /** 出力の 1 フレームが DSD の何バイト(1 チャンネルあたり)に当たるか */
    val bytesPerFrame: Double
    val framesWritten: Long
    fun headFrames(): Long
    fun play()
    fun pause()
    fun release()
    /** in[c] の先頭 n バイト(MSB 先頭)を出力する */
    fun write(input: Array<ByteArray>, n: Int, stop: () -> Boolean)
    /** 画面に出す説明 */
    val label: String
    val bitPerfect: Boolean
    /** 出力に書き込めなくなったか(作り直しが必要) */
    val failed: Boolean get() = false
}

/** ネイティブ DSD(USB DAC 直接出力のみ) */
internal class NativeDsdSink(private val fmt: DacFormat, private val channels: Int, private val swap: Boolean) : DsdSink {
    private val session = UsbDac.begin()

    init {
        AudioEngine.setOutput(
            com.uyatame.ucrt.player.OutputDevice(
                com.uyatame.ucrt.player.OutputDevices.KEY_USB_DIRECT, com.uyatame.ucrt.player.OutputKind.UsbDirect,
                UsbDac.deviceName() ?: "USB DAC", com.uyatame.ucrt.T("USB DAC · ネイティブ DSD", "USB DAC · Native DSD"), UsbDac.maxSpec(),
            ),
        )
    }
    override val sampleRate: Int = fmt.rate
    override val bytesPerFrame: Double = fmt.subslot.toDouble()
    override var framesWritten = 0L
        private set
    private var buf = ByteArray(0)
    override val label = "Native DSD"
    override val bitPerfect = true

    override fun headFrames(): Long = if (!UsbDac.streaming) framesWritten else UsbDac.played(session)
    override fun play() = UsbDac.setPaused(session, false)
    override fun pause() = UsbDac.setPaused(session, true)
    override fun release() = UsbDac.end(session)

    override fun write(input: Array<ByteArray>, n: Int, stop: () -> Boolean) {
        val slot = fmt.subslot
        val frames = n / slot
        if (frames <= 0) return
        val need = frames * slot * fmt.channels
        if (buf.size < need) buf = ByteArray(need)
        var o = 0
        for (f in 0 until frames) {
            val base = f * slot
            for (c in 0 until fmt.channels) {
                val src = input[if (c < channels) c else 0]
                if (swap) {
                    for (k in slot - 1 downTo 0) buf[o++] = src[base + k]
                } else {
                    for (k in 0 until slot) buf[o++] = src[base + k]
                }
            }
        }
        framesWritten += frames
        if (!UsbDac.write(session, buf, o, stop)) {
            while (!stop()) Thread.sleep(50)
        }
    }
}

/** DoP(DSD over PCM)。加工なしで出力できる PCM の出力先に、目印付きの 24bit として送る */
internal class DopSink(private val out: AudioOutput, private val channels: Int) : DsdSink {
    override val sampleRate: Int get() = out.sampleRate
    override val bytesPerFrame: Double = 2.0
    override val framesWritten: Long get() = out.framesWritten
    override val failed: Boolean get() = out.failed
    override val label = "DoP"
    override val bitPerfect = true
    private var marker = false
    private var bb: ByteBuffer = ByteBuffer.allocate(0)

    override fun headFrames() = out.headFrames()
    override fun play() = out.play()
    override fun pause() = out.pause()
    override fun release() = out.release()

    override fun write(input: Array<ByteArray>, n: Int, stop: () -> Boolean) {
        val frames = n / 2
        if (frames <= 0) return
        val need = frames * channels * 3
        if (bb.capacity() < need) bb = ByteBuffer.allocate(need).order(ByteOrder.LITTLE_ENDIAN)
        bb.clear()
        for (f in 0 until frames) {
            val m: Byte = if (marker) 0xFA.toByte() else 0x05
            marker = !marker
            for (c in 0 until channels) {
                val src = input[c]
                // 24bit リトルエンディアン: 下位 = 後のバイト、中位 = 先のバイト、上位 = 目印
                bb.put(src[f * 2 + 1])
                bb.put(src[f * 2])
                bb.put(m)
            }
        }
        bb.flip()
        out.write(bb, AudioFormat.ENCODING_PCM_24BIT_PACKED, stop)
    }
}

/** PCM に変換して出力する */
internal class PcmSink(private val out: AudioOutput, private val conv: DsdToPcm, private val channels: Int, dsdRate: Int) : DsdSink {
    override val sampleRate: Int get() = out.sampleRate
    override val bytesPerFrame: Double = dsdRate / 8.0 / conv.outRate
    override val framesWritten: Long get() = out.framesWritten
    override val failed: Boolean get() = out.failed
    override val label = "PCM"
    override val bitPerfect = false
    private var fbuf = FloatArray(0)
    private var bb: ByteBuffer = ByteBuffer.allocate(0)

    override fun headFrames() = out.headFrames()
    override fun play() = out.play()
    override fun pause() = out.pause()
    override fun release() = out.release()

    override fun write(input: Array<ByteArray>, n: Int, stop: () -> Boolean) {
        val maxF = conv.maxOut(n)
        if (fbuf.size < maxF * channels) fbuf = FloatArray(maxF * channels)
        val frames = conv.process(input, n, fbuf)
        if (frames <= 0) return
        val bytes = frames * channels * 4
        if (bb.capacity() < bytes) bb = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.clear()
        for (i in 0 until frames * channels) bb.putFloat(fbuf[i])
        bb.flip()
        out.write(bb, AudioFormat.ENCODING_PCM_FLOAT, stop)
    }

    companion object {
        fun log(msg: String) = AudioEngine.log(msg)
    }
}
