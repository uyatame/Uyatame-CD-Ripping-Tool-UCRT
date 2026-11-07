package com.uyatame.ucrt.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTest {
    @Test fun paths() {
        assertEquals("/a/c", NetPaths.norm("a//b/../c/"))
        assertEquals("/", NetPaths.norm(""))
        assertEquals("/a/b", NetPaths.join("/a", "b"))
        assertEquals("/a", NetPaths.parent("/a/b"))
        assertEquals("/", NetPaths.parent("/a"))
        assertEquals("b.flac", NetPaths.nameOf("/a/b.flac"))
    }

    @Test fun docIds() {
        assertEquals("k1:/FLAC/a.flac", NetworkProvider.docId("k1", "FLAC/a.flac"))
        assertEquals("k1" to "/FLAC/a b.flac", NetworkProvider.parse("k1:/FLAC/a b.flac"))
        assertEquals("k1" to "/", NetworkProvider.parse("k1"))
    }

    @Test fun mlsd() {
        val f = FtpFs.parseMlsd("type=file;size=1234;modify=20240102030405; 01 曲.flac", "/m")!!
        assertEquals("/m/01 曲.flac", f.path)
        assertEquals(1234L, f.size)
        assertFalse(f.isDir)
        assertTrue(FtpFs.parseMlsd("type=dir;modify=20240102030405; Album", "/m")!!.isDir)
        assertNull(FtpFs.parseMlsd("type=cdir; .", "/m"))
        assertNull(FtpFs.parseMlsd("type=pdir; ..", "/m"))
    }

    @Test fun listUnixAndDos() {
        val u = FtpFs.parseList("-rw-r--r--    1 ftp      ftp       5242880 Jan 02 12:34 song one.flac", "/")!!
        assertEquals("song one.flac", u.name); assertEquals(5242880L, u.size); assertFalse(u.isDir)
        assertTrue(FtpFs.parseList("drwxr-xr-x    2 ftp      ftp          4096 Jan 02  2023 Album", "/")!!.isDir)
        val d = FtpFs.parseList("01-02-24  03:04PM       <DIR>          Rock", "/")!!
        assertTrue(d.isDir); assertEquals("Rock", d.name)
        val w = FtpFs.parseList("01-02-24  03:04PM              123456 a.mp3", "/")!!
        assertEquals(123456L, w.size)
        assertNull(FtpFs.parseList("total 12", "/"))
    }

    @Test fun readAhead() {
        val data = ByteArray(5000) { (it % 251).toByte() }
        var calls = 0
        val raw = object : NetFile {
            override val size = data.size.toLong()
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
                calls++
                if (pos >= size) return -1
                val n = minOf(len.toLong(), size - pos).toInt()
                System.arraycopy(data, pos.toInt(), buf, off, n); return n
            }
            override fun close() {}
        }
        val f = ReadAheadFile(raw, 1024)
        val b = ByteArray(100)
        assertEquals(100, f.read(10, b, 0, 100)); assertEquals(data[10], b[0])
        assertEquals(100, f.read(200, b, 0, 100)); assertEquals(data[200], b[0])
        assertEquals(1, calls)
        assertEquals(-1, f.read(5000, b, 0, 100))
    }

    @Test fun smbUser() {
        assertEquals("bob" to "HOME", SmbFs.splitUser("HOME\\bob"))
        assertEquals("bob" to "home", SmbFs.splitUser("bob@home"))
        assertEquals("bob" to null, SmbFs.splitUser("bob"))
    }
}
