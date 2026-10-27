package com.uyatame.ucrt.player.aac

/**
 * AAC の設定(AudioSpecificConfig。MP4 の esds の中、または ADTS の見出しから作る)
 */
internal class AudioSpecificConfig(
    /** 中身の形式(2 = AAC-LC。SBR・PS があっても、ここは中の AAC の形式) */
    val objectType: Int,
    /** サンプリング周波数の番号(0 = 96 kHz … 12 = 7.35 kHz) */
    val samplingIndex: Int,
    /** 中の AAC のサンプリング周波数 */
    val sampleRate: Int,
    /** チャンネルの数 */
    val channels: Int,
    /** SBR があると明示されている(HE-AAC)。true: ある、false: 無いと明示、null: 書かれていない(中を見て決める) */
    val sbr: Boolean?,
    /** PS(HE-AAC v2)。true: ある、false: 無いと明示、null: 書かれていない */
    val ps: Boolean?,
    /** SBR のあとのサンプリング周波数(SBR が無ければ 0) */
    val extSampleRate: Int,
    /** 1 フレームが 960 サンプル */
    val frame960: Boolean,
) {
    companion object {
        val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        /** サンプリング周波数 → 番号(規格の表に無い周波数は近いもの。ffmpeg と同じ決め方) */
        fun indexOf(rate: Int): Int {
            val bounds = intArrayOf(92017, 75132, 55426, 46009, 37566, 27713, 23004, 18783, 13856, 11502, 9391)
            for (i in bounds.indices) if (rate >= bounds[i]) return i
            return 11
        }

        private fun objectType(b: BitReader): Int {
            val t = b.bits(5)
            return if (t == 31) 32 + b.bits(6) else t
        }

        private fun rate(b: BitReader): Pair<Int, Int> {
            val i = b.bits(4)
            if (i == 15) {
                val r = b.bits(24)
                return indexOf(r) to r
            }
            if (i >= RATES.size) throw AacException("AAC: bad sampling index $i")
            return i to RATES[i]
        }

        /** AudioSpecificConfig を読む */
        fun parse(data: ByteArray): AudioSpecificConfig {
            val b = BitReader()
            b.init(data, 0, data.size)
            var aot = objectType(b)
            val (idx0, rate0) = rate(b)
            var idx = idx0
            var rate = rate0
            var chanCfg = b.bits(4)
            var sbr: Boolean? = null
            var ps: Boolean? = null
            var extRate = 0
            if (aot == 5 || aot == 29) {
                // 明示された SBR(と PS)。続くのが中の AAC の設定
                sbr = true
                if (aot == 29) ps = true
                extRate = rate(b).second
                aot = objectType(b)
                if (aot == 22) chanCfg = b.bits(4)
            }
            var frame960 = false
            var channels = channelsOf(chanCfg)
            when (aot) {
                1, 2, 3, 4, 6, 7, 17, 19, 20, 21, 22, 23 -> {
                    // GASpecificConfig
                    frame960 = b.bit() == 1
                    if (b.bit() == 1) b.skip(14) // dependsOnCoreCoder → coreCoderDelay
                    val ext = b.bit()
                    if (chanCfg == 0) channels = ProgramConfig.read(b).channels
                    if (aot == 6 || aot == 20) b.skip(3)
                    if (ext == 1) {
                        if (aot == 22) b.skip(16)
                        if (aot == 17 || aot == 19 || aot == 20 || aot == 23) b.skip(3)
                        b.skip(1)
                    }
                }
                else -> throw AacException("AAC: object type $aot not supported")
            }
            // 後ろに付ける形の SBR・PS の知らせ(互換のための書き方)
            if (sbr == null && b.left() >= 16) {
                runCatching {
                    if (b.bits(11) == 0x2b7) {
                        val ext = objectType(b)
                        if (ext == 5) {
                            sbr = b.bit() == 1
                            if (sbr == true) {
                                extRate = rate(b).second
                                if (b.left() >= 12 && b.bits(11) == 0x548) ps = b.bit() == 1
                            }
                        }
                    }
                }
            }
            if (channels <= 0) throw AacException("AAC: bad channel configuration $chanCfg")
            return AudioSpecificConfig(aot, idx, rate, channels, sbr, ps, extRate, frame960)
        }

        /** ADTS の見出しから作る(SBR は書かれていない) */
        fun fromAdts(profile: Int, samplingIndex: Int, chanCfg: Int): AudioSpecificConfig {
            if (samplingIndex >= RATES.size) throw AacException("AAC: bad sampling index $samplingIndex")
            // チャンネルの設定 0(PCE で決める)は、ステレオとみなす
            val ch = if (chanCfg == 0) 2 else channelsOf(chanCfg)
            return AudioSpecificConfig(profile + 1, samplingIndex, RATES[samplingIndex], ch, null, null, 0, false)
        }

        fun channelsOf(cfg: Int): Int = when (cfg) {
            in 1..6 -> cfg
            7 -> 8
            else -> 0
        }
    }
}

/** program_config_element(チャンネルの並びの宣言)。チャンネルの数だけを使う */
internal class ProgramConfig(val channels: Int) {
    companion object {
        fun read(b: BitReader): ProgramConfig {
            b.skip(4) // element_instance_tag
            b.skip(2) // object_type
            b.skip(4) // sampling_frequency_index
            val front = b.bits(4)
            val side = b.bits(4)
            val back = b.bits(4)
            val lfe = b.bits(2)
            val assoc = b.bits(3)
            val cc = b.bits(4)
            if (b.bit() == 1) b.skip(4) // mono_mixdown
            if (b.bit() == 1) b.skip(4) // stereo_mixdown
            if (b.bit() == 1) b.skip(3) // matrix_mixdown
            var n = 0
            repeat(front + side + back) {
                n += if (b.bit() == 1) 2 else 1
                b.skip(4)
            }
            repeat(lfe) { b.skip(4); n++ }
            repeat(assoc) { b.skip(4) }
            repeat(cc) { b.skip(5) }
            b.align()
            val comment = b.bits(8)
            b.skip(comment * 8)
            return ProgramConfig(n)
        }
    }
}
