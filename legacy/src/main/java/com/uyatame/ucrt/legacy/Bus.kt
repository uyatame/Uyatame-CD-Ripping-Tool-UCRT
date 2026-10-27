package com.uyatame.ucrt.legacy

import android.os.Handler
import android.os.Looper

/**
 * 状態が変わったことを画面に知らせる(本家の Compose の状態の代わり)。
 * 続けて何度も知らせても、画面の処理には 1 回だけ届く(メインのスレッドで)
 */
object Bus {
    const val PLAYER = 1
    const val LIBRARY = 2
    const val STORE = 4
    const val SETTINGS = 8
    const val DISC = 16
    const val RIP = 32
    const val ART = 64
    const val SCAN = 128

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Pair<Int, (Int) -> Unit>>()
    private var pending = 0
    private val flush = Runnable {
        val m = synchronized(this) { val x = pending; pending = 0; x }
        if (m == 0) return@Runnable
        for ((mask, l) in listeners.toList()) if (mask and m != 0) l(m)
    }

    fun on(mask: Int, l: (Int) -> Unit): () -> Unit {
        val e = mask to l
        listeners.add(e)
        return { listeners.remove(e) }
    }

    /** どのスレッドからでも呼べる */
    fun emit(topic: Int) {
        val first = synchronized(this) { val f = pending == 0; pending = pending or topic; f }
        if (first) main.post(flush)
    }
}
