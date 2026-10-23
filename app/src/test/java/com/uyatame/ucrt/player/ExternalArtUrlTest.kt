package com.uyatame.ucrt.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** ほかのアプリのジャケットを高画質で読むための URL の書き換えを確かめる */
class ExternalArtUrlTest {
    private fun c(u: String) = ArtUrls.candidates(u)

    @Test
    fun spotify() {
        assertEquals(
            listOf(
                "https://i.scdn.co/image/ab67616d000082c1aaaa",
                "https://i.scdn.co/image/ab67616d0000b273aaaa",
                "https://i.scdn.co/image/ab67616d00001e02aaaa",
            ),
            c("https://i.scdn.co/image/ab67616d00001e02aaaa"),
        )
        // spotify:image:<id> の形でも、画像サーバーの URL にする
        assertEquals("https://i.scdn.co/image/ab67616d000082c1bbbb", c("spotify:image:ab67616d00004851bbbb").first())
    }

    @Test
    fun googleImages() {
        assertEquals("https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj", c("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj").first())
        assertEquals("https://yt3.ggpht.com/xyz=s1200", c("https://yt3.ggpht.com/xyz=s88").first())
    }

    @Test
    fun appleAndAmazon() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/a/b/1200x1200bb.jpg",
            c("https://is1-ssl.mzstatic.com/image/thumb/a/b/100x100bb.jpg").first(),
        )
        assertEquals("https://m.media-amazon.com/images/I/41abc._SL1200_.jpg", c("https://m.media-amazon.com/images/I/41abc._SX300_.jpg").first())
    }

    @Test
    fun otherUrlsAreKept() {
        assertEquals(listOf("content://media/external/audio/albumart/12"), c("content://media/external/audio/albumart/12"))
    }
}
