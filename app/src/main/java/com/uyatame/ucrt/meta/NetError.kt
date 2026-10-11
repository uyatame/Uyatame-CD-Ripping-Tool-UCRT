package com.uyatame.ucrt.meta

import com.uyatame.ucrt.T
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 通信エラーを、原因が分かる短い文章にする */
fun describeNetError(e: Throwable): String {
    val raw = "${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
    val hint = when {
        e is UnknownHostException -> T(
            "サーバーに接続できません。インターネット接続と、このアプリの通信許可(WLAN・モバイルデータ)を確認してください",
            "Cannot reach the server. Check your connection and this app's network permission (Wi-Fi / mobile data)",
        )
        e is SecurityException || (e.message ?: "").contains("EPERM") || (e.message ?: "").contains("EACCES") -> T(
            "このアプリの通信がOSにより制限されています。アプリ情報で通信を許可してください",
            "The OS is blocking network access for this app. Allow it in App info",
        )
        e is SocketTimeoutException -> T("応答がありません(タイムアウト)", "No response (timeout)")
        e is ConnectException -> T("接続を拒否されました", "Connection refused")
        e is SSLException -> T("安全な接続に失敗しました。端末の日時が正しいか確認してください", "Secure connection failed. Check the device date and time")
        else -> ""
    }
    return if (hint.isEmpty()) raw else "$hint\n($raw)"
}
