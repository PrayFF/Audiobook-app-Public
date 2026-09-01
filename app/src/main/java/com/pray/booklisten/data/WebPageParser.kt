package com.pray.booklisten.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

data class ExtractedWebPage(
    val title: String,
    val content: String,
    val url: String,
    val nextUrl: String?,
    val bookTitle: String = title,
)

object WebPageParser {
    suspend fun fetch(url: String): ExtractedWebPage = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "仅支持 HTTPS 网页" }
        val document = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Android) BookListen/1.0")
            .timeout(15_000)
            .maxBodySize(4 * 1024 * 1024)
            .get()
        document.select("script,style,noscript,iframe,form,nav,footer,header,aside").remove()
        val candidates = document.select("article,main,[role=main],.content,.chapter-content,.read-content,#content,#chaptercontent")
        val root = candidates.maxByOrNull { readableScore(it) } ?: document.body()
        val text = root.text().replace(Regex("\\s+"), " ").trim()
        require(text.length >= 80) { "没有识别到足够的正文，可能需要登录或手动在浏览器中选择页面" }
        val documentTitle = document.title().trim()
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { documentTitle }
        val bookTitle = WebBookTitleResolver.resolve(
            chapterTitle = title,
            documentTitle = documentTitle,
            candidates = buildList {
                listOf(
                    "meta[property=\"og:novel:book_name\"]",
                    "meta[name=\"book_name\"]",
                    "meta[property=\"book:name\"]",
                ).forEach { selector ->
                    document.selectFirst(selector)?.attr("content")?.let(::add)
                }
                document.select("script[type=application/ld+json]").forEach { script ->
                    WebBookTitleResolver.namesFromJsonLd(script.data()).forEach(::add)
                }
                document.select("#info h1,.book-info h1,.bookname,[itemprop=name]")
                    .mapTo(this) { it.text() }
                document.select(".breadcrumb a,.breadcrumbs a,.crumb a,.path a")
                    .asReversed()
                    .mapTo(this) { it.text() }
            },
        )
        val next = findNext(document.select("a[href]").toList(), url)
        ExtractedWebPage(title.ifBlank { "网页章节" }, text, url, next, bookTitle)
    }

    private fun readableScore(element: Element): Int {
        val textLength = element.text().length
        val linkLength = element.select("a").sumOf { it.text().length }
        return textLength - linkLength * 2
    }

    private fun findNext(links: List<Element>, baseUrl: String): String? {
        val candidate = links.firstOrNull { it.attr("rel").contains("next", ignoreCase = true) }
            ?: links.firstOrNull {
                it.text().trim().matches(Regex("^(下一章|下一页|下页|下一节|next|›|»).*$", RegexOption.IGNORE_CASE))
            }
        val absolute = candidate?.absUrl("href")?.takeIf(String::isNotBlank) ?: return null
        return runCatching {
            val base = URI(baseUrl)
            val next = URI(absolute)
            absolute.takeIf { base.host.equals(next.host, ignoreCase = true) }
        }.getOrNull()
    }
}

