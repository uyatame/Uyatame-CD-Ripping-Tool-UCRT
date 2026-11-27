package com.uyatame.ucrt.airplay

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirPlay 送信の音声ストリーミング。GoAirPlay の sender.go (streamPCM と
 * 付随ループ) と alac_encode.go の Kotlin 移植。
 *
 * 352 サンプル/パケットの無圧縮 ALAC フレームを RAOP 暗号化して RTP で
 * 実時間ペーシング送信する。並行してタイミング交換 (NTP)、同期パケット、
 * 再送応答、RTSP キープアライブを回す。
 *
 * stream() はブロッキング。stop() を別スレッドから呼ぶと次の境界で止まる。
 */
internal class AirPlayStreamer(
    private val sender: AirPlaySender,
    private val host: String,
    private val log: (String) -> Unit = {},
) {
    private val random = SecureRandom()

    val seq0: Int
    val ts0: Int
    val ssrc: Int

    init {
        val seed = ByteArray(8).also { random.nextBytes(it) }
        seq0 = ((seed[0].toInt() and 0xFF) shl 8) or (seed[1].toInt() and 0xFF)
        ts0 = be32(seed, 2)
        ssrc = be32(seed, 4)
    }

    private val curTS = AtomicInteger(ts0)

    // 実行世代。stream() のたびに進み、stop() でも進む。
    // ループ類は自分の世代が現役の間だけ回る (前回の残りが新しい実行を邪魔しない)。
    @Volatile
    private var runGen = 0

    private fun alive(gen: Int) = runGen == gen

    /** 一時停止ゲート。true の間は送らずに待つ (位置も進まない)。 */
    @Volatile
    var paused: Boolean = false

    /** この実行で送ったフレーム数 (開始フレームからの相対)。 */
    @Volatile
    var sentFrames: Int = 0
        private set

    /** 最後まで送り切って終わったか (止められたのでなく)。 */
    @Volatile
    var finishedNaturally: Boolean = false
        private set

    private var seqNext: Int = -1

    private val recentLock = Any()
    private val recent = HashMap<Int, ByteArray>()
    private val recentOrder = ArrayDeque<Int>()

    private fun step(msg: String) {
        log("[airplay-send] $msg")
    }

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun putBe32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    private fun putBe16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = v.toByte()
    }

    /** 現在の RTP タイムスタンプ (progress 表示などに使う)。 */
    fun currentTimestamp(): Long = curTS.get().toLong() and 0xFFFF_FFFFL

    fun baseTimestamp(): Long = ts0.toLong() and 0xFFFF_FFFFL

    fun stop() {
        runGen++
    }

    /**
     * インターリーブ済みステレオ PCM (16bit) を最後まで送る。
     * totalSamples はチャンネルあたりのサンプル数。
     */
    fun stream(pcm: ShortArray, startFrame: Int = 0) {
        val gen = ++runGen
        val addr = InetAddress.getByName(host)
        val dataSocket = DatagramSocket()
        dataSocket.connect(InetSocketAddress(addr, sender.dataPort))
        val controlSocket = DatagramSocket(null)
        controlSocket.bind(InetSocketAddress(0))
        val threads = listOf(
            Thread { timingLoop(gen) }.apply { isDaemon = true; name = "airplay-timing" },
            Thread { syncLoop(gen, controlSocket, addr) }.apply { isDaemon = true; name = "airplay-sync" },
            Thread { retransmitLoop(gen, controlSocket) }.apply { isDaemon = true; name = "airplay-retransmit" },
            Thread { keepaliveLoop(gen) }.apply { isDaemon = true; name = "airplay-keepalive" },
        )
        threads.forEach { it.start() }
        try {
            val totalFrames = pcm.size / 2
            val intervalNs = 352L * 1_000_000_000L / 44100L
            var startNs = System.nanoTime()
            var seq = if (seqNext >= 0) seqNext else seq0
            var ts = curTS.get()
            var frame = startFrame.coerceIn(0, totalFrames)
            var sent = 0
            sentFrames = 0
            finishedNaturally = false
            while (frame < totalFrames && alive(gen)) {
                // 一時停止ゲート: 待っている間は時計も進めない。
                while (paused && alive(gen)) {
                    val t0 = System.nanoTime()
                    Thread.sleep(50)
                    startNs += System.nanoTime() - t0
                }
                if (!alive(gen)) break
                val framesThis = minOf(352, totalFrames - frame)
                val chunk = ShortArray(352 * 2)
                pcm.copyInto(chunk, 0, frame * 2, frame * 2 + framesThis * 2)
                val payload = encryptRAOP(sender.aesKey, sender.aesIV, encodeALACUncompressed(chunk))
                val pkt = ByteArray(12 + payload.size)
                pkt[0] = 0x80.toByte()
                pkt[1] = if (sent == 0) 0xE0.toByte() else 0x60
                putBe16(pkt, 2, seq)
                putBe32(pkt, 4, ts)
                putBe32(pkt, 8, ssrc)
                payload.copyInto(pkt, 12)
                sendData(dataSocket, pkt, sent, startNs)
                remember(seq, pkt)
                curTS.set(ts)
                seq = (seq + 1) and 0xFFFF
                seqNext = seq
                ts += 352
                frame += framesThis
                sent++
                sentFrames = frame - startFrame
                val target = startNs + sent * intervalNs
                val sleepNs = target - System.nanoTime()
                if (sleepNs > 0) {
                    Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                }
            }
            finishedNaturally = frame >= totalFrames
            step("streamed $sent RTP packets (from frame $startFrame of ${(pcm.size / 2) / 44100.0} s) to $host:${sender.dataPort}")
        } finally {
            if (runGen == gen) runGen++
            // ソケットを先に閉じると receive で待つループが即座に起きる。
            // 長い join で次の曲を待たせない (ループは全て daemon)。
            dataSocket.close()
            controlSocket.close()
            threads.forEach { it.join(300) }
        }
    }

    private fun remember(seq: Int, pkt: ByteArray) {
        synchronized(recentLock) {
            if (!recent.containsKey(seq)) recentOrder.addLast(seq)
            recent[seq] = pkt
            while (recentOrder.size > 1024) {
                val old = recentOrder.removeFirst()
                recent.remove(old)
            }
        }
    }

    // ---------- 付随ループ ----------

    private fun keepaliveLoop(gen: Int) {
        var waited = 0L
        while (alive(gen)) {
            Thread.sleep(200)
            waited += 200
            if (waited >= 20_000) {
                waited = 0
                if (alive(gen)) sender.keepalive()
            }
        }
    }

    private fun ntpNow(): Pair<Int, Int> {
        val nowMs = System.currentTimeMillis()
        val sec = (nowMs / 1000 + 2208988800L).toInt()
        val frac = ((nowMs % 1000) * 4294967296L / 1000).toInt()
        return sec to frac
    }

    private fun ntpToNs(sec: Int, frac: Int): Long {
        val s = sec.toLong() and 0xFFFF_FFFFL
        val f = frac.toLong() and 0xFFFF_FFFFL
        return s * 1_000_000_000L + f * 1_000_000_000L / 4294967296L
    }

    /**
     * RTP 送信。Wi-Fi の瞬断で sendto が EPERM 等を返すことがあるので
     * 少し待って最大 3 回まで再試行し、それでも駄目なら「何パケット目・
     * 何 ms 経過か」を付けて投げる (切り分け用)。
     */
    private fun sendData(sock: DatagramSocket, pkt: ByteArray, index: Int, startNs: Long) {
        var last: IOException? = null
        for (attempt in 0 until 3) {
            try {
                sock.send(DatagramPacket(pkt, pkt.size))
                return
            } catch (e: IOException) {
                last = e
                if (attempt < 2) {
                    try {
                        Thread.sleep(50)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("interrupted", ie)
                    }
                }
            }
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        throw IOException("UDP送信失敗: ${index}パケット/${elapsedMs}ms経過: ${last?.message}", last)
    }

    private fun timingLoop(gen: Int) {
        val timing = sender.timingSocket ?: return
        if (sender.serverTimingPort == 0) return
        val dst = InetSocketAddress(InetAddress.getByName(host), sender.serverTimingPort)
        val buf = ByteArray(64)
        var exchanges = 0
        while (alive(gen)) {
            try {
                val req = ByteArray(32)
                req[0] = 0x80.toByte()
                req[1] = 0xd2.toByte()
                val (t1sec, t1frac) = ntpNow()
                putBe32(req, 24, t1sec)
                putBe32(req, 28, t1frac)
                val t1 = ntpToNs(t1sec, t1frac)
                timing.send(DatagramPacket(req, req.size, dst))
                timing.soTimeout = 500
                val resp = DatagramPacket(buf, buf.size)
                timing.receive(resp)
                if (resp.length < 32) continue
                val (t4sec, t4frac) = ntpNow()
                val t4 = ntpToNs(t4sec, t4frac)
                val t2 = ntpToNs(be32(buf, 16), be32(buf, 20))
                val t3 = ntpToNs(be32(buf, 24), be32(buf, 28))
                val offset = ((t2 - t1) + (t3 - t4)) / 2
                val rtt = (t4 - t1) - (t3 - t2)
                exchanges++
                if (exchanges <= 3 || exchanges % 20 == 0) {
                    step("timing #$exchanges: clock offset ${offset / 1_000_000.0}ms, rtt ${rtt / 1_000_000.0}ms")
                }
            } catch (e: Exception) {
                // タイムアウト等は無視して次周期へ。
            }
            var waited = 0L
            while (alive(gen) && waited < 3000) {
                Thread.sleep(100)
                waited += 100
            }
        }
    }

    private fun syncLoop(gen: Int, controlSocket: DatagramSocket, addr: InetAddress) {
        val dst = InetSocketAddress(addr, sender.controlPort)
        while (alive(gen)) {
            Thread.sleep(1000)
            if (!alive(gen)) break
            try {
                val pkt = ByteArray(20)
                pkt[0] = 0x80.toByte()
                pkt[1] = 0xD4.toByte()
                val now = curTS.get()
                putBe32(pkt, 4, now)
                val (sec, frac) = ntpNow()
                putBe32(pkt, 8, sec)
                putBe32(pkt, 12, frac)
                putBe32(pkt, 16, now + 44100)
                controlSocket.send(DatagramPacket(pkt, pkt.size, dst))
            } catch (e: Exception) {
                // 送信失敗は無視する。
            }
        }
    }

    private fun retransmitLoop(gen: Int, controlSocket: DatagramSocket) {
        val buf = ByteArray(2048)
        while (alive(gen)) {
            try {
                controlSocket.soTimeout = 1000
                val req = DatagramPacket(buf, buf.size)
                controlSocket.receive(req)
                if (req.length < 8) continue
                if ((buf[1].toInt() and 0x7F) != 0x55) continue
                val lostSeq = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
                val lostCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
                step("retransmit request: seq=$lostSeq count=$lostCount")
                for (i in 0 until lostCount) {
                    val s = (lostSeq + i) and 0xFFFF
                    val orig = synchronized(recentLock) { recent[s] } ?: continue
                    val resp = ByteArray(4 + orig.size)
                    resp[0] = 0x80.toByte()
                    resp[1] = 0xD6.toByte()
                    putBe16(resp, 2, s)
                    orig.copyInto(resp, 4)
                    controlSocket.send(DatagramPacket(resp, resp.size, req.address, req.port))
                }
            } catch (e: Exception) {
                // タイムアウトは通常動作。
            }
        }
    }

    companion object {
        // ---------- ALAC 無圧縮エンコーダ (alac_encode.go の移植) ----------

        private class BitWriter {
            private val buf = ArrayList<Byte>()
            private var acc = 0
            private var nbits = 0

            fun write(v: Int, bits: Int) {
                acc = (acc shl bits) or (v and ((1 shl bits) - 1))
                nbits += bits
                while (nbits >= 8) {
                    buf.add((acc ushr (nbits - 8)).toByte())
                    nbits -= 8
                    acc = acc and ((1 shl nbits) - 1)
                }
            }

            fun bytes(): ByteArray {
                if (nbits > 0) {
                    buf.add((acc shl (8 - nbits)).toByte())
                    nbits = 0
                    acc = 0
                }
                return ByteArray(buf.size) { buf[it] }
            }
        }

        /** インターリーブ済みステレオ PCM を 1 つの無圧縮 ALAC フレームにする。 */
        fun encodeALACUncompressed(pcm: ShortArray): ByteArray {
            val w = BitWriter()
            w.write(1, 3)   // element ID = CPE
            w.write(0, 4)   // instance tag
            w.write(0, 12)  // reserved
            w.write(0, 1)   // hassize = 0 (352 samples)
            w.write(0, 2)   // uncompressed_bytes
            w.write(1, 1)   // isnotcompressed
            for (s in pcm) {
                w.write(s.toInt() and 0xFFFF, 16)
            }
            return w.bytes()
        }

        /** RAOP 暗号化: AES-CBC (完全ブロックのみ、末尾の端数は平文)。 */
        fun encryptRAOP(key: ByteArray, iv: ByteArray, payload: ByteArray): ByteArray {
            val out = payload.copyOf()
            val blocks = payload.size / 16
            if (blocks == 0) return out
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val enc = cipher.doFinal(payload, 0, blocks * 16)
            enc.copyInto(out, 0)
            return out
        }
    }
}
