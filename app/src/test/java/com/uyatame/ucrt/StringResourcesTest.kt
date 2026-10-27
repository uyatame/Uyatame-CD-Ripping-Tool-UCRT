package com.uyatame.ucrt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 文言のリソース(英語の values と日本語の values-ja)がそろっているかを確かめる。
 * - 英語にある文言は、翻訳しない指定のもの以外すべて日本語にもある(逆も同じ)
 * - 埋め込む値の番号(%1$s など)が、英語と日本語で同じ
 * - 値を埋め込む文言に、そのままの % が残っていない(%% と書く)
 */
class StringResourcesTest {
    private class Res(val text: String, val translatable: Boolean, val formatted: Boolean)

    private fun load(dir: String): Map<String, Res> {
        // テストはモジュール(app)のフォルダで動く。文言は app と legacy で共通の core にある
        val f = File("../core/src/main/res/$dir/strings.xml")
        assertTrue("${f.path} がありません", f.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val list = doc.getElementsByTagName("string")
        return (0 until list.length).associate { i ->
            val e = list.item(i) as Element
            e.getAttribute("name") to Res(
                e.textContent,
                e.getAttribute("translatable") != "false",
                e.getAttribute("formatted") != "false",
            )
        }
    }

    private val en = load("values")
    private val ja = load("values-ja")

    private fun args(s: String): List<String> = Regex("""%(\d+)\$[sd]""").findAll(s).map { it.groupValues[1] }.toSet().sorted()

    @Test
    fun everyStringIsTranslated() {
        val missingJa = en.filter { it.value.translatable && it.key !in ja }.keys
        val extraJa = ja.keys.filter { it !in en }
        assertEquals("日本語が無い文言", emptySet<String>(), missingJa)
        assertEquals("英語が無い文言", emptyList<String>(), extraJa)
    }

    @Test
    fun placeholdersMatch() {
        val bad = en.filter { (k, v) -> ja[k]?.let { args(it.text) != args(v.text) } ?: false }.keys
        assertEquals("埋め込む値が英語と日本語で違う文言", emptySet<String>(), bad)
    }

    @Test
    fun noStrayPercent() {
        val bad = (en.entries + ja.entries).filter { (_, v) ->
            v.formatted && Regex("""%%|%\d+\$[sd]""").replace(v.text, "").contains('%')
        }.map { it.key }
        assertEquals("% を %% と書いていない文言", emptyList<String>(), bad)
    }
}
