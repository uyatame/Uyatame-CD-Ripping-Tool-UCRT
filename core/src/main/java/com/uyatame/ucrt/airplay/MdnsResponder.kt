package com.uyatame.ucrt.airplay

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * 最小限の mDNS 公開/応答器。GoAirPlay の mdns.go の移植で、実機の iPhone /
 * Mac から発見できることを確認済みの手順をそのまま再現する。
 * LAN の IP アドレスだけを必要とし、そのアドレスでマルチキャストグループに
 * 参加して、送信 TTL を 255 にする (RFC 6762。iOS は TTL 255 でない
 * パケットを黙って捨てる)。
 */
internal class MdnsResponder(
    private val localIp: InetAddress,
    private val port: Int,
    hostName: String,
    private val services: List<Service>,
    private val log: (String) -> Unit = {},
) {
    /** service: "_raop._tcp.local"、instance: "<id>@<名前>._raop._tcp.local" の形。 */
    class Service(val service: String, val instance: String, val txt: List<String>)

    private val host: String = "$hostName.local"

    @Volatile
    private var running = false
    private var socket: MulticastSocket? = null
    private var announceThread: Thread? = null
    private var queryThread: Thread? = null

    fun start() {
        if (running) return
        val group = InetAddress.getByName(GROUP_IP)
        val sock = MulticastSocket(null)
        sock.reuseAddress = true
        sock.bind(InetSocketAddress(5353))
        val nif = NetworkInterface.getByInetAddress(localIp)
        if (nif != null) {
            sock.networkInterface = nif
            sock.joinGroup(InetSocketAddress(group, 5353), nif)
        } else {
            @Suppress("DEPRECATION")
            sock.joinGroup(group)
        }
        sock.timeToLive = 255
        socket = sock
        running = true
        announceThread = thread(isDaemon = true, name = "airplay-mdns-announce") { announceLoop() }
        queryThread = thread(isDaemon = true, name = "airplay-mdns-query") { queryLoop() }
        log("[mdns] publishing $host via ${localIp.hostAddress}")
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: Exception) {
            // 終了時の例外は無視する。
        }
        socket = null
        announceThread?.interrupt()
        announceThread = null
        queryThread = null
    }

    // ---------- DNS ワイヤ形式 ----------

    private class RR(val name: String, val rtype: Int, val rdata: ByteArray)

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in name.removeSuffix(".").split(".")) {
            val b = label.toByteArray(Charsets.UTF_8)
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
    }

    private fun nameBytes(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        writeName(out, name)
        return out.toByteArray()
    }

    private fun writeRR(out: ByteArrayOutputStream, name: String, rtype: Int, ttl: Int, rdata: ByteArray) {
        writeName(out, name)
        // SRV / TXT / A はユニークレコードなので cache-flush ビットを立てる。
        val clazz = if (rtype == 33 || rtype == 16 || rtype == 1) 0x8001 else 1
        out.write((rtype ushr 8) and 0xFF)
        out.write(rtype and 0xFF)
        out.write((clazz ushr 8) and 0xFF)
        out.write(clazz and 0xFF)
        out.write((ttl ushr 24) and 0xFF)
        out.write((ttl ushr 16) and 0xFF)
        out.write((ttl ushr 8) and 0xFF)
        out.write(ttl and 0xFF)
        out.write((rdata.size ushr 8) and 0xFF)
        out.write(rdata.size and 0xFF)
        out.write(rdata)
    }

    private fun allRecords(): List<RR> {
        val rrs = ArrayList<RR>()
        for (s in services) {
            rrs.add(RR(s.service, 12, nameBytes(s.instance))) // PTR
            val srv = ByteArrayOutputStream()
            srv.write(0); srv.write(0) // priority
            srv.write(0); srv.write(0) // weight
            srv.write((port ushr 8) and 0xFF)
            srv.write(port and 0xFF)
            writeName(srv, host)
            rrs.add(RR(s.instance, 33, srv.toByteArray())) // SRV
            val txt = ByteArrayOutputStream()
            for (t in s.txt) {
                val b = t.toByteArray(Charsets.UTF_8)
                txt.write(b.size)
                txt.write(b)
            }
            rrs.add(RR(s.instance, 16, if (txt.size() == 0) byteArrayOf(0) else txt.toByteArray())) // TXT
        }
        rrs.add(RR(host, 1, localIp.address)) // A
        for (s in services) {
            rrs.add(RR("_services._dns-sd._udp.local", 12, nameBytes(s.service)))
        }
        return rrs
    }

    private fun buildPacket(an: List<RR>, ar: List<RR>): ByteArray {
        val out = ByteArrayOutputStream()
        val hdr = ByteArray(12)
        hdr[2] = 0x84.toByte() // 応答 + authoritative
        hdr[3] = 0x00
        hdr[6] = (an.size ushr 8).toByte()
        hdr[7] = an.size.toByte()
        hdr[10] = (ar.size ushr 8).toByte()
        hdr[11] = ar.size.toByte()
        out.write(hdr)
        for (rr in an) writeRR(out, rr.name, rr.rtype, 120, rr.rdata)
        for (rr in ar) writeRR(out, rr.name, rr.rtype, 120, rr.rdata)
        return out.toByteArray()
    }

    // ---------- ループ ----------

    private fun announceLoop() {
        val group = InetAddress.getByName(GROUP_IP)
        fun send() {
            try {
                val pkt = buildPacket(allRecords(), emptyList())
                socket?.send(DatagramPacket(pkt, pkt.size, group, 5353))
            } catch (e: Exception) {
                if (running) log("[mdns] announce failed: ${e.message}")
            }
        }
        send()
        var count = 0
        while (running) {
            try {
                Thread.sleep(if (count < 2) 1000 else 60_000)
            } catch (e: InterruptedException) {
                return
            }
            if (!running) return
            send()
            count++
        }
    }

    /** 圧縮ポインタ対応の QNAME 読み取り。(名前, 次のオフセット) を返し、失敗時は null。 */
    private fun readQName(pkt: ByteArray, start: Int): Pair<String, Int>? {
        val labels = ArrayList<String>()
        var off = start
        var end = -1
        var jumped = false
        var jumps = 0
        while (true) {
            if (off >= pkt.size) return null
            val l = pkt[off].toInt() and 0xFF
            if (l == 0) {
                off++
                if (!jumped) end = off
                break
            }
            if (l and 0xC0 == 0xC0) {
                if (off + 1 >= pkt.size) return null
                val ptr = ((l and 0x3F) shl 8) or (pkt[off + 1].toInt() and 0xFF)
                if (!jumped) end = off + 2
                off = ptr
                jumped = true
                if (++jumps > 10) return null
                continue
            }
            off++
            if (off + l > pkt.size) return null
            labels.add(String(pkt, off, l, Charsets.UTF_8))
            off += l
        }
        return labels.joinToString(".") to end
    }

    private fun queryLoop() {
        val buf = ByteArray(9000)
        val group = InetAddress.getByName(GROUP_IP)
        while (running) {
            val sock = socket ?: return
            val packet = DatagramPacket(buf, buf.size)
            try {
                sock.receive(packet)
            } catch (e: Exception) {
                return // ソケットを閉じた (stop) など
            }
            val pkt = buf.copyOf(packet.length)
            if (pkt.size < 12) continue
            val flags = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
            if (flags and 0x8000 != 0) continue // 応答は無視する
            val qd = ((pkt[4].toInt() and 0xFF) shl 8) or (pkt[5].toInt() and 0xFF)
            var off = 12
            val queries = ArrayList<Pair<String, Int>>()
            var ok = true
            for (i in 0 until qd) {
                val parsed = readQName(pkt, off)
                if (parsed == null || parsed.second + 4 > pkt.size) {
                    ok = false
                    break
                }
                val rtype = ((pkt[parsed.second].toInt() and 0xFF) shl 8) or (pkt[parsed.second + 1].toInt() and 0xFF)
                queries.add(parsed.first to rtype)
                off = parsed.second + 4
            }
            if (!ok) continue
            val all = allRecords()
            for ((qname, qtype) in queries) {
                val an = ArrayList<RR>()
                val ar = ArrayList<RR>()
                for (rr in all) {
                    if (rr.name.equals(qname, ignoreCase = true) && (qtype == 255 || rr.rtype == qtype)) {
                        an.add(rr)
                    } else {
                        ar.add(rr)
                    }
                }
                if (an.isEmpty()) continue
                val resp = buildPacket(an, ar)
                try {
                    sock.send(DatagramPacket(resp, resp.size, packet.address, packet.port))
                    // 他の問い合わせ者も拾えるよう、マルチキャストでも流す。
                    sock.send(DatagramPacket(resp, resp.size, group, 5353))
                } catch (e: Exception) {
                    if (running) log("[mdns] query reply failed: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val GROUP_IP = "224.0.0.251"

        /** mDNS のホスト名に使える形に整える (英数とハイフン以外はハイフンへ)。 */
        fun sanitizeHostName(name: String): String {
            val cleaned = name.map { c -> if (c.isLetterOrDigit() || c == '-') c else '-' }.joinToString("")
            return cleaned.ifEmpty { "UCRT" }
        }
    }
}
