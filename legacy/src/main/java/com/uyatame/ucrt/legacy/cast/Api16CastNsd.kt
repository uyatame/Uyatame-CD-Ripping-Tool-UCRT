package com.uyatame.ucrt.legacy.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/** 同じネットワークの Cast 機器を探す(mDNS。Android 4.1 から。本家の CastManager の探す部分) */
internal object Api16CastNsd {
    private val main = Handler(Looper.getMainLooper())
    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var found: ((CastManager.Device, Boolean) -> Unit)? = null

    fun start(ctx: Context, onFound: (CastManager.Device, Boolean) -> Unit) {
        if (listener != null) return
        val m = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = m
        found = onFound
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { com.uyatame.ucrt.legacy.AppLog.log("cast: discovery failed ($errorCode)") }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { main.post { resolveQueue.addLast(info); resolveNext() } }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { found?.invoke(CastManager.Device(info.serviceName, info.serviceName, "", "", 0), true) }
            }
        }
        listener = l
        runCatching { m.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { listener = null }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { nsd?.stopServiceDiscovery(l) }
        resolveQueue.clear()
    }

    /** 名前などを調べる(同時に 1 つしか調べられないので順番に) */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        val m = nsd ?: return
        resolving = true
        runCatching {
            m.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo?, errorCode: Int) {
                    main.post { resolving = false; resolveNext() }
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val host = si.host?.hostAddress
                    if (host != null) {
                        // 機器の名前などの属性は Android 5.0 から読める(それより前は mDNS の名前を使う)
                        val attrs: Map<String, ByteArray> = if (android.os.Build.VERSION.SDK_INT >= 21) Api21Attrs.of(si) else emptyMap()
                        fun attr(k: String) = attrs[k]?.let { String(it, Charsets.UTF_8) }
                        val d = CastManager.Device(attr("id") ?: si.serviceName, attr("fn") ?: si.serviceName, attr("md").orEmpty(), host, si.port)
                        main.post { found?.invoke(d, false) }
                    }
                    main.post { resolving = false; resolveNext() }
                }
            })
        }.onFailure { resolving = false }
    }
}

/** NsdServiceInfo の属性(Android 5.0 から) */
internal object Api21Attrs {
    fun of(si: NsdServiceInfo): Map<String, ByteArray> = runCatching { si.attributes }.getOrNull() ?: emptyMap()
}
