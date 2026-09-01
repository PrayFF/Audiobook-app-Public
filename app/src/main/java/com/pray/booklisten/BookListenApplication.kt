package com.pray.booklisten

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pray.booklisten.data.AppDatabase
import com.pray.booklisten.data.BookRepository
import com.pray.booklisten.playback.TtsCacheCleanupWorker
import java.util.concurrent.TimeUnit

class BookListenApplication : Application() {
    val database by lazy { AppDatabase.create(this) }
    val repository by lazy { BookRepository(this, database) }

    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "tts-cache-cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TtsCacheCleanupWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}
