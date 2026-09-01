package com.pray.booklisten.data

object TextChunker {
    private const val MAX_TTS_LENGTH = 220
    private const val FIRST_TARGET_LENGTH = 45
    private const val NORMAL_TARGET_LENGTH = 160

    fun chunk(text: String, maxLength: Int = MAX_TTS_LENGTH): List<String> {
        val normalized = text.replace("\r\n", "\n")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (normalized.isEmpty()) return emptyList()

        val sentences = normalized.split(Regex("(?<=[。！？!?；;…])|\\n+"))
            .map(String::trim)
            .filter(String::isNotEmpty)
        val result = mutableListOf<String>()
        val buffer = StringBuilder()
        var firstBlock = true
        fun flush() {
            if (buffer.isNotBlank()) {
                result += buffer.toString().trim()
                firstBlock = false
            }
            buffer.clear()
        }
        for (sentence in sentences) {
            if (sentence.length > maxLength) {
                flush()
                sentence.chunked(maxLength).forEach(result::add)
            } else if (buffer.isNotEmpty() && buffer.length + sentence.length + 1 >
                minOf(maxLength, if (firstBlock) FIRST_TARGET_LENGTH else NORMAL_TARGET_LENGTH)
            ) {
                flush()
                buffer.append(sentence)
            } else {
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(sentence)
            }
        }
        flush()
        return result
    }
}
