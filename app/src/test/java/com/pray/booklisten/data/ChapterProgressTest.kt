package com.pray.booklisten.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterProgressTest {
    private val blocks = listOf("12345", "1234567890", "12345")

    @Test fun calculatesWholeChapterFractionByTextLength() {
        assertEquals(0.5f, ChapterProgress.fraction(blocks, 1, 0.5f), 0.0001f)
    }

    @Test fun mapsChapterFractionBackToBlock() {
        val target = ChapterProgress.target(blocks, 0.5f)
        assertEquals(1, target.blockIndex)
        assertEquals(0.5f, target.fractionInBlock, 0.0001f)
    }
}
