package com.pray.booklisten.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {
    @Test fun `keeps all text while respecting maximum length`() {
        val text = (1..200).joinToString("") { "第${it}句。这是一段测试文字！" }
        val chunks = TextChunker.chunk(text, 100)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 100 })
        assertEquals(text, chunks.joinToString("").replace("\n", ""))
    }

    @Test fun `empty input creates no blocks`() {
        assertTrue(TextChunker.chunk(" \n ").isEmpty())
    }
}
