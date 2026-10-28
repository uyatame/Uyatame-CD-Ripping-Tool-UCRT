package com.uyatame.ucrt.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistFilesTest {
    private fun t(uri: String, title: String, artist: String = "A") =
        LibTrack(uri, title, artist, "Al", "", 1, 1, 1000, "", null, 0)

    private val lib = listOf(
        t("content://x/tree/primary%3AMusic/document/primary%3AMusic%2FAlbum1%2F01%20Song.flac", "Song One"),
        t("content://x/tree/primary%3AMusic/document/primary%3AMusic%2FAlbum2%2F01%20Song.flac", "Other"),
        t("file:///storage/emulated/0/Music/B/02 Two.mp3", "Two", "Bob"),
    )

    @Test fun m3uWithWindowsPathsAndFolders() {
        val p = PlaylistFiles.parse(
            "#EXTM3U\n#PLAYLIST:Mix\n#EXTINF:100,A - Song One\nC:\\Music\\Album2\\01 Song.flac\n../B/02%20Two.mp3\n#EXTINF:5,Bob - Two\nmissing.flac\n",
            "m3u8",
        )
        assertEquals("Mix", p.name)
        val (uris, miss) = PlaylistFiles.match(p.entries, lib)
        // 同じ名前は、フォルダ名で選ぶ。ファイルが無くても曲名とアーティストで見つける
        assertEquals(listOf(lib[1].uri, lib[2].uri, lib[2].uri), uris)
        assertEquals(0, miss)
    }

    @Test fun plsXspfWpl() {
        val pls = PlaylistFiles.parse("[playlist]\nFile1=/sdcard/Music/B/02 Two.mp3\nTitle1=Bob - Two\nFile2=nope.wav\nNumberOfEntries=2\n", "pls")
        assertEquals(listOf(lib[2].uri) to 1, PlaylistFiles.match(pls.entries, lib))
        val x = PlaylistFiles.parse(
            "<?xml version=\"1.0\"?><playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\"><title>X &amp; Y</title><trackList>" +
                "<track><location>file:///home/u/Music/Album1/01%20Song.flac</location><title>Song One</title></track></trackList></playlist>",
            "xspf",
        )
        assertEquals("X & Y", x.name)
        assertEquals(listOf(lib[0].uri) to 0, PlaylistFiles.match(x.entries, lib))
        val w = PlaylistFiles.parse("<?wpl version=\"1.0\"?><smil><head><title>W</title></head><body><seq><media src=\"..\\B\\02 Two.mp3\"/></seq></body></smil>", "wpl")
        assertEquals(listOf(lib[2].uri) to 0, PlaylistFiles.match(w.entries, lib))
    }
}
