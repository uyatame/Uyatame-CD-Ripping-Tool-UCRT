package com.uyatame.ucrt.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB(Windows 共有・Synology・QNAP など。SMB2 / SMB3)。
 * 「パス」の先頭は共有名(例: "music/FLAC" なら、共有 music の中の FLAC)。ユーザー名は "ドメイン\ユーザー" や "ユーザー@ドメイン" でもよい。
 * ユーザー名が空なら、ゲストでつなぐ。
 */
class SmbFs(private val src: NetSource) : NetFs {
    private var client: SMBClient? = null
    private var conn: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private val parts = NetPaths.norm(src.path).trim('/').split('/').filter { it.isNotEmpty() }
    private val shareName = parts.firstOrNull() ?: ""
    private val baseDir = parts.drop(1).joinToString("\\")

    private fun smbPath(p: String): String {
        val rel = NetPaths.norm(p).trim('/').replace('/', '\\')
        return listOf(baseDir, rel).filter { it.isNotEmpty() }.joinToString("\\")
    }

    @Synchronized
    private fun ensure(): DiskShare {
        share?.let { if (it.isConnected) return it }
        closeQuietly()
        if (shareName.isEmpty()) throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_smb_need_share))
        val cfg = SmbConfig.builder().withTimeout(30, TimeUnit.SECONDS).withSoTimeout(30, TimeUnit.SECONDS).build()
        val c = SMBClient(cfg)
        client = c
        val cn = c.connect(src.host, src.effectivePort)
        conn = cn
        val (user, domain) = splitUser(src.user)
        val auth = if (user.isEmpty()) AuthenticationContext.guest() else AuthenticationContext(user, src.password.toCharArray(), domain)
        val s = cn.authenticate(auth)
        session = s
        val sh = s.connectShare(shareName) as? DiskShare ?: throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_smb_not_disk, shareName))
        share = sh
        return sh
    }

    private fun closeQuietly() {
        runCatching { share?.close() }; runCatching { session?.close() }; runCatching { conn?.close() }; runCatching { client?.close() }
        share = null; session = null; conn = null; client = null
    }

    override fun list(path: String): List<NetEntry> {
        val sh = ensure()
        val dir = NetPaths.norm(path)
        return try {
            sh.list(smbPath(dir)).filter { it.fileName != "." && it.fileName != ".." }.map { i ->
                val isDir = (i.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                NetEntry(NetPaths.join(dir, i.fileName), i.fileName, isDir, i.endOfFile, i.changeTime?.toEpochMillis() ?: 0L)
            }
        } catch (e: IOException) { closeQuietly(); throw e } catch (e: RuntimeException) { closeQuietly(); throw IOException(e.message, e) }
    }

    override fun stat(path: String): NetEntry? {
        val sh = ensure()
        val p = NetPaths.norm(path)
        val sp = smbPath(p)
        return try {
            if (sp.isEmpty() || sh.folderExists(sp)) NetEntry(p, NetPaths.nameOf(p).ifEmpty { src.name }, true, 0, 0)
            else if (sh.fileExists(sp)) {
                val i = sh.getFileInformation(sp)
                NetEntry(p, NetPaths.nameOf(p), false, i.standardInformation.endOfFile, i.basicInformation.changeTime?.toEpochMillis() ?: 0L)
            } else null
        } catch (e: RuntimeException) { closeQuietly(); throw IOException(e.message, e) }
    }

    override fun open(path: String): NetFile {
        val sh = ensure()
        val f = sh.openFile(smbPath(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        val size = f.fileInformation.standardInformation.endOfFile
        val raw = object : NetFile {
            override val size: Long = size
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
                if (pos >= size) return -1
                val n = f.read(buf, pos, off, len)
                return if (n <= 0) -1 else n
            }
            override fun close() { runCatching { f.close() } }
        }
        return ReadAheadFile(raw, 1024 * 1024)
    }

    @Synchronized
    override fun close() = closeQuietly()

    companion object {
        /** "DOMAIN\user" / "user@domain" / "user" → (user, domain) */
        fun splitUser(u: String): Pair<String, String?> = when {
            u.contains('\\') -> u.substringAfter('\\') to u.substringBefore('\\')
            u.contains('@') -> u.substringBefore('@') to u.substringAfter('@')
            else -> u to null
        }
    }
}
