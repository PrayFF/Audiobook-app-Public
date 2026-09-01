package com.pray.booklisten.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

/** Selects a stable book name from page metadata without mistaking a chapter or site name for it. */
object WebBookTitleResolver {
    private val chapterHeading = Regex(
        "^(?:正文\\s*)?(?:第[0-9０-９零〇一二三四五六七八九十百千万两]+[章回节卷部篇]|chapter\\s*\\d+)",
        RegexOption.IGNORE_CASE,
    )
    private val titleSuffix = Regex(
        "(?:最新章节(?:列表)?|全文(?:免费)?阅读|无弹窗(?:阅读)?|在线阅读|章节目录|小说阅读).*$",
        RegexOption.IGNORE_CASE,
    )
    private val genericNames = setOf(
        "首页", "书架", "目录", "正文", "小说", "小说网", "书库", "文学网", "阅读网",
        "上一章", "下一章", "上一页", "下一页", "返回顶部", "网页书籍", "网页章节",
    )

    fun resolve(
        chapterTitle: String,
        documentTitle: String,
        candidates: List<String>,
        url: String = "",
    ): String {
        val host = runCatching { URI(url).host.orEmpty().substringBefore('.') }.getOrDefault("")
        val ordered = buildList {
            addAll(candidates)
            addAll(documentTitle.split(Regex("\\s*(?:[_|｜]|[-—–]{1,2})\\s*")))
            if (!chapterHeading.containsMatchIn(chapterTitle.trim())) add(chapterTitle)
        }
        return ordered.asSequence()
            .map(::clean)
            .filter { isPlausible(it, host) }
            .firstOrNull()
            ?: "网页书籍"
    }

    fun namesFromJsonLd(raw: String): List<String> {
        val root = runCatching { JSONTokener(raw).nextValue() }.getOrNull() ?: return emptyList()
        val names = mutableListOf<String>()
        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> repeat(value.length()) { visit(value.opt(it)) }
                is JSONObject -> {
                    val types = when (val type = value.opt("@type")) {
                        is JSONArray -> List(type.length()) { type.optString(it) }
                        else -> listOf(type?.toString().orEmpty())
                    }
                    if (types.any { it.equals("Book", true) || it.equals("Novel", true) }) {
                        value.optString("name").takeIf(String::isNotBlank)?.let(names::add)
                        value.optString("headline").takeIf(String::isNotBlank)?.let(names::add)
                    }
                    value.keys().forEachRemaining { visit(value.opt(it)) }
                }
            }
        }
        visit(root)
        return names
    }

    private fun clean(raw: String): String = raw
        .replace(Regex("^[《〈\\[【]?\\s*(?:书名|小说名)\\s*[：:]\\s*"), "")
        .replace(titleSuffix, "")
        .trim(' ', '\t', '\n', '\r', '_', '-', '—', '–', '|', '｜', '《', '》', '〈', '〉', '【', '】', '[', ']')
        .replace(Regex("\\s{2,}"), " ")

    private fun isPlausible(value: String, host: String): Boolean {
        val compact = value.replace(" ", "")
        if (compact.length !in 2..80) return false
        if (chapterHeading.containsMatchIn(compact)) return false
        if (genericNames.any { compact.equals(it, true) }) return false
        if (compact.matches(Regex("^(?:玄幻|奇幻|武侠|仙侠|都市|言情|历史|军事|科幻|灵异|校园|网络|华人)?(?:小说|文学)$"))) return false
        if (compact.matches(Regex("^.*(?:小说网|书库|文学网|阅读网|中文网|笔趣阁)$"))) return false
        if (compact.matches(Regex("^(?:登录|注册|搜索|排行|分类|收藏|帮助|关于我们).*$"))) return false
        if (host.length >= 3 && compact.equals(host, true)) return false
        return true
    }
}
