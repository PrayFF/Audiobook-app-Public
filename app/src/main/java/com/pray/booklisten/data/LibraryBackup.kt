package com.pray.booklisten.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object LibraryBackup {
    private const val FORMAT_VERSION = 1
    private const val MAX_BACKUP_BYTES = 200L * 1024 * 1024

    suspend fun export(context: Context, database: AppDatabase, uri: Uri) = withContext(Dispatchers.IO) {
        val dao = database.bookDao()
        val books = dao.getBooksOnce()
        val collections = dao.getCollectionsOnce()
        val chapters = books.flatMap { dao.getChapters(it.id) }
        val root = JSONObject()
            .put("format", "booklisten-library")
            .put("version", FORMAT_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("collections", JSONArray().apply {
                collections.forEach { item -> put(JSONObject()
                    .put("id", item.id).put("name", item.name).put("position", item.position)
                    .put("createdAt", item.createdAt)) }
            })
            .put("books", JSONArray().apply {
                books.forEach { book -> put(JSONObject()
                    .put("id", book.id).put("title", book.title).put("author", book.author)
                    .put("sourceType", book.sourceType.name).put("sourceUri", book.sourceUri)
                    .put("createdAt", book.createdAt).put("updatedAt", book.updatedAt)
                    .put("currentChapterIndex", book.currentChapterIndex)
                    .put("currentBlockIndex", book.currentBlockIndex)
                    .put("currentPositionMs", book.currentPositionMs)
                    .put("totalChapters", book.totalChapters)
                    .put("collectionId", book.collectionId ?: JSONObject.NULL)
                    .put("sortOrder", book.sortOrder)) }
            })
            .put("chapters", JSONArray().apply {
                chapters.forEach { chapter -> put(JSONObject()
                    .put("bookId", chapter.bookId).put("chapterIndex", chapter.chapterIndex)
                    .put("title", chapter.title).put("content", chapter.content)
                    .put("sourceUrl", chapter.sourceUrl ?: JSONObject.NULL)
                    .put("nextUrl", chapter.nextUrl ?: JSONObject.NULL)
                    .put("defaultTitle", chapter.defaultTitle)) }
            })
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(root.toString())
        } ?: error("无法创建备份文件")
    }

    suspend fun restore(context: Context, database: AppDatabase, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            require(descriptor.length < MAX_BACKUP_BYTES || descriptor.length < 0) { "备份文件超过 200 MB" }
        }
        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("无法读取备份文件")
        val root = JSONObject(raw)
        require(root.optString("format") == "booklisten-library") { "不是有效的听书书库备份" }
        require(root.optInt("version") in 1..FORMAT_VERSION) { "备份版本暂不支持" }
        val collections = root.getJSONArray("collections").mapObjects { item ->
            CollectionEntity(
                id = item.getString("id"), name = item.getString("name"),
                position = item.optInt("position"), createdAt = item.optLong("createdAt"),
            )
        }
        val books = root.getJSONArray("books").mapObjects { item ->
            BookEntity(
                id = item.getString("id"), title = item.getString("title"),
                author = item.optString("author"), sourceType = SourceType.valueOf(item.getString("sourceType")),
                sourceUri = item.optString("sourceUri"), createdAt = item.optLong("createdAt"),
                updatedAt = item.optLong("updatedAt"), currentChapterIndex = item.optInt("currentChapterIndex"),
                currentBlockIndex = item.optInt("currentBlockIndex"), currentPositionMs = item.optLong("currentPositionMs"),
                totalChapters = item.optInt("totalChapters"), collectionId = item.nullableString("collectionId"),
                sortOrder = item.optInt("sortOrder"),
            )
        }
        val bookIds = books.mapTo(hashSetOf()) { it.id }
        val chapters = root.getJSONArray("chapters").mapObjects { item ->
            ChapterEntity(
                bookId = item.getString("bookId"), chapterIndex = item.getInt("chapterIndex"),
                title = item.getString("title"), content = item.optString("content"),
                sourceUrl = item.nullableString("sourceUrl"), nextUrl = item.nullableString("nextUrl"),
                defaultTitle = item.optString("defaultTitle"),
            )
        }.filter { it.bookId in bookIds }
        database.withTransaction {
            val dao = database.bookDao()
            collections.chunked(500).forEach { dao.upsertCollections(it) }
            books.chunked(500).forEach { dao.upsertBooks(it) }
            chapters.chunked(500).forEach { dao.upsertChapters(it) }
        }
    }

    private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        List(length()) { index -> transform(getJSONObject(index)) }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
