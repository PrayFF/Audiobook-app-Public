package com.pray.booklisten.data

import android.content.Context
import androidx.work.*
import com.pray.booklisten.BookListenApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class WebChapterCacheWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as BookListenApplication).repository
        val bookId = inputData.getString("book") ?: return Result.failure()
        val book = repository.getBook(bookId) ?: return Result.success()
        if (book.sourceType != SourceType.WEB) return Result.success()
        val current = inputData.getInt("chapter", 0)
        var failure: String? = null
        for (index in ChapterCacheWindow.indices(current, book.totalChapters)) {
            if (isStopped) return Result.success()
            try {
                val chapter = repository.fetchChapterContent(bookId, index)
                if (chapter == null || chapter.content.isBlank()) failure = "章节未下载"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error.message ?: "章节下载失败"
                // Do not repeatedly request a verification page or a failing server.
                break
            }
            delay(500)
        }
        return if (failure == null) Result.success() else if (runAttemptCount < 3) Result.retry()
        else Result.failure(workDataOf("error" to failure))
    }

    companion object {
        fun schedule(context: Context, bookId: String, current: Int) {
            val request = OneTimeWorkRequestBuilder<WebChapterCacheWorker>()
                .setInputData(workDataOf("book" to bookId, "chapter" to current))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("web-cache-$bookId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
