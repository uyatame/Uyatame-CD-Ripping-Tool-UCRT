package com.uyatame.ucrt.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** 音質別の細かい分類(2 段目の絞り込み)と、その並び順 */
class QualityTest {
    private fun t(name: String, rate: Int = 0, bits: Int = 0) =
        LibTrack("file:///music/$name", name, "", "", "", 1, 1, 0, "", null, 0, rate = rate, bits = bits)

    private fun album(vararg tr: LibTrack) = LibAlbum("k", "a", "b", tr.toList())

    @Test fun details() {
        assertEquals("DSD256", Quality.detail(t("a.dsf", 11289600, 1)))
        assertEquals("DSD64", Quality.detail(t("a.dff", 2822400, 1)))
        assertEquals("96kHz/24bit", Quality.detail(t("a.flac", 96000, 24)))
        assertEquals("44.1kHz/24bit", Quality.detail(t("a.wav", 44100, 24)))
        assertEquals("FLAC", Quality.detail(t("a.flac", 44100, 16)))
        assertEquals("AAC", Quality.detail(t("a.m4a", 44100)))
        assertEquals("MP3", Quality.detail(t("a.mp3", 44100)))
    }

    @Test fun albumUsesBestTrackAndOrder() {
        val mixed = album(t("1.flac", 48000, 24), t("2.flac", 96000, 24))
        assertEquals("96kHz/24bit", Quality.detail(mixed))
        val order = listOf(
            album(t("a.flac", 96000, 24)), album(t("a.dsf", 2822400, 1)), album(t("a.flac", 192000, 24)),
            album(t("a.flac", 96000, 32)), album(t("a.dsf", 11289600, 1)),
        ).sortedBy { Quality.detailOrder(it) }.map { Quality.detail(it) }
        assertEquals(listOf("DSD256", "DSD64", "192kHz/24bit", "96kHz/32bit", "96kHz/24bit"), order)
    }
}
