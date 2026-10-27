package com.uyatame.ucrt.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MusicBrainz の登録画面に入れる値(release editor seeding)と、ディスクIDの追加のページ */
class MusicBrainzSeedTest {
    private val toc = MusicBrainz.DiscToc("abc.DEF_123-", "1+3+30000+150+10000+20000", 3)

    private fun fields(html: String): Map<String, String> =
        Regex("<input type=\"hidden\" name=\"([^\"]*)\" value=\"([^\"]*)\">").findAll(html)
            .associate { it.groupValues[1] to it.groupValues[2].replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&") }

    @Test fun seedsReleaseWithTocAndTracks() {
        val m = AlbumMeta(
            "アルバム <1>", "歌手", "2024-03-05",
            tracks = listOf(TrackMeta("曲 \"A\"", "歌手"), TrackMeta("B & C", "ゲスト"), TrackMeta("D", "")),
        )
        val html = MusicBrainz.seedHtml(m, toc, listOf(133000, 133333, 133333), "note")
        assertTrue(html.contains("action=\"https://musicbrainz.org/release/add\""))
        val f = fields(html)
        assertEquals("アルバム <1>", f["name"])
        assertEquals("歌手", f["artist_credit.names.0.name"])
        assertEquals("2024", f["events.0.date.year"])
        assertEquals("03", f["events.0.date.month"])
        assertEquals("05", f["events.0.date.day"])
        assertEquals("CD", f["mediums.0.format"])
        assertEquals("1 3 30000 150 10000 20000", f["mediums.0.toc"])
        assertEquals("曲 \"A\"", f["mediums.0.track.0.name"])
        assertEquals("B & C", f["mediums.0.track.1.name"])
        assertEquals("133000", f["mediums.0.track.0.length"])
        // 曲のアーティストは、アルバムと違うときだけ
        assertFalse(f.containsKey("mediums.0.track.0.artist_credit.names.0.name"))
        assertEquals("ゲスト", f["mediums.0.track.1.artist_credit.names.0.name"])
        assertFalse(f.containsKey("mediums.0.track.2.artist_credit.names.0.name"))
        assertEquals("note", f["edit_note"])
        // 送信したあとは、アプリが受け取る移り先へ(登録したリリースにジャケット画像を追加するため)
        assertEquals(MusicBrainz.DONE_URL, f["redirect_uri"])
        // 1 枚組では何枚目かを入れない
        assertFalse(f.containsKey("mediums.0.position"))
    }

    @Test fun seedsMultiDiscWithoutToc() {
        val html = MusicBrainz.seedHtml(
            "Album", "Artist", "2020",
            listOf(
                MusicBrainz.SeedDisc(1, listOf(MusicBrainz.SeedTrack("A", "Artist", 1000), MusicBrainz.SeedTrack("B", "", 0))),
                MusicBrainz.SeedDisc(2, listOf(MusicBrainz.SeedTrack("C", "Other", 3000))),
            ),
            "note",
        )
        val f = fields(html)
        assertEquals("1", f["mediums.0.position"])
        assertEquals("2", f["mediums.1.position"])
        assertEquals("C", f["mediums.1.track.0.name"])
        assertEquals("Other", f["mediums.1.track.0.artist_credit.names.0.name"])
        // 目次が無いので、ディスクIDは付けない。長さが分からない曲は長さを入れない
        assertFalse(f.keys.any { it.endsWith(".toc") })
        assertFalse(f.containsKey("mediums.0.track.1.length"))
        assertEquals("1000", f["mediums.0.track.0.length"])
        assertEquals("2020", f["events.0.date.year"])
        assertFalse(f.containsKey("events.0.date.month"))
    }

    @Test fun seedsReleaseDetails() {
        val f = fields(
            MusicBrainz.seedHtml(
                "A", "B", "", listOf(MusicBrainz.SeedDisc(0, listOf(MusicBrainz.SeedTrack("t", "", 0)))), "n",
                MusicBrainz.ReleaseExtra(
                    type = "single", country = "jp", label = "レーベル", catalog = "ABCD-1234", barcode = "4 988000 000000",
                    language = "jpn", script = "Jpan", packaging = "Jewel Case", comment = "初回盤",
                ),
            ),
        )
        assertEquals("official", f["status"])
        assertEquals("single", f["type"])
        assertEquals("JP", f["events.0.country"])
        assertEquals("レーベル", f["labels.0.name"])
        assertEquals("ABCD-1234", f["labels.0.catalog_number"])
        assertEquals("4988000000000", f["barcode"])
        assertEquals("jpn", f["language"])
        assertEquals("Jpan", f["script"])
        assertEquals("Jewel Case", f["packaging"])
        assertEquals("初回盤", f["comment"])
        assertFalse(f.containsKey("annotation"))
    }

    @Test fun attachUrl() {
        assertEquals(
            "https://musicbrainz.org/cdtoc/attach?id=abc.DEF_123-&tracks=3&toc=1+3+30000+150+10000+20000",
            MusicBrainz.attachUrl(toc),
        )
    }
}
