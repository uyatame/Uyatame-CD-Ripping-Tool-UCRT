package com.uyatame.ucrt.convert

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 純正 DSRE (全長版) のゴールデンテスト。
 * 参照出力は C++ 版 dsre_core (原典の検証済み移植) を同レート・同パラメータ (既定値) で
 * 直接叩いて作ったもの (リサンプルなし)。合格基準は設計書どおり相対 RMS 差 −40 dB 以下。
 * 参照データ: src/test/resources/pure_dsre/case*_in.f32 / case*_out.f32 (interleaved float32)
 */
class PureDsreTest {

    private fun load(name: String): FloatArray {
        val bytes = javaClass.getResourceAsStream("/pure_dsre/$name")!!.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.getFloat(it * 4) }
    }

    private fun relRmsDb(got: FloatArray, ref: FloatArray): Double {
        var num = 0.0
        var den = 0.0
        for (i in ref.indices) {
            val d = (got[i] - ref[i]).toDouble()
            num += d * d
            den += ref[i].toDouble() * ref[i].toDouble()
        }
        return 20 * log10(sqrt(num) / sqrt(den))
    }

    private fun check(case: String, rate: Int) {
        val input = load("${case}_in.f32")
        val ref = load("${case}_out.f32")
        val frames = input.size / 2
        val buf = input.copyOf()
        PureDsre(rate, 2, PureDsreParams()).process(buf, frames)
        val db = relRmsDb(buf, ref)
        assertTrue("$case: rel RMS diff $db dB (gate -40 dB)", db <= -40.0)
    }

    /** 48 kHz: トーン + ノイズ (ピーク 0.5) */
    @Test
    fun golden48k() = check("case1", 48000)

    /** 192 kHz: スイープ + トーン (Air バンドと高レートのマスク) */
    @Test
    fun golden192k() = check("case2", 192000)

    /** 48 kHz 大音量 (ピーク 0.99): 最後の 0.95 ピーク正規化が掛かるケース */
    @Test
    fun goldenLoud() = check("case3", 48000)
}
