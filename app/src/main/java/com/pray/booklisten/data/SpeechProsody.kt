package com.pray.booklisten.data

/** Normalizes novel text so offline TTS receives explicit, natural pause cues. */
object SpeechProsody {
    private val ending = Regex("[。！？!?；;…：:）)】》”’]$")
    private val chapterHeading = Regex("^(正文\\s*)?(第.{1,16}[章回节卷部篇]|序章|楔子|引子|前言|后记).*$")

    fun prepare(raw: String): String = raw
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("\\.{3,}"), "……")
        .replace(Regex("…{3,}"), "……")
        .replace(Regex("-{2,}"), "——")
        .lines()
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString("\n") { line ->
            when {
                chapterHeading.matches(line) -> line.trimEnd('。') + "。"
                ending.containsMatchIn(line) -> line
                else -> "$line。"
            }
        }
}
