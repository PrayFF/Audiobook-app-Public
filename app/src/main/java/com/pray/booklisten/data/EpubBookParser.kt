package com.pray.booklisten.data

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

object EpubBookParser {
    fun parse(bytes: ByteArray, fallbackTitle: String): ParsedBook {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) files[entry.name.replace('\\', '/')] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        val container = files["META-INF/container.xml"] ?: error("不是有效的 EPUB：缺少 container.xml")
        val containerDoc = Jsoup.parse(String(container, Charsets.UTF_8), "", Parser.xmlParser())
        val opfPath = containerDoc.getElementsByTag("rootfile").firstOrNull()?.attr("full-path")
            ?.takeIf(String::isNotBlank) ?: error("EPUB 没有内容清单")
        val opfBytes = files[opfPath] ?: error("EPUB 内容清单不存在")
        val opf = Jsoup.parse(String(opfBytes, Charsets.UTF_8), "", Parser.xmlParser())
        val title = opf.getElementsByTag("dc:title").firstOrNull()?.text().orEmpty()
            .ifBlank { fallbackTitle.substringBeforeLast('.') }
        val author = opf.getElementsByTag("dc:creator").firstOrNull()?.text().orEmpty()
        val opfDir = opfPath.substringBeforeLast('/', "")
        val manifest = opf.getElementsByTag("item").associate { it.attr("id") to it.attr("href") }
        val chapters = opf.getElementsByTag("itemref").mapNotNull { itemRef ->
            val href = manifest[itemRef.attr("idref")] ?: return@mapNotNull null
            val path = resolvePath(opfDir, href.substringBefore('#'))
            val body = files[path] ?: return@mapNotNull null
            val doc = Jsoup.parse(String(body, Charsets.UTF_8))
            doc.select("script,style,noscript,nav").remove()
            val content = doc.body().text().trim()
            if (content.isBlank()) return@mapNotNull null
            val chapterTitle = doc.selectFirst("h1,h2,h3,title")?.text()?.trim()
                ?.takeIf(String::isNotBlank) ?: "第 ${itemRef.elementSiblingIndex() + 1} 章"
            ParsedChapter(chapterTitle, content)
        }
        require(chapters.isNotEmpty()) { "EPUB 中没有可朗读的章节（加密 EPUB 暂不支持）" }
        return ParsedBook(title, author, chapters)
    }

    private fun resolvePath(base: String, relative: String): String {
        val parts = (if (base.isBlank()) relative else "$base/$relative").split('/')
        val normalized = ArrayDeque<String>()
        for (part in parts) when (part) {
            "", "." -> Unit
            ".." -> if (normalized.isNotEmpty()) normalized.removeLast()
            else -> normalized.addLast(part)
        }
        return normalized.joinToString("/")
    }
}
