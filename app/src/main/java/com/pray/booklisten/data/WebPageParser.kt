package com.pray.booklisten.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import java.net.URI

data class ExtractedWebPage(
    val title: String,
    val content: String,
    val url: String,
    val nextUrl: String?,
    val bookTitle: String = title,
    val catalogUrl: String? = null,
)

object WebPageParser {
    suspend fun fetch(
        url: String,
        userAgent: String = DEFAULT_USER_AGENT,
        cookie: String? = null,
    ): ExtractedWebPage = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "仅支持 HTTPS 网页" }
        val connection = Jsoup.connect(url)
            .userAgent(userAgent)
            .timeout(15_000)
            .maxBodySize(4 * 1024 * 1024)
            .ignoreHttpErrors(true)
        cookie?.takeIf(String::isNotBlank)?.let { connection.header("Cookie", it) }
        val response = connection.execute()
        val page = parse(response.parse())
        require(response.statusCode() in 200..299) { "网页服务器返回 HTTP ${response.statusCode()}，请在浏览器检查该页" }
        page
    }

    fun parse(document: Document): ExtractedWebPage {
        val url = document.location()
        require(!Regex("just a moment|attention required|verify you are human", RegexOption.IGNORE_CASE)
            .containsMatchIn(document.title()) && document.selectFirst("#challenge-form,#cf-challenge-running") == null) {
            "该网页需要人机验证，请在内置浏览器手动完成验证后提取正文"
        }
        // Navigation and metadata must be read BEFORE removing page chrome.
        val next = findNext(document.select("a[href],link[rel=next]").toList(), url)
        val catalog = document.select("a[href]").firstOrNull {
            it.text().trim() in setOf("目录", "返回目录", "章节目录", "返回列表")
        }?.absUrl("href") ?: if (URI(url).host == "www.hetushu.com" && URI(url).path.matches(Regex("/book/[0-9]+/[0-9]+\\.html"))) {
            URI(url).resolve("index.html").toString()
        } else null
        val text = NovelHtmlExtractor.extract(document)
        require(text.length >= 80) { "未找到足够正文，请在内置浏览器等待章节加载完毕后提取" }
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
        return ExtractedWebPage(title.ifBlank { "网页章节" }, text, url, next, bookTitle, catalog)
    }

    private fun readableScore(element: Element): Int {
        val textLength = element.text().length
        val linkLength = element.select("a").sumOf { it.text().length }
        return textLength - linkLength * 2
    }

    private fun findNext(links: List<Element>, baseUrl: String): String? {
        return links.sortedByDescending { it.attr("rel").equals("next", ignoreCase = true) }
            .firstNotNullOfOrNull { candidate ->
            val label = candidate.text().replace(Regex("\\s+"), "")
            if (!candidate.attr("rel").equals("next", true) && candidate.id() != "BookNext" &&
                !Regex("^(?:[→›»>]+)?(?:下一章|下一页|下一篇|下页|下一节|next|›|»)", RegexOption.IGNORE_CASE).containsMatchIn(label)) return@firstNotNullOfOrNull null
            val absolute = candidate.absUrl("href")
            runCatching {
            val base = URI(baseUrl)
            val next = URI(absolute)
            absolute.takeIf { next.scheme == "https" && base.host.equals(next.host, ignoreCase = true) && next.path != base.path }
            }.getOrNull()
        }
    }

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}
