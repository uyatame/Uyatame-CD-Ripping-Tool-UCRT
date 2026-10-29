package com.uyatame.ucrt.player.usb

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Linux の usbfs を直接呼び出す小さな層。
 * Android の Java API では等時転送(isochronous)を扱えないため、
 * UsbDeviceConnection から得たファイル記述子に対して ioctl を発行する(root 不要)。
 */
internal object Usbfs {
    private val ioctlFn by lazy { NativeLibrary.getInstance("c").getFunction("ioctl") }

    val PS: Int = Native.POINTER_SIZE

    // struct usbdevfs_urb のフィールド位置(32bit / 64bit で異なる)
    val OFF_TYPE = 0
    val OFF_ENDPOINT = 1
    val OFF_STATUS = 4
    val OFF_FLAGS = 8
    val OFF_BUFFER = if (PS == 8) 16 else 12
    val OFF_BUFLEN = OFF_BUFFER + PS
    val OFF_ACTUAL = OFF_BUFLEN + 4
    val OFF_START = OFF_ACTUAL + 4
    val OFF_NPACKETS = OFF_START + 4
    val OFF_ERRCOUNT = OFF_NPACKETS + 4
    val OFF_SIGNR = OFF_ERRCOUNT + 4
    val OFF_CONTEXT = OFF_SIGNR + 4
    val URB_SIZE = OFF_CONTEXT + PS
    const val ISO_DESC_SIZE = 12

    const val URB_TYPE_ISO: Byte = 0
    const val URB_ISO_ASAP = 0x02

    private fun ioc(dir: Int, nr: Int, size: Int): Int = (dir shl 30) or (size shl 16) or ('U'.code shl 8) or nr
    val SUBMITURB = ioc(2, 10, URB_SIZE) // _IOR
    val DISCARDURB = ioc(0, 11, 0) // _IO
    val REAPURB = ioc(1, 12, PS) // _IOW
    val REAPURBNDELAY = ioc(1, 13, PS) // _IOW(待たない版)
    val GET_SPEED = ioc(0, 31, 0)
    val RESET = ioc(0, 20, 0) // _IO
    val IOCTL = ioc(3, 18, 8 + PS) // _IOWR
    val CONNECT = ioc(0, 23, 0)

    fun ioctl(fd: Int, req: Int, arg: Pointer?): Int =
        ioctlFn.invokeInt(arrayOf<Any?>(fd, req, arg))

    fun errno(): Int = Native.getLastError()

    /** 1: low, 2: full, 3: high, 5 以上: super。取得できなければ 0 */
    fun speed(fd: Int): Int = runCatching { ioctl(fd, GET_SPEED, null) }.getOrDefault(0).coerceAtLeast(0)

    /**
     * USB の機器をリセットする(機器が挿した直後と同じ初期状態からやり直し、カーネルのドライバーも機器を見つけ直す)。
     * アプリから電源を切って抜いた状態にはできないので、これがいちばん挿し直しに近い
     */
    fun resetDevice(fd: Int): Boolean = runCatching { ioctl(fd, RESET, null) >= 0 }.getOrDefault(false)

    /** 切り離していたカーネル側のドライバー(Android の USB オーディオ)を、インターフェースに再び接続する */
    fun reconnectKernelDriver(fd: Int, iface: Int): Boolean {
        val m = Memory((8 + PS).toLong())
        m.clear()
        m.setInt(0, iface)
        m.setInt(4, CONNECT)
        if (PS == 8) m.setLong(8, 0) else m.setInt(8, 0)
        return runCatching { ioctl(fd, IOCTL, m) >= 0 }.getOrDefault(false)
    }
}

/** 等時転送 1 回分の要求(URB)とそのデータ領域 */
internal class IsoUrb(val endpoint: Int, val packets: Int, val maxBytes: Int, val feedback: Boolean) {
    val urb = Memory((Usbfs.URB_SIZE + Usbfs.ISO_DESC_SIZE * packets).toLong())
    val buf = Memory(maxBytes.coerceAtLeast(4).toLong())
    val address: Long = Pointer.nativeValue(urb)
    /** このURBに入れた「本物の音声」のフレーム数(無音の埋め草は含まない) */
    var dataFrames = 0
    var epoch = 0
    var inFlight = false

    /** パケットの記述(長さ・送れた長さ・状態 × パケット数)。まとめて書き込み・読み出しする */
    private val desc = IntArray(packets * 3)

    /** パケットごとの長さを設定して、送信できる状態にする(ネイティブのメモリへの書き込みは数回にまとめる) */
    fun prepare(lengths: IntArray) {
        urb.clear(Usbfs.URB_SIZE.toLong())
        urb.setByte(Usbfs.OFF_TYPE.toLong(), Usbfs.URB_TYPE_ISO)
        urb.setByte(Usbfs.OFF_ENDPOINT.toLong(), endpoint.toByte())
        urb.setInt(Usbfs.OFF_FLAGS.toLong(), Usbfs.URB_ISO_ASAP)
        urb.setPointer(Usbfs.OFF_BUFFER.toLong(), buf)
        var total = 0
        for (i in 0 until packets) {
            desc[i * 3] = lengths[i]
            desc[i * 3 + 1] = 0
            desc[i * 3 + 2] = 0
            total += lengths[i]
        }
        urb.write(Usbfs.URB_SIZE.toLong(), desc, 0, packets * 3)
        urb.setInt(Usbfs.OFF_BUFLEN.toLong(), total)
        urb.setInt(Usbfs.OFF_NPACKETS.toLong(), packets)
    }

    /** 送り終えたあとのパケットの記述をまとめて読む(長さ・送れた長さ・状態の順に 3 つずつ) */
    fun readDescriptors(): IntArray {
        urb.read(Usbfs.URB_SIZE.toLong(), desc, 0, packets * 3)
        return desc
    }

    fun status(): Int = urb.getInt(Usbfs.OFF_STATUS.toLong())
    fun packetLength(i: Int): Int = urb.getInt((Usbfs.URB_SIZE + i * Usbfs.ISO_DESC_SIZE).toLong())
    fun packetActual(i: Int): Int = urb.getInt((Usbfs.URB_SIZE + i * Usbfs.ISO_DESC_SIZE + 4).toLong())
    fun packetStatus(i: Int): Int = urb.getInt((Usbfs.URB_SIZE + i * Usbfs.ISO_DESC_SIZE + 8).toLong())
}
