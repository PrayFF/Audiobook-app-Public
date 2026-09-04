package com.pray.booklisten.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

class BookRepository(
    private val context: Context,
    private val database: AppDatabase,
) {
    private val dao = database.bookDao()

    fun observeBooks(): Flow<List<BookEntity>> = dao.observeBooks()
    fun observeChapters(bookId: String): Flow<List<ChapterEntity>> = dao.observeChapters(bookId)
    suspend fun getBook(id: String) = dao.getBook(id)
    suspend fun getChapter(bookId: String, index: Int): ChapterEntity? {
        val chapter = dao.getChapter(bookId, index) ?: return null
        val book = dao.getBook(bookId) ?: return chapter
        if (book.sourceType != SourceType.WEB) return chapter
        val cleaned = chapter.copy(content = NovelTextCleaner.clean(chapter.content, chapter.title))
        if (cleaned.content != chapter.content) dao.upsertChapters(listOf(cleaned))
        return cleaned
    }

    suspend fun getChapters(bookId: String): List<ChapterEntity> {
        val chapters = dao.getChapters(bookId)
        val book = dao.getBook(bookId) ?: return chapters
        if (book.sourceType != SourceType.WEB) return chapters
        val cleaned = chapters.map { it.copy(content = NovelTextCleaner.clean(it.content, it.title)) }
        if (cleaned.zip(chapters).any { (new, old) -> new.content != old.content }) dao.upsertChapters(cleaned)
        return cleaned
    }

    suspend fun importFile(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null }
        }.getOrNull()?.takeIf(String::isNotBlank)
            ?: Uri.decode(uri.lastPathSegment.orEmpty().substringAfterLast('/')).ifBlank { "导入书籍" }
        resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            val length = descriptor.length
            require(length < 50L * 1024 * 1024 || length < 0) { "文件超过 50 MB，暂不支持导入" }
        }
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrElse { error("无法读取本地文件：${it.message ?: "请检查文件权限"}") }
            ?: error("文件管理器没有授予读取权限，请重新选择文件")
        require(bytes.isNotEmpty()) { "所选文件为空" }
        val format = BookFileDetector.detect(name, resolver.getType(uri), bytes)
        val parsed = when (format) {
            ImportedBookFormat.TXT -> TxtBookParser.parse(bytes, name)
            ImportedBookFormat.EPUB -> EpubBookParser.parse(bytes, name)
        }
        saveParsedBook(parsed, if (format == ImportedBookFormat.EPUB) SourceType.EPUB else SourceType.TXT, uri.toString())
    }

    suspend fun importWebPage(page: ExtractedWebPage): String {
        val content = NovelTextCleaner.clean(page.content, page.title)
        require(content.length >= 40) { "清理导航信息后没有识别到足够正文，请在浏览器中打开真正的章节页" }

        // Try to fetch the full chapter catalog from the chapter page; only titles + URLs are
        // stored (no chapter body is downloaded).  If it fails, fall back to a single-chapter book
        // exactly like before.
        val catalog = page.catalogUrl?.let { runCatching { WebCatalogParser.fetchCatalog(it) }.getOrNull() }
        val chapters = if (catalog.isNullOrEmpty()) {
            listOf(ParsedChapter(page.title, content, page.url, page.nextUrl))
        } else {
            buildCatalogChapters(catalog, page.title, content, page.url)
        }
        val parsed = ParsedBook(page.bookTitle, chapters = chapters)
        return saveParsedBook(parsed, SourceType.WEB, page.url)
    }

    /**
     * Builds the chapter list from a catalog: every catalog entry becomes a placeholder chapter
     * (empty content), except the entry matching the currently-open chapter URL, which carries the
     * already-extracted body.  This way the book is created with a full table of contents but no
     * bulk download.
     */
    private fun buildCatalogChapters(
        catalog: List<CatalogChapter>,
        currentTitle: String,
        currentContent: String,
        currentUrl: String,
    ): List<ParsedChapter> {
        val normalizedCurrent = currentUrl.trimEnd('/', '#')
        val chapters = mutableListOf<ParsedChapter>()
        var matched = false
        for (entry in catalog) {
            val isCurrent = entry.url.trimEnd('/', '#') == normalizedCurrent
            if (isCurrent) {
                chapters.add(ParsedChapter(currentTitle, currentContent, currentUrl, null))
                matched = true
            } else {
                chapters.add(ParsedChapter(entry.title, "", entry.url, null))
            }
        }
        // If the current chapter wasn't in the catalog, still include it so the user can read
        // the chapter they just opened.
        if (!matched) {
            chapters.add(ParsedChapter(currentTitle, currentContent, currentUrl, null))
        }
        return chapters
    }

    /**
     * Fetches a chapter's body by its stored source URL and fills it in.  Used when the user taps
     * a not-yet-downloaded chapter in the table of contents.
     */
    suspend fun fetchChapterContent(bookId: String, chapterIndex: Int): ChapterEntity? {
        val chapter = dao.getChapter(bookId, chapterIndex) ?: return null
        if (chapter.content.isNotBlank()) return chapter
        val url = chapter.sourceUrl ?: return chapter
        val page = WebPageParser.fetch(url)
        val content = NovelTextCleaner.clean(page.content, page.title)
        require(content.length >= 40) { "该章节没有识别到足够正文" }
        // Keep a user-renamed title; otherwise adopt the page's own chapter heading.
        val title = chapter.title.takeIf { it.isNotBlank() }
            ?: page.title.ifBlank { chapter.title }
        val updated = chapter.copy(content = content, title = title)
        dao.upsertChapters(listOf(updated))
        return updated
    }

    suspend fun appendNextWebChapter(bookId: String): Boolean {
        val chapters = dao.getChapters(bookId)
        val last = chapters.lastOrNull() ?: return false
        val nextUrl = last.nextUrl ?: return false
        return appendByUrl(bookId, chapters.size, nextUrl)
    }

    private suspend fun appendByUrl(bookId: String, index: Int, url: String): Boolean {
        val page = WebPageParser.fetch(url)
        dao.upsertChapters(
            listOf(ChapterEntity(bookId, index, page.title, NovelTextCleaner.clean(page.content, page.title), page.url, page.nextUrl))
        )
        val book = dao.getBook(bookId) ?: return true
        dao.updateBook(book.copy(totalChapters = index + 1, updatedAt = System.currentTimeMillis()))
        return true
    }

    suspend fun renameChapter(bookId: String, chapterIndex: Int, newTitle: String) {
        val title = newTitle.trim().replace(Regex("\\s+"), " ")
        require(title.isNotEmpty()) { "章节名不能为空" }
        require(title.length <= 120) { "章节名不能超过 120 个字符" }
        val chapter = dao.getChapter(bookId, chapterIndex) ?: error("没有找到该章节")
        dao.upsertChapters(listOf(chapter.copy(title = title)))
    }

    suspend fun renameChapters(bookId: String, titles: Map<Int, String>) {
        val chapters = dao.getChapters(bookId)
        val updates = titles.mapNotNull { (index, raw) ->
            val title = raw.trim().replace(Regex("\\s+"), " ")
            if (title.isEmpty() || title.length > 120) return@mapNotNull null
            chapters.firstOrNull { it.chapterIndex == index }?.copy(title = title)
        }
        if (updates.isNotEmpty()) dao.upsertChapters(updates)
    }

    suspend fun cleanStoredWebBook(bookId: String) = withContext(Dispatchers.IO) {
        val chapters = dao.getChapters(bookId)
        val cleaned = chapters.map { chapter ->
            chapter.copy(content = NovelTextCleaner.clean(chapter.content, chapter.title))
        }
        if (cleaned.zip(chapters).any { (new, old) -> new.content != old.content }) {
            dao.upsertChapters(cleaned)
        }
    }

    suspend fun saveProgress(bookId: String, chapterIndex: Int, blockIndex: Int, positionMs: Long) {
        val book = dao.getBook(bookId) ?: return
        dao.updateBook(
            book.copy(
                currentChapterIndex = chapterIndex,
                currentBlockIndex = blockIndex,
                currentPositionMs = positionMs.coerceAtLeast(0),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun delete(book: BookEntity) = dao.deleteBook(book)

    suspend fun renameBook(bookId: String, newTitle: String) {
        val title = newTitle.trim().replace(Regex("\\s+"), " ")
        require(title.isNotEmpty()) { "书名不能为空" }
        require(title.length <= 100) { "书名不能超过 100 个字符" }
        val book = dao.getBook(bookId) ?: error("没有找到这本书")
        dao.updateBook(book.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    private suspend fun saveParsedBook(parsed: ParsedBook, type: SourceType, sourceUri: String): String {
        val id = UUID.randomUUID().toString()
        val chapters = parsed.chapters.mapIndexed { index, chapter ->
            ChapterEntity(id, index, chapter.title, chapter.content, chapter.sourceUrl, chapter.nextUrl)
        }
        database.withTransaction {
            dao.upsertBook(
                BookEntity(
                    id = id,
                    title = parsed.title,
                    author = parsed.author,
                    sourceType = type,
                    sourceUri = sourceUri,
                    totalChapters = chapters.size,
                )
            )
            dao.upsertChapters(chapters)
        }
        return id
    }
}
