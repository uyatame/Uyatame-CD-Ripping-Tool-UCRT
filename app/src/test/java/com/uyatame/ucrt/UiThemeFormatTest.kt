package com.uyatame.ucrt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** テーマのファイルの読み書き: 範囲の外・知らない値・不正な入力で壊れないこと、書き出して読み直すと同じになること */
class UiThemeFormatTest {
    private fun file(body: String = "", id: String = "my-theme", name: String = "Mine") =
        """{"format":"ucrt-theme","id":"$id","name":"$name"$body}"""

    @Test fun readsValuesAndClampsRanges() {
        val t = UiThemeFormat.parse(
            file(
                ""","accent":"#FFC46B","seed":"#11223344","corner":99,"player":{"artCorner":-5,"titleWeight":450,"blurLevel":77,"artFrame":true},
                "app":{"coverCorner":500,"headlineWeight":2000000000}""",
            ),
        )
        assertEquals(0xFFFFC46B.toInt(), t.accent)
        assertEquals(0x11223344, t.seed)
        assertEquals(32, t.corner)
        assertEquals(0, t.artCorner)
        assertEquals(400, t.titleWeight)
        assertEquals(10, t.blurLevel)
        assertTrue(t.artFrame)
        assertEquals(40, t.coverCorner)
        assertEquals(900, t.headlineWeight)
    }

    @Test fun unknownChoicesFallBackToDefaults() {
        val d = UiTheme("x", "x")
        val t = UiThemeFormat.parse(file(""","player":{"tabs":"neon","controls":7,"seek":null},"app":{"nav":"x","card":"","progress":"BLOCKS"}"""))
        assertEquals(d.tabs, t.tabs)
        assertEquals(d.controls, t.controls)
        assertEquals(d.seek, t.seek)
        assertEquals(d.nav, t.nav)
        assertEquals(d.card, t.card)
        // 大文字・小文字は区別しない
        assertEquals("blocks", t.progress)
    }

    @Test fun idIsMadeSafeForFileNames() {
        assertEquals("etcpasswd", UiThemeFormat.parse(file(id = "../etc/passwd")).id)
        assertEquals("my-theme_2", UiThemeFormat.parse(file(id = "My-Theme_2")).id)
        assertEquals(40, UiThemeFormat.parse(file(id = "a".repeat(100))).id.length)
        assertEquals(40, UiThemeFormat.parse(file(name = "n".repeat(100))).name.length)
    }

    @Test fun rejectsFilesThatAreNotThemes() {
        for (bad in listOf(
            "", "not json", "[]", "{}", """{"format":"other","id":"a","name":"b"}""",
            """{"format":"ucrt-theme","id":"","name":"b"}""", """{"format":"ucrt-theme","id":"!!!","name":"b"}""",
            """{"format":"ucrt-theme","id":"a","name":"  "}""",
        )) {
            try {
                UiThemeFormat.parse(bad)
                fail("読めてしまった: $bad")
            } catch (_: Exception) {
            }
        }
    }

    @Test fun colorsAreOnlyTheTwoDocumentedForms() {
        assertEquals(0xFF112233.toInt(), UiThemeFormat.parseColor("#112233"))
        assertEquals(0x80112233.toInt(), UiThemeFormat.parseColor(" #80112233 "))
        assertEquals(0xFFABCDEF.toInt(), UiThemeFormat.parseColor("#abcdef"))
        for (none in listOf("", "0", "default", "DEFAULT", "red", "#123", "#1234567", "#GGGGGG", "112233", "#+12233", "#-12233")) {
            assertEquals(none, 0, UiThemeFormat.parseColor(none))
        }
    }

    @Test fun roundTripKeepsEverything() {
        val t = UiTheme(
            "round-trip", "Round trip", author = "me", description = "d", accent = 0xFF00FF00.toInt(), seed = 0xFF0000FF.toInt(),
            corner = 12, tabs = "segmented", artCorner = 3, artFrame = true, artGlow = true, info = "panel", controls = "square",
            seek = "ticks", titleWeight = 900, blurLevel = 4, nav = "line", card = "panel", sectionTitle = "caps", listRow = "divided",
            coverCorner = 2, bars = "line", headlineWeight = 700, background = "tint", header = "caps", button = "square",
            tile = "square", progress = "blocks",
        )
        assertEquals(t, UiThemeFormat.parse(UiThemeFormat.toJson(t).toString(2)))
        // 何も指定しないテーマ(標準)も、書き出して読み直すと同じ
        val c = UiTheme("classic", "Classic")
        assertEquals(c, UiThemeFormat.parse(UiThemeFormat.toJson(c).toString()))
    }

    @Test fun templateOfABuiltinThemeImportsAsAnotherTheme() {
        val builtin = UiTheme("sumi", "Sumi", builtin = true, accent = 0xFFF3D58C.toInt(), corner = 16)
        val text = UiThemeFormat.template(builtin, "澄")
        val t = UiThemeFormat.parse(text)
        assertEquals("my-sumi", t.id)
        assertEquals("澄 (custom)", t.name)
        assertFalse(t.builtin)
        assertEquals(builtin.accent, t.accent)
        assertEquals(16, t.corner)
        // 説明(_help)が付いていて、読み込むときは読み飛ばされる
        assertTrue(text.contains("_help"))
    }

    @Test fun validIdsAreSafeFileNames() {
        for (ok in listOf("a", "classic", "my-theme_2", "0")) assertTrue(ok, UiThemeFormat.validId(ok))
        for (bad in listOf("", "..", "../x", "a/b", "a.b", "A", "a b", "a\u0000b", "x".repeat(49))) assertFalse(bad, UiThemeFormat.validId(bad))
    }

    @Test fun glassFlagReadsWritesAndDefaultsOff() {
        assertFalse(UiThemeFormat.parse(file()).glass)
        assertFalse(UiThemeFormat.parse(file(""","app":{"glass":"yes"}""")).glass)
        val on = UiThemeFormat.parse(file(""","app":{"glass":true}"""))
        assertTrue(on.glass)
        // 書き出して読み直しても同じ
        assertTrue(UiThemeFormat.parse(UiThemeFormat.toJson(on).toString()).glass)
        assertTrue(UiThemeFormat.template(on, "x").contains("\"glass\""))
    }
}
