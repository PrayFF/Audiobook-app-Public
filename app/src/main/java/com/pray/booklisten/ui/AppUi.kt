package com.pray.booklisten.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pray.booklisten.MainViewModel
import com.pray.booklisten.data.BookEntity
import com.pray.booklisten.data.ChapterProgress
import com.pray.booklisten.data.ExtractedWebPage
import com.pray.booklisten.data.SourceType
import com.pray.booklisten.data.WebBookTitleResolver
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONTokener

private enum class Screen { LIBRARY, BROWSER, PLAYER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookListenApp(viewModel: MainViewModel, initialUrl: String?) {
    var screen by rememberSaveable { mutableStateOf(if (initialUrl != null) Screen.BROWSER else Screen.LIBRARY) }
    var browserUrl by rememberSaveable { mutableStateOf(initialUrl.orEmpty()) }
    val message by viewModel.message.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    BackHandler(enabled = screen != Screen.LIBRARY) {
        if (screen == Screen.PLAYER) viewModel.saveCurrentProgress()
        screen = Screen.LIBRARY
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (screen) {
                            Screen.LIBRARY -> "我的听书"
                            Screen.BROWSER -> "联网找书"
                            Screen.PLAYER -> "正在听"
                        }
                    )
                },
                navigationIcon = {
                    if (screen != Screen.LIBRARY) TextButton(onClick = { screen = Screen.LIBRARY }) { Text("返回") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.LIBRARY -> LibraryScreen(
                    viewModel = viewModel,
                    onOpenBook = {
                        viewModel.openBook(it)
                        screen = Screen.PLAYER
                    },
                    onBrowse = {
                        browserUrl = ""
                        screen = Screen.BROWSER
                    },
                )
                Screen.BROWSER -> BrowserScreen(
                    initialUrl = browserUrl,
                    viewModel = viewModel,
                    onImported = { screen = Screen.LIBRARY },
                )
                Screen.PLAYER -> PlayerScreen(viewModel)
            }
            if (busy) Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun LibraryScreen(
    viewModel: MainViewModel,
    onOpenBook: (BookEntity) -> Unit,
    onBrowse: () -> Unit,
) {
    val books by viewModel.books.collectAsState()
    var renameTarget by remember { mutableStateOf<BookEntity?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<BookEntity?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importFile)
    }
    Box(Modifier.fillMaxSize()) {
        if (books.isEmpty()) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("书架还是空的", style = MaterialTheme.typography.headlineSmall)
                Text("导入 TXT / EPUB，或从网页提取正文。所有数据只保存在本机。")
                Button(onClick = { launcher.launch(arrayOf("*/*")) }) {
                    Text("导入本地书籍")
                }
                OutlinedButton(onClick = onBrowse) { Text("联网找书") }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 8.dp, 12.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(books, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        onClick = { onOpenBook(book) },
                        onRename = {
                            renameTarget = book
                            renameText = book.title
                        },
                        onDelete = { deleteTarget = book },
                    )
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FloatingActionButton(onClick = onBrowse) { Text("搜") }
            FloatingActionButton(onClick = { launcher.launch(arrayOf("*/*")) }) {
                Text("导入")
            }
        }
    }

    renameTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名小说") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { if (it.length <= 100) renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("小说名称") },
                    supportingText = { Text("${renameText.length}/100") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        viewModel.renameBook(book, renameText)
                        renameTarget = null
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    deleteTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除小说") },
            text = { Text("确定要删除《${book.title}》吗？删除后将无法恢复，播放进度也会一并清除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(book)
                        deleteTarget = null
                    },
                ) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun BookCard(
    book: BookEntity,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(if (book.sourceType == SourceType.WEB) "网" else "书", fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${book.totalChapters} 章 · ${book.sourceType.name}", style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            TextButton(onClick = onRename) { Text("重命名") }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserScreen(initialUrl: String, viewModel: MainViewModel, onImported: () -> Unit) {
    var input by rememberSaveable { mutableStateOf(initialUrl) }
    var requestedUrl by rememberSaveable { mutableStateOf("") }
    var currentUrl by rememberSaveable { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var extracting by remember { mutableStateOf(false) }
    var pageLoading by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun normalizedInput(): String? {
        val raw = input.trim()
        if (raw.isEmpty()) {
            viewModel.showMessage("请先输入书名或粘贴网页地址")
            return null
        }
        val target = when {
            raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("http://", ignoreCase = true) -> "https://" + raw.substringAfter("://")
            else -> "https://$raw"
        }
        if (Uri.parse(target).host.isNullOrBlank()) {
            viewModel.showMessage("网址格式不正确，请输入完整网址，例如 https://example.com/book")
            return null
        }
        return target
    }

    fun openInput() {
        val target = normalizedInput() ?: return
        requestedUrl = target
        currentUrl = target
        webView?.loadUrl(target)
    }

    if (requestedUrl.isBlank()) {
        Column(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("输入书籍网页网址", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("请先在浏览器中找到书籍正文页，再把网址复制到这里。应用不会自动登录或绕过付费页面。")
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("网页网址") },
                placeholder = { Text("https://example.com/book/chapter-1") },
            )
            Button(onClick = ::openInput, modifier = Modifier.fillMaxWidth()) { Text("打开这个网址") }
            Text(
                "如果网址以 http:// 开头，应用会优先尝试对应的 https:// 地址。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("网址（可修改后重新打开）") },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::openInput, modifier = Modifier.weight(1f)) { Text("打开网址") }
                    OutlinedButton(onClick = {
                        webView?.stopLoading()
                        webView?.destroy()
                        webView = null
                        requestedUrl = ""
                        currentUrl = ""
                    }) { Text("重新输入") }
                }
                Button(
                    enabled = currentUrl.startsWith("https://") && !extracting && !pageLoading,
                    onClick = {
                        extracting = true
                        val activeWebView = webView
                        if (activeWebView == null) {
                            extracting = false
                            viewModel.showMessage("浏览器尚未准备好")
                        } else activeWebView.evaluateJavascript(EXTRACT_SCRIPT) { result ->
                            runCatching {
                                val decoded = JSONTokener(result).nextValue() as? String ?: result
                                val json = JSONObject(decoded)
                                val content = json.optString("content").trim()
                                require(content.length >= 80) { "当前页面没有识别到足够的正文" }
                                val chapterTitle = json.optString("title").ifBlank { "网页章节" }
                                val candidates = json.optJSONArray("bookTitleCandidates")?.let { array ->
                                    List(array.length()) { array.optString(it) }
                                }.orEmpty()
                                ExtractedWebPage(
                                    title = chapterTitle,
                                    content = content,
                                    url = currentUrl,
                                    nextUrl = json.optString("nextUrl").takeIf { it.startsWith("https://") },
                                    bookTitle = WebBookTitleResolver.resolve(
                                        chapterTitle = chapterTitle,
                                        documentTitle = json.optString("documentTitle"),
                                        candidates = candidates,
                                        url = currentUrl,
                                    ),
                                    catalogUrl = json.optString("catalogUrl").takeIf { it.startsWith("https://") },
                                )
                            }.onSuccess { page ->
                                viewModel.importWebPage(page) { onImported() }
                            }.onFailure {
                                viewModel.showMessage(it.message ?: "正文提取失败，请确认当前打开的是正文页面")
                            }
                            extracting = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (extracting) "正在提取…" else "提取当前正文并加入书架") }
                Text(
                    if (pageLoading) "网页加载中…" else "当前页面：$currentUrl",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = {
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                pageLoading = true
                                currentUrl = url.orEmpty()
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageLoading = false
                                currentUrl = url.orEmpty()
                            }
                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                if (request?.isForMainFrame == true) {
                                    pageLoading = false
                                    viewModel.showMessage("网页打开失败：${error?.description ?: "请检查网址和网络"}")
                                }
                            }
                        }
                        webView = this
                        loadUrl(requestedUrl)
                    }
                },
                update = { webView = it },
            )
        }
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }
}

@Composable
private fun PlayerScreen(viewModel: MainViewModel) {
    val book by viewModel.selectedBook.collectAsState()
    val chapter by viewModel.selectedChapter.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val playback by viewModel.playback.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val voicePackStates by viewModel.voicePackStates.collectAsState()
    val voicePackDownload by viewModel.voicePackDownload.collectAsState()
    val engines by viewModel.engines.collectAsState()
    val playbackPreparing by viewModel.playbackPreparing.collectAsState()
    val blocks = remember(chapter?.content) { viewModel.textBlocks() }
    val activeBlockIndex = when {
        playback.bookId == book?.id && playback.chapterIndex == chapter?.chapterIndex -> playback.blockIndex
        chapter?.chapterIndex == book?.currentChapterIndex -> book?.currentBlockIndex ?: 0
        else -> -1
    }
    val listState = rememberLazyListState()
    var catalogDialog by remember { mutableStateOf(false) }
    var editingCatalog by remember { mutableStateOf(false) }
    var renameChapterTarget by remember { mutableStateOf<Int?>(null) }
    var renameChapterText by remember { mutableStateOf("") }
    var batchRemoveFixed by remember { mutableStateOf(false) }
    var batchFixedText by remember { mutableStateOf("") }
    var batchTrimDialog by remember { mutableStateOf(false) }
    var batchTrimMode by remember { mutableStateOf("front") }
    var batchTrimCount by remember { mutableStateOf("") }
    var speedMenu by remember { mutableStateOf(false) }
    var timerMenu by remember { mutableStateOf(false) }
    var voiceDialog by remember { mutableStateOf(false) }
    var customSpeedDialog by remember { mutableStateOf(false) }
    var customSpeedText by remember { mutableStateOf("") }
    var customTimerDialog by remember { mutableStateOf(false) }
    var customTimerText by remember { mutableStateOf("") }
    var draggingChapterProgress by remember { mutableStateOf(false) }
    var draggedChapterProgress by remember { mutableStateOf(0f) }
    val playingThisChapter = playback.bookId == book?.id && playback.chapterIndex == chapter?.chapterIndex
    val fractionInCurrentBlock = if (playingThisChapter && playback.durationMs > 0) {
        playback.positionMs.toFloat() / playback.durationMs.toFloat()
    } else 0f
    val chapterProgress = ChapterProgress.fraction(blocks, activeBlockIndex.coerceAtLeast(0), fractionInCurrentBlock)

    LaunchedEffect(chapterProgress, draggingChapterProgress) {
        if (!draggingChapterProgress) draggedChapterProgress = chapterProgress
    }

    LaunchedEffect(activeBlockIndex, blocks.size) {
        if (blocks.isNotEmpty() && activeBlockIndex >= 0) {
            listState.animateScrollToItem(activeBlockIndex.coerceIn(blocks.indices))
        }
        if (playback.isPlaying && blocks.isNotEmpty() && activeBlockIndex >= blocks.lastIndex - 1) {
            viewModel.ensureNextWebChapter()
        }
    }

    if (book == null || chapter == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(book!!.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { catalogDialog = true }) {
                Text("${chapter!!.chapterIndex + 1}/${chapters.size}  ${chapter!!.title}", maxLines = 1)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(blocks) { index, text ->
                Text(
                    text,
                    modifier = Modifier.fillMaxWidth()
                        .background(
                            if (index == activeBlockIndex) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!playback.connected) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("本章进度 ${(draggedChapterProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = draggedChapterProgress.coerceIn(0f, 1f),
                enabled = blocks.isNotEmpty() && !playbackPreparing,
                onValueChange = { value ->
                    draggingChapterProgress = true
                    draggedChapterProgress = value
                },
                onValueChangeFinished = {
                    draggingChapterProgress = false
                    viewModel.seekWithinChapter(draggedChapterProgress)
                },
                valueRange = 0f..1f,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::previousChapter) { Text("上章") }
                IconButton(onClick = { viewModel.playback.seekBy(-15_000) }) { Text("−15s") }
                Button(
                    enabled = !playbackPreparing,
                    onClick = { if (playback.connected && playback.durationMs > 0) viewModel.playback.toggle() else viewModel.resumeSelected() },
                ) {
                    Text(if (playbackPreparing) "准备中…" else if (playback.isPlaying) "暂停" else "播放")
                }
                IconButton(onClick = { viewModel.playback.seekBy(15_000) }) { Text("+15s") }
                IconButton(onClick = viewModel::nextChapter) { Text("下章") }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Box {
                    TextButton(onClick = { speedMenu = true }) { Text("${settings.speed}×") }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f).forEach { speed ->
                            DropdownMenuItem(text = { Text("${speed}×") }, onClick = {
                                speedMenu = false
                                viewModel.setSpeed(speed)
                            })
                        }
                        DropdownMenuItem(
                            text = { Text("自定义倍速") },
                            onClick = {
                                speedMenu = false
                                customSpeedText = ""
                                customSpeedDialog = true
                            },
                        )
                    }
                }
                TextButton(onClick = {
                    viewModel.refreshVoicePackStates()
                    voiceDialog = true
                }) { Text("音色") }
                Box {
                    TextButton(onClick = { timerMenu = true }) { Text("定时") }
                    DropdownMenu(expanded = timerMenu, onDismissRequest = { timerMenu = false }) {
                        listOf(10, 15, 20, 30, 60).forEach { minutes ->
                            DropdownMenuItem(text = { Text("$minutes 分钟") }, onClick = {
                                timerMenu = false
                                viewModel.setSleepTimer(minutes)
                            })
                        }
                        DropdownMenuItem(text = { Text("自定义分钟") }, onClick = {
                            timerMenu = false
                            customTimerText = ""
                            customTimerDialog = true
                        })
                        DropdownMenuItem(text = { Text("播完本章") }, onClick = {
                            timerMenu = false
                            viewModel.setSleepTimer(-1)
                        })
                        DropdownMenuItem(text = { Text("取消定时") }, onClick = {
                            timerMenu = false
                            viewModel.setSleepTimer(0)
                        })
                    }
                }
                chapter!!.nextUrl?.let {
                    TextButton(onClick = { viewModel.ensureNextWebChapter() }) { Text("抓取下章") }
                }
            }
        }
    }

    if (catalogDialog) AlertDialog(
        onDismissRequest = { catalogDialog = false; editingCatalog = false },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("目录（${chapters.size} 章）", modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (editingCatalog) {
                        editingCatalog = false
                    } else {
                        editingCatalog = true
                        renameChapterTarget = null
                    }
                }) { Text(if (editingCatalog) "完成" else "编辑") }
            }
        },
        text = {
            if (editingCatalog) {
                CatalogEditContent(
                    chapters = chapters,
                    renameChapterTarget = renameChapterTarget,
                    renameChapterText = renameChapterText,
                    onRenameTarget = { renameChapterTarget = it; renameChapterText = chapters.getOrNull(it)?.title.orEmpty() },
                    onRenameText = { renameChapterText = it },
                    onSaveRename = {
                        renameChapterTarget?.let { viewModel.renameChapter(it, renameChapterText) }
                        renameChapterTarget = null
                    },
                    onCancelRename = { renameChapterTarget = null },
                    onRemoveFixedField = { batchRemoveFixed = true; batchFixedText = "" },
                    onTrimFront = { batchTrimMode = "front"; batchTrimCount = ""; batchTrimDialog = true },
                    onTrimBack = { batchTrimMode = "back"; batchTrimCount = ""; batchTrimDialog = true },
                )
            } else {
                LazyColumn(Modifier.height(440.dp)) {
                    itemsIndexed(chapters, key = { _, c -> c.chapterIndex }) { _, item ->
                        val downloaded = item.content.isNotBlank()
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                catalogDialog = false
                                viewModel.selectChapter(item.chapterIndex)
                            }.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (item.chapterIndex == chapter!!.chapterIndex) "▶ " else "   ",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!downloaded) {
                                    Text("未下载，点击后加载", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { catalogDialog = false; editingCatalog = false }) { Text("关闭") } },
    )

    if (batchRemoveFixed) {
        val sample = chapters.firstOrNull()?.title.orEmpty()
        AlertDialog(
            onDismissRequest = { batchRemoveFixed = false },
            title = { Text("批量去掉固定字段") },
            text = {
                Column {
                    Text(
                        "输入章节名里重复出现的固定文字（例如书名《XX》或“第XX章”），会从所有章节名里去掉，保留剩余标题。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = batchFixedText,
                        onValueChange = { batchFixedText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("要删除的固定文字") },
                        placeholder = { Text("例如：重生之都市修仙") },
                    )
                    if (batchFixedText.isNotBlank()) {
                        Text(
                            "示例：${sample} → ${sample.replace(batchFixedText, "").ifBlank { "（空）" }}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = batchFixedText.isNotBlank(),
                    onClick = {
                        val fixed = batchFixedText
                        val map = chapters.associate { c ->
                            c.chapterIndex to c.title.replace(fixed, "").trim()
                        }.filterValues { it.isNotEmpty() }
                        viewModel.renameChapters(map)
                        batchRemoveFixed = false
                    },
                ) { Text("应用") }
            },
            dismissButton = { TextButton(onClick = { batchRemoveFixed = false }) { Text("取消") } },
        )
    }

    if (batchTrimDialog) {
        val count = batchTrimCount.trim().toIntOrNull()
        val valid = count != null && count > 0
        val sample = chapters.firstOrNull()?.title.orEmpty()
        val preview = if (valid) {
            if (batchTrimMode == "front") sample.drop(count).ifBlank { "（空）" }
            else sample.dropLast(count).ifBlank { "（空）" }
        } else ""
        AlertDialog(
            onDismissRequest = { batchTrimDialog = false },
            title = { Text(if (batchTrimMode == "front") "批量去掉开头 N 字" else "批量去掉结尾 N 字") },
            text = {
                Column {
                    OutlinedTextField(
                        value = batchTrimCount,
                        onValueChange = { batchTrimCount = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("字数") },
                        placeholder = { Text("例如 5") },
                        supportingText = { Text("对每个章节名去掉开头/结尾这么多字") },
                    )
                    if (preview.isNotEmpty()) {
                        Text("示例：${sample} → $preview", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        val n = count!!
                        val map = chapters.associate { c ->
                            val t = if (batchTrimMode == "front") c.title.drop(n) else c.title.dropLast(n)
                            c.chapterIndex to t.trim()
                        }.filterValues { it.isNotEmpty() }
                        viewModel.renameChapters(map)
                        batchTrimDialog = false
                    },
                ) { Text("应用") }
            },
            dismissButton = { TextButton(onClick = { batchTrimDialog = false }) { Text("取消") } },
        )
    }

    if (voiceDialog) AlertDialog(
        onDismissRequest = { voiceDialog = false },
        title = { Text("离线语音与音色") },
        text = {
            val anyDownloading = voicePackStates.any { it.downloading }
            LazyColumn(Modifier.height(420.dp)) {
                item {
                    Text("语音引擎", fontWeight = FontWeight.Bold)
                }
                items(engines, key = { it.packageName }) { engine ->
                    TextButton(onClick = { viewModel.setEngine(engine.packageName) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (engine.packageName == settings.ttsEngine) "✓ ${engine.label}" else engine.label)
                    }
                }
                item {
                    Text("语音包（点击直接下载）", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    Text(
                        "语音包就是离线音色，在手机本地运行，不上传书籍正文。各语音包共用同一个离线引擎：安装新的会替换当前离线音色，但已下载的安装包和各音色的朗读缓存都会保留，切换时不会自动删除。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                val notDownloaded = voicePackStates.filter { !it.downloaded }
                items(notDownloaded, key = { it.option.model }) { state ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(state.option.label)
                            Text("约 ${state.option.sizeMb} MB", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(
                            enabled = !anyDownloading,
                            onClick = { viewModel.selectVoicePack(state.option) },
                        ) {
                            Text(
                                if (state.downloading) "下载中 ${voicePackDownload?.percent ?: 0}%"
                                else "下载"
                            )
                        }
                    }
                }
                item {
                    Text("已下载的语音包", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                }
                val downloaded = voicePackStates.filter { it.downloaded }
                if (downloaded.isEmpty()) {
                    item {
                        Text(
                            "还没有已下载的语音包。下载过的安装包会保存在本机，可随时重装或删除。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                items(downloaded, key = { it.option.model }) { state ->
                    val tag = when {
                        state.installed -> " · 当前使用"
                        state.lastInstalled -> " · 上次安装"
                        else -> ""
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(state.option.label + tag)
                            Text("约 ${state.option.sizeMb} MB", style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.downloading) {
                            Text("下载中 ${voicePackDownload?.percent ?: 0}%", style = MaterialTheme.typography.bodySmall)
                        } else {
                            TextButton(onClick = { viewModel.installVoicePack(state.option) }) {
                                Text(if (state.installed) "重装" else "安装")
                            }
                            TextButton(onClick = { viewModel.deleteVoicePack(state.option) }) { Text("删除") }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    if (settings.ttsEngine.isNotBlank()) {
                        OutlinedButton(onClick = viewModel::openEngineVoiceSettings, modifier = Modifier.fillMaxWidth()) {
                            Text("打开引擎设置（选择说话人）")
                        }
                    }
                    OutlinedButton(onClick = viewModel::rescanVoiceEngines, modifier = Modifier.fillMaxWidth()) {
                        Text("重新扫描已安装的引擎")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { voiceDialog = false }) { Text("关闭") } },
    )

    if (customSpeedDialog) {
        val parsed = customSpeedText.trim().toFloatOrNull()
        val valid = parsed != null && parsed >= 0.5f && parsed <= 4f
        AlertDialog(
            onDismissRequest = { customSpeedDialog = false },
            title = { Text("自定义倍速") },
            text = {
                Column {
                    OutlinedTextField(
                        value = customSpeedText,
                        onValueChange = { customSpeedText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("倍速") },
                        placeholder = { Text("例如 1.75") },
                        supportingText = {
                            Text(if (valid) "将使用 ${parsed}× 播放" else "请输入 0.5 ~ 4 之间的数字")
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        viewModel.setSpeed(parsed!!)
                        customSpeedDialog = false
                    },
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { customSpeedDialog = false }) { Text("取消") } },
        )
    }

    if (customTimerDialog) {
        val parsed = customTimerText.trim().toIntOrNull()
        val valid = parsed != null && parsed in 1..600
        AlertDialog(
            onDismissRequest = { customTimerDialog = false },
            title = { Text("自定义定时") },
            text = {
                Column {
                    OutlinedTextField(
                        value = customTimerText,
                        onValueChange = { customTimerText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("分钟") },
                        placeholder = { Text("例如 45") },
                        supportingText = {
                            Text(if (valid) "将在 $parsed 分钟后停止播放" else "请输入 1 ~ 600 之间的整数")
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        viewModel.setSleepTimer(parsed!!)
                        customTimerDialog = false
                    },
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { customTimerDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun CatalogEditContent(
    chapters: List<com.pray.booklisten.data.ChapterEntity>,
    renameChapterTarget: Int?,
    renameChapterText: String,
    onRenameTarget: (Int) -> Unit,
    onRenameText: (String) -> Unit,
    onSaveRename: () -> Unit,
    onCancelRename: () -> Unit,
    onRemoveFixedField: () -> Unit,
    onTrimFront: () -> Unit,
    onTrimBack: () -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRemoveFixedField, modifier = Modifier.weight(1f)) {
                Text("去固定字段", maxLines = 1)
            }
            OutlinedButton(onClick = onTrimFront, modifier = Modifier.weight(1f)) {
                Text("去开头 N 字", maxLines = 1)
            }
            OutlinedButton(onClick = onTrimBack, modifier = Modifier.weight(1f)) {
                Text("去结尾 N 字", maxLines = 1)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "点击章节可单独改名；上方按钮为批量操作，会应用到全部章节。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.height(380.dp)) {
            itemsIndexed(chapters, key = { _, c -> c.chapterIndex }) { _, item ->
                if (renameChapterTarget == item.chapterIndex) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = renameChapterText,
                            onValueChange = { if (it.length <= 120) onRenameText(it) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        TextButton(onClick = onSaveRename, enabled = renameChapterText.isNotBlank()) { Text("保存") }
                        TextButton(onClick = onCancelRename) { Text("取消") }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().clickable { onRenameTarget(item.chapterIndex) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("改", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private const val EXTRACT_SCRIPT = """
(() => {
  const bad = 'script,style,noscript,iframe,form,nav,footer,header,aside';
  const candidates = Array.from(document.querySelectorAll('article,main,[role=main],.content,.chapter-content,.read-content,#content,#chaptercontent'));
  candidates.push(document.body);
  const score = el => (el.innerText || '').length - Array.from(el.querySelectorAll('a')).reduce((n,a) => n + (a.innerText || '').length * 2, 0);
  const root = candidates.sort((a,b) => score(b) - score(a))[0];
  const clone = root.cloneNode(true);
  clone.querySelectorAll(bad).forEach(el => el.remove());
  const content = (clone.innerText || '').replace(/[ \\t]+/g, ' ').replace(/\\n{3,}/g, '\\n\\n').trim();
  const links = Array.from(document.querySelectorAll('a[href]'));
  const next = links.find(a => (a.rel || '').toLowerCase().includes('next')) || links.find(a => /^(下一章|下一页|下页|下一节|next|›|»)/i.test((a.innerText || '').trim()));
  const bookTitleCandidates = [];
  ['meta[property="og:novel:book_name"]','meta[name="book_name"]','meta[property="book:name"]'].forEach(selector => {
    const value = document.querySelector(selector)?.content?.trim();
    if (value) bookTitleCandidates.push(value);
  });
  const visitJsonLd = value => {
    if (Array.isArray(value)) return value.forEach(visitJsonLd);
    if (!value || typeof value !== 'object') return;
    const types = Array.isArray(value['@type']) ? value['@type'] : [value['@type']];
    if (types.some(type => /^(book|novel)$/i.test(type || ''))) {
      if (value.name) bookTitleCandidates.push(String(value.name));
      if (value.headline) bookTitleCandidates.push(String(value.headline));
    }
    Object.values(value).forEach(visitJsonLd);
  };
  document.querySelectorAll('script[type="application/ld+json"]').forEach(script => {
    try { visitJsonLd(JSON.parse(script.textContent)); } catch (_) {}
  });
  document.querySelectorAll('#info h1,.book-info h1,.bookname,[itemprop="name"]').forEach(el => {
    const value = (el.innerText || '').trim();
    if (value) bookTitleCandidates.push(value);
  });
  Array.from(document.querySelectorAll('.breadcrumb a,.breadcrumbs a,.crumb a,.path a')).reverse().forEach(el => {
    const value = (el.innerText || '').trim();
    if (value) bookTitleCandidates.push(value);
  });
  let nextUrl = '';
  try { if (next && new URL(next.href).host === location.host) nextUrl = next.href; } catch (_) {}
  let catalogUrl = '';
  const catalogLinks = Array.from(document.querySelectorAll('a[href]'));
  const catalogLink = catalogLinks.find(a => {
    const t = (a.innerText || a.textContent || '').trim();
    return t && /^(章节目录|目录|目錄|全文目录|章节列表|全部章节|章节目录列表|小说目录|作品目录)$/.test(t);
  });
  try { if (catalogLink && new URL(catalogLink.href).host === location.host) catalogUrl = catalogLink.href; } catch (_) {}
  return JSON.stringify({
    title: (document.querySelector('h1')?.innerText || document.title || '').trim(),
    documentTitle: (document.title || '').trim(),
    bookTitleCandidates,
    content,
    nextUrl,
    catalogUrl
  });
})()
"""
