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
    private val chapterMarker = Regex(
        "(?:正文\\s*)?(?:第[零〇○一二三四五六七八九十百千万两0-9０-９._—-]{1,16}[章回节卷部篇]|序章|楔子|引子|前言)",
    )

    fun clean(raw: String, title: String = ""): String {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n')
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
        val navigationStarts = Regex("上一页|下一页|上一章|下一章|返回目录|目录")
            .findAll(tail)
            .map { it.range.first }
        val footerOffset = navigationStarts.firstOrNull { offset ->
            val candidate = tail.substring(offset, minOf(tail.length, offset + 500))
            val beforeSentenceEnd = candidate.substringBeforeAny("。", "！", "？")
            footerWords.count { beforeSentenceEnd.contains(it) } >= 3
        }
        if (footerOffset != null) result = result.substring(0, tailStart + footerOffset).trim()

        return result.trim(' ', '\n', '\t', '|', '丨', '/', '>', '»', '›', '·', '-')
    }

    private fun String.substringBeforeAny(vararg delimiters: String): String {
        val index = delimiters.map { indexOf(it) }.filter { it >= 0 }.minOrNull() ?: length
        return substring(0, index)
    }

    private fun isFooterStart(line: String): Boolean {
        val compact = line.replace(" ", "")
        val matches = footerWords.count { compact.contains(it) }
        val hasSeparators = compact.any { it in "|丨/>»›·" }
        return matches >= 3 || (matches >= 2 && hasSeparators) ||
            compact.matches(Regex("^(上一页|下一页|上一章|下一章|目录|返回顶部)([|丨/\\s>»›·-].*)?$"))
    }

    private fun isEdgeLine(line: String, words: List<String>, title: String): Boolean {
        val compact = line.replace(" ", "")
        if (compact.length > 180 || chapterTitle.matches(line) || chapterMarker.containsMatchIn(line)) return false
        if (title.isNotBlank() && compact == title.replace(" ", "")) return false
        val matches = words.count { compact.contains(it) }
        val hasSeparators = compact.any { it in "|丨/>»›·" }
        return matches >= 3 || (matches >= 2 && hasSeparators) ||
            compact.matches(Regex("^(首页|目录|上一页|下一页|上一章|下一章|加入收藏|返回顶部)$"))
    }
}
