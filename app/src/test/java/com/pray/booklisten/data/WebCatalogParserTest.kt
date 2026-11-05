package com.pray.booklisten.data

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class WebCatalogParserTest {
    @Test
    fun keepsAllQuanbenCatalogGroupsInsteadOfOnlyLargestGroup() {
        val document = Jsoup.parse(
            """
            <div class="volume"><a href="1.html">第1章 山边小村</a><a href="2.html">第2章 青牛镇</a></div>
            <div class="volume"><a href="3.html">第3章 七玄门</a><a href="4.html">第4章 炼骨崖</a></div>
            <div class="volume"><a href="5.html">第5章 墨大夫</a></div>
            """.trimIndent(),
            "https://www.quanben.io/n/fanrenxiuxianzhuan/list.html",
        )

        assertEquals(
            listOf("第1章 山边小村", "第2章 青牛镇", "第3章 七玄门", "第4章 炼骨崖", "第5章 墨大夫"),
            WebCatalogParser.parse(document).map { it.title },
        )
    }

    @Test
    fun expandsHetushuMobileCatalogSelectInPageOrder() {
        val document = Jsoup.parse(
            """
            <select>
              <option value="3">第3页</option>
              <option selected value="1">第1页</option>
              <option value="2">第2页</option>
              <option value="2">重复页</option>
            </select>
            """.trimIndent(),
            "https://m.hetushu.com/book/38/catalog-1.html",
        )

        assertEquals(
            listOf(
                "https://m.hetushu.com/book/38/catalog-1.html",
                "https://m.hetushu.com/book/38/catalog-2.html",
                "https://m.hetushu.com/book/38/catalog-3.html",
            ),
            WebCatalogParser.hetushuCatalogPageUrls(document),
        )
    }

    @Test
    fun doesNotExpandUnrelatedSites() {
        val document = Jsoup.parse(
            "<select><option value='1'>1</option><option value='2'>2</option></select>",
            "https://example.com/book/38/catalog-1.html",
        )

        assertEquals(emptyList<String>(), WebCatalogParser.hetushuCatalogPageUrls(document))
    }
}
