package com.pray.booklisten.playback

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TtsCacheCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val directory = File(applicationContext.filesDir, "tts-audio")
        if (!directory.exists()) return@withContext Result.success()
        val files = directory.listFiles()?.filter(File::isFile)?.sortedBy { it.lastModified() }.orEmpty()
        val remaining = files
        var total = remaining.sumOf(File::length)
        val limit = 2L * 1024 * 1024 * 1024
        for (file in remaining) {
            if (total <= limit) break
            val length = file.length()
            if (file.delete()) total -= length
        }
        Result.success()
    }
}
