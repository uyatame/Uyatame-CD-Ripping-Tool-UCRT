package com.uyatame.ucrt

import org.junit.Assert.assertEquals
import org.junit.Test

/** 下のメニューの設定: 標準・切り替え・すべて隠す・知らない名前 */
class NavTabsTest {
    @Test fun defaultHidesAgingOnly() {
        assertEquals(setOf("library", "disc", "streaming", "upsample"), NavTabs.parse(""))
    }

    @Test fun togglesKeepTheFixedOrder() {
        var s = NavTabs.toggle("", NavTabs.AGING, true)
        assertEquals("library,disc,streaming,upsample,aging", s)
        s = NavTabs.toggle(s, NavTabs.DISC, false)
        assertEquals("library,streaming,upsample,aging", s)
        assertEquals(setOf("library", "streaming", "upsample", "aging"), NavTabs.parse(s))
    }

    @Test fun hidingEverythingIsNotTheDefault() {
        var s = ""
        for (id in NavTabs.ALL) s = NavTabs.toggle(s, id, false)
        assertEquals("none", s)
        assertEquals(emptySet<String>(), NavTabs.parse(s))
    }

    @Test fun ignoresUnknownNames() {
        assertEquals(setOf("disc"), NavTabs.parse("disc, foo ,"))
    }
}
