package com.uyatame.ucrt.player

/**
 * アプリ内蔵のデコーダー(FLAC・ALAC・WavPack・APE など)の共通の形。
 * 端末の OS のデコーダーに頼らずに PCM にする(古い Android や、OS のデコーダーが動かない端末でも再生できるように)。
 *
 * 使い方: open() → decodeNext() を繰り返す(1 回で 1 ブロック)。途中からは seekSample() のあと decodeNext()。
 */
internal interface PcmDecoder {
    val sampleRate: Int
    val channels: Int
    /** 元の 1 サンプルのビット数(16 / 24 など) */
    val bitsPerSample: Int
    /** 全体のサンプル数(1 チャンネルあたり。分からなければ 0) */
    val totalSamples: Long

    /** デコードしたブロック: samples[ch][0 until blockSize](右詰めの符号付き整数) */
    val samples: Array<IntArray>
    val blockSize: Int
    /** このブロックの最初のサンプルの番号 */
    val frameSample: Long

    /** ファイルの先頭を読む。対応していない形式なら例外 */
    fun open()

    /** 次のブロックをデコードする。終わりなら false */
    fun decodeNext(): Boolean

    /**
     * target(サンプル番号)を含むブロックの手前に移る。
     * 次の decodeNext() からそのブロックが出る(手前の分は呼び出し側で frameSample を見て捨てる)
     */
    fun seekSample(target: Long)
}
