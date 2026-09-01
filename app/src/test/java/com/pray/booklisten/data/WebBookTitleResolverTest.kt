package com.pray.booklisten.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WebBookTitleResolverTest {
    @Test
    fun prefersExplicitNovelMetadataOverChapterAndSiteNames() {
        val result = WebBookTitleResolver.resolve(
            chapterTitle = "第一百二十章 再见故人",
            documentTitle = "第一百二十章 再见故人_凡人修仙传_天涯书库",
            candidates = listOf("天涯书库", "华人文学", "校园小说", "第一百二十章 再见故人", "凡人修仙传"),
            url = "https://example.com/read/120.html",
        )

        assertEquals("凡人修仙传", result)
    }

    @Test
    fun derivesBookNameFromDocumentTitleWhenMetadataIsMissing() {
        val result = WebBookTitleResolver.resolve(
            chapterTitle = "第12章 风雪",
            documentTitle = "第12章 风雪_庆余年最新章节_某某小说网",
            candidates = emptyList(),
        )

        assertEquals("庆余年", result)
    }
}
