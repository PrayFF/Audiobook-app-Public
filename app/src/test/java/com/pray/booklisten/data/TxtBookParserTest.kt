package com.pray.booklisten.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TxtBookParserTest {
    @Test fun `splits Chinese chapter headings`() {
        val text = "书籍简介\n第1章 开始\n第一段。\n第二章 继续\n第二段。"
        val parsed = TxtBookParser.parse(text.toByteArray(), "测试.txt")
        assertEquals("测试", parsed.title)
        assertEquals(3, parsed.chapters.size)
        assertEquals("第1章 开始", parsed.chapters[1].title)
    }

    @Test fun `falls back to GB18030`() {
        val source = "第一章\n你好，世界。"
        val decoded = TxtBookParser.decode(source.toByteArray(Charset.forName("GB18030")))
        assertEquals(source, decoded)
    }

    @Test fun `plain text becomes one chapter`() {
        val parsed = TxtBookParser.parse("没有章节标题的正文".toByteArray(), "无标题.txt")
        assertTrue(parsed.chapters.single().content.contains("正文"))
    }
}

