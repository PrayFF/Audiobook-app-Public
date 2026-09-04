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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

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
    private val _engines = MutableStateFlow<List<TtsEngineOption>>(emptyList())
    val engines = _engines.asStateFlow()
    private val _voicePackStates = MutableStateFlow<List<VoicePackUiState>>(emptyList())
    val voicePackStates = _voicePackStates.asStateFlow()
    private val _voicePackDownload = MutableStateFlow<VoicePackDownload?>(null)
    val voicePackDownload = _voicePackDownload.asStateFlow()
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
        refreshVoicePackStates()
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
        val sherpaEngine = _engines.value.firstOrNull { isSherpaEngine(it) }
        if (!autoSelectedNeuralEngine && settings.value.ttsEngine.isBlank() && sherpaEngine != null) {
            autoSelectedNeuralEngine = true
            setEngine(sherpaEngine.packageName)
        } else if (settings.value.ttsEngine.isNotBlank() &&
            _engines.value.none { it.packageName == settings.value.ttsEngine }
        ) {
            _message.value = "之前选择的语音引擎未安装，请重新选择"
        }
        refreshVoicePackStates()
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
            var chapter = repository.getChapter(book.id, index) ?: return@launch
            // A placeholder chapter (no body yet) needs to be fetched on demand before playback.
            if (chapter.content.isBlank() && chapter.sourceUrl != null) {
                _playbackPreparing.value = true
                _message.value = "正在下载《${chapter.title}》正文…"
                chapter = runCatching { repository.fetchChapterContent(book.id, index) }
                    .getOrElse {
                        _playbackPreparing.value = false
                        _message.value = it.message ?: "章节正文下载失败"
                        return@launch
                    } ?: return@launch
            }
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
            putExtra(PlaybackService.EXTRA_TTS_CACHE_EPOCH, settings.value.ttsCacheEpoch)
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
        refreshVoicePackStates()
        viewModelScope.launch {
            delay(600)
            _message.value = if (_engines.value.any(::isSherpaEngine)) {
                "已识别到离线语音引擎，可在语音引擎列表中选择"
            } else {
                "仍未发现离线语音引擎，请确认语音包已完成安装"
            }
        }
    }

    // ---------------------------------------------------------------------
    // 离线语音包：所有语音包共用同一个引擎包名，安装新的会覆盖旧的；
    // 已下载的 APK 安装文件保留在本应用目录，可随时重装或手动删除，切换时不会自动删除。
    // ---------------------------------------------------------------------

    fun selectVoicePack(option: VoicePackOption) {
        val file = voicePackFile(option)
        if (file != null && file.isFile) installVoicePack(option) else downloadVoicePack(option)
    }

    fun installVoicePack(option: VoicePackOption) {
        val context = getApplication<Application>()
        val file = voicePackFile(option)
        if (file == null || !file.isFile) {
            _message.value = "当前手机处理器暂不支持此离线语音包"
            return
        }
        viewModelScope.launch {
            appSettings.setInstalledVoicePack(option.model)
            refreshVoicePackStates()
        }
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context, "com.pray.booklisten.fileprovider", file,
            )
        }.getOrNull() ?: Uri.fromFile(file)
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.isSuccess
        _message.value = if (opened) {
            "请在系统页面确认安装${option.label}；完成后返回本应用"
        } else {
            "无法打开安装页面，请在设置中允许本应用安装未知应用后重试"
        }
    }

    fun deleteVoicePack(option: VoicePackOption) {
        val file = voicePackFile(option)
        if (file == null || !file.isFile) {
            refreshVoicePackStates()
            return
        }
        if (file.delete()) {
            _message.value = "已删除语音包安装文件：${option.label}"
        } else {
            _message.value = "删除失败，请稍后重试"
        }
        refreshVoicePackStates()
    }

    private fun voicePackFile(option: VoicePackOption): File? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in SHERPA_APK_URLS } ?: return null
        return getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve("sherpa-onnx-${option.model}-$abi.apk")
    }

    fun refreshVoicePackStates() {
        viewModelScope.launch {
            val states = withContext(Dispatchers.IO) {
                val installedModel = detectInstalledVoicePackModel()
                val hintModel = settings.value.installedVoicePack
                val download = _voicePackDownload.value
                VOICE_PACK_OPTIONS.map { option ->
                    val downloading = download?.model == option.model
                    VoicePackUiState(
                        option = option,
                        downloaded = voicePackFile(option)?.isFile == true,
                        installed = installedModel == option.model,
                        lastInstalled = hintModel == option.model && installedModel != option.model,
                        downloading = downloading,
                        progressPercent = if (downloading) download.percent else 0,
                    )
                }
            }
            _voicePackStates.value = states
        }
    }

    // All voice-pack APKs share one package name, so identify the currently installed
    // model by looking for its asset folder inside the installed engine APK.
    private fun detectInstalledVoicePackModel(): String? {
        val engine = _engines.value.firstOrNull(::isSherpaEngine)?.packageName ?: return null
        val apkPath = runCatching {
            getApplication<Application>().packageManager.getApplicationInfo(engine, 0).sourceDir
        }.getOrNull() ?: return null
        return runCatching {
            ZipFile(apkPath).use { zip ->
                val assetDirs = zip.entries().asSequence()
                    .filter { it.name.startsWith("assets/") && it.name.length > "assets/".length }
                    .map { it.name.removePrefix("assets/").substringBefore('/').lowercase() }
                    .toSet()
                VOICE_PACK_OPTIONS.firstOrNull { option ->
                    assetDirs.any { it.contains(option.assetKey) }
                }?.model
            }
        }.getOrNull()
    }

    private fun downloadVoicePack(option: VoicePackOption) {
        if (voiceDownloadId != null) {
            _message.value = "语音包正在下载，请稍候"
            return
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in SHERPA_APK_URLS } ?: run {
            _message.value = "当前手机处理器暂不支持此离线语音包"
            return
        }
        val context = getApplication<Application>()
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "sherpa-onnx-${option.model}-$abi.apk"
        // Hugging Face itself is frequently unreachable on mainland-mobile networks.  This
        // HTTPS mirror serves the same public Sherpa APK and redirects to the release CDN.
        val url = "https://hf-mirror.com/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-engine-new/1.13.3/" +
            "sherpa-onnx-1.13.3-$abi-zho-tts-engine-${option.model}.apk?download=true"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("正在下载${option.label}")
            .setDescription("约 ${option.sizeMb} MB，下载后需要确认安装")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val id = runCatching { manager.enqueue(request) }.getOrElse {
            _message.value = "无法开始下载：${it.message ?: "请检查网络"}"
            return
        }
        voiceDownloadId = id
        _voicePackDownload.value = VoicePackDownload(option.model, 0)
        refreshVoicePackStates()
        _message.value = "已开始下载${option.label}（约 ${option.sizeMb} MB）"
        viewModelScope.launch {
            while (voiceDownloadId == id) {
                delay(1_000)
                manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use
                    val statusColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    when (cursor.getInt(statusColumn)) {
                        DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                            val total = cursor.getLong(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            )
                            val done = cursor.getLong(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            )
                            if (total > 0) {
                                _voicePackDownload.value =
                                    VoicePackDownload(option.model, (done * 100 / total).toInt())
                            }
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            voiceDownloadId = null
                            val percent = _voicePackDownload.value?.percent ?: 100
                            _voicePackDownload.value = null
                            val apk = manager.getUriForDownloadedFile(id)
                            if (apk == null) {
                                _message.value = "下载完成，但无法打开安装包"
                            } else {
                                appSettings.setInstalledVoicePack(option.model)
                                val opened = runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(apk, "application/vnd.android.package-archive")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    })
                                }.isSuccess
                                _message.value = when {
                                    !opened -> "下载完成。请在设置中允许本应用安装未知应用后重试安装"
                                    else -> "下载完成（$percent%），请在系统页面确认安装${option.label}"
                                }
                            }
                            refreshVoicePackStates()
                        }
                        DownloadManager.STATUS_FAILED -> {
                            voiceDownloadId = null
                            _voicePackDownload.value = null
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            _message.value = "语音包下载失败：${downloadFailureReason(reason)}"
                            refreshVoicePackStates()
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
            _message.value = "请在引擎设置中选择说话人（Speaker ID）；返回后重新播放本章即可生效"
        } else {
            _message.value = "无法打开离线引擎设置，请确认语音包已完成安装"
        }
    }

    fun onHostResume() {
        refreshVoiceEngines()
        refreshVoicePackStates()
        if (waitingForEngineSettings) {
            waitingForEngineSettings = false
            // The speaker may have changed inside the engine app; bump the cache epoch so
            // stale audio is regenerated instead of being auto-deleted.
            viewModelScope.launch { appSettings.bumpTtsCacheEpoch() }
            resetVoiceEngine()
            _message.value = "音色设置已刷新，请重新点击播放"
        }
    }

    private fun resetVoiceEngine() {
        _playbackPreparing.value = false
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

    // ------------------------------------------------------------------
    // 收藏夹（书单）与书架排序
    // ------------------------------------------------------------------

    val collections = repository.observeCollections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createCollection(name: String) = launchBusy("收藏夹已创建") {
        repository.createCollection(name)
    }

    fun renameCollection(collectionId: String, newName: String) = launchBusy("收藏夹已重命名") {
        repository.renameCollection(collectionId, newName)
    }

    fun deleteCollection(collectionId: String) = launchBusy("收藏夹已删除（书籍保留在书架）") {
        repository.deleteCollection(collectionId)
    }

    fun moveBookToCollection(bookId: String, collectionId: String?) = launchBusy(
        if (collectionId == null) "已移出收藏夹" else "已加入收藏夹",
    ) {
        repository.moveBookToCollection(bookId, collectionId)
    }

    fun moveBook(visibleBooks: List<BookEntity>, bookId: String, up: Boolean) {
        viewModelScope.launch { repository.moveBook(visibleBooks, bookId, up) }
    }

    fun renameBook(book: BookEntity, newTitle: String) = launchBusy("书名已更新") {
        repository.renameBook(book.id, newTitle)
        if (_selectedBook.value?.id == book.id) {
            _selectedBook.value = book.copy(title = newTitle.trim())
        }
    }

    fun renameChapter(chapterIndex: Int, newTitle: String) = launchBusy("章节名已更新") {
        val book = _selectedBook.value ?: return@launchBusy
        repository.renameChapter(book.id, chapterIndex, newTitle)
        _selectedChapter.value = _selectedChapter.value?.takeIf { it.chapterIndex == chapterIndex }
            ?.copy(title = newTitle.trim())
    }

    fun renameChapters(titles: Map<Int, String>) = launchBusy("章节名已批量更新") {
        val book = _selectedBook.value ?: return@launchBusy
        repository.renameChapters(book.id, titles)
    }

    fun resetChapterTitle(chapterIndex: Int) = launchBusy("已恢复默认章节名") {
        val book = _selectedBook.value ?: return@launchBusy
        repository.resetChapterTitle(book.id, chapterIndex)
        _selectedChapter.value = repository.getChapter(book.id, chapterIndex) ?: _selectedChapter.value
    }

    fun resetAllChapterTitles() = launchBusy("已恢复全部默认章节名") {
        val book = _selectedBook.value ?: return@launchBusy
        repository.resetAllChapterTitles(book.id)
    }

    fun setLibraryLayout(grid: Boolean) {
        viewModelScope.launch { appSettings.setLibraryLayout(if (grid) "grid" else "list") }
    }

    private fun downloadFailureReason(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载存储不可用"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "同名下载文件异常，请重试"
        DownloadManager.ERROR_CANNOT_RESUME -> "网络中断，无法续传"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "下载服务器或网络连接异常"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "下载地址重定向过多"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "下载服务器拒绝了请求"
        else -> "请检查网络、存储空间后重试（错误码 $reason）"
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

data class VoicePackOption(
    val model: String,
    val label: String,
    val sizeMb: Int,
    // Distinctive fragment of the model's asset folder inside the engine APK,
    // used to detect which pack is currently installed.
    val assetKey: String,
)

data class VoicePackUiState(
    val option: VoicePackOption,
    val downloaded: Boolean,
    val installed: Boolean,
    val lastInstalled: Boolean,
    val downloading: Boolean,
    val progressPercent: Int,
)

data class VoicePackDownload(val model: String, val percent: Int)

// Curated Chinese voice packs.  All of them are verified to exist on the
// hf-mirror.com release CDN for every supported ABI at sherpa-onnx 1.13.3.
val VOICE_PACK_OPTIONS = listOf(
    VoicePackOption(
        model = "vits-zh-hf-fanchen-wnj",
        label = "中文男声（单说话人）",
        sizeMb = 131,
        assetKey = "fanchen",
    ),
    VoicePackOption(
        model = "sherpa-onnx-vits-zh-ll",
        label = "中文女声 · 多说话人（5 种）",
        sizeMb = 130,
        assetKey = "vits-zh-ll",
    ),
    VoicePackOption(
        model = "vits-piper-zh_CN-huayan-medium",
        label = "中文女声 · huayan",
        sizeMb = 83,
        assetKey = "huayan",
    ),
    VoicePackOption(
        model = "vits-piper-zh_CN-chaowen-medium",
        label = "中文男声 · chaowen",
        sizeMb = 75,
        assetKey = "chaowen",
    ),
    VoicePackOption(
        model = "vits-icefall-zh-aishell3",
        label = "中文多说话人 · aishell3（最省空间）",
        sizeMb = 50,
        assetKey = "aishell3",
    ),
    VoicePackOption(
        model = "vits-melo-tts-zh_en",
        label = "中英双语 · 多说话人",
        sizeMb = 176,
        assetKey = "melo-tts",
    ),
    VoicePackOption(
        model = "matcha-icefall-zh-baker",
        label = "中文女声 · baker（matcha）",
        sizeMb = 138,
        assetKey = "baker",
    ),
)

private fun isSherpaEngine(engine: TtsEngineOption): Boolean =
    engine.packageName.contains("sherpa", ignoreCase = true) ||
        engine.label.contains("sherpa", ignoreCase = true)

private val SHERPA_APK_URLS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
