package com.uyatame.ucrt.player

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * メーカー独自の内蔵 DAC の仕組み(Sony のウォークマンの DSD の再生など)を、アプリから使えるかを調べる(実験的・読むだけ)。
 *
 * 調べること:
 * 1. ROM の中にある、音声・DSD・メーカーのライブラリのファイル
 * 2. アプリが読み込める公開ライブラリの一覧(public.libraries*.txt)に載っているか
 * 3. ライブラリの中の JNI 関数の名前(ELF を読む。読めない端末では飛ばす)
 * 4. System.loadLibrary が通るか(別のプロセスで試す。落ちても UCRT 本体は落ちない)
 * 5. メーカーの音声の設定(AudioManager.getParameters。読むだけ。書き換えない)
 * 設定や再生は変えない。結果は文章にして、画面とログに出す。
 */
object VendorAudioProbe {
    /** 探すライブラリの名前(大文字小文字は区別しない) */
    private val NAME = Regex("(?i)(wmmediaplayer|izm|sony|walkman|dsd|hires|tinyalsa|audio\\.(primary|usb)|vendor\\.audio|dac)")

    /** 探すフォルダ(ROM の中) */
    private val DIRS = listOf(
        "/system/lib64", "/system/lib", "/system_ext/lib64", "/system_ext/lib", "/vendor/lib64", "/vendor/lib",
        "/vendor/lib64/hw", "/product/lib64", "/odm/lib64",
    )

    /** 読み込めるかを試すライブラリの名前(Sony の純正プレーヤーが System.loadLibrary で読んでいるもの。APK から確認した名前) */
    private val TRY_LOAD = listOf("wmmediaplayer_walkman", "wmmediaplayer_songpal")

    /** 読むだけの音声の設定のキー(Sony の izm_ のもの。書き換えない) */
    private val PARAMS = listOf(
        "izm_jack_status_se", "izm_jack_status_btl", "izm_dsd_pcm_filter", "izm_dsd_pcm_gain", "izm_master_volume",
        "izm_headphone_smaster_se_gain_mode", "izm_headphone_smaster_btl_gain_mode",
    )

    /** 調べる(時間がかかるので、メインのスレッド以外で呼ぶ) */
    fun run(ctx: Context): String {
        val sb = StringBuilder()
        fun line(s: String = "") { sb.append(s).append('\n') }
        line("${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / ${Build.FINGERPRINT}")
        // 1) ライブラリのファイル
        line("[1] libraries")
        val found = ArrayList<File>()
        for (d in DIRS) {
            val fs = runCatching { File(d).listFiles() }.getOrNull()
            if (fs == null) { line("  $d: cannot list"); continue }
            val m = fs.filter { it.isFile && it.name.endsWith(".so") && NAME.containsMatchIn(it.name) }.sortedBy { it.name }
            line("  $d: ${fs.size} files, ${m.size} matching")
            for (f in m) { line("    ${f.name} (${f.length()} bytes)"); found += f }
        }
        // 2) 公開ライブラリの一覧(アプリが読み込めるのは、ここに載っているものと、アプリ自身のもの)
        line("[2] public libraries")
        val publicNames = HashSet<String>()
        for (f in listOf("/system/etc/public.libraries.txt", "/vendor/etc/public.libraries.txt", "/system_ext/etc/public.libraries.txt",
            "/product/etc/public.libraries.txt", "/odm/etc/public.libraries.txt")) {
            val file = File(f)
            if (!file.exists()) continue
            val t = runCatching { file.readLines() }.getOrNull()
            if (t == null) { line("  $f: cannot read"); continue }
            val names = t.map { it.trim().substringBefore(' ') }.filter { it.isNotEmpty() && !it.startsWith("#") }
            publicNames += names
            line("  $f: ${names.size} libraries")
        }
        val interesting = found.filter { it.name in publicNames }
        line("  matching libraries listed as public: ${interesting.joinToString { it.name }.ifEmpty { "none" }}")
        // 3) JNI 関数の名前(ELF を読む)
        line("[3] exported symbols (JNI / DSD / ALSA)")
        // 調べたいもの(メーカーのプレーヤー・メディア・USB のライブラリ)を先にする(上限があるので、先に出さないと後回しになる)
        val symbolTargets = found.filter { Regex("(?i)(wmmediaplayer|spmedia|spusb|tinyalsa|izm)").containsMatchIn(it.name) }
            .sortedBy { if (Regex("(?i)(wmmediaplayer|spmedia|spusb|tinyalsa)").containsMatchIn(it.name)) 0 else 1 }
            .distinctBy { it.name }
        for (f in symbolTargets.take(8)) {
            val syms = runCatching { dynamicSymbols(f) }.getOrNull()
            if (syms == null) { line("  ${f.name}: cannot read as ELF"); continue }
            val pick = syms.filter { it.startsWith("Java_") || it == "JNI_OnLoad" || Regex("(?i)(dsd|dop|alsa|pcm_open|mixer)").containsMatchIn(it) }
            line("  ${f.name}: ${syms.size} symbols, ${pick.size} of interest")
            for (s in pick.take(60)) line("    $s")
        }
        // 4) 読み込めるか(別のプロセスで)
        line("[4] System.loadLibrary from this app (separate process)")
        val names = (TRY_LOAD + found.filter { it.name.startsWith("lib") && it.name.contains("wmmediaplayer", true) }
            .map { it.name.removePrefix("lib").removeSuffix(".so") }).distinct()
        line(loadInSeparateProcess(ctx, names))
        // 5) 音声の設定(読むだけ)
        line("[5] audio parameters (read only)")
        val am = androidx.core.content.ContextCompat.getSystemService(ctx, AudioManager::class.java)
        for (k in PARAMS) {
            val v = runCatching { am?.getParameters(k) }.getOrElse { "error: ${it.javaClass.simpleName}" }
            line("  $k = ${v?.takeIf { it.isNotEmpty() } ?: "(empty)"}")
        }
        return sb.toString()
    }

    /** ELF64 の動的シンボル(.dynsym)の名前を読む。読めなければ例外 */
    private fun dynamicSymbols(f: File): List<String> {
        RandomAccessFile(f, "r").use { raf ->
            val ch = raf.channel
            val head = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            ch.read(head, 0); head.flip()
            require(head.get(0) == 0x7F.toByte() && head.get(1) == 'E'.code.toByte() && head.get(4).toInt() == 2) { "not ELF64" }
            val shoff = head.getLong(0x28)
            val shentsize = head.getShort(0x3A).toInt() and 0xFFFF
            val shnum = head.getShort(0x3C).toInt() and 0xFFFF
            val sh = ByteBuffer.allocate(shentsize * shnum).order(ByteOrder.LITTLE_ENDIAN)
            ch.read(sh, shoff); sh.flip()
            var symOff = 0L; var symSize = 0L; var strIdx = -1; var entSize = 24L
            for (i in 0 until shnum) {
                val b = i * shentsize
                val type = sh.getInt(b + 4)
                if (type == 11) { // SHT_DYNSYM
                    symOff = sh.getLong(b + 0x18); symSize = sh.getLong(b + 0x20); strIdx = sh.getInt(b + 0x28)
                    entSize = sh.getLong(b + 0x38).takeIf { it > 0 } ?: 24L
                }
            }
            require(strIdx in 0 until shnum && symSize > 0) { "no dynsym" }
            val sb = strIdx * shentsize
            val strOff = sh.getLong(sb + 0x18); val strSize = sh.getLong(sb + 0x20)
            require(symSize < (32 shl 20) && strSize < (32 shl 20)) { "too large" }
            val sym = ByteBuffer.allocate(symSize.toInt()).order(ByteOrder.LITTLE_ENDIAN); ch.read(sym, symOff); sym.flip()
            val str = ByteArray(strSize.toInt()); ch.read(ByteBuffer.wrap(str), strOff)
            val out = ArrayList<String>()
            val step = entSize.toInt()
            val total = symSize.toInt()
            var p = 0
            while (p + step <= total) {
                val nameOff = sym.getInt(p)
                if (nameOff in 1 until str.size) {
                    var e = nameOff
                    while (e < str.size && str[e].toInt() != 0) e++
                    out += String(str, nameOff, e - nameOff, Charsets.ISO_8859_1)
                }
                p += step
            }
            return out
        }
    }

    /** 別のプロセスで System.loadLibrary を試して、結果の文章を返す(最大 8 秒待つ。落ちたら、そう書く) */
    private fun loadInSeparateProcess(ctx: Context, names: List<String>): String {
        val out = File(ctx.filesDir, VendorLibProbeService.RESULT_FILE)
        out.delete()
        runCatching {
            ctx.startService(Intent(ctx, VendorLibProbeService::class.java).putExtra(VendorLibProbeService.EXTRA_NAMES, names.toTypedArray()))
        }.onFailure { return "  cannot start the probe: ${it.javaClass.simpleName}: ${it.message}" }
        val deadline = System.currentTimeMillis() + 8000
        var text = ""
        while (System.currentTimeMillis() < deadline) {
            text = runCatching { out.readText() }.getOrDefault("")
            if (text.endsWith("done\n")) break
            Thread.sleep(150)
        }
        runCatching { ctx.stopService(Intent(ctx, VendorLibProbeService::class.java)) }
        val rows = text.lines().filter { it.isNotBlank() && it != "done" }.map { "  " + it.replace("\t", ": ") }
        val tail = when {
            text.endsWith("done\n") -> ""
            rows.isEmpty() -> "  (no result: the probe process died or timed out)"
            else -> "  (stopped after the last line: the probe process died or timed out while loading the next library)"
        }
        return (rows + listOfNotNull(tail.takeIf { it.isNotEmpty() })).joinToString("\n")
    }
}
