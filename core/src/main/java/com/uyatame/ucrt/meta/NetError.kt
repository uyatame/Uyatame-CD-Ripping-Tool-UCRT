package com.uyatame.ucrt.meta

import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 通信エラーを、原因が分かる短い文章にする */
fun describeNetError(e: Throwable): String {
    val raw = "${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
    val hint = when {
        e is UnknownHostException -> S(R.string.cant_reach_server_check_your)
        e is SecurityException || (e.message ?: "").contains("EPERM") || (e.message ?: "").contains("EACCES") -> S(R.string.android_is_blocking_network_access)
        e is SocketTimeoutException -> S(R.string.no_response_timeout)
        e is ConnectException -> S(R.string.connection_refused)
        e is SSLException -> S(R.string.secure_connection_failed_check_devices)
        else -> ""
    }
    return if (hint.isEmpty()) raw else "$hint\n($raw)"
}
