package com.uyatame.ucrt.player

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * メーカーのライブラリを調べるサービス(テスト版にだけ実物がある)。マニフェストには、リリース版にも宣言が残るので、
 * リリース版には、何もせずに終わる空のサービスを置く
 */
class VendorLibProbeService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf()
        return START_NOT_STICKY
    }
}
