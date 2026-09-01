package com.pray.booklisten.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBookParserTest {
    @Test fun `reads spine order and metadata`() {
        val parsed = EpubBookParser.parse(epub(), "fallback.epub")
        assertEquals("示例书", parsed.title)
        assertEquals("作者", parsed.author)
        assertEquals(listOf("第一章", "第二章"), parsed.chapters.map { it.title })
    }

    private fun epub(): ByteArray {
        val entries = linkedMapOf(
            "META-INF/container.xml" to """<?xml version="1.0"?><container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to """<?xml version="1.0"?><package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>示例书</dc:title><dc:creator>作者</dc:creator></metadata><manifest><item id="c1" href="c1.xhtml"/><item id="c2" href="c2.xhtml"/></manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""",
            "OEBPS/c1.xhtml" to "<html><body><h1>第一章</h1><p>第一章正文。</p></body></html>",
            "OEBPS/c2.xhtml" to "<html><body><h1>第二章</h1><p>第二章正文。</p></body></html>",
        )
        return ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }
}

