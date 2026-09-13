package com.pray.booklisten.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import java.net.URI

data class CatalogChapter(
    val title: String,
    val url: String,
)

/**
 * Finds the "目录 / 章节目录 / 章节列表" link on a chapter page and fetches it, then parses the
 * full list of chapter titles and their URLs.  No chapter body is downloaded here — the caller
 * only stores placeholders and fetches a chapter on demand.
 */
object WebCatalogParser {
    private val chapterHeading = Regex(
        "^(?:正文\\s*)?(?:第[0-9０-９零〇一二三四五六七八九十百千万两]+[章回节卷部篇]|chapter\\s*\\d+|序章|楔子|引子|前言|后记|番外)",
        RegexOption.IGNORE_CASE,
    )
    private val skipLabels = setOf(
        "首页", "上一页", "下一页", "上一章", "下一章", "返回顶部", "返回目录", "目录",
        "加入收藏", "手机版", "电脑版", "登录", "注册", "排行榜", "最新章节", "全部小说",
        "推荐", "收藏", "书架", "小说分类", "作品分类",
    )

    /** Fetches a catalog page and returns its chapter list in document order. */
    suspend fun fetchCatalog(
        url: String,
        userAgent: String = DEFAULT_USER_AGENT,
        cookie: String? = null,
    ): List<CatalogChapter> = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "仅支持 HTTPS 网页" }
        val connection = Jsoup.connect(url)
            .userAgent(userAgent)
            .timeout(15_000)
            .maxBodySize(4 * 1024 * 1024)
        cookie?.takeIf(String::isNotBlank)?.let { connection.header("Cookie", it) }
        val document = connection.get()
        parse(document)
    }

    fun parse(document: Document): List<CatalogChapter> {
        val url = document.location()
        val document = document.clone()
        document.select("script,style,noscript,iframe,form,nav,footer,header,aside").remove()

        val base = runCatching { URI(url) }.getOrNull()
        val host = base?.host.orEmpty()
        val seen = LinkedHashMap<String, CatalogChapter>()
        val containers = document.select("a[href]")
        // Prefer links inside a listing container; fall back to all links on the page.
        val bookDirectory = base?.path?.substringBeforeLast('/')?.takeIf { it.matches(Regex("/book/[0-9]+")) }
        val linkElements = if (bookDirectory != null) containers.toList() else pickCatalogLinks(containers)
        for (link in linkElements) {
            val title = link.text().trim()
            if (!isChapterTitle(title)) continue
            val absolute = link.absUrl("href").trim()
            if (absolute.isEmpty()) continue
            val sameHost = runCatching {
                val target = URI(absolute)
                target.scheme == "https" && target.host.equals(host, ignoreCase = true) &&
                    (bookDirectory == null || target.path.substringBeforeLast('/') == bookDirectory)
            }.getOrDefault(false)
            if (!sameHost) continue
            if (seen.containsKey(absolute)) continue
            seen[absolute] = CatalogChapter(title, absolute)
        }
        require(seen.isNotEmpty()) { "没有在目录页找到章节，请在目录页再试一次" }
        return seen.values.toList()
    }

    /** Chooses the anchor container(s) most likely to hold the chapter list. */
    private fun pickCatalogLinks(links: org.jsoup.select.Elements): List<Element> {
        val explicit = links.firstOrNull()?.ownerDocument()?.select("#dir,#list,#catalog,.catalog,.book-list,.chapter-list,dl")
            ?.maxByOrNull { el -> el.select("a[href]").count { isChapterTitle(it.text().trim()) } }
        if (explicit != null && explicit.select("a[href]").count { isChapterTitle(it.text().trim()) } >= 2) {
            return explicit.select("a[href]").toList()
        }
        val scored = links.groupBy { it.parent()?.parent() ?: it.parent() ?: it }
            .maxByOrNull { (_, group) ->
                group.count { isChapterTitle(it.text().trim()) }
            }
        return scored?.value?.toList() ?: links.toList()
    }

    private fun isChapterTitle(title: String): Boolean {
        if (title.isEmpty() || title.length > 120) return false
        if (skipLabels.contains(title)) return false
        return chapterHeading.containsMatchIn(title)
    }

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}
