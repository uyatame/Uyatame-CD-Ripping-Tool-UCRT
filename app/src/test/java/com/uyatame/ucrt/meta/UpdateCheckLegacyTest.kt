package com.uyatame.ucrt.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** UCRT Legacy の更新の確認: 本家と一緒のリリース(v0.9.0〜)と、以前の Legacy だけのリリース(legacy-v)のどちらも見る */
class UpdateCheckLegacyTest {
    private fun rel(tag: String, vararg assets: String, draft: Boolean = false, pre: Boolean = false) =
        """{"tag_name":"$tag","name":"$tag","body":"notes $tag","html_url":"https://x/$tag","draft":$draft,"prerelease":$pre,
            "assets":[${assets.joinToString(",") { """{"name":"$it"}""" }}]}"""

    @Test fun picksTheCombinedReleaseWhenItIsNewer() {
        val json = "[" + listOf(
            rel("v0.9.0", "UCRT_v0.9.0.apk", "UCRT_Legacy_v0.9.0.apk"),
            rel("legacy-v0.9.0", "UCRT_Legacy_v0.9.0.apk"),
            rel("v0.8.0", "UCRT_v0.8.0.apk"),
            rel("legacy-v0.3.0", "UCRT_Legacy_v0.3.0.apk"),
        ).joinToString(",") + "]"
        val r = UpdateCheck.pickLegacy(json)!!
        assertEquals("0.9.0", r.version)
    }

    @Test fun ignoresAppOnlyReleasesDraftsAndOtherTags() {
        val json = "[" + listOf(
            rel("v0.8.0", "UCRT_v0.8.0.apk"),
            rel("ffmpeg-8.1.3-2", "ucrt-ffmpeg.zip"),
            rel("v1.0.0", "UCRT_v1.0.0.apk", "UCRT_Legacy_v1.0.0.apk", draft = true),
            rel("legacy-v0.3.0", "UCRT_Legacy_v0.3.0.apk"),
        ).joinToString(",") + "]"
        assertEquals("0.3.0", UpdateCheck.pickLegacy(json)!!.version)
        assertNull(UpdateCheck.pickLegacy("[" + rel("v0.8.0", "UCRT_v0.8.0.apk") + "]"))
    }

    @Test fun olderLegacyOnlyPrereleaseStillCounts() {
        val json = "[" + rel("legacy-v0.1.1-alpha", "UCRT_Legacy_v0.1.1-alpha.apk", pre = true) + "]"
        assertEquals("0.1.1-alpha", UpdateCheck.pickLegacy(json)!!.version)
    }
}
