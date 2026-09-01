package com.pray.booklisten.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BookFileDetectorTest {
    @Test fun `detects epub from mime when provider hides extension`() {
        assertEquals(
            ImportedBookFormat.EPUB,
            BookFileDetector.detect("导入书籍", "application/epub+zip", byteArrayOf(0x50, 0x4B, 0x03, 0x04)),
        )
    }

    @Test fun `detects epub from zip header with generic mime`() {
        assertEquals(
            ImportedBookFormat.EPUB,
            BookFileDetector.detect("content", "application/octet-stream", byteArrayOf(0x50, 0x4B, 0x03, 0x04)),
        )
    }

    @Test fun `detects extensionless Chinese text`() {
        assertEquals(
            ImportedBookFormat.TXT,
            BookFileDetector.detect("content", "application/octet-stream", "第一章\n正文".toByteArray()),
        )
    }

    @Test fun `rejects unknown binary`() {
        assertThrows(IllegalStateException::class.java) {
            BookFileDetector.detect("binary", "application/octet-stream", byteArrayOf(0, 1, 2, 3))
        }
    }
}
