package com.pray.booklisten.data

import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelHtmlExtractorTest {
    @Test
    fun extracts69ShubaTxtnavAndRemovesReaderChrome() {
        val story = "二愣子睁大双眼，望着茅草和泥糊成的屋顶。".repeat(12)
        val document = Jsoup.parse(
            """
            <div class="txtnav">
              <div class="txtinfo hide720">2023-05-01 作者：忘语</div>
              <div id="txtright">广告</div>
              <div class="err_tips">章节报错</div>
              <p>$story</p>
              <div class="page1"><a>上一章</a><a>目录</a><a>下一章</a></div>
            </div>
            """.trimIndent(),
            "https://www.69shuba.com/txt/1296/3985597",
        )

        val extracted = NovelHtmlExtractor.extract(document)
        assertTrue(extracted.contains(story))
        assertFalse(extracted.contains("作者：忘语"))
        assertFalse(extracted.contains("章节报错"))
        assertFalse(extracted.contains("下一章"))
    }
}
