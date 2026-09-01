package com.pray.booklisten.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object TxtBookParser {
    private val chapterPattern = Regex(
        "(?m)^[ \\t]*(第[0-9零一二三四五六七八九十百千万两〇]+[章回节卷部篇][^\\n]{0,50}|序章|楔子|引子|前言|后记|尾声|番外[^\\n]{0,30})[ \\t]*$"
    )

    fun parse(bytes: ByteArray, fallbackTitle: String): ParsedBook {
        val text = decode(bytes).replace("\u0000", "").trim()
        require(text.isNotBlank()) { "TXT 文件没有可读取的文字" }
        val matches = chapterPattern.findAll(text).toList()
        val chapters = if (matches.isEmpty()) {
            listOf(ParsedChapter("正文", text))
        } else {
            buildList {
                if (matches.first().range.first > 0) {
                    text.substring(0, matches.first().range.first).trim().takeIf(String::isNotEmpty)
                        ?.let { add(ParsedChapter("前言", it)) }
                }
                matches.forEachIndexed { index, match ->
                    val start = match.range.last + 1
                    val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
                    val body = text.substring(start, end).trim()
                    if (body.isNotEmpty()) add(ParsedChapter(match.value.trim(), body))
                }
            }
        }
        return ParsedBook(fallbackTitle.substringBeforeLast('.'), chapters = chapters)
    }

    internal fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, charset("GB18030"))
        }
    }
}

