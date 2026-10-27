package com.uyatame.ucrt.legacy.compat

import android.content.Context
import android.os.Build
import com.uyatame.ucrt.R
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 古い Android でも MusicBrainz などにつながるようにする(アプリの中の通信だけ)。
 * - Android 7.0 までは、Let's Encrypt の証明書の元(ISRG Root X1・X2)を信頼していないので、アプリに入れた 2 つも信頼する
 *   (端末の信頼する証明書で確かめて、だめなときだけ。ほかの証明書は信頼しない)
 * - Android 4.1〜4.4 は TLS 1.2 が使えるのに、初めは切ってあるので入れる(Android 4.0 には TLS 1.2 が無い)
 */
object Tls {
    fun install(ctx: Context) {
        runCatching {
            val system = trustManager(null)
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            val cf = CertificateFactory.getInstance("X.509")
            listOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2).forEachIndexed { i, id ->
                ctx.resources.openRawResource(id).use { ks.setCertificateEntry("isrg$i", cf.generateCertificate(it)) }
            }
            val extra = trustManager(ks)
            val tm = Both(system, extra)
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, arrayOf<TrustManager>(tm), null)
            val f = if (Build.VERSION.SDK_INT < 20) Modern(sc.socketFactory) else sc.socketFactory
            HttpsURLConnection.setDefaultSSLSocketFactory(f)
        }
    }

    private fun trustManager(ks: KeyStore?): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(ks)
        return f.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    /** 端末の信頼で確かめ、だめならアプリに入れた ISRG の証明書で確かめる */
    private class Both(private val a: X509TrustManager, private val b: X509TrustManager) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = a.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                a.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                try { b.checkServerTrusted(chain, authType) } catch (e2: CertificateException) { throw e }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = a.acceptedIssuers + b.acceptedIssuers
    }

    /** 作った接続で TLS 1.1・1.2 を使えるようにする(Android 4.1〜4.4) */
    private class Modern(private val f: SSLSocketFactory) : SSLSocketFactory() {
        private fun on(s: Socket): Socket {
            if (s is SSLSocket) {
                val want = s.supportedProtocols.filter { it == "TLSv1" || it == "TLSv1.1" || it == "TLSv1.2" }
                if (want.isNotEmpty()) s.enabledProtocols = want.toTypedArray()
            }
            return s
        }

        override fun getDefaultCipherSuites(): Array<String> = f.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = f.supportedCipherSuites
        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket = on(f.createSocket(s, host, port, autoClose))
        override fun createSocket(host: String?, port: Int): Socket = on(f.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = on(f.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress?, port: Int): Socket = on(f.createSocket(host, port))
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = on(f.createSocket(address, port, localAddress, localPort))
    }
}
