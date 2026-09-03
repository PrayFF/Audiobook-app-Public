package com.pray.booklisten.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.pray.booklisten.BookListenApplication
import com.pray.booklisten.MainActivity
import com.pray.booklisten.R
import com.pray.booklisten.data.TextChunker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest

@UnstableApi
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var synthesizer: TtsSynthesizer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var synthesisJob: Job? = null
    private var sleepJob: Job? = null
    private var currentBookId = ""
    private var currentChapterIndex = 0
    private var currentVoiceName = ""
    private var currentEnginePackage = ""
    private var currentCacheEpoch = 0
    private var engineResetNeeded = false
    private var synthesisComplete = false
    private var stopAfterChapter = false

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(15_000)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && synthesisComplete &&
                    player.currentMediaItemIndex >= player.mediaItemCount - 1
                ) {
                    if (stopAfterChapter) stopAfterChapter = false else playNextChapter()
                }
            }
        })
        session = MediaSession.Builder(this, player).build()
        synthesizer = TtsSynthesizer(this)
        scope.launch {
            while (true) {
                delay(5_000)
                persistProgress()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_CHAPTER -> {
                showPreparingNotification()
                loadChapter(
                bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: return START_NOT_STICKY,
                chapterIndex = intent.getIntExtra(EXTRA_CHAPTER_INDEX, 0),
                startBlock = intent.getIntExtra(EXTRA_BLOCK_INDEX, 0),
                startPosition = intent.getLongExtra(EXTRA_POSITION_MS, 0),
                voiceName = intent.getStringExtra(EXTRA_VOICE_NAME).orEmpty(),
                enginePackage = intent.getStringExtra(EXTRA_TTS_ENGINE).orEmpty(),
                cacheEpoch = intent.getIntExtra(EXTRA_TTS_CACHE_EPOCH, 0),
                speed = intent.getFloatExtra(EXTRA_SPEED, 1f),
                )
            }
            ACTION_SET_SLEEP_TIMER -> setSleepTimer(intent.getIntExtra(EXTRA_MINUTES, 0))
            ACTION_CANCEL_SLEEP_TIMER -> sleepJob?.cancel()
            ACTION_RESET_VOICE_ENGINE -> resetVoiceEngine()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun loadChapter(
        bookId: String,
        chapterIndex: Int,
        startBlock: Int,
        startPosition: Long,
        voiceName: String,
        enginePackage: String,
        cacheEpoch: Int,
        speed: Float,
    ) {
        synthesisJob?.cancel()
        if (currentBookId.isNotBlank() && player.mediaItemCount > 0) {
            val oldBookId = currentBookId
            val oldChapter = currentChapterIndex
            val oldBlock = currentTextBlockIndex()
            val oldPosition = player.currentPosition.coerceAtLeast(0)
            scope.launch { persistProgress(oldBookId, oldChapter, oldBlock, oldPosition) }
        }
        currentBookId = bookId
        currentChapterIndex = chapterIndex
        currentVoiceName = voiceName
        currentCacheEpoch = cacheEpoch
        if (enginePackage != currentEnginePackage || engineResetNeeded) {
            synthesizer.shutdown()
            synthesizer = TtsSynthesizer(this, enginePackage)
            currentEnginePackage = enginePackage
            engineResetNeeded = false
        }
        synthesisComplete = false
        synthesisJob = scope.launch {
            val app = application as BookListenApplication
            val book = app.repository.getBook(bookId) ?: return@launch
            val chapter = app.repository.getChapter(bookId, chapterIndex) ?: return@launch
            val chunks = TextChunker.chunk(chapter.content)
            player.stop()
            player.clearMediaItems()
            player.setPlaybackSpeed(speed.coerceIn(0.75f, 2.5f))
            val safeStartBlock = startBlock.coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
            chunks.drop(safeStartBlock).forEachIndexed { queueIndex, text ->
                val index = safeStartBlock + queueIndex
                val output = ttsCacheFile(bookId, chapterIndex, index, enginePackage, voiceName, text)
                try {
                    val file = synthesizer.synthesize(text, output, voiceName)
                        val item = MediaItem.Builder()
                            .setMediaId("$bookId:$chapterIndex:$index")
                            .setUri(Uri.fromFile(file))
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(chapter.title)
                                    .setArtist(book.title)
                                    .setTrackNumber(index + 1)
                                    .setTotalTrackCount(chunks.size)
                                    .build()
                            )
                            .build()
                        player.addMediaItem(item)
                        if (queueIndex == 0) {
                            player.prepare()
                            player.seekTo(0, startPosition)
                            player.play()
                        } else if (player.playbackState == Player.STATE_ENDED) {
                            player.seekTo(queueIndex, 0)
                            player.prepare()
                            player.play()
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    val message = error.message ?: "语音合成失败"
                    PlaybackEvents.report(message)
                    showErrorNotification(message)
                    return@launch
                }
            }
            synthesisComplete = true
            if (player.playbackState == Player.STATE_ENDED && chunks.isNotEmpty() &&
                player.currentMediaItemIndex >= player.mediaItemCount - 1
            ) {
                if (stopAfterChapter) stopAfterChapter = false else playNextChapter()
            }
            prefetchNextChapter(bookId, chapterIndex + 1, enginePackage, voiceName)
        }
    }

    private suspend fun prefetchNextChapter(
        bookId: String,
        nextChapterIndex: Int,
        enginePackage: String,
        voiceName: String,
    ) {
        val app = application as BookListenApplication
        repeat(12) {
            val next = app.repository.getChapter(bookId, nextChapterIndex)
            if (next != null) {
                val first = TextChunker.chunk(next.content).firstOrNull() ?: return
                val output = ttsCacheFile(bookId, nextChapterIndex, 0, enginePackage, voiceName, first)
                try {
                    synthesizer.synthesize(first, output, voiceName)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Prefetch is best-effort; normal playback will surface an actual error.
                }
                return
            }
            delay(5_000)
        }
    }

    private fun ttsCacheFile(
        bookId: String,
        chapterIndex: Int,
        blockIndex: Int,
        enginePackage: String,
        voiceName: String,
        text: String,
    ): File {
        // The cache key includes the engine's install timestamp (voice-pack APKs share one
        // package name, so a replacing install must not reuse old audio) and a cache epoch
        // (bumped when the in-engine speaker selection changes).  Nothing is ever deleted
        // on switch; stale entries are simply left for the cache-cleanup worker.
        val digest = sha256(
            "$TTS_CACHE_VERSION:$bookId:$chapterIndex:$blockIndex:" +
                "${engineSignature(enginePackage)}:$voiceName:$currentCacheEpoch:$text"
        ).take(20)
        val persistent = File(filesDir, "tts-audio/$digest.wav")
        if (!persistent.exists()) {
            val legacy = File(cacheDir, "tts/$digest.wav")
            if (legacy.exists() && legacy.length() > 44) {
                persistent.parentFile?.mkdirs()
                if (!legacy.renameTo(persistent)) legacy.copyTo(persistent, overwrite = true)
            }
        }
        return persistent
    }

    private fun engineSignature(enginePackage: String): String {
        if (enginePackage.isBlank()) return "default"
        val updateTime = runCatching {
            packageManager.getPackageInfo(enginePackage, 0).lastUpdateTime
        }.getOrDefault(0L)
        return "$enginePackage@$updateTime"
    }

    private fun playNextChapter() {
        val bookId = currentBookId.takeIf(String::isNotBlank) ?: return
        val nextIndex = currentChapterIndex + 1
        scope.launch {
            val app = application as BookListenApplication
            val next = app.repository.getChapter(bookId, nextIndex) ?: return@launch
            loadChapter(bookId, next.chapterIndex, 0, 0, currentVoiceName, currentEnginePackage, currentCacheEpoch, player.playbackParameters.speed)
        }
    }

    private suspend fun persistProgress() {
        if (currentBookId.isBlank() || !::player.isInitialized || player.mediaItemCount == 0) return
        persistProgress(
            currentBookId,
            currentChapterIndex,
            currentTextBlockIndex(),
            player.currentPosition.coerceAtLeast(0),
        )
    }

    private suspend fun persistProgress(bookId: String, chapterIndex: Int, blockIndex: Int, positionMs: Long) {
        val app = application as BookListenApplication
        app.repository.saveProgress(
            bookId, chapterIndex, blockIndex, positionMs,
        )
    }

    private fun currentTextBlockIndex(): Int = player.currentMediaItem?.mediaId
        ?.substringAfterLast(':')?.toIntOrNull()
        ?: player.currentMediaItemIndex.coerceAtLeast(0)

    private fun showPreparingNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(PREPARING_CHANNEL, "听书播放", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, PREPARING_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在准备离线语音…")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(PREPARING_NOTIFICATION_ID, notification)
    }

    private fun showErrorNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(
            PREPARING_NOTIFICATION_ID,
            NotificationCompat.Builder(this, PREPARING_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("无法开始播放")
                .setContentText(message)
                .setAutoCancel(true)
                .build(),
        )
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        stopAfterChapter = minutes == -1
        if (minutes <= 0) return
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            val original = player.volume
            repeat(10) { step ->
                player.volume = original * (9 - step) / 10f
                delay(250)
            }
            player.pause()
            player.volume = original
        }
    }

    private fun resetVoiceEngine() {
        synthesisJob?.cancel()
        synthesisJob = null
        synthesisComplete = false
        player.stop()
        player.clearMediaItems()
        // Cached audio is deliberately kept: it is keyed per engine install time and cache
        // epoch, so stale files are simply not reused and are evicted by the cleanup worker.
        engineResetNeeded = true
    }

    override fun onDestroy() {
        if (::player.isInitialized && currentBookId.isNotBlank() && player.mediaItemCount > 0) {
            val bookId = currentBookId
            val chapter = currentChapterIndex
            val block = currentTextBlockIndex()
            val position = player.currentPosition.coerceAtLeast(0)
            runCatching { runBlocking(Dispatchers.IO) { persistProgress(bookId, chapter, block, position) } }
        }
        synthesisJob?.cancel()
        sleepJob?.cancel()
        synthesizer.shutdown()
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val ACTION_PLAY_CHAPTER = "com.pray.booklisten.PLAY_CHAPTER"
        const val ACTION_SET_SLEEP_TIMER = "com.pray.booklisten.SET_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "com.pray.booklisten.CANCEL_SLEEP_TIMER"
        const val ACTION_RESET_VOICE_ENGINE = "com.pray.booklisten.RESET_VOICE_ENGINE"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAPTER_INDEX = "chapter_index"
        const val EXTRA_BLOCK_INDEX = "block_index"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_VOICE_NAME = "voice_name"
        const val EXTRA_TTS_ENGINE = "tts_engine"
        const val EXTRA_TTS_CACHE_EPOCH = "tts_cache_epoch"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_MINUTES = "minutes"
        private const val PREPARING_CHANNEL = "booklisten_playback"
        private const val PREPARING_NOTIFICATION_ID = 2401
        private const val TTS_CACHE_VERSION = 4
    }
}
