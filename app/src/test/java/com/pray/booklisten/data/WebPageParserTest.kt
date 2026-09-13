package com.pray.booklisten.data

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class WebPageParserTest {
    private val story = "风穿过山谷，他沿着小路继续向前走。".repeat(12)

    @Test fun prefersArticleBodyOverWrapperWithLongCommentsAndSiteHeader() {
        val doc = Jsoup.parse("<div id='content'><div class='site-menu'>顶部分类</div>" +
            "<article><h1>引子</h1><div class='article-meta'>2007年 分类：小说</div>" +
            "<div class='article-content'><p>$story</p><p>① 注释应当保留。</p>" +
            "<div class='article-share'>分享到：</div></div></article>" +
            "<div class='comments-area'><h3>评论1989</h3>${"读者评论不是正文。".repeat(500)}</div>" +
            "<div class='related-posts'>近期文章</div></div>",
            "https://www.guichuideng.org/jing-jue-gu-cheng-00.html")
        val page = WebPageParser.parse(doc)
        assertEquals(listOf(story, "① 注释应当保留。"), page.content.lines().filter { it.isNotBlank() })
    }

    @Test fun keepsNarrativeWordsThatLookLikeSiteLabels() {
        val ending = "他评论道：下一章里的人为什么总说分享到？"
        val doc = Jsoup.parse("<article class='article-content'><p>$story</p><p>$ending</p></article>", "https://example.com/1")
        assertTrue(WebPageParser.parse(doc).content.endsWith(ending))
    }

    @Test(expected = IllegalStateException::class)
    fun doesNotTreatCommentOnlyPageAsChapter() {
        WebPageParser.parse(Jsoup.parse("<div class='comments-area'>$story</div>", "https://example.com/1"))
    }

    @Test fun extractsNeirongWithoutCommentsAndKeepsNavigation() {
        val doc = Jsoup.parse("<h1>引子</h1><div id='neirong'><p>$story</p><p>故事继续。</p></div>" +
            "<div id='comments'>${"无关评论".repeat(300)}</div>" +
            "<nav><a id='BookNext' href='2464.html'>第1章 白纸人</a></nav>",
            "https://www.51shucheng.net/book/2463.html")
        val page = WebPageParser.parse(doc)
        assertTrue(page.content.contains(story))
        assertFalse(page.content.contains("无关评论"))
        assertEquals("https://www.51shucheng.net/book/2464.html", page.nextUrl)
    }

    @Test fun keepsFooterNextAndSkipsInvalidCandidates() {
        val doc = Jsoup.parse("<div id='content'>$story</div><footer>" +
            "<a rel='next' href='https://other.test/ad'>广告</a>" +
            "<a href='#'>下一页</a><a href='87889.htm'>下 一 页</a></footer>",
            "https://www.99csw.com/book/2852/87888.htm")
        assertEquals("https://www.99csw.com/book/2852/87889.htm", WebPageParser.parse(doc).nextUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesChallengeInsteadOfImportingItAsStory() {
        WebPageParser.parse(Jsoup.parse("<title>Just a moment...</title><main>$story</main>", "https://example.com/1"))
    }
}
