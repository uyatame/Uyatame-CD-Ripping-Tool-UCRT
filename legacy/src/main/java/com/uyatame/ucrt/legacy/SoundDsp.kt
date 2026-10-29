package com.uyatame.ucrt.legacy

import android.content.Context
import android.os.Build
import com.uyatame.ucrt.player.EqBand
import com.uyatame.ucrt.player.EqBands
import com.uyatame.ucrt.player.ParamEq
import com.uyatame.ucrt.player.ParamEqualizer
import com.uyatame.ucrt.player.ReplayGain
import com.uyatame.ucrt.player.RgStore
import kotlin.math.max
import kotlin.math.pow

/**
 * 音の加工(本家の AudioEngine のイコライザーと ReplayGain)。再生のスレッドで、曲の音(float)に掛ける。
 * 設定は画面から変わり、再生中の曲にもすぐ効く
 */
class SoundDsp(private val ctx: Context) : LegacyPlayer.Dsp {
    @Volatile private var eqOn = false
    @Volatile private var bands: List<EqBand> = emptyList()
    @Volatile private var maxBoost = 0f
    @Volatile private var preampDb = 0f
    @Volatile private var autoPreamp = true
    @Volatile private var version = 0

    private var eq: ParamEqualizer? = null
    private var eqVersion = -1
    private var rate = 0
    private var ch = 0
    @Volatile private var rgGain = 1f
    @Volatile private var rgFor: String? = null

    /** 設定を反映する(どのスレッドからでも) */
    fun apply(p: Prefs) {
        val list = if (p.eqMode == 0) ParamEq.fromGains(EqBands.decode(p.eqSimpleGains.ifEmpty { p.sp.getString("eqGains", "") ?: "" })) else
            (if (p.eqBands.isNotEmpty()) ParamEq.decode(p.eqBands) else ParamEq.fromGains(EqBands.decode(p.sp.getString("eqGains", "") ?: "")))
        setEq(p.eqActive, list, p.eqPreamp, p.eqAutoPreamp)
        val rgMode = p.rgEffective
        if (ReplayGain.mode != rgMode) rgFor = null
        ReplayGain.mode = rgMode
        ReplayGain.preampDb = p.rgPreamp.toFloat()
        ReplayGain.preventClipping = p.rgNoClip
        appGain = if (p.sp.getBoolean("fineVolume", false)) appGainOf(p.sp.getInt("appVolumeStep", 200)) else 1f
    }

    /** イコライザーを今すぐ変える(画面で動かしている途中。保存はしない) */
    fun setEq(enabled: Boolean, b: List<EqBand>, preamp: Float, auto: Boolean) {
        if (b != bands) maxBoost = ParamEq.maxBoostDb(b)
        bands = b
        eqOn = enabled
        preampDb = preamp
        autoPreamp = auto
        version++
    }

    private fun preampGain(): Float {
        val auto = if (autoPreamp) max(0f, maxBoost) else 0f
        return 10.0.pow((preampDb - auto) / 20.0).toFloat()
    }

    override fun start(rate: Int, channels: Int, item: QItem) {
        if (rate != this.rate || channels != ch) {
            this.rate = rate; ch = channels
            eq = ParamEqualizer(rate, channels)
            eqVersion = -1
        }
        // ReplayGain の値(曲に書かれたもの、無ければ解析したもの)
        if (ReplayGain.mode != 0) {
            val u = android.net.Uri.parse(item.uri)
            val info = ReplayGain.read(ctx, u) ?: RgStore.get(item.uri)
            rgGain = ReplayGain.linear(info)
            rgFor = item.uri
        } else {
            rgGain = 1f
            rgFor = null
        }
    }

    override fun process(buf: FloatArray, frames: Int, channels: Int) {
        val e = eq
        val useEq = eqOn && e != null
        if (useEq && eqVersion != version) {
            eqVersion = version
            e!!.update(bands)
        }
        var g = rgGain
        if (useEq) g *= preampGain()
        // 細かい音量(USB DAC へ直接出しているときは DAC の音量を使うので掛けない)
        if (!UsbAudio.inUse) g *= appGain
        val n = frames * channels
        if (g != 1f) for (i in 0 until n) buf[i] *= g
        if (useEq) e!!.process(buf, n)
    }

    companion object {
        /**
         * アプリの中の音量(倍率。設定の「細かい音量」をオンにすると、音量の画面で端末の音量より細かく(0.5 dB ごと)変えられる。本家と同じ)
         */
        @Volatile var appGain = 1f

        /** 0〜200 の段階(200 = 0 dB、1 段 0.5 dB。0 は消音) → 倍率 */
        fun appGainOf(step: Int): Float = if (step <= 0) 0f else Math.pow(10.0, (step - 200) * 0.5 / 20).toFloat()

        /** AudioTrack が受け付ける周波数の上限(Android の版で違う) */
        val trackMax: Int get() = when {
            Build.VERSION.SDK_INT >= 23 -> 192000
            Build.VERSION.SDK_INT >= 21 -> 96000
            else -> 48000
        }

        /**
         * DAR の PCM アップサンプリングで出す周波数(使わないときは src)。
         * pcm: 0 = 出力の上限、1 = 2 倍、2 = 4 倍、3 = 8 倍、4 = 16 倍。integer: 整数倍だけにする
         */
        fun darRate(src: Int, mode: Int, pcm: Int, integer: Boolean): Int {
            if (mode != 1 || src <= 0) return src
            val max = trackMax
            if (src >= max) return src
            val want = if (pcm == 0) Int.MAX_VALUE else src * (1 shl pcm)
            val k = (minOf(want, max) / src)
            if (k >= 2) return src * k
            return if (integer) src else minOf(want, max)
        }
    }
}
