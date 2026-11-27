package com.uyatame.ucrt.airplay

import com.uyatame.ucrt.player.PcmDecoder

/**
 * AirPlay 受信を UCRT の再生パイプラインへ渡すための PcmDecoder (Phase 3)。
 * ネットワーク側がデコードした PCM フレームをキューから 1 つずつ取り、
 * チャンネル別の IntArray に直して返す。データが来ていない間は無音の
 * ブロックを返して再生を止めない (ライブ受信なので「終わり」は無い)。
 * PlayerController への実際の接続は Phase 4 で行う。
 */
internal class AirPlayPcmDecoder(
    private val frames: AirPlayStream.FrameQueue,
    private val frameSamples: Int = 352,
) : PcmDecoder {

    override val sampleRate: Int = 44100
    override val channels: Int = 2
    override val bitsPerSample: Int = 16

    /** ライブ受信なので全体の長さは不明 (0 = 不明)。 */
    override val totalSamples: Long = 0

    override var samples: Array<IntArray> = Array(channels) { IntArray(frameSamples) }
        private set

    override var blockSize: Int = 0
        private set

    override var frameSample: Long = 0
        private set

    @Volatile
    private var closed = false

    /** 送信側の音量 (SET_PARAMETER volume:) を反映する倍率。1.0 が等倍。 */
    @Volatile
    var gain: Double = 1.0

    private var delivered = 0L

    override fun open() {
        // キューはネットワーク側が既に流しているので、ここでは何もしない。
    }

    /** 受信の終了 (TEARDOWN や画面を閉じたとき) に呼ぶ。次から false を返す。 */
    fun close() {
        closed = true
    }

    override fun decodeNext(): Boolean {
        if (closed) return false
        val frame = frames.poll(250)
        if (frame == null) {
            if (closed) return false
            // データ待ち: 無音でつなぐ。
            for (chArr in samples) chArr.fill(0)
            blockSize = frameSamples
            frameSample = delivered
            delivered += frameSamples
            return true
        }
        val n = minOf(frameSamples, frame.size / (channels * 2))
        val g = gain
        for (i in 0 until n) {
            for (c in 0 until channels) {
                val o = (i * channels + c) * 2
                var v = (frame[o].toInt() and 0xFF) or (frame[o + 1].toInt() shl 8)
                if (g != 1.0) v = (v * g).toInt().coerceIn(-32768, 32767)
                samples[c][i] = v
            }
        }
        blockSize = n
        frameSample = delivered
        delivered += n
        return true
    }

    override fun seekSample(target: Long) {
        // ライブ受信ではシークできないので何もしない。
    }
}
