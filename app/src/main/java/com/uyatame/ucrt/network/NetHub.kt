package com.uyatame.ucrt.network

import android.content.Context
import android.os.Build
import java.io.IOException

/** 接続先ごとの接続を作って使い回す。接続先を編集・削除したら [invalidate] で捨てる */
object NetHub {
    private val conns = HashMap<String, NetFs>()

    /** SMB / SFTP の部品は Android 8 以上を前提にしている */
    fun supported(p: NetProtocol): Boolean = when (p) {
        NetProtocol.SMB, NetProtocol.SFTP -> Build.VERSION.SDK_INT >= 26
        else -> true
    }

    @Synchronized
    fun fs(ctx: Context, id: String): NetFs {
        conns[id]?.let { return it }
        val src = NetSourceStore.get(ctx, id) ?: throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_source_missing))
        val f = create(ctx.applicationContext, src)
        conns[id] = f
        return f
    }

    fun create(ctx: Context, src: NetSource): NetFs {
        if (!supported(src.protocol)) throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_needs_android8))
        return when (src.protocol) {
            NetProtocol.SMB -> SmbFs(src)
            NetProtocol.SFTP -> SftpFs(src) { fp -> NetSourceStore.get(ctx, src.id)?.let { NetSourceStore.save(ctx, it.copy(hostKey = fp)) } }
            NetProtocol.FTP -> FtpFs(src)
            NetProtocol.WEBDAV, NetProtocol.WEBDAVS -> WebDavFs(src)
        }
    }

    @Synchronized
    fun invalidate(id: String) {
        conns.remove(id)?.let { f -> Thread { runCatching { f.close() } }.start() }
    }

    /** 設定の画面の「接続テスト」。保存前の内容でつないで、最初のフォルダの一覧が取れるか調べる。成功なら null、失敗なら理由 */
    fun test(ctx: Context, src: NetSource): String? {
        val f = try { create(ctx.applicationContext, src) } catch (e: Throwable) { return e.message ?: e.javaClass.simpleName }
        return try {
            f.list("/")
            null
        } catch (e: Throwable) {
            e.message ?: e.javaClass.simpleName
        } finally { runCatching { f.close() } }
    }
}
