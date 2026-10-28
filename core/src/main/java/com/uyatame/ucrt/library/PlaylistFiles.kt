package com.uyatame.ucrt.library

/**
 * ほかのアプリで作ったプレイリストのファイル(M3U・M3U8・PLS・XSPF・WPL・ZPL)を読み、ライブラリの曲に結び付ける。
 * ファイルに書かれた場所(絶対パス・相対パス・file:// ・Windows のパス)は端末ごとに違うので、
 * ファイル名(とフォルダ名)で探し、見つからなければ曲名・アーティスト名で探す
 */
object PlaylistFiles {
    /** 対応している拡張子 */
    val EXTENSIONS = setOf("m3u", "m3u8", "pls", "xspf", "wpl", "zpl")

    /** 1 曲分(場所と、書いてあれば曲名・アーティスト) */
    class Entry(val path: String, val title: String? = null, val artist: String? = null)

    class Parsed(val name: String?, val entries: List<Entry>)

    /** 中身を読む。kind は拡張子(分からなければ中身から決める) */
    fun parse(text: String, kind: String?): Parsed {
        val t = text.removePrefix("\uFEFF")
        val k = kind?.lowercase()
        return when {
            k == "pls" || t.trimStart().startsWith("[playlist]", ignoreCase = true) -> pls(t)
            k == "xspf" || t.contains("<playlist", ignoreCase = true) && t.contains("xspf", ignoreCase = true) -> xspf(t)
            k == "wpl" || k == "zpl" || t.contains("<smil", ignoreCase = true) -> wpl(t)
            else -> m3u(t)
        }
    }

    private fun m3u(t: String): Parsed {
        val out = ArrayList<Entry>()
        var name: String? = null
        var info: Pair<String?, String?>? = null
        for (raw in t.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#")) {
                when {
                    l.startsWith("#PLAYLIST:", true) -> name = l.substringAfter(':').trim().ifEmpty { null }
                    l.startsWith("#EXTINF:", true) -> {
                        // #EXTINF:長さ,アーティスト - 曲名
                        val d = l.substringAfter(',', "").trim()
                        info = if (" - " in d) d.substringBefore(" - ").trim() to d.substringAfter(" - ").trim() else null to d.ifEmpty { null }
                    }
                }
                continue
            }
            out.add(Entry(l, info?.second, info?.first))
            info = null
        }
        return Parsed(name, out)
    }

    private fun pls(t: String): Parsed {
        val files = HashMap<Int, String>()
        val titles = HashMap<Int, String>()
        for (raw in t.lineSequence()) {
            val l = raw.trim()
            val key = l.substringBefore('=').trim().lowercase()
            val v = l.substringAfter('=', "").trim()
            val n = key.filter { it.isDigit() }.toIntOrNull() ?: continue
            when {
                key.startsWith("file") -> files[n] = v
                key.startsWith("title") -> titles[n] = v
            }
        }
        return Parsed(null, files.keys.sorted().map { n ->
            val d = titles[n]
            if (d != null && " - " in d) Entry(files[n]!!, d.substringAfter(" - ").trim(), d.substringBefore(" - ").trim())
            else Entry(files[n]!!, d)
        })
    }

    private fun xml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun tag(block: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(block)?.groupValues?.get(1)?.let { xml(it.trim()) }

    private fun xspf(t: String): Parsed {
        val name = Regex("<playlist[^>]*>.*?<title>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(t)
            ?.groupValues?.get(1)?.let { xml(it.trim()) }
        val out = Regex("<track>(.*?)</track>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(t).mapNotNull { m ->
            val b = m.groupValues[1]
            val loc = tag(b, "location") ?: return@mapNotNull null
            Entry(loc, tag(b, "title"), tag(b, "creator"))
        }.toList()
        return Parsed(name, out)
    }

    private fun wpl(t: String): Parsed {
        val name = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.let { xml(it.trim()) }
        val out = Regex("<media\\s[^>]*src\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).findAll(t).map { Entry(xml(it.groupValues[1])) }.toList()
        return Parsed(name, out)
    }

    /** 場所から、ファイル名とフォルダ名(小文字。分からなければ空) */
    fun nameParts(path: String): Pair<String, String> {
        var p = path.trim()
        if (p.startsWith("file:", true)) p = p.substringAfter("file:").trimStart('/').let { "/$it" }
        p = runCatching { java.net.URLDecoder.decode(p.replace("+", "%2B"), "UTF-8") }.getOrDefault(p)
        val parts = p.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        return (parts.lastOrNull() ?: "").lowercase() to (parts.getOrNull(parts.size - 2) ?: "").lowercase()
    }

    private fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    /**
     * 曲に結び付ける。見つかった曲の uri の並び(見つからなかったものは除く)と、見つからなかった数を返す
     */
    fun match(entries: List<Entry>, tracks: List<LibTrack>): Pair<List<String>, Int> {
        val byName = HashMap<String, MutableList<LibTrack>>()
        val byTitle = HashMap<String, MutableList<LibTrack>>()
        for (t in tracks) {
            val (n, _) = nameParts(t.uri)
            if (n.isNotEmpty()) byName.getOrPut(n) { ArrayList() }.add(t)
            byTitle.getOrPut(norm(t.title)) { ArrayList() }.add(t)
        }
        val out = ArrayList<String>()
        var miss = 0
        for (e in entries) {
            val (n, dir) = nameParts(e.path)
            val cands = byName[n]
            val hit = when {
                cands == null -> null
                cands.size == 1 -> cands[0]
                // 同じ名前のファイルがいくつかあれば、フォルダ名が合うもの
                else -> cands.firstOrNull { nameParts(it.uri).second == dir } ?: cands[0]
            } ?: e.title?.let { ti ->
                val c = byTitle[norm(ti)] ?: return@let null
                if (e.artist != null) c.firstOrNull { norm(it.artist) == norm(e.artist) } ?: c.singleOrNull() else c.singleOrNull()
            }
            if (hit != null) out.add(hit.uri) else miss++
        }
        return out to miss
    }

    /** M3U8 にして書き出す(場所は、分かればファイルの絶対パス、分からなければ曲の uri) */
    fun toM3u8(name: String, tracks: List<LibTrack>, pathOf: (String) -> String?): String = buildString {
        append("#EXTM3U\n")
        append("#PLAYLIST:").append(name).append('\n')
        for (t in tracks) {
            append("#EXTINF:").append(t.durationMs / 1000).append(',').append(t.artist).append(" - ").append(t.title).append('\n')
            append(pathOf(t.uri) ?: t.uri).append('\n')
        }
    }
}
