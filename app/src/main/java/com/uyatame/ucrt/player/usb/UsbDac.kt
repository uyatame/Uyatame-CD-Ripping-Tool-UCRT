package com.uyatame.ucrt.player.usb

import android.app.PendingIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.player.AudioEngine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** USB DAC に送る形式 */
internal class DacFormat(val rate: Int, val channels: Int, val subslot: Int, val bits: Int, val dsd: Boolean = false) {
    val frameBytes get() = channels * subslot
    /** データが足りないときに埋める値(ネイティブ DSD は無音パターン 0x69) */
    val fill: Byte get() = if (dsd) 0x69 else 0
    fun same(o: DacFormat?) = o != null && o.rate == rate && o.channels == channels && o.subslot == subslot && o.bits == bits && o.dsd == dsd
}

/**
 * Android の音声機能を通さず、USB オーディオクラス(UAC1 / UAC2)の DAC を直接動かす独自ドライバー。
 * 使用中は DAC を独占する(通知音なども DAC からは鳴らない)。
 */
object UsbDac {
    private const val ACTION_PERMISSION = "com.uyatame.ucrt.DAC_PERMISSION"
    private val main = Handler(Looper.getMainLooper())

    private lateinit var app: Context
    private var inited = false

    /** 許可が得られたとき・DAC が外れたときなどに呼ばれる(再生をやり直す合図) */
    @Volatile var onChanged: (() -> Unit)? = null
    /** 使用中の DAC が外れたとき */
    @Volatile var onDetached: (() -> Unit)? = null
    /** DAC を直接使っている状態が変わったとき(音量キーの切り替え用) */
    @Volatile var onActiveChanged: ((Boolean) -> Unit)? = null
    /**
     * USB の転送の取りこぼしが続いたとき(メインスレッドで呼ぶ)。知らせる文と、周波数を下げたか(下げたら出力を作り直す)
     */
    @Volatile var onTransferTrouble: ((String, Boolean) -> Unit)? = null

    /**
     * 取りこぼしが続いたために下げた、アップサンプリングの上限の周波数(この値未満にする)。
     * DAC ごとに覚え、別の DAC をつないだら使わない。アプリを起動し直すと元に戻る
     */
    private var capRate = 0
    private var capKey: String? = null
    /** 最後に準備したときの音源の周波数(下げられるか=アップサンプリングしているかの判断用) */
    @Volatile private var lastSrcRate = 0

    @Volatile var active = false
        private set
    /** 開くたびに増える(古い「閉じる」要求で開き直した DAC を閉じないため) */
    @Volatile var openGen = 0
        private set
    /** この時刻までは「イヤホンが外れた」通知を無視する(DAC を掴む・離すときに出るため) */
    @Volatile var ignoreNoisyUntil = 0L
        private set

    private var device: UsbDevice? = null
    private var conn: UsbDeviceConnection? = null
    private var info: UacInfo? = null
    private var claimed = ArrayList<UsbInterface>()
    private var rates: List<Int> = emptyList()
    private var speed = 0
    /** 許可を求めた機器(受信のスレッドと再生のスレッドの両方から触るので、同時に使える集合にする) */
    private val asked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private var format: DacFormat? = null
    private var curAlt: UacAlt? = null
    /** 再生のスレッドからも読むので @Volatile */
    @Volatile private var stream: StreamThread? = null
    /**
     * 回収できなかった(機器が外れたなど)転送要求。カーネルが後で書き込むかもしれないので、
     * 接続を閉じるまでは解放されないように持っておく(解放済みのメモリに書かれて壊れないように)
     */
    private val orphanUrbs = ArrayList<IsoUrb>()

    // 音量
    @Volatile var hwVolume = false
        private set
    private var volUnit: FeatureUnit? = null
    private var volMin = -12800
    private var volMax = 0
    @Volatile private var volumePercent = -1
    /** ハードウェア音量が無いときに使うソフトウェア音量(倍率) */
    @Volatile var softGain = 1.0
        private set

    private val lock = Object()

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val dev = IntentCompat.getParcelableExtra(i, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                when (i.action) {
                    ACTION_PERMISSION -> {
                        val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        AudioEngine.log("usb dac permission: $ok")
                        if (ok) onChanged?.invoke()
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        if (dev != null && dev.deviceName == device?.deviceName) {
                            AudioEngine.log("usb dac detached")
                            Thread { close(reattach = false); onDetached?.invoke() }.start()
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        if (dev != null && isDac(dev)) {
                            // 挿し直したら、改めて許可を求められるようにする
                            asked.remove(dev.deviceName)
                            // ビットパーフェクト再生がオンなら、再生を始める前に許可をもらっておく
                            if (AudioEngine.wantUsbDirect) requestPermissionIfNeeded()
                            main.postDelayed({ onChanged?.invoke() }, 500)
                        }
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(app, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun usb(): UsbManager = androidx.core.content.ContextCompat.getSystemService(app, UsbManager::class.java)!!

    /**
     * USB DAC がつながっていて、まだ使用の許可が無ければ許可を求める。
     * 再生を始める前に許可を済ませておくことで、再生開始時に止まったり切り替わったりしないようにする。
     */
    fun requestPermissionIfNeeded() {
        if (!inited) return
        val dev = findDac() ?: return
        if (usb().hasPermission(dev)) return
        if (!asked.add(dev.deviceName)) return
        val pi = PendingIntent.getBroadcast(
            app, 7, Intent(ACTION_PERMISSION).setPackage(app.packageName),
            com.uyatame.ucrt.mutablePendingFlags(),
        )
        main.post { runCatching { usb().requestPermission(dev, pi) } }
        AudioEngine.log("usb dac: requesting permission for ${dev.productName}")
    }

    /** USB オーディオの再生用インターフェースを持つ機器か */
    private fun isDac(d: UsbDevice): Boolean {
        for (k in 0 until d.interfaceCount) {
            val it = d.getInterface(k)
            if (it.interfaceClass == 1 && it.interfaceSubclass == 2) {
                for (e in 0 until it.endpointCount) {
                    val ep = it.getEndpoint(e)
                    if (ep.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        ep.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT
                    ) return true
                }
            }
        }
        return false
    }

    /** 対応している最大の形式(「最大 32bit / 768kHz」。DAC を開いたことがなければ空) */
    fun maxSpec(): String {
        val inf = info ?: return lastSpec
        val bits = inf.alts.filter { !it.raw }.maxOfOrNull { it.bits } ?: 0
        val rate = rates.maxOrNull() ?: 0
        if (rate <= 0) return lastSpec
        val dsd = inf.alts.any { it.raw }
        lastSpec = S(R.string.up_to) + (if (bits > 0) "${bits}bit / " else "") +
            com.uyatame.ucrt.player.OutputDevices.khz(rate) + (if (dsd) " · DSD" else "")
        return lastSpec
    }
    @Volatile private var lastSpec = ""

    /** 使用中の DAC の名前 */
    fun deviceName(): String? = device?.productName?.trim()?.ifEmpty { null }

    fun findDac(): UsbDevice? = runCatching { usb().deviceList.values.firstOrNull { isDac(it) } }.getOrNull()

    /** 音量(0〜100)。-1 は自動(USB から音量を変えられる DAC は 50、変えられない DAC は 100) */
    fun setVolume(percent: Int) {
        val v = if (percent < 0) -1 else percent.coerceIn(0, 100)
        if (v == volumePercent && inited) return
        volumePercent = v
        runCatching { volExec.execute { synchronized(lock) { applyVolume() } } }
    }

    /** 今の音量(自動のときは実際に使っている値) */
    fun volume(): Int = if (volumePercent < 0) (if (hwVolume) 50 else 100) else volumePercent

    /** 0〜100 を dB に。上の方ほど細かく調整できるよう二乗のカーブにする(50 で -15 dB) */
    private fun percentToDb(p: Int): Double = -60.0 * (1 - p / 100.0) * (1 - p / 100.0)

    private fun suppressNoisy() { ignoreNoisyUntil = SystemClock.elapsedRealtime() + 4000 }

    /** 画面表示用: DAC を使っているか(active と同じ。画面が変化を受け取れるように) */
    var inUse by androidx.compose.runtime.mutableStateOf(false)
        private set

    private fun updateActive(v: Boolean) {
        if (active == v) return
        active = v
        main.post {
            inUse = v
            onActiveChanged?.invoke(v)
        }
    }

    /**
     * PCM 用に準備する。使えなければ null(呼び出し側は通常の出力に切り替える)。exact なら周波数の変換もソフトウェア音量も使わない(DoP 用)。
     * darMaxRate が 0 より大きければ(DAR の PCM アップサンプリング)、その周波数までで DAC が対応するいちばん高い整数倍に上げる。
     */
    internal fun prepare(srcRate: Int, srcCh: Int, srcBits: Int, exact: Boolean = false, darMaxRate: Int = 0): DacFormat? {
        return synchronized(lock) { prepareLocked(srcRate, srcCh, srcBits, exact, darMaxRate) }
    }

    /** ネイティブ DSD 用に準備する。DAC が対応していなければ null */
    internal fun prepareDsd(dsdRate: Int, ch: Int): DacFormat? {
        return synchronized(lock) {
            if (!ensureOpen()) return@synchronized null
            val inf = info ?: return@synchronized null
            val cands = inf.alts.filter { it.raw && it.channels == ch }
            var pick: UacAlt? = null
            var rate = 0
            for (a in cands.sortedByDescending { it.subslot }) {
                val r = dsdRate / (8 * a.subslot)
                if (supports(a, r)) { pick = a; rate = r; break }
            }
            val alt = pick
            if (alt == null) {
                AudioEngine.log("usb dac: native DSD not available for ${dsdRate} Hz ${ch}ch (raw alts: ${cands.size})")
                return@synchronized null
            }
            if (!hwVolume) {
                // DSD には音量を掛けられないので、音量は DAC 本体のボタンなどで調整してもらう
                AudioEngine.log("usb dac: native DSD without USB volume control (use the DAC's own volume)")
            }
            val fmt = DacFormat(rate, alt.channels, alt.subslot, alt.subslot * 8, dsd = true)
            if (!fmt.same(format) || curAlt !== alt || stream?.isAlive != true) {
                stopStream()
                if (!startStream(alt, fmt)) return@synchronized null
            }
            format
        }
    }

    /** DAC を開く(まだなら)。使えなければ false */
    private fun ensureOpen(): Boolean {
        if (!inited) {
            AudioEngine.log("usb dac: not initialized")
            return false
        }
        val dev = findDac()
        if (dev == null) {
            AudioEngine.log("usb dac: no USB DAC found")
            AudioEngine.report(false, S(R.string.usb_dac_not_found_using))
            close(reattach = true)
            return false
        }
        if (!usb().hasPermission(dev)) {
            AudioEngine.log("usb dac: no permission for ${dev.deviceName}")
            if (asked.add(dev.deviceName)) {
                val pi = PendingIntent.getBroadcast(
                    app, 7, Intent(ACTION_PERMISSION).setPackage(app.packageName),
                    com.uyatame.ucrt.mutablePendingFlags(),
                )
                main.post { runCatching { usb().requestPermission(dev, pi) } }
            }
            AudioEngine.report(false, S(R.string.allow_access_to_usb_dac))
            return false
        }
        if (device?.deviceName != dev.deviceName || conn == null) {
            close(reattach = true)
            if (!open(dev)) {
                close(reattach = true)
                return false
            }
        }
        return info != null
    }

    private fun devKey(): String? = device?.let { "%04x:%04x".format(it.vendorId, it.productId) }

    /** 取りこぼし対策で周波数を下げているときの上限(下げていなければ 0) */
    private fun cap(): Int = if (capRate > 0 && capKey == devKey()) capRate else 0

    /** 取りこぼしのために周波数を下げているときの、再生画面に出す説明(下げていなければ空) */
    internal fun capNotice(rate: Int): String {
        val c = cap()
        if (c <= 0 || rate >= c) return ""
        return S(R.string.usb_transfers_kept_failing_so, khz(rate))
    }

    /** 転送のスレッドから: 取りこぼしが続いている */
    private fun transferTrouble(rate: Int) = main.post {
        if (stream == null || format?.rate != rate) return@post
        val src = lastSrcRate
        if (src in 1 until rate) {
            // アップサンプリングしているので、1 段下の周波数にする
            capRate = rate
            capKey = devKey()
            AudioEngine.log("usb dac: transfers keep failing at ${khz(rate)}, lowering the rate")
            onTransferTrouble?.invoke(
                S(R.string.transfers_to_usb_dac_keep, khz(rate)),
                true,
            )
        } else {
            AudioEngine.log("usb dac: transfers keep failing at ${khz(rate)} (source rate, can't lower)")
            onTransferTrouble?.invoke(
                S(R.string.transfers_to_usb_dac_keep_2),
                false,
            )
        }
    }

    private fun prepareLocked(srcRate: Int, srcCh: Int, srcBits: Int, exact: Boolean, darMaxRate: Int): DacFormat? {
        if (!ensureOpen()) return null
        val inf = info ?: return null
        lastSrcRate = srcRate
        // 形式(代替設定)を選ぶ
        val wantBits = if (srcBits in 1..32) srcBits else 24
        val pcmAlts = inf.alts.filter { !it.raw && it.subslot >= 2 }
        val chOk = pcmAlts.filter { it.channels == srcCh }.ifEmpty { pcmAlts.filter { it.channels == 2 } }
        if (chOk.isEmpty()) {
            AudioEngine.report(false, S(R.string.this_dac_does_not_support, srcCh))
            close(reattach = true)
            return null
        }
        // サンプリング周波数: そのまま使えなければ整数倍に上げる。
        // DAR のときは、上限までで対応しているいちばん高い倍率を先に探す
        var rate = 0
        var alt: UacAlt? = null
        val muls = (if (darMaxRate > 0 && !exact) (16 downTo 2).filter { srcRate.toLong() * it <= darMaxRate } else emptyList()) +
            (1..(if (exact) 1 else 8))
        // 取りこぼしで下げたときは、上限未満の倍率を先に試す(どれも合わなければ、上限を気にせず選ぶ)
        val c = cap()
        val capped = if (c > 0) muls.filter { it == 1 || srcRate.toLong() * it < c } else emptyList()
        for (mul in capped + muls) {
            val r = srcRate * mul
            val cands = chOk.filter { supports(it, r) }
            if (cands.isNotEmpty()) {
                rate = r
                // アップサンプリングするときは計算で細かい値が出るので、いちばん細かいビット数で送る
                alt = (if (mul > 1) null else cands.filter { it.bits >= wantBits }.minByOrNull { it.subslot * 100 + it.bits })
                    ?: cands.maxByOrNull { it.bits * 10 + it.subslot }
                break
            }
        }
        if (alt == null) {
            AudioEngine.report(
                false,
                S(R.string.this_dac_does_not_support_2, khz(srcRate)),
            )
            if (!exact) close(reattach = true)
            return null
        }
        if (exact && alt.bits < wantBits) {
            AudioEngine.log("usb dac: no ${wantBits}bit format for exact output")
            return null
        }
        val fmt = DacFormat(rate, alt.channels, alt.subslot, alt.bits)
        if (!fmt.same(format) || curAlt !== alt || stream?.isAlive != true) {
            stopStream()
            if (!startStream(alt, fmt)) {
                close(reattach = true)
                return null
            }
        }
        return format
    }

    private fun supports(a: UacAlt, r: Int): Boolean {
        val inf = info ?: return false
        if (!inf.uac2) {
            return if (a.rates.isNotEmpty()) r in a.rates else r in a.rateMin..a.rateMax
        }
        return r in rates
    }

    private fun open(dev: UsbDevice): Boolean {
        val c = usb().openDevice(dev) ?: run {
            AudioEngine.log("usb dac: openDevice failed")
            return false
        }
        conn = c
        device = dev
        val raw = c.rawDescriptors
        val inf = raw?.let { UacDescriptors.parse(it) }
        if (inf == null) {
            AudioEngine.log("usb dac: no playable streaming interface")
            return false
        }
        info = inf
        speed = Usbfs.speed(c.fileDescriptor)
        val sb = StringBuilder()
        sb.append("usb dac: ${dev.productName} ${"%04X:%04X".format(dev.vendorId, dev.productId)} ")
            .append(if (inf.uac2) "UAC2" else "UAC1").append(" speed=$speed ac=if${inf.acIface}\n")
        inf.alts.forEach { sb.append("  ").append(it).append('\n') }
        // 制御インターフェースと再生インターフェースを、Android のドライバーから切り離して確保する
        suppressNoisy()
        val ifaces = (listOf(inf.acIface) + inf.alts.map { it.iface }).distinct()
        for (n in ifaces) {
            val ui = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { it.id == n && it.alternateSetting == 0 }
                ?: (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { it.id == n }
            if (ui == null || !c.claimInterface(ui, true)) {
                sb.append("  claim if$n failed\n")
                AudioEngine.log(sb.toString().trimEnd())
                return false
            }
            claimed.add(ui)
            // 再生インターフェースは帯域を使わない代替設定 0 から始める
            if (n != inf.acIface && ui.alternateSetting == 0) runCatching { c.setInterface(ui) }
        }
        // 対応周波数
        // UAC1 で連続値の代替設定は、範囲に入るよく使う周波数を並べる(表示用。対応の判定は代替設定ごとに行う)
        rates = if (inf.uac2) queryUac2Rates(sb) else inf.alts.flatMap { a ->
            if (a.rates.isNotEmpty()) a.rates else COMMON_RATES.filter { it in a.rateMin..a.rateMax }
        }.distinct().sorted()
        sb.append("  rates: ").append(rates.joinToString(" ") { khz(it) }).append('\n')
        setupVolume(sb)
        AudioEngine.log(sb.toString().trimEnd())
        openGen++
        updateActive(true)
        return true
    }

    /** UAC2: 再生用端子につながるクロック ID を探す */
    private fun clockFor(alt: UacAlt?): Int {
        val inf = info ?: return -1
        var id = alt?.let { inf.terminalClock[it.terminalLink] } ?: inf.terminalClock.values.firstOrNull() ?: inf.clockSources.firstOrNull() ?: -1
        repeat(8) {
            if (id in inf.clockSources) return id
            val sel = inf.clockSelectors[id]
            if (sel != null) {
                val cur = ctrlIn(0x01, 0x0100, (id shl 8) or inf.acIface, 1)?.let { (it[0].toInt() and 0xFF) } ?: 1
                id = sel.getOrNull(cur - 1) ?: sel.firstOrNull() ?: return -1
                return@repeat
            }
            val mul = inf.clockMultipliers[id]
            if (mul != null) { id = mul; return@repeat }
            return id
        }
        return id
    }

    private fun queryUac2Rates(sb: StringBuilder): List<Int> {
        val inf = info ?: return emptyList()
        val clk = clockFor(inf.alts.firstOrNull())
        if (clk < 0) return emptyList()
        val head = ctrlIn(0x02, 0x0100, (clk shl 8) or inf.acIface, 2)?.takeIf { it.size >= 2 } ?: run {
            sb.append("  clock $clk range request failed\n")
            return listOf(44100, 48000, 88200, 96000, 176400, 192000)
        }
        val fallback = listOf(44100, 48000, 88200, 96000, 176400, 192000)
        // 範囲の数(おかしな値で大きすぎる要求を出さないよう抑える)
        val n = ((head[0].toInt() and 0xFF) or ((head[1].toInt() and 0xFF) shl 8)).coerceAtMost(256)
        val full = ctrlIn(0x02, 0x0100, (clk shl 8) or inf.acIface, 2 + n * 12) ?: run {
            sb.append("  clock $clk range request failed\n")
            return fallback
        }
        val out = ArrayList<Int>()
        val common = COMMON_RATES
        for (k in 0 until n) {
            val o = 2 + k * 12
            if (o + 12 > full.size) break
            fun le(p: Int) = (full[p].toInt() and 0xFF) or ((full[p + 1].toInt() and 0xFF) shl 8) or
                ((full[p + 2].toInt() and 0xFF) shl 16) or ((full[p + 3].toInt() and 0xFF) shl 24)
            val mn = le(o); val mx = le(o + 4); val res = le(o + 8)
            if (mn == mx) out += mn
            else common.filter { it in mn..mx && (res <= 0 || (it - mn) % res == 0) }.forEach { out += it }
        }
        if (out.isEmpty()) {
            sb.append("  clock $clk reported no rates\n")
            return fallback
        }
        return out.distinct().sorted()
    }

    private fun ctrlIn(req: Int, value: Int, index: Int, len: Int): ByteArray? {
        val c = conn ?: return null
        val b = ByteArray(len)
        val n = c.controlTransfer(0xA1, req, value, index, b, len, 1000)
        return if (n >= 1) b.copyOf(n) else null
    }

    private fun ctrlOut(type: Int, req: Int, value: Int, index: Int, data: ByteArray): Boolean {
        val c = conn ?: return false
        return c.controlTransfer(type, req, value, index, data, data.size, 1000) >= 0
    }

    private fun setRate(alt: UacAlt, rate: Int): Boolean {
        val inf = info ?: return false
        return if (inf.uac2) {
            val clk = clockFor(alt)
            val d = byteArrayOf(rate.toByte(), (rate shr 8).toByte(), (rate shr 16).toByte(), (rate shr 24).toByte())
            val ok = ctrlOut(0x21, 0x01, 0x0100, (clk shl 8) or inf.acIface, d)
            // 4 バイトより短い返事をする DAC でも落ちないように
            val now = ctrlIn(0x01, 0x0100, (clk shl 8) or inf.acIface, 4)?.takeIf { it.size >= 3 }
            val got = now?.let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) or ((it[2].toInt() and 0xFF) shl 16) }
            AudioEngine.log("usb dac: set rate $rate on clock $clk -> $ok (now $got)")
            ok
        } else {
            val d = byteArrayOf(rate.toByte(), (rate shr 8).toByte(), (rate shr 16).toByte())
            val ok = ctrlOut(0x22, 0x01, 0x0100, alt.epOut, d)
            AudioEngine.log("usb dac: set rate $rate on ep ${alt.epOut} -> $ok")
            ok || alt.rates.size <= 1
        }
    }

    // ---------------- 音量 ----------------

    private fun setupVolume(sb: StringBuilder) {
        val inf = info ?: return
        // 出力端子から入力側へたどり、最初に見つかった音量付きの機能ユニットを使う
        var unit: FeatureUnit? = null
        for (ot in inf.outputTerminals) {
            var id: Int? = inf.sourceOf[ot]
            var steps = 0
            while (id != null && steps++ < 16 && unit == null) {
                val f = inf.features.firstOrNull { it.id == id }
                if (f != null && (f.masterVolume || f.channelVolume.isNotEmpty())) unit = f
                else id = inf.sourceOf[id]
            }
            if (unit != null) break
        }
        if (unit == null) unit = inf.features.firstOrNull { it.masterVolume || it.channelVolume.isNotEmpty() }
        volUnit = unit
        hwVolume = false
        softGain = 1.0
        val u = unit
        if (u == null) {
            sb.append("  volume: none (software)\n")
            applyVolume()
            return
        }
        val ch = if (u.masterVolume) 0 else u.channelVolume.first()
        val idx = (u.id shl 8) or inf.acIface
        var mn = -12800; var mx = 0
        if (inf.uac2) {
            val r = ctrlIn(0x02, (0x02 shl 8) or ch, idx, 2 + 6)
            if (r != null && r.size >= 8) {
                mn = ((r[2].toInt() and 0xFF) or (r[3].toInt() shl 8)).toShort().toInt()
                mx = ((r[4].toInt() and 0xFF) or (r[5].toInt() shl 8)).toShort().toInt()
            }
        } else {
            ctrlIn(0x82, (0x02 shl 8) or ch, idx, 2)?.takeIf { it.size >= 2 }?.let { mn = ((it[0].toInt() and 0xFF) or (it[1].toInt() shl 8)).toShort().toInt() }
            ctrlIn(0x83, (0x02 shl 8) or ch, idx, 2)?.takeIf { it.size >= 2 }?.let { mx = ((it[0].toInt() and 0xFF) or (it[1].toInt() shl 8)).toShort().toInt() }
        }
        if (mx <= mn) { mn = -12800; mx = 0 }
        volMin = mn; volMax = mx
        hwVolume = true
        sb.append("  volume: unit ${u.id} ch ${if (u.masterVolume) "master" else u.channelVolume.joinToString(",")} ")
            .append("%.1f..%.1f dB".format(mn / 256.0, mx / 256.0)).append('\n')
        applyVolume()
    }

    private fun applyVolume() {
        val p = volume()
        val inf = info
        val u = volUnit
        if (!hwVolume || inf == null || u == null || conn == null) {
            // ソフトウェア音量(100 のときは何も掛けない = ビットパーフェクト)
            softGain = if (p <= 0) 0.0 else if (p >= 100) 1.0 else 10.0.pow(percentToDb(p) / 20.0)
            return
        }
        softGain = 1.0
        // DAC の可変範囲のうち、上 60 dB を使う
        val lo = max(volMin, volMax - 60 * 256)
        val db256 = volMax + percentToDb(p) * 256
        val v = if (p <= 0) volMin else db256.roundToInt().coerceIn(lo, volMax)
        val d = byteArrayOf(v.toByte(), (v shr 8).toByte())
        val idx = (u.id shl 8) or inf.acIface
        val chs = if (u.masterVolume) listOf(0) else u.channelVolume
        for (c in chs) ctrlOut(0x21, 0x01, (0x02 shl 8) or c, idx, d)
    }

    // ---------------- 転送 ----------------

    private fun startStream(alt: UacAlt, fmt: DacFormat): Boolean {
        val c = conn ?: return false
        val dev = device ?: return false
        val ui = (0 until dev.interfaceCount).map { dev.getInterface(it) }
            .firstOrNull { it.id == alt.iface && it.alternateSetting == alt.alt }
        if (ui == null) return false
        val uac2 = info?.uac2 == true
        // UAC2 はクロックの周波数を先に設定してから代替設定を選ぶ。UAC1 は代替設定を選んでからエンドポイントに設定する(Linux と同じ順序)
        if (uac2 && !setRate(alt, fmt.rate)) AudioEngine.log("usb dac: set rate failed")
        if (!c.setInterface(ui)) {
            AudioEngine.log("usb dac: setInterface if${alt.iface} alt${alt.alt} failed")
            return false
        }
        if (!uac2 && !setRate(alt, fmt.rate)) AudioEngine.log("usb dac: set rate failed")
        // 診断: DAC 側で本当にその代替設定になったかを聞き直す(GET_INTERFACE。答えない DAC もある)
        runCatching {
            val r = ByteArray(1)
            val n = c.controlTransfer(0x81, 0x0A, 0, alt.iface, r, 1, 500)
            AudioEngine.log("usb dac: if${alt.iface} now alt ${if (n == 1) r[0].toInt() and 0xFF else "? ($n)"} (asked alt${alt.alt})")
        }
        // DAC によっては、周波数や代替設定を切り替えると音量が初期値(最大のことが多い)に戻るので、送り直す
        if (hwVolume) applyVolume()
        val st = StreamThread(c.fileDescriptor, alt, fmt, speed)
        stream = st
        format = fmt
        curAlt = alt
        st.start()
        UsbStabilizer.start(app)
        AudioEngine.log("usb dac: streaming ${khz(fmt.rate)} ${fmt.channels}ch ${fmt.bits}bit (slot ${fmt.subslot * 8})")
        return true
    }

    private fun stopStream() {
        UsbStabilizer.stop()
        stream?.let {
            // いきなり止めると DAC から雑音が出ることがあるので、少しのあいだ無音を送ってから止める
            if (it.isAlive) {
                it.silenceOnly = true
                runCatching { Thread.sleep(80) }
            }
            it.halt()
            runCatching { it.join(1500) }
        }
        stream = null
        format = null
        curAlt = null
        // 帯域を返すため、ゼロ帯域の代替設定に戻す
        val dev = device; val c = conn; val inf = info
        if (dev != null && c != null && inf != null) {
            inf.alts.map { it.iface }.distinct().forEach { n ->
                (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { it.id == n && it.alternateSetting == 0 }
                    ?.let { runCatching { c.setInterface(it) } }
            }
        }
    }

    /** 指定の世代のまま開いているときだけ閉じる */
    fun closeIfGen(gen: Int) {
        synchronized(lock) {
            if (gen == openGen) close(reattach = true)
        }
    }

    /** DAC を手放す。reattach なら Android の USB オーディオに戻す */
    fun close(reattach: Boolean = true) = synchronized(lock) {
        val c = conn
        stopStream()
        if (c != null) {
            suppressNoisy()
            val fd = c.fileDescriptor
            for (ui in claimed) runCatching { c.releaseInterface(ui) }
            if (reattach) for (ui in claimed) Usbfs.reconnectKernelDriver(fd, ui.id)
            runCatching { c.close() }
            AudioEngine.log("usb dac: closed")
        }
        // 接続を閉じたので、カーネルが回収できなかった転送要求に書き込むことはもう無い
        synchronized(orphanUrbs) { orphanUrbs.clear() }
        claimed = ArrayList()
        conn = null
        device = null
        info = null
        rates = emptyList()
        updateActive(false)
    }

    // ---------------- 音声データの受け渡し ----------------

    @Volatile private var session = 0
    @Volatile private var failCount = 0
    @Volatile private var lastFailAt = 0L

    /** 再生セッションの開始(リングバッファを空にして位置を 0 に)。セッション番号を返す */
    internal fun begin(): Int {
        session++
        stream?.ring?.let { it.reset(); it.paused = true }
        return session
    }

    internal fun write(id: Int, b: ByteArray, n: Int, stop: () -> Boolean): Boolean {
        if (id != session) return false
        val st = stream ?: return false
        if (!st.isAlive) return false
        return st.ring.write(b, n) { stop() || id != session || !st.isAlive }
    }

    internal fun played(id: Int): Long = if (id == session) stream?.ring?.played ?: 0L else 0L

    /**
     * 止めたまま 30 秒たったら、音切れ対策(無音の再生・CPU を起こしておく・画面の描画のタイミング)をやめる。
     * DAC へは無音を送り続けるので DAC は眠らず、再生を始めればまた対策をかける(止めている間に電池と CPU を使わないように)
     */
    private val stabilizerIdle = Runnable { if (stream?.ring?.paused != false) UsbStabilizer.stop() }

    private fun pausedChanged(p: Boolean) {
        main.removeCallbacks(stabilizerIdle)
        if (p) main.postDelayed(stabilizerIdle, 30_000) else if (streaming) UsbStabilizer.start(app)
    }

    internal fun setPaused(id: Int, p: Boolean) {
        if (id != session) return
        stream?.ring?.paused = p
        pausedChanged(p)
    }

    internal fun end(id: Int) {
        if (id == session) stream?.ring?.let { it.reset(); it.paused = true; pausedChanged(true) }
    }

    internal val streaming: Boolean get() = stream?.isAlive == true

    private val volExec = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** よく使うサンプリング周波数(範囲で報告する DAC 用) */
    private val COMMON_RATES = listOf(
        8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 176400, 192000,
        352800, 384000, 705600, 768000, 1411200, 1536000,
    )

    fun khz(hz: Int) = if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(java.util.Locale.US, hz / 1000.0)

    /** 再生側(書き込み)と USB 転送側(読み出し)の間のリングバッファ */
    internal class Ring(val frameBytes: Int, frames: Int) {
        private val buf = ByteArray(frameBytes * frames)
        private var r = 0
        private var w = 0
        private var count = 0
        @Volatile var paused = false
        /** 実際に DAC へ送り終えたフレーム数 */
        @Volatile var played = 0L
        /** reset のたびに増える(前の曲のデータを数えないため) */
        @Volatile var epoch = 0
        private val lk = Object()

        fun level(): Int = synchronized(lk) { count / frameBytes }

        fun reset(): Long = synchronized(lk) {
            r = 0; w = 0; count = 0; played = 0L
            epoch++
            lk.notifyAll()
            0L
        }

        /** 書き手が空きを待っているか(読み手は、空きがまとまったときだけ起こす) */
        private var writerWaiting = false

        fun write(src: ByteArray, n: Int, stop: () -> Boolean): Boolean {
            var off = 0
            while (off < n) {
                synchronized(lk) {
                    // 満杯のときは、1/8 秒分(か残り全部)の空きができるまで待つ。
                    // 1 パケットずつ起こすと、毎秒数千回スレッドが切り替わって重い
                    val need = min(n - off, buf.size / 8).coerceAtLeast(1)
                    while (buf.size - count < need && !stop()) {
                        writerWaiting = true
                        lk.wait(50)
                    }
                    writerWaiting = false
                    if (stop()) return false
                    val k = min(n - off, buf.size - count)
                    val first = min(k, buf.size - w)
                    System.arraycopy(src, off, buf, w, first)
                    if (k > first) System.arraycopy(src, off + first, buf, 0, k - first)
                    w = (w + k) % buf.size
                    count += k
                    off += k
                }
            }
            return true
        }

        /** 送り終えたフレーム数を足す(reset と同時に起きても、前の曲の分を新しい曲に数えないように) */
        fun addPlayed(ep: Int, frames: Int) = synchronized(lk) {
            if (ep == epoch) played += frames
        }

        /** 最大 n バイトを取り出す(フレーム単位)。取り出したバイト数を返す */
        fun read(dst: Memory, dstOff: Long, n: Int): Int = synchronized(lk) {
            val k = if (paused) 0 else min(n, count) / frameBytes * frameBytes
            if (k <= 0) return@synchronized 0
            val first = min(k, buf.size - r)
            dst.write(dstOff, buf, r, first)
            if (k > first) dst.write(dstOff + first, buf, 0, k - first)
            r = (r + k) % buf.size
            count -= k
            if (writerWaiting && buf.size - count >= buf.size / 8) lk.notifyAll()
            k
        }
    }

    /** USB への等時転送を回し続けるスレッド */
    /** 速さの報告の桁を探す順(毎回配列を作らないように) */
    private val FB_SHIFTS = intArrayOf(0, -1, 1, -2, 2, -3, 3, -4, 4)

    private class StreamThread(val fd: Int, val alt: UacAlt, val fmt: DacFormat, speed: Int) : Thread("usb-dac") {
        @Volatile private var stopping = false
        // 1 秒分(アップサンプリングや DSD 変換で計算が一瞬遅れても、途切れないように)
        val ring = Ring(fmt.frameBytes, fmt.rate)

        private val highSpeed = speed >= 3 || (speed == 0 && alt.epOutInterval <= 4 && alt.maxPacketBytes > 192)
        private val busRate = if (highSpeed) 8000 else 1000
        private val dataShift = (alt.epOutInterval - 1).coerceIn(0, 4)
        private val packetsPerSec = busRate shr dataShift
        /** 1 回の転送(URB)に入れるパケット数(約 4ms 分) */
        private val ppu = (packetsPerSec / 250).coerceIn(1, 32)
        private val nUrb = 12
        private val maxFramesPerPacket = (alt.maxPacketBytes / fmt.frameBytes).coerceAtLeast(1)

        /** 1 バス周期あたりの公称フレーム数(16.16 固定小数) */
        private val freqn: Long = ((fmt.rate.toLong() shl 16) + busRate / 2) / busRate
        @Volatile private var freqm: Long = freqn
        private var freqShift = Int.MIN_VALUE
        private var fbCount = 0
        private var fbRejected = 0
        private var phase: Long = 0

        private val urbs = ArrayList<IsoUrb>()
        private val byAddr = HashMap<Long, IsoUrb>()
        private val lengths = IntArray(64)

        @Volatile private var halted = false

        fun halt() {
            halted = true
            stopping = true
            // 転送のスレッドが一覧を作っている途中でも落ちないように
            runCatching { for (u in urbs.toTypedArray()) if (u.inFlight) runCatching { Usbfs.ioctl(fd, Usbfs.DISCARDURB, u.urb) } }
        }

        private fun nextPacketFrames(): Int {
            phase = (phase and 0xFFFF) + (freqm shl dataShift)
            return min((phase shr 16).toInt(), maxFramesPerPacket)
        }

        /** 転送を始めた時刻(DAC が新しい周波数に落ち着くまで、音声を送らずに無音を送る) */
        private val startedAt = SystemClock.elapsedRealtime()
        /** DAC から正しい速度の報告を受け取った回数 */
        @Volatile private var fbAccepted = 0
        /** 止める前に無音だけを送る(切り替え時の雑音を防ぐ) */
        @Volatile var silenceOnly = false

        /** 音声を送ってよい状態か: 開始直後は DAC の周波数が安定するまで待つ */
        private fun warmedUp(): Boolean {
            if (silenceOnly) return false
            val t = SystemClock.elapsedRealtime() - startedAt
            if (t < 150) return false
            val needFb = alt.syncType == 1 && alt.epFb >= 0
            // 速度の報告が正しい値になるまで待つ(最大 1 秒)
            return !needFb || fbAccepted >= 4 || t > 1000
        }

        private fun fill(u: IsoUrb) {
            // パケットごとの長さを先に決めて、リングからは URB 1 回分をまとめて取り出す(鍵の出し入れを URB ごとに 1 回にする)。
            // 足りない分は、URB の最後を無音で埋める(並びは同じ)
            var total = 0
            for (p in 0 until u.packets) {
                val bytes = nextPacketFrames() * fmt.frameBytes
                lengths[p] = bytes
                total += bytes
            }
            val got = if (warmedUp()) ring.read(u.buf, 0L, total) else 0
            if (got < total) u.buf.setMemory(got.toLong(), (total - got).toLong(), fmt.fill)
            u.dataFrames = got / fmt.frameBytes
            u.epoch = ring.epoch
            u.prepare(lengths)
        }

        private fun submit(u: IsoUrb): Boolean {
            val r = Usbfs.ioctl(fd, Usbfs.SUBMITURB, u.urb)
            if (r < 0) {
                AudioEngine.log("usb dac: submit failed errno=${Usbfs.errno()} ep=0x%02x".format(u.endpoint))
                return false
            }
            u.inFlight = true
            return true
        }

        private fun handleFeedback(u: IsoUrb) {
            val n = u.packetActual(0)
            if (u.status() != 0 || n < 3) return
            val f: Long = if (n == 3) {
                ((u.buf.getByte(0).toLong() and 0xFF) or ((u.buf.getByte(1).toLong() and 0xFF) shl 8) or
                    ((u.buf.getByte(2).toLong() and 0xFF) shl 16)) shl 2
            } else {
                (u.buf.getInt(0).toLong() and 0x0FFFFFFFL)
            }
            if (f == 0L) return
            // 機器ごとに単位(桁)がずれていることがあるので、公称値の ±2% に入る桁を毎回探す。
            // 周波数を切り替えた直前は、前の周波数の値を返してくる DAC がある(iBasso など)ので、
            // 公称値から大きく外れた値は使わない(以前は最初の値で桁を決め打ちしていたため、
            // 前の周波数の値で桁を誤り、速さがずれて早送りのような音になっていた)
            val tol = freqn / 50
            var picked = -1L
            var shiftUsed = 0
            for (sft in FB_SHIFTS) {
                val v = if (sft >= 0) f shl sft else f shr -sft
                if (v >= freqn - tol && v <= freqn + tol) { picked = v; shiftUsed = sft; break }
            }
            fbCount++
            if (picked < 0) {
                fbRejected++
                if (fbRejected == 1 || fbRejected % 500 == 0) {
                    AudioEngine.log(
                        "usb dac: feedback ignored ${"%.4f".format(f / 65536.0)} (nominal ${"%.4f".format(freqn / 65536.0)}, rejected $fbRejected)",
                    )
                }
                return
            }
            if (freqShift != shiftUsed) {
                freqShift = shiftUsed
                AudioEngine.log("usb dac: feedback ${"%.4f".format(picked / 65536.0)} frames/interval (shift $shiftUsed, nominal ${"%.4f".format(freqn / 65536.0)})")
            }
            freqm = picked
            fbAccepted++
        }

        override fun run() {
            try {
                loop()
            } catch (t: Throwable) {
                AudioEngine.log("usb dac: stream error ${t.javaClass.simpleName}: ${t.message}")
            }
            // 送信中のものを取り消して回収する(回収しないと、次の転送のときにカーネルが古いメモリへ書き込む)
            runCatching { drain() }
            // 止める指示なしに転送が終わった(エラー・切断)ときは、再生側に出力のやり直しを促す
            if (!halted) {
                AudioEngine.report(false, S(R.string.usb_dac_streaming_stopped))
                // 短い間に何度も止まるときは、やり直しを繰り返さない
                val now = SystemClock.elapsedRealtime()
                if (now - lastFailAt > 60_000) failCount = 0
                lastFailAt = now
                if (++failCount <= 3) main.post { onChanged?.invoke() }
                else AudioEngine.log("usb dac: too many failures, not retrying")
            }
        }

        private fun loop() {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            val maxBytes = maxFramesPerPacket * fmt.frameBytes * ppu
            repeat(nUrb) {
                val u = IsoUrb(alt.epOut, ppu, maxBytes, false)
                urbs += u; byAddr[u.address] = u
            }
            val fbUse = alt.syncType == 1 && alt.epFb >= 0
            if (fbUse) repeat(2) {
                val u = IsoUrb(alt.epFb, 1, alt.epFbMaxPacket, true)
                urbs += u; byAddr[u.address] = u
            }
            AudioEngine.log(
                "usb dac: ${if (highSpeed) "high" else "full"}-speed, $packetsPerSec packets/s, $ppu packets/urb, " +
                    "max $maxFramesPerPacket frames/packet, feedback=${if (fbUse) "0x%02x".format(alt.epFb) else "none"}",
            )
            for (u in urbs) {
                if (u.feedback) { lengths[0] = alt.epFbMaxPacket; u.prepare(lengths) } else fill(u)
                if (!submit(u)) { stopping = true; break }
            }
            // 転送 1 回分(約 4ms)の間隔で処理を終える必要があると端末に伝える
            val h = PerfHint.open(app, ppu * 1_000_000_000L / packetsPerSec)
            hint = h
            AudioEngine.log("usb dac: performance hint ${if (h != null) "on" else "not available"}")
            try {
                loopBody()
            } finally {
                hint = null
                h?.close()
            }
        }

        private var hint: PerfHint? = null

        private fun loopBody() {
            val ptr = Memory(Usbfs.PS.toLong())
            var errors = 0
            // 動作の記録用
            val t0 = SystemClock.elapsedRealtime()
            var lastStat = t0
            var lastReap = t0
            var stPackets = 0L; var stFrames = 0L; var stData = 0L; var stUrbs = 0
            var stShortPk = 0; var stFb = 0; var stalls = 0
            // 診断: 送り切れなかったパケット(実際に送れた長さが、頼んだ長さより短い)の数と、無音でない音を送ったか
            var stShortTx = 0
            var stAudible = false
            var scan = ByteArray(0)
            val stErr = HashMap<Int, Int>()
            // 取りこぼし(パケットの失敗)が続いているかを 2 秒ごとに見る
            var missWinAt = t0
            var missWin = 0
            var missBad = 0
            var missReported = false
            var kicked = false
            while (urbs.any { it.inFlight }) {
                val r = Usbfs.ioctl(fd, Usbfs.REAPURBNDELAY, ptr)
                val now = SystemClock.elapsedRealtime()
                if (r < 0) {
                    val e = Usbfs.errno()
                    if (e == 11 || e == 4) { // EAGAIN(まだ終わっていない) / EINTR
                        if (!stopping && !kicked && now - lastReap > 300) {
                            // 転送が止まった: いったん全部取り消して出し直す
                            stalls++
                            AudioEngine.log("usb dac: stall (no completion for ${now - lastReap} ms), restarting transfers")
                            kicked = true
                            for (x in urbs) if (x.inFlight) runCatching { Usbfs.ioctl(fd, Usbfs.DISCARDURB, x.urb) }
                            lastReap = now
                        }
                        // URB は 12 個(約 48ms 分)送ってあるので、2ms ごとに見れば十分(1ms ごとだと、待つだけで CPU が休めない)
                        Thread.sleep(2)
                        continue
                    }
                    AudioEngine.log("usb dac: reap failed errno=$e")
                    break
                }
                lastReap = now
                val p = ptr.getPointer(0) ?: continue
                val u = byAddr[Pointer.nativeValue(p)]
                if (u == null) {
                    AudioEngine.log("usb dac: unknown urb reaped")
                    continue
                }
                u.inFlight = false
                if (stopping) continue
                val st = u.status()
                if ((st == -2 || st == -104) && kicked) {
                    // 出し直しのために取り消したもの(取り消すと ENOENT か ECONNRESET が返る)
                } else if (st != 0 && st != -18) { // -18: EXDEV(一部のパケットだけ失敗)は続行
                    errors++
                    stErr[st] = (stErr[st] ?: 0) + 1
                    if (errors <= 5) AudioEngine.log("usb dac: urb status $st")
                    if (st == -19 || st == -108 || errors > 200) { stopping = true; continue } // 機器が外れた
                }
                if (kicked && st == 0) kicked = false
                if (u.feedback) {
                    stFb++
                    handleFeedback(u)
                    lengths[0] = alt.epFbMaxPacket
                    u.prepare(lengths)
                } else {
                    stUrbs++
                    stPackets += u.packets
                    var sent = 0
                    // パケットの状態は 1 回でまとめて読む(1 つずつ読むと、毎秒数万回ネイティブを呼ぶことになる)
                    val desc = u.readDescriptors()
                    for (k in 0 until u.packets) {
                        val len = desc[k * 3]
                        val ps = desc[k * 3 + 2]
                        if (ps != 0) { stErr[1000 + ps] = (stErr[1000 + ps] ?: 0) + 1; missWin++ }
                        if (desc[k * 3 + 1] < len) stShortTx++
                        sent += len
                    }
                    // (無音が続く間も重くならないよう、16 回に 1 回だけ、まとめて読み出して調べる)
                    if (!stAudible && u.dataFrames > 0 && (stUrbs and 15) == 0 && sent > 0) {
                        if (scan.size < sent) scan = ByteArray(sent)
                        u.buf.read(0, scan, 0, sent)
                        for (j in 0 until sent) if (scan[j].toInt() != 0) { stAudible = true; break }
                    }
                    ring.addPlayed(u.epoch, u.dataFrames)
                    hint?.tick()
                    stData += u.dataFrames
                    fill(u)
                    // 毎秒数百回通るので、リストを作らずに足し合わせる
                    var bytes = 0
                    for (k in 0 until u.packets) bytes += lengths[k]
                    val frames = bytes / fmt.frameBytes
                    stFrames += frames
                    if (u.dataFrames < frames) stShortPk++
                }
                if (!submit(u)) stopping = true
                if (now - missWinAt >= 2000) {
                    // 始めた直後(DAC が落ち着くまで)は数えない。2 秒に 2 回以上の取りこぼしが 3 回(6 秒)続いたら知らせる
                    if (now - t0 >= 4000 && warmedUp() && !ring.paused) {
                        missBad = if (missWin >= 2) missBad + 1 else 0
                        if (missBad >= 3 && !missReported) {
                            missReported = true
                            transferTrouble(fmt.rate)
                        }
                    }
                    missWinAt = now
                    missWin = 0
                }
                // 最初は 2 秒ごと、その後は 10 秒ごとに状態を記録する
                val span = now - lastStat
                if (span >= (if (now - t0 < 10_000) 2000 else 10_000)) {
                    AudioEngine.log(
                        "usb dac: stat ${span}ms urbs=$stUrbs packets/s=${stPackets * 1000 / span} frames/s=${stFrames * 1000 / span} " +
                            "data/s=${stData * 1000 / span} underrun=$stShortPk fb=$stFb fbval=${"%.4f".format(freqm / 65536.0)} " +
                            "ring=${ring.level()} paused=${ring.paused} stalls=$stalls err=$stErr short=$stShortTx audible=$stAudible",
                    )
                    lastStat = now
                    stPackets = 0; stFrames = 0; stData = 0; stUrbs = 0; stShortPk = 0; stFb = 0
                    stShortTx = 0; stAudible = false
                    stErr.clear()
                }
            }
            if (!stopping) AudioEngine.log("usb dac: stream ended")
        }

        /** 送信中のものが残っていれば取り消して回収する */
        private fun drain() {
            for (u in urbs) if (u.inFlight) runCatching { Usbfs.ioctl(fd, Usbfs.DISCARDURB, u.urb) }
            val ptr = Memory(Usbfs.PS.toLong())
            var guard = 0
            while (urbs.any { it.inFlight } && guard++ < 64) {
                if (Usbfs.ioctl(fd, Usbfs.REAPURB, ptr) < 0) {
                    if (Usbfs.errno() == 4) continue // EINTR: やり直す
                    break
                }
                ptr.getPointer(0)?.let { byAddr[Pointer.nativeValue(it)]?.inFlight = false }
            }
            val left = urbs.filter { it.inFlight }
            if (left.isNotEmpty()) synchronized(orphanUrbs) { orphanUrbs.addAll(left) }
        }
    }

    @Suppress("unused")
    private fun near(a: Int, b: Int) = abs(a - b) < 2
}
