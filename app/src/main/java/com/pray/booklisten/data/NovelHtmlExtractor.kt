package com.pray.booklisten.data

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Shared by the rendered WebView snapshot and downloaded chapter HTML. */
object NovelHtmlExtractor {
    private const val chrome = "script,style,noscript,iframe,form,nav,footer,header,aside," +
        "[hidden],[aria-hidden=true],#comments,#comment,#respond,.comments,.commentlist,.comment-list," +
        ".comment-respond,.comment-body,.comment-item,.comment-area,.comments-area,[id^=comment-]," +
        ".post-meta,.entry-meta,.article-meta,.article-info,.post-info,.chapter-header," +
        ".breadcrumbs,.breadcrumb,.post-share,.article-share,.share,.shares,.sharing,.social-share," +
        ".post-like,.article-like,.post-copyright,.post-nav,.article-nav,.related-posts,.related," +
        ".widget,.sidebar,.reading-ad-top,.reading-ad-bottom,ins.adsbygoogle"

    fun extract(document: Document): String {
        val page = document.clone()
        page.select(chrome).remove()
        // Named body containers outrank a broad #content wrapper, even if the wrapper is longer.
        val bodies = page.select("#neirong,.neirong,.bookreadercontent,.article-content,.entry-content," +
            ".post-content,[itemprop=articleBody],.chapter-content,.read-content,#chaptercontent")
            .filter { score(it) >= 80 }
        val candidates = bodies.ifEmpty {
            page.select("#content,article,main,[role=main],.content,div,section")
                .filter { el -> el.children().any { it.tagName() == "p" } && score(el) >= 80 }
        }
        val root = candidates.maxByOrNull(::score)
            ?: page.select("#content,article,main,[role=main],.content").maxByOrNull(::score)
            ?: error("未定位到小说正文区域，请确认当前是章节页")
        root.select("h1").remove()
        root.select("br").forEach { it.before("\n") }
        root.select("p,div,section").forEach { it.appendText("\n") }
        return root.wholeText().replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun score(element: Element): Int = element.text().length -
        element.select("a").sumOf { it.text().length } * 3
}
