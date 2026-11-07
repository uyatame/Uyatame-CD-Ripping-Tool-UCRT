package com.uyatame.ucrt.network

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** WebDAV(HTTP / HTTPS)。一覧は PROPFIND、読み込みは Range つきの GET。認証は Basic。HTTPS の証明書はふつうに検証する */
class WebDavFs(private val src: NetSource) : NetFs {
    private val https = src.protocol == NetProtocol.WEBDAVS
    /** サーバー上の、つなぎ先の最初のフォルダ("" か "/a/b") */
    private val base = NetPaths.norm(src.path).let { if (it == "/") "" else it }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun enc(path: String): String =
        path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun url(path: String): String {
        val full = NetPaths.norm(base + NetPaths.norm(path))
        val host = if (src.host.contains(':') && !src.host.startsWith("[")) "[${src.host}]" else src.host
        return (if (https) "https" else "http") + "://" + host + ":" + src.effectivePort + enc(full)
    }

    private fun req(path: String): Request.Builder {
        val b = Request.Builder().url(url(path))
        if (src.user.isNotEmpty()) b.header("Authorization", Credentials.basic(src.user, src.password, Charsets.UTF_8))
        return b
    }

    private fun propfind(path: String, depth: String): List<NetEntry>? {
        val body = PROPFIND_BODY.toRequestBody("application/xml".toMediaType())
        val r = req(path).method("PROPFIND", body).header("Depth", depth).build()
        client.newCall(r).execute().use { resp ->
            if (resp.code == 404) return null
            if (resp.code == 401) throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_auth_failed))
            if (resp.code != 207 && !resp.isSuccessful) throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_webdav_error, resp.code.toString()))
            return parseMultistatus(resp.body?.string() ?: "", base)
        }
    }

    override fun list(path: String): List<NetEntry> {
        val p = NetPaths.norm(path)
        return (propfind(p, "1") ?: throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_not_found_404))).filter { it.path != p }
    }

    override fun stat(path: String): NetEntry? {
        val p = NetPaths.norm(path)
        val e = propfind(p, "0")?.firstOrNull() ?: return null
        return NetEntry(p, NetPaths.nameOf(p), e.isDir, e.size, e.modified)
    }

    override fun open(path: String): NetFile {
        val p = NetPaths.norm(path)
        val size = stat(p)?.size ?: throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_not_found))
        val raw = object : NetFile {
            override val size: Long = size
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
                if (pos >= size) return -1
                val end = minOf(size - 1, pos + len - 1)
                val r = req(p).get().header("Range", "bytes=$pos-$end").build()
                client.newCall(r).execute().use { resp ->
                    if (resp.code == 416) return -1
                    if (!resp.isSuccessful) throw IOException(com.uyatame.ucrt.S(com.uyatame.ucrt.R.string.net_read_error, resp.code.toString()))
                    val ins = resp.body?.byteStream() ?: return -1
                    // Range に対応しないサーバーは 200 で全体を返す。先頭を読み飛ばす
                    if (resp.code == 200 && pos > 0) {
                        var skip = pos
                        while (skip > 0) { val s = ins.skip(skip); if (s <= 0) return -1; skip -= s }
                    }
                    var total = 0
                    while (total < len) {
                        val n = ins.read(buf, off + total, len - total)
                        if (n < 0) break
                        total += n
                    }
                    return if (total == 0) -1 else total
                }
            }
            override fun close() {}
        }
        return ReadAheadFile(raw)
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"

        /** PROPFIND の応答(multistatus)を読む。href(サーバー上の絶対パス)から base を除いたものを path にする */
        fun parseMultistatus(xml: String, base: String): List<NetEntry> {
            val out = ArrayList<NetEntry>()
            val p = android.util.Xml.newPullParser()
            p.setInput(java.io.StringReader(xml))
            var href = ""; var isDir = false; var len = 0L; var mod = 0L
            var inResp = false
            var ev = p.eventType
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    when (p.name.lowercase()) {
                        "response" -> { inResp = true; href = ""; isDir = false; len = 0; mod = 0 }
                        "href" -> if (inResp) href = p.nextText()
                        "collection" -> isDir = true
                        "getcontentlength" -> len = p.nextText().trim().toLongOrNull() ?: 0L
                        "getlastmodified" -> mod = parseDate(p.nextText().trim())
                    }
                } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG && p.name.lowercase() == "response" && inResp) {
                    inResp = false
                    // href は "/dav/a%20b/" か "http://host/dav/a%20b/"
                    var path = if (href.contains("://")) "/" + href.substringAfter("://").substringAfter('/', "") else href
                    path = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
                    path = NetPaths.norm(path)
                    if (base.isNotEmpty() && (path == base || path.startsWith("$base/"))) path = NetPaths.norm(path.removePrefix(base))
                    out.add(NetEntry(path, NetPaths.nameOf(path), isDir, len, mod))
                }
                ev = p.next()
            }
            return out
        }

        private fun parseDate(s: String): Long = runCatching {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(s)?.time ?: 0L
        }.getOrDefault(0L)
    }
}
