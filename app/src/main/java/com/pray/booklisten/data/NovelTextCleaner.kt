package com.pray.booklisten.data

/** Removes site chrome only from the beginning and end of extracted novel text. */
object NovelTextCleaner {
    private val chapterTitle = Regex("^(正文\\s*)?(第.{1,12}[章回节卷部篇]|序章|楔子|引子|前言|后记)(\\s|[:：].*)?$")
    private val edgeWords = listOf(
        "首页", "书库", "华人文学", "校园小说", "小说分类", "作品分类", "阅读记录",
        "上一页", "下一页", "上一章", "下一章", "目录", "关于我们", "联系我们", "版权声明",
        "广告服务", "帮助中心", "申请链接", "加入收藏", "返回顶部", "手机版", "电脑版",
        "登录", "注册", "搜索", "排行榜", "最新章节", "全部小说", "热门小说", "阅读设置",
    )
    private val footerWords = listOf(
        "上一页", "下一页", "上一章", "下一章", "目录", "关于我们", "联系我们", "版权声明",
        "广告服务", "帮助中心", "申请链接", "加入收藏", "返回顶部", "网站地图",
        "免责声明", "ICP备案", "推荐阅读", "热门推荐", "Copyright", "版权所有",
    )
    // These are site-navigation / promotion terms, rather than terms that normally occur in
    // a chapter.  They are only used together with edge position or navigation structure so a
    // sentence in the middle of a novel is not removed merely for mentioning one of them.
    private val chromeWords = edgeWords + footerWords + listOf(
        "返回列表", "返回目录", "章节列表", "上一篇", "下一篇", "本站", "网盘合集",
        "图书与文学", "宗教与信仰", "科幻与奇幻", "惊悚片", "犯罪片", "悬疑片",
        "阅读模式", "转码阅读", "退出转码", "浏览器强制进入",
    )
    private val chapterMarker = Regex(
        "(?:正文\\s*)?(?:第[零〇○一二三四五六七八九十百千万两0-9０-９._—-]{1,16}[章回节卷部篇]|序章|楔子|引子|前言)",
    )
    // "下一章：第一章 白纸人和鼠友" style navigation — a paging token immediately followed by a
    // chapter heading.  Applied on the already-compacted text, so separators are already gone.
    private val navigationToChapterHeading = Regex(
        "^(上一章|下一章|上一篇|下一篇|返回列表|返回目录|目录)[\\s:：]*第[零〇○一二三四五六七八九十百千万两0-9０-９]+[章回节卷部篇]",
    )

    fun clean(raw: String, title: String = ""): String {
        // A row of '=' is typically inserted between the site header and the chapter body.
        // Turning it into a line boundary lets the normal edge cleaner remove both sides.
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n')
            .replace(Regex("[=＝]{3,}"), "\n")
            .lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        var start = 0
        val titleIndex = lines.take(40).indexOfFirst { chapterTitle.matches(it) }
        if (titleIndex > 0) {
            start = titleIndex
        } else {
            while (start < minOf(lines.size, 40) && isEdgeLine(lines[start], edgeWords, title)) start++
        }

        var end = lines.size
        val scanStart = maxOf(start + 1, lines.size - 50)
        for (index in scanStart until lines.size) {
            if (isFooterStart(lines[index])) {
                end = index
                break
            }
        }
        while (end > start && isEdgeLine(lines[end - 1], footerWords, title)) end--

        val lineCleaned = lines.subList(start.coerceAtMost(end), end).joinToString("\n\n").trim()
        return trimInlineChrome(lineCleaned, title)
    }

    /** Handles sites whose DOM/text extraction collapses navigation, story and footer into one line. */
    private fun trimInlineChrome(value: String, title: String): String {
        var result = value.trim()
        if (result.isEmpty()) return result

        val titleMarker = title.takeIf { it.length in 2..100 }?.let { result.indexOf(it) }
            ?.takeIf { it in 1 until minOf(result.length, 2_000) }
        val marker = chapterMarker.find(result)
        val contentStart = marker?.range?.first ?: titleMarker
        if (contentStart != null && contentStart in 1 until minOf(result.length, 2_000)) {
            val prefix = result.substring(0, contentStart)
            if (edgeWords.count { prefix.contains(it) } >= 2 ||
                (prefix.any { it in "|丨/>»›·" } && edgeWords.any { prefix.contains(it) })
            ) result = result.substring(contentStart).trim()
        }

        // A footer normally begins with paging controls and contains several site links without
        // sentence punctuation. Only inspect the last 40%/2000 chars so narrative stays untouched.
        val tailStart = maxOf(minOf(result.length * 3 / 5, result.length - 2_000), 0)
        val tail = result.substring(tailStart)
        val navigationStarts = Regex("上一页|下一页|上一章|下一章|返回列表|返回目录|目录")
            .findAll(tail)
            .map { it.range.first }
        val footerOffset = navigationStarts.firstOrNull { offset ->
            val candidate = tail.substring(offset, minOf(tail.length, offset + 500))
            val beforeSentenceEnd = candidate.substringBeforeAny("。", "！", "？")
            isFooterStart(beforeSentenceEnd) || isNavigationCluster(beforeSentenceEnd)
        }
        if (footerOffset != null) result = result.substring(0, tailStart + footerOffset).trim()

        return result.trim(' ', '\n', '\t', '|', '丨', '/', '>', '»', '›', '·', '-')
    }

    private fun String.substringBeforeAny(vararg delimiters: String): String {
        val index = delimiters.map { indexOf(it) }.filter { it >= 0 }.minOrNull() ?: length
        return substring(0, index)
    }

    private fun isFooterStart(line: String): Boolean {
        val compact = compactForMatch(line)
        val matches = footerWords.count { compact.contains(it) }
        val hasSeparators = compact.any { it in "|丨/>»›·:" }
        return matches >= 3 || (matches >= 2 && hasSeparators) ||
            isNavigationCluster(compact) || isReaderModeWarning(compact) ||
            compact.matches(Regex("^(上一页|下一页|上一章|下一章|返回列表|返回目录|目录|返回顶部)([|丨/:：\\s>»›·-].*)?$"))
    }

    private fun isEdgeLine(line: String, words: List<String>, title: String): Boolean {
        val compact = compactForMatch(line)
        if (compact.length > 180 || chapterTitle.matches(line)) return false
        // A navigation line that also contains a chapter heading (e.g. "下一章：第一章 白纸人和鼠友")
        // is still site chrome.  This must win over the chapter-title guard below, otherwise such a
        // line stops the edge scan and leaks navigation into the extracted narrative.
        if (navigationToChapterHeading.containsMatchIn(compact)) return true
        if (chapterMarker.containsMatchIn(line)) return false
        if (title.isNotBlank() && compact == title.replace(" ", "")) return false
        val matches = words.count { compact.contains(it) }
        val chromeMatches = chromeWords.count { compact.contains(it) }
        val hasSeparators = compact.any { it in "|丨/>»›·:" }
        return matches >= 3 || (matches >= 2 && hasSeparators) ||
            chromeMatches >= 3 || isNavigationCluster(compact) || isReaderModeWarning(compact) ||
            compact.matches(Regex("^(首页|目录|上一页|下一页|上一章|下一章|返回列表|返回目录|加入收藏|返回顶部)$"))
    }

    private fun compactForMatch(value: String): String =
        value.replace(Regex("[\\s/\\\\|丨>»›·:：=＝_-]"), "")

    private fun isNavigationCluster(compact: String): Boolean {
        val navigationTerms = listOf("上一页", "下一页", "上一章", "下一章", "返回列表", "返回目录", "目录")
        val matches = navigationTerms.count { compact.contains(it) }
        return matches >= 2 ||
            (compact.length <= 100 && matches >= 1 &&
                (compact.contains("第一章") || compact.contains("第1章") || compact.contains("章节")))
    }

    private fun isReaderModeWarning(compact: String): Boolean =
        (compact.contains("浏览器强制进入") && compact.contains("阅读模式")) ||
            compact.contains("退出转码阅读")
}
