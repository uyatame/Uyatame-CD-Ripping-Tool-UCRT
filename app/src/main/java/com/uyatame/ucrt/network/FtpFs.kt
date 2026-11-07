package com.uyatame.ucrt.network

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * FTP(暗号化なし)。制御の接続は 1 本で、同時には 1 つの操作だけ行う(synchronized)。
 * 読み込みは REST で位置を指定する。UTF-8 のファイル名に対応している機器なら、OPTS UTF8 ON で日本語の名前も読める。
 */
/** FTP の制御の接続 1 本。読み込み中のファイルは、他の操作と混ざらないよう、専用の接続を使う */
private class FtpConn(private val src: NetSource) {
    private var sock: Socket? = null
    private var rd: BufferedReader? = null
    private var utf8 = true
    private val base = NetPaths.norm(src.path)

    fun full(p: String) = NetPaths.norm(base + NetPaths.norm(p))

    fun send(cmd: String) {
        val s = sock ?: throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_not_connected))
        s.getOutputStream().write((cmd + "\r\n").toByteArray(if (utf8) Charsets.UTF_8 else Charsets.ISO_8859_1))
        s.getOutputStream().flush()
    }

    /** 応答を 1 つ読む(複数行の応答は最後まで)。(コード, 本文) */
    fun reply(): Pair<Int, String> {
        val r = rd ?: throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_not_connected))
        val first = r.readLine() ?: throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_disconnected))
        val code = first.take(3).toIntOrNull() ?: throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_unknown_reply, first))
        val sb = StringBuilder(first)
        if (first.length > 3 && first[3] == '-') {
            while (true) {
                val l = r.readLine() ?: throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_disconnected))
                sb.append('\n').append(l)
                if (l.length >= 4 && l.take(3) == code.toString() && l[3] == ' ') break
            }
        }
        return code to sb.toString()
    }

    fun cmd(c: String): Pair<Int, String> { send(c); return reply() }

    @Synchronized
    fun ensure() {
        val s = sock
        if (s != null && s.isConnected && !s.isClosed) return
        closeQuietly()
        val ns = Socket()
        ns.connect(InetSocketAddress(src.host, src.effectivePort), 15000)
        ns.soTimeout = 30000
        sock = ns
        rd = BufferedReader(InputStreamReader(ns.getInputStream(), Charsets.UTF_8))
        val hello = reply()
        if (hello.first != 220) throw IOException("FTP: ${hello.second}")
        val user = src.user.ifEmpty { "anonymous" }
        val pass = if (src.user.isEmpty()) "ucrt@" else src.password
        var r = cmd("USER $user")
        if (r.first == 331) r = cmd("PASS $pass")
        if (r.first != 230) throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_ftp_login_failed, r.second))
        cmd("TYPE I")
        // UTF-8 の名前に対応する機器ならここで切り替わる(使えなくても、UTF-8 のまま扱って困らないことが多い)
        cmd("OPTS UTF8 ON")
    }

    fun closeQuietly() {
        runCatching { sock?.close() }
        sock = null; rd = null
    }

    /** データ用の接続を開く(パッシブ)。EPSV を先に試す */
    fun openData(): Socket {
        var r = cmd("EPSV")
        val host = src.host
        val port: Int
        if (r.first == 229) {
            port = Regex("\\(\\|\\|\\|(\\d+)\\|\\)").find(r.second)?.groupValues?.get(1)?.toInt() ?: throw IOException("EPSV: ${r.second}")
        } else {
            r = cmd("PASV")
            if (r.first != 227) throw IOException("PASV: ${r.second}")
            val m = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(r.second) ?: throw IOException("PASV: ${r.second}")
            port = m.groupValues[5].toInt() * 256 + m.groupValues[6].toInt()
        }
        val d = Socket()
        d.connect(InetSocketAddress(host, port), 15000)
        d.soTimeout = 30000
        return d
    }

}

/**
 * FTP(暗号化なし)。一覧・確認用の接続 1 本と、開いたファイルごとの専用の接続を使う
 * (読みかけのファイルがあっても、他の操作の応答と混ざらないように)。
 * 読み込みは REST で位置を指定する。UTF-8 のファイル名に対応している機器なら、OPTS UTF8 ON で日本語の名前も読める。
 */
class FtpFs(private val src: NetSource) : NetFs {
    private val c = FtpConn(src)

    @Synchronized
    override fun list(path: String): List<NetEntry> {
        c.ensure()
        val dir = c.full(path)
        val lines = try { readLines("MLSD " + dir) ?: readLines("LIST " + dir) ?: throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_cannot_list)) }
        catch (e: IOException) { c.closeQuietly(); throw e }
        val mlsd = lines.first
        return lines.second.mapNotNull { l -> if (mlsd) parseMlsd(l, path) else parseList(l, path) }
    }

    /** 一覧のコマンドを実行して行を集める。使えないコマンド(500 番台)なら null。(MLSD だったか, 行) */
    private fun readLines(cmd: String): Pair<Boolean, List<String>>? {
        val d = c.openData()
        try {
            val r = c.cmd(cmd)
            if (r.first >= 500) return null
            if (r.first != 150 && r.first != 125) throw IOException("FTP: ${r.second}")
            val lines = BufferedReader(InputStreamReader(d.getInputStream(), Charsets.UTF_8)).readLines()
            d.close()
            val end = c.reply()
            if (end.first != 226 && end.first != 250) throw IOException("FTP: ${end.second}")
            return cmd.startsWith("MLSD") to lines
        } finally { runCatching { d.close() } }
    }

    @Synchronized
    override fun stat(path: String): NetEntry? {
        c.ensure()
        val p = NetPaths.norm(path)
        if (p == "/") return NetEntry("/", src.name, true, 0, 0)
        val f = c.full(p)
        val sz = c.cmd("SIZE $f")
        if (sz.first == 213) return NetEntry(p, NetPaths.nameOf(p), false, sz.second.substringAfter(' ').trim().toLongOrNull() ?: 0L, 0)
        // フォルダかどうか: CWD できれば
        val cur = c.cmd("PWD")
        val cw = c.cmd("CWD $f")
        if (cw.first in 200..299) {
            Regex("\"(.*)\"").find(cur.second)?.groupValues?.get(1)?.let { c.cmd("CWD $it") }
            return NetEntry(p, NetPaths.nameOf(p), true, 0, 0)
        }
        return null
    }

    override fun open(path: String): NetFile {
        val p = NetPaths.norm(path)
        val size = stat(p)?.size ?: throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_not_found))
        val fc = FtpConn(src)
        val raw = object : NetFile {
            override val size: Long = size
            var stream: InputStream? = null
            var data: Socket? = null
            var at = -1L

            @Synchronized
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
                if (pos >= size) return -1
                try {
                    if (stream == null || at != pos) startAt(pos)
                    var total = 0
                    while (total < len) {
                        val n = stream!!.read(buf, off + total, len - total)
                        if (n < 0) break
                        total += n
                    }
                    at = pos + total
                    return if (total == 0) -1 else total
                } catch (e: IOException) {
                    // 途切れたら、次の読み込みでつなぎ直す
                    runCatching { data?.close() }; data = null; stream = null; fc.closeQuietly()
                    throw e
                }
            }

            private fun startAt(pos: Long) {
                stopData()
                fc.ensure()
                val d = fc.openData()
                val rr = fc.cmd("REST $pos")
                if (rr.first != 350) { d.close(); throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_ftp_no_resume)) }
                val r = fc.cmd("RETR ${fc.full(p)}")
                if (r.first != 150 && r.first != 125) { d.close(); throw IOException("FTP: ${r.second}") }
                data = d
                stream = BufferedInputStream(d.getInputStream(), 64 * 1024)
                at = pos
            }

            /** 読みかけのデータ接続を閉じて、制御の接続を次の命令に使える状態に戻す */
            private fun stopData() {
                if (data == null) return
                runCatching { data?.close() }
                data = null; stream = null
                // 途中で閉じた RETR は 426 → 226 の順で応答が来ることがある
                runCatching {
                    val a = fc.reply()
                    if (a.first == 426 || a.first == 451) fc.reply()
                }
            }

            @Synchronized
            override fun close() { runCatching { stopData() }; fc.closeQuietly() }
        }
        return ReadAheadFile(raw, 1024 * 1024)
    }

    @Synchronized
    override fun close() {
        runCatching { c.cmd("QUIT") }
        c.closeQuietly()
    }

    companion object {
        /** MLSD の 1 行: "type=file;size=123;modify=20240101120000; name" */
        fun parseMlsd(line: String, dir: String): NetEntry? {
            val sp = line.indexOf(' ')
            if (sp <= 0) return null
            val facts = line.substring(0, sp).split(';').filter { it.contains('=') }.associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
            val name = line.substring(sp + 1)
            val type = facts["type"]?.lowercase() ?: "file"
            if (type == "cdir" || type == "pdir" || name == "." || name == "..") return null
            val isDir = type == "dir"
            val mod = facts["modify"]?.let { m ->
                runCatching {
                    java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(m.take(14))?.time
                }.getOrNull()
            } ?: 0L
            return NetEntry(NetPaths.join(dir, name), name, isDir, facts["size"]?.toLongOrNull() ?: 0L, mod)
        }

        /** LIST の 1 行(Unix 形式 / Windows(IIS)形式) */
        fun parseList(line: String, dir: String): NetEntry? {
            val unix = Regex("^([\\-dlcbps])[rwxsStT\\-]{9}[+@.]?\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+(\\w{3}\\s+\\d{1,2}\\s+[\\d:]{4,5})\\s+(.+)$").find(line)
            if (unix != null) {
                var name = unix.groupValues[4]
                val t = unix.groupValues[1]
                if (t == "l") name = name.substringBefore(" -> ")
                if (name == "." || name == "..") return null
                return NetEntry(NetPaths.join(dir, name), name, t == "d" || t == "l", unix.groupValues[2].toLongOrNull() ?: 0L, 0)
            }
            val dos = Regex("^(\\d{2}-\\d{2}-\\d{2,4})\\s+(\\d{1,2}:\\d{2}(?:AM|PM)?)\\s+(<DIR>|\\d+)\\s+(.+)$").find(line)
            if (dos != null) {
                val name = dos.groupValues[4]
                val isDir = dos.groupValues[3] == "<DIR>"
                return NetEntry(NetPaths.join(dir, name), name, isDir, if (isDir) 0 else dos.groupValues[3].toLongOrNull() ?: 0L, 0)
            }
            return null
        }
    }
}
