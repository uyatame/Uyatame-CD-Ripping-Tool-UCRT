package com.uyatame.ucrt.player

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File

/**
 * メーカー(Sony など)の内蔵 DAC 用のライブラリを、アプリから読み込めるかを試すサービス。
 * アプリの本体とは別のプロセス(:libprobe)で動くので、読み込みの途中でそのライブラリが落ちても、UCRT 本体は落ちない。
 * 結果は、アプリの files の中のファイルに書く(プロセスが違っても読める)。結果のファイルが出来ないまま終われば、「落ちた」と分かる。
 */
class VendorLibProbeService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val names = intent?.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()
        val out = File(filesDir, RESULT_FILE)
        Thread({
            val sb = StringBuilder()
            for (n in names) {
                val r = try {
                    System.loadLibrary(n)
                    "loaded"
                } catch (e: Throwable) {
                    "failed: ${e.javaClass.simpleName}: ${e.message}"
                }
                sb.append(n).append('\t').append(r).append('\n')
                // 1 つ試すごとに書く(次の読み込みで落ちても、ここまでの結果は残る)
                runCatching { out.writeText(sb.toString()) }
            }
            runCatching { out.writeText(sb.toString() + "done\n") }
            stopSelf()
        }, "libprobe").start()
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_NAMES = "names"
        const val RESULT_FILE = "vendor_lib_probe.txt"
    }
}
