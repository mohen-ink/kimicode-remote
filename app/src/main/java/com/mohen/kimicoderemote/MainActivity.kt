package com.mohen.kimicoderemote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                AppRoot()
            }
        }
    }
}

private enum class Screen { Home, Scan, Browser, History }

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.Home) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var dialogText by remember { mutableStateOf<String?>(null) }
    var decoding by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // bump to refresh history list after changes
    var historyVersion by remember { mutableStateOf(0) }
    var pendingEntry by remember { mutableStateOf<HistoryEntry?>(null) }
    var historyRefreshTick by remember { mutableStateOf(0) }

    fun openUrl(url: String) {
        val entry = HistoryEntry(url = url, title = url, timestamp = System.currentTimeMillis())
        HistoryStore.add(context, url, null)
        historyVersion++
        pendingEntry = entry
        screen = Screen.Browser
    }

    fun openFromHistory(entry: HistoryEntry) {
        HistoryStore.restoreCookies(entry)
        pendingEntry = entry
        screen = Screen.Browser
    }

    fun leaveBrowser() {
        pendingEntry?.let { entry ->
            HistoryStore.add(context, entry.url, null, HistoryStore.captureCookies(entry.url))
        }
        historyVersion++
        pendingEntry = null
        screen = Screen.Home
    }

    val scope = rememberCoroutineScope()

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            decoding = true
            pendingUri = uri
        }
    }

    LaunchedEffect(pendingUri) {
        val uri = pendingUri ?: return@LaunchedEffect
        val (text, error) = QrDecoder.decodeFromUri(context, uri)
        decoding = false
        pendingUri = null
        if (text != null && (text.startsWith("http://") || text.startsWith("https://"))) {
            openUrl(text)
        } else if (text != null) {
            dialogText = text
        } else {
            dialogText = error ?: "未能从所选图片中识别到二维码"
        }
    }

    when (screen) {
        Screen.Home -> {
            val lastEntry = remember(historyVersion) {
                HistoryStore.load(context).firstOrNull()
            }
            HomeScreen(
                decoding = decoding,
                lastUrl = lastEntry?.let { if (it.title != it.url) it.title else it.host },
                onUseLast = { lastEntry?.let { openFromHistory(it) } },
                onScan = { screen = Screen.Scan },
                onPickImage = {
                    pickImageLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onHistory = { screen = Screen.History }
            )
        }

        Screen.Scan -> ScanScreen(
            onQrResult = { text ->
                if (text.startsWith("http://") || text.startsWith("https://")) {
                    openUrl(text)
                } else {
                    dialogText = text
                }
            },
            onBack = { screen = Screen.Home }
        )

        Screen.Browser -> pendingEntry?.let { entry ->
            BrowserScreen(
                url = entry.url,
                onTitle = { title ->
                    HistoryStore.add(context, entry.url, title, HistoryStore.captureCookies(entry.url))
                    historyVersion++
                },
                onBack = { leaveBrowser() }
            )
        }

        Screen.History -> HistoryScreen(
            refreshTick = historyVersion,
            onOpen = { entry -> openFromHistory(entry) },
            onClear = {
                HistoryStore.clear(context)
                historyVersion++
            },
            onBack = { screen = Screen.Home }
        )
    }

    dialogText?.let { text ->
        QrTextDialog(
            text = text,
            onDismiss = { dialogText = null }
        )
    }
}

@Composable
private fun HomeScreen(
    decoding: Boolean,
    lastUrl: String?,
    onUseLast: () -> Unit,
    onScan: () -> Unit,
    onPickImage: () -> Unit,
    onHistory: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Kimi远程控制",
                style = MaterialTheme.typography.headlineLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth(0.6f),
                shape = CircleShape,
                enabled = !decoding
            ) {
                Text("扫码")
            }
            OutlinedButton(
                onClick = onPickImage,
                modifier = Modifier.fillMaxWidth(0.6f),
                shape = CircleShape,
                enabled = !decoding
            ) {
                if (decoding) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text("从相册选择")
            }
            if (lastUrl != null) {
                Button(
                    onClick = onUseLast,
                    modifier = Modifier.fillMaxWidth(0.6f),
                    shape = CircleShape,
                    enabled = !decoding
                ) {
                    Text(
                        text = "使用上一次链接",
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = lastUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.7f)
                )
            }
            OutlinedButton(
                onClick = onHistory,
                modifier = Modifier.fillMaxWidth(0.6f),
                shape = CircleShape
            ) {
                Text("历史连接")
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    refreshTick: Int,
    onOpen: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var entries by remember(refreshTick) {
        mutableStateOf(HistoryStore.load(context))
    }

    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回")
            }
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "历史连接",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text("清空")
                }
            }
        }

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无连接记录",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f)
            ) {
                items(entries.size) { index ->
                    val entry = entries[index]
                    Surface(
                        onClick = { onOpen(entry) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = entry.host,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanScreen(
    onQrResult: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    BackHandler(enabled = !paused) { onBack() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            CameraPreview(
                paused = paused,
                onQrDetected = { text ->
                    if (!paused) {
                        paused = true
                        onQrResult(text)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            ScanFrameOverlay(modifier = Modifier.fillMaxSize())

            // Resume scanning when returning from browser/text dialog
            LaunchedEffect(Unit) { paused = false }

            // Back button over camera, below status bar
            TextButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 48.dp, start = 8.dp)
            ) {
                Text("← 返回", color = Color.White)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (permissionRequested) "相机权限被拒绝，无法扫码" else "需要相机权限才能扫码",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    permissionRequested = true
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }) {
                    Text("授权相机")
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    paused: Boolean,
    onQrDetected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val analyzerRef = remember { mutableStateOf<QrAnalyzer?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analyzer = QrAnalyzer { text ->
                    previewView.post { onQrDetected(text) }
                }
                analyzerRef.value = analyzer
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analyzerExecutor, analyzer) }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                } catch (_: Exception) {
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            analyzerRef.value?.stop()
            analyzerExecutor.shutdown()
        }
    }
}

@Composable
private fun ScanFrameOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val cornerColor = Color.White
        val cornerLen = 36.dp
        val stroke = 4.dp
        androidx.compose.foundation.Canvas(modifier = Modifier.size(240.dp)) {
            val s = stroke.toPx()
            val l = cornerLen.toPx()
            val w = size.width
            val h = size.height
            // top-left
            drawLine(cornerColor, androidx.compose.ui.geometry.Offset(0f, l), androidx.compose.ui.geometry.Offset(0f, 0f), strokeWidth = s)
            drawLine(cornerColor, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(l, 0f), strokeWidth = s)
            // top-right
            drawLine(cornerColor, androidx.compose.ui.geometry.Offset(w - l, 0f), androidx.compose.ui.geometry.Offset(w, 0f), strokeWidth = s)
            drawLine(cornerColor, androidx.compose.ui.geometry.Offset(w, 0f), androidx.compose.ui.geometry.Offset(w, l), strokeWidth = s)
            // bottom-left
            drawLine(cornerColor, androidx.compose.ui.geometry.Offset(0f, h - l), androidx.compose.ui.geometry.Offset(0f, h), strokeWidth = s)
            drawLine(cornerColor, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(l, h), strokeWidth = s)
            // bottom-right
            drawLine(cornerColor, androidx.compose.ui.geometry.Offset(w - l, h), androidx.compose.ui.geometry.Offset(w, h), strokeWidth = s)
            drawLine(cornerColor, androidx.compose.ui.geometry.Offset(w, h - l), androidx.compose.ui.geometry.Offset(w, h), strokeWidth = s)
        }
    }
}

@Composable
private fun BrowserScreen(url: String, onTitle: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // WebView <input type="file"> support; null callback = user cancelled
    var fileChooserCallback by remember {
        mutableStateOf<android.webkit.ValueCallback<Array<android.net.Uri>>?>(null)
    }
    var fileChooserMimeTypes by remember { mutableStateOf<Array<String>?>(null) }
    var fileChooserCapture by remember { mutableStateOf<String?>(null) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        if (callback == null) return@rememberLauncherForActivityResult
        var uris = android.webkit.WebChromeClient.FileChooserParams.parseResult(
            result.resultCode, result.data
        )
        // Fallback: some pickers return files via ClipData only
        if (uris == null && result.data?.clipData != null) {
            val clip = result.data!!.clipData!!
            uris = Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
        }
        // Ensure the WebView can read the picked files
        uris?.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        callback.onReceiveValue(uris ?: arrayOf())
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        if (callback == null) return@rememberLauncherForActivityResult
        if (success) {
            callback.onReceiveValue(capturedPhotoUri?.let { arrayOf(it) } ?: arrayOf())
        } else {
            callback.onReceiveValue(arrayOf())
        }
    }

    BackHandler {
        val webView = webViewRef.value
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else {
            onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, title: String?) {
                            if (!title.isNullOrBlank()) onTitle(title)
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>,
                            fileChooserParams: FileChooserParams
                        ): Boolean {
                            // A picker is already open; cancel the previous one
                            fileChooserCallback?.onReceiveValue(arrayOf())
                            fileChooserCallback = filePathCallback
                            fileChooserMimeTypes = fileChooserParams.acceptTypes
                                .takeIf { it.any { t -> t.isNotBlank() } }
                            fileChooserCapture =
                                if (fileChooserParams.isCaptureEnabled) "camera" else null

                            if (fileChooserCapture != null) {
                                // Direct camera capture requested by the page
                                val photoUri = newCaptureUri(ctx)
                                capturedPhotoUri = photoUri
                                val ok = runCatching {
                                    takePictureLauncher.launch(photoUri)
                                }.isSuccess
                                if (!ok) {
                                    fileChooserCallback = null
                                    capturedPhotoUri = null
                                    filePathCallback.onReceiveValue(arrayOf())
                                }
                            } else {
                                // Build a robust ACTION_GET_CONTENT intent ourselves:
                                // createIntent() + EXTRA_MIME_TYPES breaks on some devices
                                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                    type = fileChooserMimeTypes?.firstOrNull() ?: "*/*"
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    fileChooserMimeTypes?.takeIf { it.size > 1 }
                                        ?.let { putExtra(Intent.EXTRA_MIME_TYPES, it) }
                                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.mode ==
                                        FileChooserParams.MODE_OPEN_MULTIPLE)
                                }
                                runCatching { fileChooserLauncher.launch(intent) }
                                    .onFailure {
                                        fileChooserCallback = null
                                        filePathCallback.onReceiveValue(arrayOf())
                                    }
                            }
                            return true
                        }
                    }
                    loadUrl(url)
                    webViewRef.value = this
                }
            },
            onRelease = { webView ->
                webViewRef.value = null
                webView.stopLoading()
                webView.destroy()
            }
        )
    }
}

private var capturedPhotoUri by mutableStateOf<android.net.Uri?>(null)

private fun newCaptureUri(context: android.content.Context): android.net.Uri {
    val file = java.io.File(
        context.cacheDir,
        "webview_upload_${System.currentTimeMillis()}.jpg"
    )
    return androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
}

@Composable
private fun QrTextDialog(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("识别结果") },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("qr", text)
                )
                onDismiss()
            }) { Text("复制") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
