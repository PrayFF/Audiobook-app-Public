package com.pray.booklisten

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pray.booklisten.data.BookEntity
import com.pray.booklisten.data.ChapterEntity
import com.pray.booklisten.data.ChapterProgress
import com.pray.booklisten.data.ExtractedWebPage
import com.pray.booklisten.data.TextChunker
import com.pray.booklisten.data.SourceType
import com.pray.booklisten.playback.PlaybackConnection
import com.pray.booklisten.playback.PlaybackEvents
import com.pray.booklisten.playback.PlaybackService
import com.pray.booklisten.settings.AppSettings
import com.pray.booklisten.settings.ReaderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BookListenApplication
    private val repository = app.repository
    private val appSettings = AppSettings(application)
    val books = repository.observeBooks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = appSettings.values.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())
    val playback = PlaybackConnection(application, viewModelScope)

    private val _selectedBook = MutableStateFlow<BookEntity?>(null)
    val selectedBook: StateFlow<BookEntity?> = _selectedBook.asStateFlow()
    val chapters = _selectedBook.flatMapLatest { book ->
        if (book == null) flowOf(emptyList()) else repository.observeChapters(book.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _selectedChapter = MutableStateFlow<ChapterEntity?>(null)
    val selectedChapter = _selectedChapter.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _playbackPreparing = MutableStateFlow(false)
    val playbackPreparing = _playbackPreparing.asStateFlow()
    private val _voices = MutableStateFlow<List<String>>(emptyList())
    val voices = _voices.asStateFlow()
    private val _engines = MutableStateFlow<List<TtsEngineOption>>(emptyList())
    val engines = _engines.asStateFlow()
    private var voiceProbe: TextToSpeech? = null
    private var nextPageLoading = false
    private var voiceDownloadId: Long? = null
    private var autoSelectedNeuralEngine = false
    private var waitingForEngineSettings = false

    init {
        viewModelScope.launch {
            playback.state.collect { state ->
                if (state.bookId.isNotBlank() && state.durationMs > 0) _playbackPreparing.value = false
                val book = _selectedBook.value
                if (state.bookId == book?.id && state.chapterIndex != _selectedChapter.value?.chapterIndex) {
                    _selectedChapter.value = repository.getChapter(book.id, state.chapterIndex)
                }
            }
        }
        refreshVoiceEngines()
        viewModelScope.launch {
            PlaybackEvents.messages.collect {
                _playbackPreparing.value = false
                _message.value = it
            }
        }
    }

    fun refreshVoiceEngines() {
        voiceProbe?.shutdown()
        val selectedEngine = settings.value.ttsEngine
        voiceProbe = if (selectedEngine.isBlank()) TextToSpeech(getApplication()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                updateVoiceCatalog()
            }
        } else TextToSpeech(getApplication(), { status ->
            if (status == TextToSpeech.SUCCESS) updateVoiceCatalog()
        }, selectedEngine)
    }

    private fun updateVoiceCatalog() {
        val probe = voiceProbe ?: return
        probe.language = Locale.SIMPLIFIED_CHINESE
        _engines.value = probe.engines.orEmpty().map { TtsEngineOption(it.label, it.name) }
        _voices.value = probe.voices.orEmpty()
            .filter { it.locale.language in setOf("zh", "en") }
            .sortedWith(compareBy({ it.locale.language != "zh" }, { it.name }))
            .map { it.name }
        val sherpaEngine = _engines.value.firstOrNull { isSherpaEngine(it) }
        if (!autoSelectedNeuralEngine && settings.value.ttsEngine.isBlank() && sherpaEngine != null) {
            autoSelectedNeuralEngine = true
            setEngine(sherpaEngine.packageName)
        } else if (settings.value.ttsEngine.isNotBlank() &&
            _engines.value.none { it.packageName == settings.value.ttsEngine }
        ) {
            _message.value = "之前选择的语音引擎未安装，请重新选择"
        }
    }

    fun clearMessage() { _message.value = null }

    fun importFile(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        launchBusy("书籍已导入") { repository.importFile(uri) }
    }

    fun showMessage(value: String) { _message.value = value }

    fun importWebPage(page: ExtractedWebPage, onDone: (String) -> Unit = {}) = launchBusy("网页正文已加入书架") {
        repository.importWebPage(page).also(onDone)
    }

    fun openBook(book: BookEntity, chapterIndex: Int = book.currentChapterIndex) {
        _selectedBook.value = book
        viewModelScope.launch {
            if (book.sourceType == SourceType.WEB) repository.cleanStoredWebBook(book.id)
            _selectedChapter.value = repository.getChapter(book.id, chapterIndex)
        }
    }

    fun selectChapter(index: Int, autoPlay: Boolean = true) {
        val book = _selectedBook.value ?: return
        viewModelScope.launch {
            val chapter = repository.getChapter(book.id, index) ?: return@launch
            _selectedChapter.value = chapter
            if (autoPlay) playChapter(book, chapter, 0, 0)
        }
    }

    fun resumeSelected() {
        val book = _selectedBook.value ?: return
        val chapter = _selectedChapter.value ?: return
        playChapter(book, chapter, book.currentBlockIndex, book.currentPositionMs)
    }

    private fun playChapter(book: BookEntity, chapter: ChapterEntity, block: Int, position: Long) {
        if (_playbackPreparing.value) return
        _playbackPreparing.value = true
        val context = getApplication<Application>()
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY_CHAPTER
            putExtra(PlaybackService.EXTRA_BOOK_ID, book.id)
            putExtra(PlaybackService.EXTRA_CHAPTER_INDEX, chapter.chapterIndex)
            putExtra(PlaybackService.EXTRA_BLOCK_INDEX, block)
            putExtra(PlaybackService.EXTRA_POSITION_MS, position)
            putExtra(PlaybackService.EXTRA_VOICE_NAME, settings.value.voiceName)
            putExtra(PlaybackService.EXTRA_TTS_ENGINE, settings.value.ttsEngine)
            putExtra(PlaybackService.EXTRA_SPEED, settings.value.speed)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun previousChapter() {
        val current = _selectedChapter.value ?: return
        if (current.chapterIndex > 0) selectChapter(current.chapterIndex - 1)
    }

    fun nextChapter() {
        val current = _selectedChapter.value ?: return
        val list = chapters.value
        if (current.chapterIndex + 1 < list.size) selectChapter(current.chapterIndex + 1)
        else ensureNextWebChapter(playAfter = true)
    }

    fun ensureNextWebChapter(playAfter: Boolean = false) {
        val book = _selectedBook.value ?: return
        val current = _selectedChapter.value ?: return
        if (current.nextUrl == null || nextPageLoading) return
        nextPageLoading = true
        viewModelScope.launch {
            runCatching { repository.appendNextWebChapter(book.id) }
                .onSuccess { appended ->
                    if (appended && playAfter) selectChapter(current.chapterIndex + 1)
                }
                .onFailure { _message.value = it.message ?: "下一页提取失败" }
            nextPageLoading = false
        }
    }

    fun saveCurrentProgress() {
        val book = _selectedBook.value ?: return
        val state = playback.state.value
        // Do not overwrite a saved resume point with 0 when a book was only opened, not played.
        if (state.bookId != book.id) return
        viewModelScope.launch {
            repository.saveProgress(book.id, state.chapterIndex, state.blockIndex, state.positionMs)
        }
    }

    fun seekWithinChapter(fraction: Float) {
        val book = _selectedBook.value ?: return
        val chapter = _selectedChapter.value ?: return
        val blocks = TextChunker.chunk(chapter.content)
        val target = ChapterProgress.target(blocks, fraction)
        if (!playback.seekToBlock(target.blockIndex, target.fractionInBlock)) {
            _playbackPreparing.value = false
            playChapter(book, chapter, target.blockIndex, 0)
        }
    }

    fun setSpeed(speed: Float) {
        playback.setSpeed(speed)
        viewModelScope.launch { appSettings.setSpeed(speed) }
    }

    fun setVoice(voice: String) {
        viewModelScope.launch {
            appSettings.setVoice(voice)
            _message.value = "音色将在重新播放本章后生效"
        }
    }

    fun setEngine(packageName: String) {
        viewModelScope.launch {
            appSettings.setEngine(packageName)
            appSettings.setVoice("")
            delay(100)
            refreshVoiceEngines()
            _message.value = "语音引擎已切换，请重新播放本章"
        }
    }

    fun rescanVoiceEngines() {
        refreshVoiceEngines()
        viewModelScope.launch {
            delay(600)
            _message.value = if (_engines.value.any(::isSherpaEngine)) {
                "已识别到免费神经音色，正在自动选择"
            } else {
                "仍未发现神经音色，请确认 TTS Engine 已完成安装"
            }
        }
    }

    fun downloadNeuralVoice() {
        downloadVoicePack("sherpa-onnx-vits-zh-ll", "中文多音色（5 种）", 137)
    }

    fun downloadMaleVoice() {
        downloadVoicePack("vits-zh-hf-fanchen-wnj", "中文男声", 133)
    }

    private fun downloadVoicePack(model: String, label: String, sizeMb: Int) {
        if (voiceDownloadId != null) {
            _message.value = "音色正在下载，请稍候"
            return
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in SHERPA_APK_URLS } ?: run {
            _message.value = "当前手机处理器暂不支持此离线音色"
            return
        }
        val context = getApplication<Application>()
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "sherpa-onnx-$model-$abi.apk"
        val url = "https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-engine-new/1.13.3/" +
            "sherpa-onnx-1.13.3-$abi-zho-tts-engine-$model.apk?download=true"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("正在下载$label")
            .setDescription("约 $sizeMb MB，下载后需要确认安装")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val id = runCatching { manager.enqueue(request) }.getOrElse {
            _message.value = "无法开始下载：${it.message ?: "请检查网络"}"
            return
        }
        voiceDownloadId = id
        _message.value = "已开始下载$label（约 $sizeMb MB）"
        viewModelScope.launch {
            while (voiceDownloadId == id) {
                delay(1_000)
                manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use
                    when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            voiceDownloadId = null
                            clearTtsAudioCache()
                            val apk = manager.getUriForDownloadedFile(id)
                            if (apk == null) {
                                _message.value = "下载完成，但无法打开安装包"
                            } else {
                                _message.value = "下载完成，请在系统页面确认安装"
                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(apk, "application/vnd.android.package-archive")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            }
                        }
                        DownloadManager.STATUS_FAILED -> {
                            voiceDownloadId = null
                            _message.value = "音色下载失败，请检查网络后重试"
                        }
                    }
                }
            }
        }
    }

    fun openEngineVoiceSettings() {
        val installedSherpa = _engines.value.firstOrNull(::isSherpaEngine)?.packageName
        val selectedEngine = settings.value.ttsEngine
        val packageName = selectedEngine.takeIf { it.contains("sherpa", ignoreCase = true) }
            ?: installedSherpa
            ?: selectedEngine
        if (packageName.isBlank()) {
            _message.value = "请先选择一个语音引擎"
            return
        }
        val context = getApplication<Application>()
        resetVoiceEngine()
        if (packageName != selectedEngine && installedSherpa != null) setEngine(installedSherpa)

        val candidates = buildList {
            if (packageName.contains("sherpa", ignoreCase = true)) {
                add(Intent("android.speech.tts.engine.CONFIGURE_ENGINE").setClassName(
                    packageName,
                    "com.k2fsa.sherpa.onnx.tts.engine.MainActivity",
                ))
                add(Intent().setClassName(packageName, "$packageName.MainActivity"))
            }
            add(Intent("android.speech.tts.engine.CONFIGURE_ENGINE").setPackage(packageName))
            context.packageManager.getLaunchIntentForPackage(packageName)?.let(::add)
        }
        val opened = candidates.any { candidate ->
            runCatching {
                candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(candidate)
            }.isSuccess
        }
        if (opened) {
            waitingForEngineSettings = true
            _message.value = "请选择 Speaker ID；返回后重新播放本章即可生效"
        } else {
            _message.value = "无法打开 Sherpa 设置，请确认 137 MB TTS Engine 已完成安装"
        }
    }

    private fun clearTtsAudioCache() {
        val application = getApplication<Application>()
        application.cacheDir.resolve("tts").deleteRecursively()
        application.filesDir.resolve("tts-audio").deleteRecursively()
    }

    fun onHostResume() {
        refreshVoiceEngines()
        if (waitingForEngineSettings) {
            waitingForEngineSettings = false
            resetVoiceEngine()
            _message.value = "音色设置已刷新，请重新点击播放"
        }
    }

    private fun resetVoiceEngine() {
        _playbackPreparing.value = false
        clearTtsAudioCache()
        val context = getApplication<Application>()
        context.startService(Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_RESET_VOICE_ENGINE
        })
    }

    fun setSleepTimer(minutes: Int) {
        val context = getApplication<Application>()
        context.startService(Intent(context, PlaybackService::class.java).apply {
            action = if (minutes != 0) PlaybackService.ACTION_SET_SLEEP_TIMER else PlaybackService.ACTION_CANCEL_SLEEP_TIMER
            putExtra(PlaybackService.EXTRA_MINUTES, minutes)
        })
        _message.value = when {
            minutes == -1 -> "将在本章结束后停止播放"
            minutes > 0 -> "$minutes 分钟后停止播放"
            else -> "已取消定时"
        }
    }

    fun delete(book: BookEntity) = viewModelScope.launch { repository.delete(book) }

    fun renameBook(book: BookEntity, newTitle: String) = launchBusy("书名已更新") {
        repository.renameBook(book.id, newTitle)
        if (_selectedBook.value?.id == book.id) {
            _selectedBook.value = book.copy(title = newTitle.trim())
        }
    }

    fun textBlocks(): List<String> = _selectedChapter.value?.let { TextChunker.chunk(it.content) }.orEmpty()

    private fun launchBusy(successMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { block() }
                .onSuccess { _message.value = successMessage }
                .onFailure { _message.value = it.message ?: "操作失败" }
            _busy.value = false
        }
    }

    override fun onCleared() {
        saveCurrentProgress()
        voiceProbe?.shutdown()
        playback.release()
        super.onCleared()
    }
}

data class TtsEngineOption(val label: String, val packageName: String)

private fun isSherpaEngine(engine: TtsEngineOption): Boolean =
    engine.packageName.contains("sherpa", ignoreCase = true) ||
        engine.label.contains("sherpa", ignoreCase = true)

private val SHERPA_APK_URLS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
