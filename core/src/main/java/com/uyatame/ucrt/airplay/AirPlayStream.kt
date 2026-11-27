package com.uyatame.ucrt.airplay

import com.uyatame.ucrt.player.AlacDecoder
import java.net.DatagramPacket
import java.net.DatagramSocket
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * AirPlay の UDP 受信 (GoAirPlay の setup.go の移植)。
 * SETUP 1 でタイミングサーバー (6002)、SETUP 2 でデータ (6000) と
 * 制御 (6001) を起動する。データは RAOP 復号 → ALAC デコード →
 * インターリーブ 16bit PCM のフレームキューへ。制御パケットは受けて捨てる
 * (GoAirPlay と同じ)。統計は 500 パケットごとにログへ出す。
 */
internal class AirPlayStream(
    private val log: (String) -> Unit,
) {
    /** デコード済み PCM フレームのキュー。1 フレーム = spf サンプル × 全 ch。 */
    internal class FrameQueue(private val capacityFrames: Int) {
        private val frames = ArrayDeque<ByteArray>()

        @Synchronized
        fun offer(frame: ByteArray) {
            while (frames.size >= capacityFrames) frames.removeFirst() // 古いものから捨てる
            frames.addLast(frame)
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (this as Object).notifyAll()
        }

        /** 最大 timeoutMs 待って 1 フレーム取る。時間切れは null。 */
        @Synchronized
        fun poll(timeoutMs: Long): ByteArray? {
            if (frames.isEmpty() && timeoutMs > 0) {
                try {
                    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                    (this as Object).wait(timeoutMs)
                } catch (e: InterruptedException) {
                    return null
                }
            }
            return if (frames.isEmpty()) null else frames.removeFirst()
        }

        @Synchronized
        fun clear() {
            frames.clear()
        }
    }

    /** 約 1.5 秒分 (352 サンプル/フレーム ≒ 8ms なので 190 フレーム)。 */
    val pcmFrames = FrameQueue(190)

    @Volatile
    private var aesKey: ByteArray? = null

    @Volatile
    private var aesIV: ByteArray? = null

    @Volatile
    private var streamCt: Int = 0

    @Volatile
    private var streamSpf: Int = 0

    private var timingSocket: DatagramSocket? = null
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null

    // 統計 (データスレッドだけが触る)
    private var dataPkts = 0L
    private var decodeErrs = 0L
    private var peak = 0

    fun setKey(key: ByteArray, iv: ByteArray) {
        aesKey = key.copyOf()
        aesIV = iv.copyOf()
    }

    /** SETUP 2 の音声ストリーム設定。ct=2 (ALAC) ならデコーダーを用意する。 */
    fun configure(ct: Int, spf: Int) {
        streamCt = ct
        streamSpf = spf
        if (ct == 2) {
            // GoAirPlay と同じ: fmtp は "spf 0 16 40 10 14 2 255 0 0 44100" 固定。
            val dec = AlacDecoder(EmptyRandomInput)
            dec.openRaw(
                frameLength = if (spf > 0) spf else 352,
                bitDepth = 16, pb = 40, mb = 10, kb = 14,
                channels = 2, sampleRate = 44100,
            )
            decoder = dec
            log("[setup] ALAC decoder ready (spf=$spf)")
        } else {
            decoder = null
            log("[setup] stream ct=$ct is not ALAC - decode skipped for now")
        }
    }

    private var decoder: AlacDecoder? = null

    /** TEARDOWN 時: 溜まった音を捨てる (ソケットは生かしたまま)。 */
    fun reset() {
        pcmFrames.clear()
    }

    fun stopAll() {
        for (s in listOf(timingSocket, dataSocket, controlSocket)) {
            try {
                s?.close()
            } catch (e: Exception) {
                // 終了時の例外は無視する。
            }
        }
        timingSocket = null
        dataSocket = null
        controlSocket = null
    }

    // ---------- タイミング (NTP) ----------

    @Synchronized
    fun startTiming() {
        if (timingSocket != null) return
        val sock = DatagramSocket(UDP_TIMING_PORT)
        timingSocket = sock
        log("[ntp] timing server on :$UDP_TIMING_PORT")
        thread(isDaemon = true, name = "airplay-timing") {
            val buf = ByteArray(64)
            var count = 0L
            while (true) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(packet)
                } catch (e: Exception) {
                    return@thread
                }
                count++
                if (count <= 5) log("[ntp] request #$count len=${packet.length} from ${packet.address}")
                val resp = buildTimingResponse(buf, packet.length) ?: continue
                try {
                    sock.send(DatagramPacket(resp, resp.size, packet.address, packet.port))
                } catch (e: Exception) {
                    log("[ntp] reply write error: ${e.message}")
                }
            }
        }
    }

    // ---------- 制御 (受けて捨てる) ----------

    @Synchronized
    fun startDataControl() {
        if (dataSocket == null) {
            val sock = DatagramSocket(UDP_DATA_PORT)
            dataSocket = sock
            log("[rtp] data server on :$UDP_DATA_PORT")
            thread(isDaemon = true, name = "airplay-data") { dataLoop(sock) }
        }
        if (controlSocket == null) {
            val sock = DatagramSocket(UDP_CONTROL_PORT)
            controlSocket = sock
            log("[ctrl] control server on :$UDP_CONTROL_PORT")
            thread(isDaemon = true, name = "airplay-control") {
                val buf = ByteArray(1024)
                var count = 0L
                while (true) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(packet)
                    } catch (e: Exception) {
                        return@thread
                    }
                    count++
                    if (count <= 3) {
                        val head = buf.copyOf(minOf(20, packet.length))
                        log("[ctrl] pkt #$count len=${packet.length} first bytes: ${head.joinToString("") { "%02x".format(it) }}")
                    }
                }
            }
        }
    }

    private fun dataLoop(sock: DatagramSocket) {
        val buf = ByteArray(4096)
        while (true) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                sock.receive(packet)
            } catch (e: Exception) {
                return
            }
            val n = packet.length
            val key = aesKey
            val iv = aesIV
            if (key == null || iv == null) continue
            dataPkts++
            if (dataPkts <= 3) log("[rtp] pkt #$dataPkts len=$n")
            if (n <= 12) continue
            val payload = buf.copyOfRange(12, n)
            val plain = decryptRaop(key, iv, payload)
            val dec = decoder
            if (dec != null && streamCt == 2) {
                val pcm = decodeFrame(dec, plain, dataPkts)
                if (pcm != null && pcm.isNotEmpty()) {
                    pcmFrames.offer(pcm)
                }
            }
            if (dataPkts % 500 == 0L) {
                log("[rtp] $dataPkts packets, peak=$peak, decode errors=$decodeErrs")
                peak = 0
            }
        }
    }

    /** ALAC フレームを 1 つデコードし、インターリーブ 16bit LE の PCM にする。 */
    private fun decodeFrame(dec: AlacDecoder, frame: ByteArray, pktNo: Long): ByteArray? {
        val samples: Int
        try {
            samples = dec.decodePacket(frame, 0, frame.size)
        } catch (e: Exception) {
            decodeErrs++
            if (decodeErrs <= 3) log("[alac] decode error: ${e.message}")
            return null
        }
        if (samples <= 0) return null
        val ch = dec.channels
        val out = ByteArray(samples * ch * 2)
        var o = 0
        for (i in 0 until samples) {
            for (c in 0 until ch) {
                val v = dec.samples[c][i]
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
                out[o++] = (v and 0xFF).toByte()
                out[o++] = ((v ushr 8) and 0xFF).toByte()
            }
        }
        if (pktNo <= 3) log("[rtp] pkt #$pktNo decoded pcm=${out.size} bytes, decode errors=$decodeErrs")
        return out
    }

    companion object {
        const val UDP_TIMING_PORT = 6002
        const val UDP_CONTROL_PORT = 6001
        const val UDP_DATA_PORT = 6000

        private const val NTP_EPOCH_OFFSET = 2208988800L // 1900 年から 1970 年までの秒数

        /** NTP の現在時刻 (秒, 小数部)。ミリ秒精度で十分間に合う。 */
        private fun ntpNow(): Pair<Long, Long> {
            val nowMs = System.currentTimeMillis()
            val sec = nowMs / 1000 + NTP_EPOCH_OFFSET
            val frac = (nowMs % 1000) * (1L shl 32) / 1000
            return sec to frac
        }

        /**
         * タイミング要求への応答を作る (GoAirPlay の runTimingServer の移植)。
         * 32 バイト未満なら null。
         */
        fun buildTimingResponse(req: ByteArray, n: Int): ByteArray? {
            if (n < 32) return null
            val res = ByteArray(32)
            System.arraycopy(req, 0, res, 0, minOf(n, 32))
            val (recvSec, recvFrac) = ntpNow()
            res[0] = 0x80.toByte()
            res[1] = 0xD3.toByte() // タイミング応答
            // origin = クライアントの送信時刻 (要求の 24..31 バイト)
            System.arraycopy(req, 24, res, 8, 8)
            putU32(res, 16, recvSec)
            putU32(res, 20, recvFrac)
            val (sendSec, sendFrac) = ntpNow()
            putU32(res, 24, sendSec)
            putU32(res, 28, sendFrac)
            return res
        }

        private fun putU32(b: ByteArray, off: Int, v: Long) {
            b[off] = (v ushr 24).toByte()
            b[off + 1] = (v ushr 16).toByte()
            b[off + 2] = (v ushr 8).toByte()
            b[off + 3] = v.toByte()
        }

        /**
         * RAOP の音声ペイロードを復号する: 16 バイトブロック分は AES-CBC、
         * 端数のブロックはそのまま (GoAirPlay の decryptRAOP の移植)。
         */
        fun decryptRaop(key: ByteArray, iv: ByteArray, payload: ByteArray): ByteArray {
            val out = payload.copyOf()
            val blocks = payload.size / 16
            if (blocks == 0) return out
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val dec = cipher.doFinal(payload, 0, blocks * 16)
            System.arraycopy(dec, 0, out, 0, dec.size)
            return out
        }
    }

    /** openRaw 専用に使う、読み取りをしない RandomInput。 */
    private object EmptyRandomInput : com.uyatame.ucrt.player.RandomInput {
        override val size: Long = 0
        override fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int = -1
    }
}
