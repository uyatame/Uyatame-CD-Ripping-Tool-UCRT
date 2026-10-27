package com.uyatame.ucrt.player.usb

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process

/**
 * 端末に「このスレッドは決まった間隔で処理を終えなければならない」と伝える(Android 12 以降)。
 * 画面が止まって端末が暇になると、省電力のために CPU やメモリの速さが落ち、
 * USB の等時転送が間に合わない(パケットの EXDEV)ことがある機種への対策。
 * 対応していない端末では何もしない
 */
internal class PerfHint private constructor(private val s: PerformanceHintManager.Session, private val targetNs: Long) {
    private var n = 0

    /** 転送 1 回ごとに呼ぶ。呼び出しを減らすため、8 回に 1 回だけ伝える */
    fun tick() {
        if (++n < 8) return
        n = 0
        // 処理に目標の時間をちょうど使い切っている、と伝えて速さを落とさせない
        runCatching { s.reportActualWorkDuration(targetNs) }
    }

    fun close() { runCatching { s.close() } }

    companion object {
        /** 呼んだスレッドについて開く。使えなければ null */
        fun open(ctx: Context, targetNs: Long): PerfHint? = runCatching { create(ctx, targetNs) }.getOrNull()

        private fun create(ctx: Context, targetNs: Long): PerfHint? {
            if (Build.VERSION.SDK_INT < 31) return null
            val m = androidx.core.content.ContextCompat.getSystemService(ctx, PerformanceHintManager::class.java) ?: return null
            val s = m.createHintSession(intArrayOf(Process.myTid()), targetNs) ?: return null
            if (Build.VERSION.SDK_INT >= 35) runCatching { s.setPreferPowerEfficiency(false) }
            return PerfHint(s, targetNs)
        }
    }
}
