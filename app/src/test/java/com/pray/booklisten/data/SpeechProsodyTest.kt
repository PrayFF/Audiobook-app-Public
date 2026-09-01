package com.pray.booklisten.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechProsodyTest {
    @Test fun addsPauseAtChapterHeadingAndParagraphEnd() {
        val result = SpeechProsody.prepare("第一章 夜雨\n他推开了门\n“你来了？”")
        assertEquals("第一章 夜雨。\n他推开了门。\n“你来了？”", result)
    }

    @Test fun normalizesEllipsisAndDash() {
        val result = SpeechProsody.prepare("等等......不要走--他说。")
        assertTrue(result.contains("……"))
        assertTrue(result.contains("——"))
    }
}
