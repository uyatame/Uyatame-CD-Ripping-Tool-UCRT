package com.uyatame.ucrt.meta

import com.uyatame.ucrt.R
import com.uyatame.ucrt.S

import android.util.Base64
import com.uyatame.ucrt.usb.TocTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** 曲の情報。composer 以降は、利用者が入力した詳しい情報(取り込むときにファイルに書く) */
data class TrackMeta(
    val title: String,
    val artist: String,
    val composer: String = "",
    val lyricist: String = "",
    val comment: String = "",
    val lyrics: String = "",
)

data class AlbumMeta(
    val title: String,
    val artist: String,
    val date: String = "",
    val releaseId: String? = null,
    val tracks: List<TrackMeta> = emptyList(),
    val discNo: Int = 1,
    val discTotal: Int = 1,
    /** ジャンル(利用者が入力したもの。取り込むときにファイルに書く) */
    val genre: String = "",
)

/**
 * 曲情報の候補。
 * exact = ディスクIDが完全一致した候補。loader がある候補は、選んだときに曲目を取得する(名前検索の結果)。
 */
class ReleaseCandidate(
    val meta: AlbumMeta,
    val summary: String,
    val source: String,
    val exact: Boolean,
    val trackCount: Int,
    val loader: (() -> AlbumMeta?)? = null,
)

/** 取得元ごとの結果。error が null でなければ通信や応答の異常 */
class SourceResult(val source: String, val candidates: List<ReleaseCandidate>, val error: String?)

/**
 * MusicBrainz(https://musicbrainz.org)からCDの曲情報を取得する。
 * コアデータはCC0。APIの利用規約に従い、アプリを識別できるUser-Agentを送り、
 * 1秒に1回までに間隔を空けて問い合わせる。
 */
object MusicBrainz {
    const val SOURCE = "MusicBrainz"
    private const val BASE = "https://musicbrainz.org/ws/2"

    /** 連絡先(任意)。MusicBrainz は User-Agent に連絡先を含めることを推奨している */
    @Volatile var contact: String = ""
    @Volatile var version: String = "0.1"

    /** 公開リポジトリのURL(MusicBrainz に送る連絡先として使う) */
    const val PROJECT_URL = "https://github.com/uyatame/Uyatame-CD-Ripping-Tool-UCRT"

    private val ua: String
        get() = "UCRT/$version ( " + contact.ifBlank { PROJECT_URL } + " )"

    class DiscToc(val discId: String, val tocParam: String, val audioCount: Int)

    fun toc(tracks: List<TocTrack>, leadOut: Int): DiscToc? {
        val audio = tracks.filter { it.isAudio }
        if (audio.isEmpty()) return null
        val first = audio.first().number
        val last = audio.last().number
        val next = tracks.getOrNull(tracks.indexOf(audio.last()) + 1)
        val lo = (if (next != null && !next.isAudio) next.startLba - 11400 else leadOut) + 150
        val sb = StringBuilder()
        sb.append("%02X".format(first)).append("%02X".format(last)).append("%08X".format(lo))
        for (i in 1..99) {
            val t = audio.firstOrNull { it.number == i }
            sb.append("%08X".format(if (t != null) t.startLba + 150 else 0))
        }
        val sha = MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray(Charsets.US_ASCII))
        val id = Base64.encodeToString(sha, Base64.NO_WRAP).replace('+', '.').replace('/', '_').replace('=', '-')
        val tocParam = (listOf(first, last, lo) + audio.map { it.startLba + 150 }).joinToString("+")
        return DiscToc(id, tocParam, audio.size)
    }

    /** ディスクIDで検索(見つからなければ曲の長さが近いものを探す)。 */
    fun lookup(t: DiscToc): SourceResult = try {
        val base = "$BASE/discid/${t.discId}?toc=${t.tocParam}&cdstubs=no&media-format=all&fmt=json&inc="
        // 対応していない inc 指定で 400 が返った場合は、最小限の指定で取り直す
        val bytes = try {
            get(base + "artist-credits+recordings+labels", "application/json")
        } catch (e: IOException) {
            if (e.message?.startsWith("HTTP 400") == true) get(base + "artist-credits+recordings", "application/json") else throw e
        }
        val list = if (bytes == null) emptyList() else parseReleases(JSONObject(String(bytes, Charsets.UTF_8)), t)
        SourceResult(SOURCE, list, null)
    } catch (e: Exception) {
        SourceResult(SOURCE, emptyList(), describeNetError(e))
    }

    private fun parseReleases(root: JSONObject, t: DiscToc): List<ReleaseCandidate> {
        val rels = root.optJSONArray("releases") ?: return emptyList()
        // ディスクIDで見つかった場合は応答そのものがディスク(id = ディスクID)。曲の長さが近いものを探した結果には id がない
        val discMatched = root.optString("id") == t.discId
        val out = ArrayList<ReleaseCandidate>()
        val seen = HashSet<String>()
        for (i in 0 until rels.length()) {
            val r = rels.getJSONObject(i)
            val media = r.optJSONArray("media") ?: continue
            var medium: JSONObject? = null
            var exact = discMatched
            loop@ for (j in 0 until media.length()) {
                val m = media.getJSONObject(j)
                val discs = m.optJSONArray("discs") ?: continue
                for (k in 0 until discs.length()) {
                    if (discs.getJSONObject(k).optString("id") == t.discId) { medium = m; exact = true; break@loop }
                }
            }
            if (medium == null) {
                for (j in 0 until media.length()) {
                    val m = media.getJSONObject(j)
                    val cnt = m.optJSONArray("tracks")?.length() ?: m.optInt("track-count")
                    if (cnt == t.audioCount) { medium = m; break }
                }
            }
            val md = medium ?: continue
            val id = r.optString("id")
            val pos = md.optInt("position", 1)
            if (!seen.add("$id/$pos")) continue
            val meta = mediumMeta(r, md, media.length())
            out.add(ReleaseCandidate(meta, summary(r, meta, media.length()), SOURCE, exact, meta.tracks.size))
        }
        return out.sortedByDescending { it.exact }
    }

    private fun mediumMeta(r: JSONObject, md: JSONObject, total: Int): AlbumMeta {
        val albumArtist = credit(r.optJSONArray("artist-credit"))
        val ta = md.optJSONArray("tracks") ?: JSONArray()
        val tracks = (0 until ta.length()).map { k ->
            val tr = ta.getJSONObject(k)
            val title = tr.optString("title").ifEmpty { tr.optJSONObject("recording")?.optString("title") ?: "" }
            TrackMeta(title, credit(tr.optJSONArray("artist-credit")).ifEmpty { albumArtist })
        }
        return AlbumMeta(
            r.optString("title"), albumArtist, r.optString("date"), r.optString("id"),
            tracks, md.optInt("position", 1), total,
        )
    }

    private fun summary(r: JSONObject, meta: AlbumMeta, total: Int, trackCount: Int = meta.tracks.size): String {
        val label = r.optJSONArray("label-info")?.optJSONObject(0)?.let { li ->
            listOfNotNull(
                li.optJSONObject("label")?.optString("name")?.takeIf { it.isNotEmpty() },
                li.optString("catalog-number").takeIf { it.isNotEmpty() },
            ).joinToString(" ")
        } ?: ""
        return listOf(
            meta.date, r.optString("country"), label,
            if (total > 1) S(R.string.disc_2, meta.discNo, total) else "",
            S(R.string.tracks, trackCount),
        ).filter { it.isNotEmpty() }.joinToString(S(R.string.label_5))
    }

    private val luceneSpecial = Regex("""([+\-&|!(){}\[\]^"~*?:\\/])""")
    private fun esc(s: String) = s.trim().replace(luceneSpecial, "\\\\$1")

    /**
     * アルバム名・アーティスト名で検索する。結果には曲目が含まれないため、
     * 選ばれたときに loader で曲目を取得する。trackCount に合うディスクを優先して並べる。
     */
    fun search(album: String, artist: String, trackCount: Int): SourceResult = try {
        val parts = ArrayList<String>()
        if (album.isNotBlank()) parts.add("release:(${esc(album)})")
        if (artist.isNotBlank()) parts.add("artist:(${esc(artist)})")
        if (parts.isEmpty()) throw IOException(S(R.string.enter_search_term))
        val q = URLEncoder.encode(parts.joinToString(" AND "), "UTF-8")
        val bytes = get("$BASE/release/?query=$q&limit=25&fmt=json", "application/json")
        val rels = bytes?.let { JSONObject(String(it, Charsets.UTF_8)).optJSONArray("releases") } ?: JSONArray()
        val out = ArrayList<ReleaseCandidate>()
        for (i in 0 until rels.length()) {
            val r = rels.getJSONObject(i)
            val media = r.optJSONArray("media") ?: JSONArray()
            var pos = 1
            var cnt = r.optInt("track-count")
            for (j in 0 until media.length()) {
                val m = media.getJSONObject(j)
                if (m.optInt("track-count") == trackCount) { pos = m.optInt("position", j + 1); cnt = trackCount; break }
            }
            if (media.length() == 1) cnt = media.getJSONObject(0).optInt("track-count", cnt)
            val id = r.optString("id")
            val meta = AlbumMeta(
                r.optString("title"), credit(r.optJSONArray("artist-credit")), r.optString("date"), id,
                emptyList(), pos, maxOf(media.length(), 1),
            )
            val p = pos
            out.add(
                ReleaseCandidate(
                    meta, summary(r, meta, maxOf(media.length(), 1), cnt), SOURCE, false, cnt,
                    loader = { loadRelease(id, trackCount, p) },
                ),
            )
        }
        SourceResult(SOURCE, out.sortedByDescending { it.trackCount == trackCount }, null)
    } catch (e: Exception) {
        SourceResult(SOURCE, emptyList(), describeNetError(e))
    }

    /** リリースの曲目を取得する。trackCount と曲数が一致するディスクを選ぶ。 */
    fun loadRelease(id: String, trackCount: Int, preferredPos: Int): AlbumMeta? {
        val bytes = get("$BASE/release/$id?inc=artist-credits+recordings+labels&fmt=json", "application/json") ?: return null
        val r = JSONObject(String(bytes, Charsets.UTF_8))
        val media = r.optJSONArray("media") ?: return null
        var md: JSONObject? = null
        for (j in 0 until media.length()) {
            val m = media.getJSONObject(j)
            if (m.optInt("position") == preferredPos && (m.optJSONArray("tracks")?.length() ?: 0) == trackCount) md = m
        }
        if (md == null) {
            for (j in 0 until media.length()) {
                val m = media.getJSONObject(j)
                if ((m.optJSONArray("tracks")?.length() ?: 0) == trackCount) { md = m; break }
            }
        }
        val chosen = md ?: media.optJSONObject(0) ?: return null
        return mediumMeta(r, chosen, media.length())
    }

    // ---- 曲情報の登録(アップロード) ----
    // MusicBrainz には、アプリから直接リリースを登録する API が無い。公式の「リリースの登録画面に値を入れて開く」仕組み
    // (release editor seeding)を使い、内容の確認と送信は利用者が MusicBrainz にログインして行う

    private fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
        .lowercase().replace(Regex("\\s+"), " ").trim()

    /**
     * 同じアルバム(アルバム名・アーティスト名・曲数が同じ)が、すでに MusicBrainz にあるか。あればその候補。
     * 通信できなかったときは IOException(登録してよいか分からないので、登録には進まない)
     */
    fun findSame(m: AlbumMeta, trackCount: Int): ReleaseCandidate? {
        val r = search(m.title, m.artist, trackCount)
        if (r.error != null) throw IOException(r.error)
        return r.candidates.firstOrNull {
            it.trackCount == trackCount && norm(it.meta.title) == norm(m.title) && norm(it.meta.artist) == norm(m.artist)
        }
    }

    /** このCDのディスクIDを、すでにあるリリースに追加する MusicBrainz のページ(ブラウザで開く) */
    fun attachUrl(t: DiscToc): String = "https://musicbrainz.org/cdtoc/attach?id=${t.discId}&tracks=${t.audioCount}&toc=${t.tocParam}"

    fun releaseUrl(id: String): String = "https://musicbrainz.org/release/$id"

    /** リリースにジャケット画像を追加するページ */
    fun addCoverArtUrl(id: String): String = "https://musicbrainz.org/release/$id/add-cover-art"

    /**
     * 登録画面で送信したあとに MusicBrainz が移る先(実在しないアドレス。アプリの中のブラウザが受け取って、
     * 登録されたリリースの MBID を知り、ジャケット画像の追加へ進む)。MusicBrainz は後ろに ?release_mbid=… を付ける
     */
    const val DONE_URL = "https://ucrt.invalid/musicbrainz-done"

    /** 登録のときに付ける編集メモ(MusicBrainz の利用者が読むので英語) */
    val EDIT_NOTE: String
        get() = "Track list entered by the user in UCRT ($PROJECT_URL) for this CD. The disc ID comes from the CD's TOC."

    /** ライブラリ(取り込み済みの曲のタグ)から登録するときの編集メモ。CD の目次は無い */
    val EDIT_NOTE_LIBRARY: String
        get() = "Track list from the user's tagged files in UCRT ($PROJECT_URL). Track lengths are from the files; no disc ID (CD TOC) is available."

    /**
     * リリースの詳しい情報(空のものは入れない)。
     * type: album / single / ep / other、status: official / promotion / bootleg、country: ISO 3166 の 2 文字(JP など)、
     * language: ISO 639-3(jpn など)、script: ISO 15924(Jpan など)、packaging: MusicBrainz の名前(Jewel Case など)、
     * comment: 同じ名前のリリースを見分けるための短い説明、annotation: 自由な注記
     */
    class ReleaseExtra(
        val type: String = "",
        val status: String = "official",
        val country: String = "",
        val label: String = "",
        val catalog: String = "",
        val barcode: String = "",
        val language: String = "",
        val script: String = "",
        val packaging: String = "",
        val comment: String = "",
        val annotation: String = "",
    )

    /** 登録する 1 曲(長さが分からなければ 0) */
    class SeedTrack(val title: String, val artist: String, val lengthMs: Long)

    /** 登録する 1 枚(toc は CD の目次。分かれば付けると、ディスクIDも一緒に登録される) */
    class SeedDisc(val position: Int, val tracks: List<SeedTrack>, val toc: String? = null)

    /**
     * MusicBrainz のリリースの登録画面を、曲情報を入れた状態で開くページ(CD から)。
     * lengthsMs は曲ごとの長さ。CD の TOC も付けるので、登録するとディスクIDも一緒に登録される
     */
    fun seedHtml(m: AlbumMeta, t: DiscToc, lengthsMs: List<Long>, note: String): String =
        seedHtml(
            m.title, m.artist, m.date,
            listOf(
                SeedDisc(
                    if (m.discTotal > 1) m.discNo else 0,
                    lengthsMs.indices.map { i -> m.tracks.getOrNull(i).let { SeedTrack(it?.title.orEmpty(), it?.artist.orEmpty(), lengthsMs[i]) } },
                    t.tocParam.replace('+', ' '),
                ),
            ),
            note,
        )

    /**
     * MusicBrainz のリリースの登録画面を、曲情報を入れた状態で開くページ(読み込むとすぐ送信するフォーム)。
     * discs の position が 0 なら、何枚目かを入れない(1 枚組)
     */
    fun seedHtml(
        title: String, artist: String, date: String, discs: List<SeedDisc>, note: String,
        extra: ReleaseExtra = ReleaseExtra(),
    ): String {
        val fields = ArrayList<Pair<String, String>>()
        fun f(k: String, v: String) { if (v.isNotBlank()) fields.add(k to v.trim()) }
        f("name", title)
        f("artist_credit.names.0.name", artist)
        f("status", extra.status)
        f("type", extra.type)
        f("comment", extra.comment)
        f("annotation", extra.annotation)
        f("barcode", extra.barcode.filter { it.isDigit() })
        f("language", extra.language)
        f("script", extra.script)
        f("packaging", extra.packaging)
        f("events.0.country", extra.country.uppercase())
        if (extra.label.isNotBlank() || extra.catalog.isNotBlank()) {
            f("labels.0.name", extra.label)
            f("labels.0.catalog_number", extra.catalog)
        }
        val d = Regex("^(\\d{4})(?:-(\\d{1,2}))?(?:-(\\d{1,2}))?").find(date.trim())
        if (d != null) {
            f("events.0.date.year", d.groupValues[1])
            f("events.0.date.month", d.groupValues[2])
            f("events.0.date.day", d.groupValues[3])
        }
        discs.forEachIndexed { n, disc ->
            val md = "mediums.$n"
            f("$md.format", "CD")
            if (disc.position > 0) f("$md.position", disc.position.toString())
            disc.toc?.let { f("$md.toc", it) }
            disc.tracks.forEachIndexed { i, tr ->
                f("$md.track.$i.number", (i + 1).toString())
                f("$md.track.$i.name", tr.title)
                if (tr.lengthMs > 0) f("$md.track.$i.length", tr.lengthMs.toString())
                // 曲のアーティストがアルバムと違うときだけ入れる(同じなら MusicBrainz がアルバムのアーティストを使う)
                if (tr.artist.isNotBlank() && norm(tr.artist) != norm(artist)) f("$md.track.$i.artist_credit.names.0.name", tr.artist)
            }
        }
        f("edit_note", note)
        f("redirect_uri", DONE_URL)
        fun h(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val inputs = fields.joinToString("\n") { (k, v) -> "<input type=\"hidden\" name=\"${h(k)}\" value=\"${h(v)}\">" }
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<style>body{font-family:sans-serif;padding:24px}</style></head>
<body><p>MusicBrainz…</p>
<form id="f" method="post" action="https://musicbrainz.org/release/add" accept-charset="utf-8">
$inputs
</form><script>document.getElementById('f').submit()</script></body></html>"""
    }

    /** Cover Art Archive の表ジャケット(500px)。画像の権利は各権利者に帰属。 */
    class CoverResult(val data: ByteArray?, val message: String)

    /**
     * ジャケット画像を取得する。リリースに画像がなければ、同じアルバム(リリースグループ)の別の版の画像を探す。
     * Cover Art Archive は archive.org へ転送するため、転送先を https にそろえて自前でたどる。
     */
    fun cover(releaseId: String): CoverResult {
        return try {
            fetchImage("https://coverartarchive.org/release/$releaseId/front-500")?.let { return CoverResult(it, "release") }
            val bytes = get("$BASE/release/$releaseId?inc=release-groups&fmt=json", "application/json")
            val rg = bytes?.let { JSONObject(String(it, Charsets.UTF_8)).optJSONObject("release-group")?.optString("id") }.orEmpty()
            if (rg.isNotEmpty()) {
                fetchImage("https://coverartarchive.org/release-group/$rg/front-500")?.let { return CoverResult(it, "release-group") }
            }
            CoverResult(null, S(R.string.no_artwork_is_available_for))
        } catch (e: Exception) {
            CoverResult(null, describeNetError(e))
        }
    }

    private fun fetchImage(start: String): ByteArray? {
        var url = start
        for (hop in 0 until 8) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", ua)
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            try {
                val code = c.responseCode
                when {
                    code == 404 -> return null
                    code in 300..399 -> {
                        val loc = c.getHeaderField("Location") ?: return null
                        url = URL(URL(url), loc).toString().replaceFirst(Regex("^http://"), "https://")
                    }
                    code in 200..299 -> return c.inputStream.use { it.readBytes() }
                    else -> throw IOException("HTTP $code")
                }
            } finally {
                c.disconnect()
            }
        }
        return null
    }

    private fun credit(a: JSONArray?): String {
        if (a == null) return ""
        val sb = StringBuilder()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            sb.append(o.optString("name").ifEmpty { o.optJSONObject("artist")?.optString("name") ?: "" })
            sb.append(o.optString("joinphrase"))
        }
        return sb.toString().trim()
    }

    private val lock = Any()
    private var lastRequest = 0L

    /** 1秒に1回までに抑えて取得する。404は null、混雑(503)は1回だけ待って再試行。 */
    private fun get(url: String, accept: String, throttle: Boolean = true): ByteArray? {
        for (attempt in 0 until 2) {
            if (throttle) {
                synchronized(lock) {
                    val wait = lastRequest + 1100 - System.currentTimeMillis()
                    if (wait > 0) Thread.sleep(wait)
                    lastRequest = System.currentTimeMillis()
                }
            }
            val c = URL(url).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", accept)
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            try {
                val code = c.responseCode
                if (code == 404) return null
                if (code == 503 && attempt == 0) { Thread.sleep(1500); continue }
                if (code !in 200..299) {
                    val body = runCatching { c.errorStream?.use { String(it.readBytes()).take(200) } }.getOrNull().orEmpty()
                    throw IOException("HTTP $code $body".trim())
                }
                return c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        }
        throw IOException(S(R.string.server_is_busy_http_503))
    }
}
