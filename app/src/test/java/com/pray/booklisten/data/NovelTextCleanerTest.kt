package com.pray.booklisten.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelTextCleanerTest {
    @Test fun trimsNavigationOnlyAtEdges() {
        val result = NovelTextCleaner.clean(
            """
            天涯书库 | 首页 | 华人文学 | 校园小说
            第十二章 重逢
            下一页并不是他此刻想考虑的事情。
            他推开门，雨声忽然变得很近。
            下一页 | 天涯书库 | 关于我们 | 联系我们 | 版权声明 | 广告服务 | 帮助中心 | 申请链接 | 加入收藏 | 返回顶部
            """.trimIndent()
        )
        assertTrue(result.startsWith("第十二章 重逢"))
        assertTrue(result.contains("下一页并不是他此刻想考虑的事情。"))
        assertTrue(result.endsWith("雨声忽然变得很近。"))
        assertFalse(result.contains("关于我们"))
    }

    @Test fun leavesOrdinaryNarrativeUntouched() {
        val input = "第一段是正文。\n第二段提到联系我们并不是网站链接。\n故事结束。"
        assertEquals(input.replace("\n", "\n\n"), NovelTextCleaner.clean(input))
    }

    @Test fun trimsChromeWhenTheWholePageIsCollapsedToOneLine() {
        val result = NovelTextCleaner.clean(
            "天涯书库 | 首页 | 华人文学 | 校园小说 第十二章 重逢 他推开门，雨声忽然变得很近。下一页 | 天涯书库 | 关于我们 | 联系我们 | 版权声明 | 广告服务 | 帮助中心 | 申请链接 | 加入收藏 | 返回顶部"
        )
        assertTrue(result.startsWith("第十二章"))
        assertTrue(result.contains("他推开门"))
        assertFalse(result.contains("华人文学"))
        assertFalse(result.contains("关于我们"))
    }

    @Test fun doesNotCutNarrativeThatMerelyMentionsNextPage() {
        val input = "第一章 夜雨\n他想，下一页也许会写到联系我们的旧事。但故事仍在继续。"
        assertTrue(NovelTextCleaner.clean(input).contains("故事仍在继续"))
    }
}
