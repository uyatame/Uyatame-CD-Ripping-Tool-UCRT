package com.uyatame.ucrt.network

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.IOException
import java.security.PublicKey

/**
 * SFTP(SSH)。パスワードでログインする。
 * ホストの鍵は、初めてつないだときの指紋を覚えて、次からは同じ指紋でなければ接続を断る(なりすまし対策)。
 * [onHostKey] には、初めて見た指紋を保存するための処理を渡す。
 */
class SftpFs(private val src: NetSource, private val onHostKey: (String) -> Unit) : NetFs {
    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null
    private val base = NetPaths.norm(src.path)
    private var known = src.hostKey

    private fun full(p: String) = NetPaths.norm(base + NetPaths.norm(p))

    @Synchronized
    private fun ensure(): SFTPClient {
        sftp?.let { if (ssh?.isConnected == true) return it }
        closeQuietly()
        BcSetup.ensure()
        val c = SSHClient()
        c.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val fp = SecurityUtils.getFingerprint(key)
                if (known.isEmpty()) { known = fp; onHostKey(fp); return true }
                return known == fp
            }
            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        c.connectTimeout = 15000
        c.timeout = 30000
        c.connect(src.host, src.effectivePort)
        c.authPassword(src.user, src.password)
        ssh = c
        val s = c.newSFTPClient()
        sftp = s
        return s
    }

    private fun closeQuietly() {
        runCatching { sftp?.close() }; runCatching { ssh?.disconnect() }
        sftp = null; ssh = null
    }

    override fun list(path: String): List<NetEntry> {
        val s = ensure()
        val dir = NetPaths.norm(path)
        return try {
            s.ls(full(dir)).filter { it.name != "." && it.name != ".." }.map {
                val isDir = it.attributes.type == FileMode.Type.DIRECTORY ||
                    (it.attributes.type == FileMode.Type.SYMLINK && runCatching { s.stat(it.path).type == FileMode.Type.DIRECTORY }.getOrDefault(false))
                NetEntry(NetPaths.join(dir, it.name), it.name, isDir, it.attributes.size, it.attributes.mtime * 1000L)
            }
        } catch (e: IOException) { closeQuietly(); throw e }
    }

    override fun stat(path: String): NetEntry? {
        val s = ensure()
        val p = NetPaths.norm(path)
        return try {
            val a = s.statExistence(full(p)) ?: return null
            NetEntry(p, NetPaths.nameOf(p).ifEmpty { src.name }, a.type == FileMode.Type.DIRECTORY, a.size, a.mtime * 1000L)
        } catch (e: IOException) { closeQuietly(); throw e }
    }

    override fun open(path: String): NetFile {
        val s = ensure()
        val rf = s.open(full(path))
        val size = rf.length()
        val raw = object : NetFile {
            override val size: Long = size
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
                if (pos >= size) return -1
                val n = rf.read(pos, buf, off, len)
                return if (n <= 0) -1 else n
            }
            override fun close() { runCatching { rf.close() } }
        }
        return ReadAheadFile(raw, 1024 * 1024)
    }

    @Synchronized
    override fun close() = closeQuietly()
}

/** Android 標準の BouncyCastle は機能が削られているので、sshj が使う完全版に差し替える(1 回だけ) */
internal object BcSetup {
    private var done = false
    @Synchronized
    fun ensure() {
        if (done) return
        done = true
        runCatching {
            java.security.Security.removeProvider("BC")
            java.security.Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
        }
    }
}
