package com.pray.booklisten.data

enum class ImportedBookFormat { TXT, EPUB }

object BookFileDetector {
    fun detect(displayName: String, mimeType: String?, bytes: ByteArray): ImportedBookFormat {
        val lowerName = displayName.lowercase()
        val lowerMime = mimeType.orEmpty().lowercase()
        val isZip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] in setOf(0x03.toByte(), 0x05.toByte(), 0x07.toByte())
        return when {
            lowerName.endsWith(".epub") || lowerMime == "application/epub+zip" || isZip -> ImportedBookFormat.EPUB
            lowerName.endsWith(".txt") || lowerMime.startsWith("text/") || looksLikeText(bytes) -> ImportedBookFormat.TXT
            else -> error("无法识别文件格式，请选择 TXT 或无 DRM 的 EPUB 文件")
        }
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val sample = bytes.take(4096)
        if (sample.any { it == 0.toByte() }) return false
        val controlCount = sample.count { value ->
            val unsigned = value.toInt() and 0xFF
            unsigned < 0x20 && unsigned !in setOf(0x09, 0x0A, 0x0D)
        }
        return controlCount * 20 < sample.size
    }
}

