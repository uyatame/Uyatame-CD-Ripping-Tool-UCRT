package com.uyatame.ucrt.player

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build

/**
 * サイレントモード: 通知音・着信音・アラーム・操作音を止めて、音楽だけを鳴らす(DAP のように使うため)。
 * (LockDeck の SilentMode を、UCRT に合わせて移したもの。いつものアプリと UCRT Legacy で共通。Android 4.0 から動く)
 *
 * 方式は自動で選ぶ:
 *  1. 「おやすみモードへのアクセス」が許可されていれば(Android 6.0 から)、おやすみモード(すべてオフ)にする。音量の値は触らない
 *  2. 許可が無ければ、音楽以外の音量を 0 にしてミュートする(元の音量は保存しておき、戻すときに使う)
 *
 * 元の状態は、変える前に同期で保存するので、変えた直後にアプリが落ちても、0 になった音量を元の値と取り違えない。
 * [sync] は何度呼んでもよい
 */
class SilentMode(context: Context, private val log: (String) -> Unit = {}) {
    private val ctx = context.applicationContext
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val sp = ctx.getSharedPreferences("silent_mode", Context.MODE_PRIVATE)

    /** 今サイレントにしているか */
    val isActive: Boolean get() = sp.contains(KEY)

    /** おやすみモードの方式を使えるか(Android 6.0 から。「おやすみモードへのアクセス」の許可) */
    val hasPolicyAccess: Boolean
        get() = Build.VERSION.SDK_INT >= 23 && runCatching { DndApi.granted(nm) }.getOrDefault(false)

    /** 「おやすみモードへのアクセス」の許可の画面(Android 6.0 から) */
    fun policyAccessIntent(): Intent = Intent("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS")

    /** 望む状態に合わせる */
    fun sync(want: Boolean) { if (want) enable() else disable() }

    fun enable() {
        val fresh = !isActive
        if (fresh) {
            val snap = if (hasPolicyAccess) "dnd=${DndApi.current(nm)}"
            else STREAMS.joinToString(";") { "$it=${am.getStreamVolume(it)}" }
            // 変える前に、元の状態を同期で保存する
            sp.edit().putString(KEY, snap).commit()
            log("silent mode: on (${if (snap.startsWith("dnd=")) "do not disturb" else "volumes"})")
        }
        apply(sp.getString(KEY, "") ?: "", fresh)
    }

    fun disable() {
        val snap = sp.getString(KEY, null) ?: return
        val map = parse(snap)
        if (map.containsKey("dnd")) {
            if (Build.VERSION.SDK_INT >= 23) runCatching { DndApi.set(nm, map.getValue("dnd")) }
        } else {
            for (s in STREAMS) runCatching {
                mute(s, false)
                val saved = map[s.toString()] ?: return@runCatching
                am.setStreamVolume(s, saved.coerceAtMost(am.getStreamMaxVolume(s)), 0)
            }
        }
        // 戻してから消す(途中で落ちても、次にやり直せる)
        sp.edit().remove(KEY).commit()
        log("silent mode: off")
    }

    /** fresh: 今サイレントにしたところか(Android 5.1 以前のミュートは、呼んだ回数を数えるので、1 回だけにする) */
    private fun apply(snap: String, fresh: Boolean) {
        if (snap.startsWith("dnd=")) {
            if (Build.VERSION.SDK_INT >= 23) runCatching { DndApi.set(nm, NotificationManager.INTERRUPTION_FILTER_NONE) }
        } else {
            // 音量ごとに、失敗しても続ける(おやすみモードの制限で SecurityException になる端末がある)
            for (s in STREAMS) runCatching {
                am.setStreamVolume(s, 0, 0)
                // Android 5.1 以前の setStreamMute は、呼んだ回数だけ解除しないと戻らない。何度も呼ぶと、オフにしても音が出なくなる
                if (fresh || Build.VERSION.SDK_INT >= 23) mute(s, true)
            }
        }
    }

    /** ミュート(Android 6.0 からは adjustStreamVolume、それより前は setStreamMute) */
    private fun mute(stream: Int, on: Boolean) {
        if (Build.VERSION.SDK_INT >= 23) am.adjustStreamVolume(stream, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        else @Suppress("DEPRECATION") am.setStreamMute(stream, on)
    }

    private fun parse(s: String): Map<String, Int> =
        s.split(';').mapNotNull {
            val p = it.split('=')
            if (p.size != 2) return@mapNotNull null
            p[1].toIntOrNull()?.let { n -> p[0] to n }
        }.toMap()

    private companion object {
        const val KEY = "snapshot"
        // 音楽(STREAM_MUSIC)は対象にしない
        val STREAMS = intArrayOf(
            AudioManager.STREAM_SYSTEM, AudioManager.STREAM_RING, AudioManager.STREAM_ALARM,
            AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_DTMF, AudioManager.STREAM_VOICE_CALL,
        )
    }
}

/** おやすみモード(Android 6.0 から)。古い Android でクラスの検証に失敗しないよう、呼ぶ所を分けておく */
private object DndApi {
    fun granted(nm: NotificationManager): Boolean = nm.isNotificationPolicyAccessGranted
    fun current(nm: NotificationManager): Int = nm.currentInterruptionFilter
    fun set(nm: NotificationManager, f: Int) = nm.setInterruptionFilter(f)
}
