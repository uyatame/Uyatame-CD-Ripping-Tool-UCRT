package com.uyatame.ucrt.network

import java.io.Closeable
import java.io.IOException

/** ネットワーク上の 1 つのファイル・フォルダ */
class NetEntry(
    /** 接続先の中のパス(先頭は "/"、フォルダでも末尾に "/" はつけない。一番上は "/") */
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    /** 更新時刻(ミリ秒。分からなければ 0) */
    val modified: Long,
)

/** 開いたファイル。位置を指定して読める */
interface NetFile : Closeable {
    val size: Long
    /** pos から最大 len バイトを読む。終わりを過ぎていたら -1 */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int
}

/** プロトコルごとの接続。1 つの接続先につき 1 つ作り、使い回す(複数のスレッドから呼ばれる) */
interface NetFs : Closeable {
    @Throws(IOException::class)
    fun list(path: String): List<NetEntry>

    /** 無ければ null */
    @Throws(IOException::class)
    fun stat(path: String): NetEntry?

    @Throws(IOException::class)
    fun open(path: String): NetFile
}

object NetPaths {
    /** "a//b/../c/" → "/a/c" */
    fun norm(p: String): String {
        val out = ArrayList<String>()
        for (s in p.split('/')) {
            when (s) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out.add(s)
            }
        }
        return "/" + out.joinToString("/")
    }

    fun join(dir: String, name: String): String = norm(if (dir.endsWith("/")) dir + name else "$dir/$name")

    fun parent(p: String): String {
        val n = norm(p)
        val i = n.lastIndexOf('/')
        return if (i <= 0) "/" else n.substring(0, i)
    }

    fun nameOf(p: String): String = norm(p).substringAfterLast('/')
}

/**
 * 小さな読み込みを、まとめて先読みする。デコーダは数 KB ずつ読むので、そのままでは通信の回数が多すぎる。
 * 直前の窓(既定 512KB)に入っていれば通信しない。
 */
class ReadAheadFile(private val inner: NetFile, private val window: Int = 512 * 1024) : NetFile {
    override val size: Long get() = inner.size
    private var start = -1L
    private var have = 0
    private val buf = ByteArray(window)

    @Synchronized
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (pos >= size) return -1
        if (len >= window) return inner.read(pos, buf, off, len)
        if (start < 0 || pos < start || pos >= start + have) {
            val n = inner.read(pos, this.buf, 0, window)
            if (n <= 0) { start = -1; have = 0; return -1 }
            start = pos; have = n
        }
        val rel = (pos - start).toInt()
        val n = minOf(len, have - rel)
        System.arraycopy(this.buf, rel, buf, off, n)
        return n
    }

    override fun close() = inner.close()
}
