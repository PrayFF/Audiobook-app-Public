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

    @Test fun removesPromotionalHeaderSeparatorAndChapterNavigation() {
        val result = NovelTextCleaner.clean(
            """
            本站全部小说网盘合集:深入探索书籍图书与文学宗教与信仰科幻与奇幻惊悚片、犯罪片与悬疑片上一篇一《鬼吹灯》下一篇。
            ==================
            第一章 白纸人
            夜色压在屋檐上，院里没有一点声音。
            上一章:返回列表
            下一章:第一章白纸人和鼠友:
            """.trimIndent(),
        )
        assertTrue(result.startsWith("第一章 白纸人"))
        assertTrue(result.contains("夜色压在屋檐上"))
        assertFalse(result.contains("网盘合集"))
        assertFalse(result.contains("上一章:返回列表"))
        assertFalse(result.contains("下一章:第一章"))
    }

    @Test fun removesObfuscatedReadingModeWarningAndDirectoryFooter() {
        val result = NovelTextCleaner.clean(
            """
            第二章 进山
            山路在雨后泛着微光。
            如果被/浏/览/器/强/制进入它们的阅/读/模/式了,阅读体/验极/差请退出转/码阅读
            返回列表返回目录
            """.trimIndent(),
        )
        assertEquals("第二章 进山\n\n山路在雨后泛着微光。", result)
    }
}
