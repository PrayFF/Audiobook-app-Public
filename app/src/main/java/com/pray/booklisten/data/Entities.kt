package com.pray.booklisten.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class SourceType { TXT, EPUB, WEB }

@Entity(tableName = "books", primaryKeys = ["id"])
data class BookEntity(
    val id: String,
    val title: String,
    val author: String = "",
    val sourceType: SourceType,
    val sourceUri: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val currentChapterIndex: Int = 0,
    val currentBlockIndex: Int = 0,
    val currentPositionMs: Long = 0,
    val totalChapters: Int = 0,
)

@Entity(
    tableName = "chapters",
    primaryKeys = ["bookId", "chapterIndex"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("bookId")],
)
data class ChapterEntity(
    val bookId: String,
    val chapterIndex: Int,
    val title: String,
    val content: String,
    val sourceUrl: String? = null,
    val nextUrl: String? = null,
)

data class ParsedBook(
    val title: String,
    val author: String = "",
    val chapters: List<ParsedChapter>,
)

data class ParsedChapter(
    val title: String,
    val content: String,
    val sourceUrl: String? = null,
    val nextUrl: String? = null,
)
