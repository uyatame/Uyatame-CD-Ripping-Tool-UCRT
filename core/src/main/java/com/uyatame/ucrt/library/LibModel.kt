package com.uyatame.ucrt.library

import com.uyatame.ucrt.R
import com.uyatame.ucrt.S

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ライブラリの曲・アルバムと、曲情報の読み込み(app と legacy で共通)

data class LibTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val track: Int,
    val disc: Int,
    val durationMs: Long,
    val folderId: String,
    val coverUri: String?,
    val modified: Long,
    val year: String = "",
    val genre: String = "",
    /** サンプリング周波数(DSD は 1 チャンネルあたりの DSD の周波数)。分からなければ 0 */
    val rate: Int = 0,
    /** 量子化ビット数(DSD は 1)。分からなければ 0 */
    val bits: Int = 0,
    /** CUE シートで分けた曲の、元のファイルの中での始まり(ミリ秒) */
    val startMs: Long = 0,
    /** CUE シートで分けた曲の終わり(0 ならファイルの終わりまで) */
    val endMs: Long = 0,
    /** CUE シートで曲に分けたため、一覧には出さない元のファイル */
    val hidden: Boolean = false,
    /** CUE シートで分けた曲の、CUE シートの URI */
    val sheet: String? = null,
    /** 拡張子だけでは分からない形式(MP4 の中身が MPEG-H なら "MPEG-H")。空なら拡張子から決める */
    val codec: String = "",
)

data class LibAlbum(val key: String, val title: String, val artist: String, val tracks: List<LibTrack>) {
    val first: LibTrack get() = tracks.first()
    // 以下は並べ替え・絞り込みのたびに何度も使うので、最初に使ったときに一度だけ計算する

    /** 発売年(曲ごとに違えば最初に見つかったもの) */
    val year: String by lazy { tracks.firstOrNull { it.year.isNotEmpty() }?.year.orEmpty() }
    /** いちばん新しく追加・更新された曲の時刻 */
    val added: Long by lazy { tracks.maxOf { it.modified } }
    /** 音質の区分(いちばん良い曲に合わせる) */
    internal val quality: QualityClass by lazy { Quality.albumClass(tracks) }
    /** 形式と音質の表記 */
    internal val badge: String by lazy { Quality.albumBadge(tracks) }
    /** いちばん良い曲と、その細かい分類(音質別の絞り込み) */
    internal val best: LibTrack? by lazy { Quality.bestTrack(tracks) }
    internal val detail: String by lazy { best?.let { Quality.detail(it) }.orEmpty() }
}

val UNKNOWN_ALBUM: String get() = S(R.string.unknown_album)
val UNKNOWN_ARTIST: String get() = S(R.string.unknown_artist)

/** ライブラリに入れる音声ファイルの拡張子 */
val LIB_AUDIO_EXT = setOf("flac", "wav", "m4a", "mp3", "ogg", "opus", "aac", "dsf", "dff", "aif", "aiff", "aifc", "mp4", "wv", "ape")
/** ジャケットとして使う画像ファイルの名前 */
val LIB_COVER_NAMES = setOf(
    "cover.jpg", "cover.jpeg", "cover.png", "cover.webp", "folder.jpg", "folder.jpeg", "folder.png",
    "front.jpg", "front.jpeg", "front.png", "albumart.jpg", "albumartlarge.jpg", "album.jpg", "album.png", "jacket.jpg", "jacket.png",
)

/** ライブラリの一覧の保存・読み込みと、アルバムへのまとめ方 */
object LibCore {
    /** 一覧の形式の版(音質・ジャンルを追加したので 2) */
    const val CACHE_VERSION = 3

    /**
     * 保存した一覧を読む。2 つ目の値は、前回の版で作った一覧で音質やジャンルの情報が入っていない(全曲を読み直す)とき true。
     * key は同じファイルを見分けるキー(重なったものは 1 つにする)
     */
    fun loadCache(cacheFile: File, key: (String) -> String): Pair<List<LibTrack>, Boolean> {
        var needsFullRescan = false
        val list = try {
            if (!cacheFile.exists()) emptyList() else {
                val text = cacheFile.readText()
                val a: JSONArray
                if (text.trimStart().startsWith("[")) {
                    // 以前の版の形式(音質・ジャンルなし)
                    a = JSONArray(text)
                    needsFullRescan = a.length() > 0
                } else {
                    val root = JSONObject(text)
                    a = root.optJSONArray("tracks") ?: JSONArray()
                    needsFullRescan = root.optInt("v") < CACHE_VERSION && a.length() > 0
                }
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    LibTrack(
                        o.getString("u"), o.optString("t"), o.optString("a"), o.optString("al"), o.optString("aa"),
                        o.optInt("n"), o.optInt("d", 1), o.optLong("ms"), o.optString("f"),
                        if (o.has("c")) o.getString("c") else null, o.optLong("m"), o.optString("y"),
                        o.optString("g"), o.optInt("sr"), o.optInt("bt"),
                        o.optLong("ss"), o.optLong("se"), o.optBoolean("h"), if (o.has("cs")) o.getString("cs") else null,
                        o.optString("cd"),
                    )
                }.distinctBy { key(it.uri) }
            }
        } catch (e: Exception) {
            emptyList()
        }
        return list to needsFullRescan
    }

    fun saveCache(cacheFile: File, list: List<LibTrack>) {
        val a = JSONArray()
        for (t in list) {
            val o = JSONObject()
            o.put("u", t.uri); o.put("t", t.title); o.put("a", t.artist); o.put("al", t.album); o.put("aa", t.albumArtist)
            o.put("n", t.track); o.put("d", t.disc); o.put("ms", t.durationMs); o.put("f", t.folderId); o.put("m", t.modified)
            if (t.coverUri != null) o.put("c", t.coverUri)
            o.put("y", t.year)
            if (t.genre.isNotEmpty()) o.put("g", t.genre)
            if (t.rate > 0) o.put("sr", t.rate)
            if (t.bits > 0) o.put("bt", t.bits)
            if (t.startMs > 0) o.put("ss", t.startMs)
            if (t.endMs > 0) o.put("se", t.endMs)
            if (t.hidden) o.put("h", true)
            if (t.sheet != null) o.put("cs", t.sheet)
            if (t.codec.isNotEmpty()) o.put("cd", t.codec)
            a.put(o)
        }
        val root = JSONObject().put("v", CACHE_VERSION).put("tracks", a)
        // 書き込みの途中でアプリが終わっても一覧が壊れないよう、別のファイルに書いてから置き換える
        runCatching {
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(cacheFile)) { cacheFile.writeText(root.toString()); tmp.delete() }
        }
    }

    /** ジャンル名の整理(ID3v1 の番号 "(17)" などは外す) */
    fun cleanGenre(g: String?): String {
        val s = g?.trim().orEmpty()
        if (s.isEmpty()) return ""
        val m = Regex("^\\((\\d+)\\)(.*)$").find(s)
        if (m != null) return m.groupValues[2].trim().ifEmpty { m.groupValues[1].toIntOrNull()?.let { ID3V1_GENRES.getOrNull(it) } ?: "" }
        s.toIntOrNull()?.let { return ID3V1_GENRES.getOrNull(it) ?: "" }
        return s
    }

    private val ID3V1_GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    )

    private fun isUnknownAlbum(a: String) = a == UNKNOWN_ALBUM || a == "不明なアルバム" || a == "Unknown album"

    /** いちばん多い値(空は数えない。無ければ null) */
    private fun mostCommon(values: List<String>): String? =
        values.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /**
     * 曲をアルバムにまとめる。
     * 同じフォルダにあって同じアルバム名の曲は、曲ごとのアーティスト(「feat. ○○」など)が違っても 1 枚のアルバムにする。
     * アルバムアーティストがあれば、別のフォルダ(Disc 1 / Disc 2 など)の曲も同じアルバムにまとめる
     */
    fun group(tracks: List<LibTrack>): List<LibAlbum> {
        // 1) フォルダとアルバム名でまとめる
        val byFolder = tracks.filter { !it.hidden }.groupBy { t ->
            if (isUnknownAlbum(t.album)) "f:" + t.folderId else t.folderId + "\u0000" + t.album
        }
        // 2) まとまりごとに、アルバムのキーを決める(同じキーのまとまりは 1 枚にする)
        val merged = LinkedHashMap<String, MutableList<LibTrack>>()
        for ((fk, list) in byFolder) {
            val first = list.first()
            val key = when {
                isUnknownAlbum(first.album) -> fk
                else -> {
                    val aa = mostCommon(list.map { it.albumArtist })
                    val artists = list.map { it.artist }.distinct()
                    when {
                        aa != null -> "a:" + aa + "\u0000" + first.album
                        // 全曲が同じアーティストなら、以前と同じキー(お気に入りなどがそのまま使える)
                        artists.size == 1 -> "a:" + artists[0] + "\u0000" + first.album
                        else -> "d:" + first.folderId + "\u0000" + first.album
                    }
                }
            }
            merged.getOrPut(key) { ArrayList() }.addAll(list)
        }
        return merged.map { (k, list) ->
            val sorted = list.sortedWith(compareBy({ it.disc }, { it.track }, { it.title }))
            val f = sorted.first()
            val artist = mostCommon(sorted.map { it.albumArtist }) ?: run {
                // アルバムアーティストが無ければ、半分以上の曲のアーティスト(feat. 付きの曲が混ざっていても)
                val top = sorted.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }
                if (top != null && top.value * 2 >= sorted.size) top.key else S(R.string.various_artists)
            }
            LibAlbum(k, f.album, artist, sorted)
        }.sortedBy { it.artist.lowercase() + "\u0000" + it.title.lowercase() }
    }
}

/**
 * 曲のファイルから曲情報を読む。open はファイルを開く方法、fileOf はファイルの場所が分かるときにそれを返す方法
 * (app は保存場所の URI、legacy はファイルの場所)
 */
class TagScan(
    private val ctx: Context,
    private val open: (Uri) -> java.io.InputStream?,
    private val fileOf: (Uri) -> File?,
) {
    /** 足りない分を読み切る */
    private fun readFully(ins: java.io.InputStream, b: ByteArray): Int {
        var off = 0
        while (off < b.size) {
            val r = ins.read(b, off, b.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    /**
     * FLAC はファイルの先頭(STREAMINFO と VORBIS_COMMENT)だけを自前で読む。
     * Android 標準の読み取り機能より大幅に速い。読めなければ null。
     */
    private fun readFlacTags(uri: Uri, name: String, folder: String, cover: String?, mod: Long): LibTrack? {
        val raw = runCatching { open(uri) }.getOrNull() ?: return null
        return runCatching {
            raw.use { r0 ->
                val ins = java.io.BufferedInputStream(r0, 1 shl 16)
                val head = ByteArray(4)
                if (readFully(ins, head) != 4 || String(head, Charsets.ISO_8859_1) != "fLaC") return null
                var rate = 0
                var bits = 0
                var samples = 0L
                val tags = HashMap<String, String>()
                while (true) {
                    val bh = ByteArray(4)
                    if (readFully(ins, bh) != 4) break
                    val last = (bh[0].toInt() and 0x80) != 0
                    val type = bh[0].toInt() and 0x7F
                    val len = ((bh[1].toInt() and 0xFF) shl 16) or ((bh[2].toInt() and 0xFF) shl 8) or (bh[3].toInt() and 0xFF)
                    if (type == 0 || type == 4) {
                        val b = ByteArray(len)
                        if (readFully(ins, b) != len) break
                        if (type == 0 && len >= 18) {
                            fun u(i: Int) = b[i].toLong() and 0xFF
                            rate = ((u(10) shl 12) or (u(11) shl 4) or (u(12) shr 4)).toInt()
                            bits = (((u(12) and 1) shl 4) or (u(13) shr 4)).toInt() + 1
                            samples = ((u(13) and 0x0F) shl 32) or (u(14) shl 24) or (u(15) shl 16) or (u(16) shl 8) or u(17)
                        } else if (type == 4) {
                            val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            val vlen = bb.int
                            bb.position(bb.position() + vlen)
                            val n = bb.int
                            for (k in 0 until n) {
                                if (bb.remaining() < 4) break
                                val l = bb.int
                                if (l < 0 || l > bb.remaining()) break
                                val sArr = ByteArray(l)
                                bb.get(sArr)
                                val c = String(sArr, Charsets.UTF_8)
                                val eq = c.indexOf('=')
                                if (eq > 0) {
                                    val key = c.substring(0, eq).uppercase()
                                    if (key !in tags) tags[key] = c.substring(eq + 1).trim()
                                }
                            }
                        }
                    } else {
                        var left = len.toLong()
                        while (left > 0) {
                            val sk = ins.skip(left)
                            if (sk <= 0) break
                            left -= sk
                        }
                    }
                    if (last) break
                }
                val base = name.substringBeforeLast('.')
                fun t(k: String) = tags[k]?.takeIf { it.isNotEmpty() }
                LibTrack(
                    uri.toString(),
                    t("TITLE") ?: base,
                    t("ARTIST") ?: UNKNOWN_ARTIST,
                    t("ALBUM") ?: UNKNOWN_ALBUM,
                    t("ALBUMARTIST") ?: t("ALBUM ARTIST") ?: "",
                    t("TRACKNUMBER")?.substringBefore('/')?.trim()?.toIntOrNull() ?: base.take(2).toIntOrNull() ?: 0,
                    t("DISCNUMBER")?.substringBefore('/')?.trim()?.toIntOrNull() ?: 1,
                    if (rate > 0) samples * 1000 / rate else 0L,
                    folder, cover, mod,
                    (t("DATE") ?: t("YEAR"))?.take(4) ?: "",
                    cleanGenre(t("GENRE")),
                    rate, bits,
                )
            }
        }.getOrNull()
    }

    /** DSD(DSF / DFF)は Android 標準の機能で読めないので、自前で読む */
    private fun readDsdTags(uri: Uri, name: String, folder: String, cover: String?, mod: Long): LibTrack {
        val base = name.substringBeforeLast('.')
        val r = com.uyatame.ucrt.player.dsd.DsdTags.read(ctx, uri)
        val t = r?.tags
        return LibTrack(
            uri.toString(),
            t?.title ?: base,
            t?.artist ?: UNKNOWN_ARTIST,
            t?.album ?: UNKNOWN_ALBUM,
            t?.albumArtist ?: "",
            t?.track ?: base.take(2).toIntOrNull() ?: 0,
            t?.disc ?: 1,
            r?.info?.durationMs ?: 0L,
            folder, cover, mod,
            t?.year ?: "",
            cleanGenre(t?.genre),
            r?.info?.rate ?: 0, if (r != null) 1 else 0,
        )
    }

    /** WAV は Android 標準の機能では曲情報を読めないので、自前で読む(LIST/INFO と id3 チャンク) */
    private fun readWavTags(uri: Uri, name: String, folder: String, cover: String?, mod: Long): LibTrack? {
        val w = runCatching { com.uyatame.ucrt.tags.WavTags.read(ctx, uri) }.getOrNull() ?: return null
        val base = name.substringBeforeLast('.')
        val t = w.tags
        return LibTrack(
            uri.toString(),
            t?.title ?: base,
            t?.artist ?: UNKNOWN_ARTIST,
            t?.album ?: UNKNOWN_ALBUM,
            t?.albumArtist ?: "",
            t?.track ?: base.take(2).toIntOrNull() ?: 0,
            t?.disc ?: 1,
            w.durationMs,
            folder, cover, mod,
            t?.year ?: "",
            cleanGenre(t?.genre),
            w.rate, w.bits,
        )
    }

    /** AIFF・WavPack・APE は Android 標準の機能では読めないので、アプリ内蔵のデコーダーとタグの読み込みで読む */
    private fun readBuiltinTags(uri: Uri, name: String, folder: String, cover: String?, mod: Long, kind: String): LibTrack? {
        val (info, t) = com.uyatame.ucrt.player.BuiltinCodecs.probe(ctx, uri, kind) ?: return null
        val base = name.substringBeforeLast('.')
        return LibTrack(
            uri.toString(),
            t?.title ?: base,
            t?.artist ?: UNKNOWN_ARTIST,
            t?.album ?: UNKNOWN_ALBUM,
            t?.albumArtist ?: "",
            t?.track ?: base.take(2).toIntOrNull() ?: 0,
            t?.disc ?: 1,
            info.durationMs,
            folder, cover, mod,
            t?.year ?: "",
            cleanGenre(t?.genre),
            info.rate, info.bits,
        )
    }

    fun readTags(uri: Uri, name: String, folder: String, cover: String?, mod: Long): LibTrack {
        val kind = com.uyatame.ucrt.player.BuiltinCodecs.kindOf(name)
        if (kind == "aiff" || kind == "wavpack" || kind == "ape") readBuiltinTags(uri, name, folder, cover, mod, kind)?.let { return it }
        // MP4 / M4A: 中身の形式を見る
        if (kind == "alac") {
            val entry = com.uyatame.ucrt.player.BuiltinCodecs.openInput(ctx, uri)?.use { com.uyatame.ucrt.player.Mp4Probe.audioEntry(it) }
            // MPEG-H 3D Audio(360 Reality Audio など)なら、そう記録する
            if (com.uyatame.ucrt.player.Mp4Probe.isMpegH(entry)) return readTagsRetriever(uri, name, folder, cover, mod).copy(codec = "MPEG-H")
            val t = readTagsRetriever(uri, name, folder, cover, mod)
            // ALAC: Android 10 より前は周波数・ビット数を教えてくれないので、中身から読む(AAC なら 0 のまま)
            if (entry != "alac" || t.bits > 0) return t
            val info = com.uyatame.ucrt.player.BuiltinCodecs.openInput(ctx, uri)?.use { com.uyatame.ucrt.player.BuiltinCodecs.info("alac", it) }
                ?: return t
            return t.copy(rate = info.rate, bits = info.bits, durationMs = if (t.durationMs > 0) t.durationMs else info.durationMs)
        }
        return readTagsRetriever(uri, name, folder, cover, mod)
    }

    private fun readTagsRetriever(uri: Uri, name: String, folder: String, cover: String?, mod: Long): LibTrack {
        if (com.uyatame.ucrt.player.dsd.DsdFile.isDsd(name)) return readDsdTags(uri, name, folder, cover, mod)
        if (name.lowercase().endsWith(".wav")) readWavTags(uri, name, folder, cover, mod)?.let { return it }
        if (name.lowercase().endsWith(".flac")) readFlacTags(uri, name, folder, cover, mod)?.let { return it }
        val base = name.substringBeforeLast('.')
        val r = MediaMetadataRetriever()
        return try {
            // ファイル記述子を直接渡す方が速い
            val file = fileOf(uri)
            if (file != null) {
                r.setDataSource(file.absolutePath)
            } else {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                // (ParcelFileDescriptor が Closeable になったのは Android 4.1 からなので use は使わない)
                if (pfd != null) try { r.setDataSource(pfd.fileDescriptor) } finally { pfd.close() } else r.setDataSource(ctx, uri)
            }
            fun m(k: Int): String? = r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
            val tn = m(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.toIntOrNull()
                ?: base.take(2).toIntOrNull() ?: 0
            LibTrack(
                uri.toString(),
                m(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: base,
                m(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: UNKNOWN_ARTIST,
                m(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: UNKNOWN_ALBUM,
                m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: "",
                tn,
                m(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.substringBefore('/')?.toIntOrNull() ?: 1,
                m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                folder, cover, mod,
                m(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: m(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4) ?: "",
                cleanGenre(m(MediaMetadataRetriever.METADATA_KEY_GENRE)),
                m(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0,
                m(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0,
            )
        } catch (e: Exception) {
            LibTrack(uri.toString(), base, UNKNOWN_ARTIST, UNKNOWN_ALBUM, "", base.take(2).toIntOrNull() ?: 0, 1, 0L, folder, cover, mod)
        } finally {
            runCatching { r.release() }
        }
    }

    private fun cleanGenre(g: String?): String = LibCore.cleanGenre(g)

}
