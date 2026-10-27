package com.uyatame.ucrt.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Build

/**
 * USB の API の版による違い(Android 4.0〜4.2 で使えないものを避ける)。app と legacy で共通
 */
object UsbCompat {
    private var tmp = ByteArray(0)

    /**
     * buf の off から len バイトを送る・受け取る。途中の位置を指定する bulkTransfer は Android 4.3 から
     * (それより前は、一時の場所を使って写す)
     */
    fun bulk(c: UsbDeviceConnection, ep: UsbEndpoint, buf: ByteArray, off: Int, len: Int, timeout: Int): Int {
        if (off == 0) return c.bulkTransfer(ep, buf, len, timeout)
        if (Build.VERSION.SDK_INT >= 18) return Usb18.bulk(c, ep, buf, off, len, timeout)
        synchronized(this) {
            if (tmp.size < len) tmp = ByteArray(len)
            val out = ep.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT
            if (out) System.arraycopy(buf, off, tmp, 0, len)
            val r = c.bulkTransfer(ep, tmp, len, timeout)
            if (!out && r > 0) System.arraycopy(tmp, 0, buf, off, r)
            return r
        }
    }

    fun productName(d: UsbDevice): String? = if (Build.VERSION.SDK_INT >= 21) Usb21.product(d) else null
    fun manufacturerName(d: UsbDevice): String? = if (Build.VERSION.SDK_INT >= 21) Usb21.manufacturer(d) else null
    fun alternate(f: UsbInterface): Int = if (Build.VERSION.SDK_INT >= 21) Usb21.alternate(f) else 0
    fun setInterface(c: UsbDeviceConnection, f: UsbInterface) {
        if (Build.VERSION.SDK_INT >= 21) runCatching { Usb21.setInterface(c, f) }
    }
}

/** Android 4.3 からの USB の API */
object Usb18 {
    fun bulk(c: UsbDeviceConnection, ep: UsbEndpoint, buf: ByteArray, off: Int, len: Int, timeout: Int): Int = c.bulkTransfer(ep, buf, off, len, timeout)
}

/** Android 5.0 からの USB の API */
object Usb21 {
    fun product(d: UsbDevice): String? = d.productName
    fun manufacturer(d: UsbDevice): String? = d.manufacturerName
    fun alternate(f: UsbInterface): Int = f.alternateSetting
    fun setInterface(c: UsbDeviceConnection, f: UsbInterface): Boolean = c.setInterface(f)
}
