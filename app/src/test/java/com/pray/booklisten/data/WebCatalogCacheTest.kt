package com.pray.booklisten.data

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class WebCatalogCacheTest {
    @Test fun catalogKeepsVolumeOrderAndExcludesOtherBooksAndDuplicateLinks() {
        val doc = Jsoup.parse("<a href='78347.html'>在线阅读</a>" +
            "<dl><dd><a href='78347.html'>第一章 铁砂掌</a></dd></dl>" +
            "<dl><dd><a href='12.html'>第二章 下一站</a></dd></dl>" +
            "<a href='78347.html'>第一章 铁砂掌</a>" +
            "<a href='/book/999/2.html'>第三章 无关书籍</a>",
            "https://www.hetushu.com/book/106/index.html")
        val chapters = WebCatalogParser.parse(doc)
        assertEquals(listOf("第一章 铁砂掌", "第二章 下一站"), chapters.map { it.title })
        assertEquals(listOf("https://www.hetushu.com/book/106/78347.html", "https://www.hetushu.com/book/106/12.html"), chapters.map { it.url })
    }

    @Test fun fixedWindowMovesWithReadingAndStopsAtEnd() {
        assertEquals((0..10).toList(), ChapterCacheWindow.indices(0, 100))
        assertEquals((20..30).toList(), ChapterCacheWindow.indices(20, 100))
        assertEquals(listOf(98, 99), ChapterCacheWindow.indices(98, 100))
        assertTrue(ChapterCacheWindow.indices(0, 0).isEmpty())
        assertEquals((4..14).toList(), ChapterCacheWindow.indices(4, 100))
    }
}
