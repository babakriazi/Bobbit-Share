package com.babakriazi.bobbitshare

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.babakriazi.bobbitshare.ui.rememberQrImageBitmap
import com.babakriazi.bobbitshare.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var shareService: ShareService? = null
    private var bound = false
    private var serviceReadyCallback: (() -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ShareService.LocalBinder
            shareService = binder.getService()
            bound = true
            Log.d(TAG, "Service connected")
            serviceReadyCallback?.invoke()
            serviceReadyCallback = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shareService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        bindShareService()

        setContent {
            BobbitShareTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                    AppRoot(
                        getService = { shareService },
                        ensureService = { onReady -> ensureServiceBound(onReady) }
                    )
                }
            }
        }
    }

    private fun bindShareService() {
        val intent = Intent(this, ShareService::class.java)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService", e)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun ensureServiceBound(onReady: () -> Unit) {
        if (bound && shareService != null) {
            onReady()
        } else {
            serviceReadyCallback = onReady
            bindShareService()
            Handler(Looper.getMainLooper()).postDelayed({
                if (shareService != null) {
                    serviceReadyCallback?.invoke()
                    serviceReadyCallback = null
                } else {
                    Toast.makeText(this, "سرویس آماده نشد. دوباره تلاش کنید", Toast.LENGTH_SHORT).show()
                }
            }, 1500)
        }
    }

    override fun onDestroy() {
        if (bound) {
            try { unbindService(connection) } catch (_: Exception) {}
            bound = false
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
fun AppRoot(
    getService: () -> ShareService?,
    ensureService: (onReady: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var showExitDialog by remember { mutableStateOf(false) }
    var lastBackTime by remember { mutableLongStateOf(0L) }

    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackTime < 2000) {
            showExitDialog = true
        } else {
            lastBackTime = now
            Toast.makeText(context, "برای خروج دوباره دکمه بازگشت را بزنید", Toast.LENGTH_SHORT).show()
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("خروج از برنامه", fontWeight = FontWeight.Bold) },
            text = { Text("می‌خواهید از Bobbit Share خارج شوید؟") },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    (context as? ComponentActivity)?.finishAffinity()
                }) { Text("خروج", color = Error) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("انصراف") }
            },
            containerColor = Surface
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Background).statusBarsPadding()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Bobbit Share",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                style = LocalTextStyle.current.copy(
                    brush = Brush.linearGradient(listOf(GradientStart, GradientEnd))
                )
            )
        }

        Text(
            text = "v1.1.1",
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            textAlign = TextAlign.Center
        )

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Background,
            contentColor = PrimaryLight,
            divider = {}
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("ارسال", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) },
                icon = { Icon(Icons.Rounded.Upload, null, modifier = Modifier.size(20.dp)) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("دریافت", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) },
                icon = { Icon(Icons.Rounded.Download, null, modifier = Modifier.size(20.dp)) }
            )
        }

        when (selectedTab) {
            0 -> SendScreen(getService = getService, ensureService = ensureService)
            1 -> ReceiveScreen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    getService: () -> ShareService?,
    ensureService: (onReady: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val configuration = LocalConfiguration.current

    val screenWidth = configuration.screenWidthDp.dp
    val isCompact = screenWidth < 360.dp
    val qrSize = when {
        screenWidth < 340.dp -> 180.dp
        screenWidth < 400.dp -> 220.dp
        else -> 260.dp
    }
    val horizontalPadding = if (isCompact) 16.dp else 24.dp

    var selectedFiles by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isSharing by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var clientCount by remember { mutableStateOf(0) }
    var isStarting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val items = uris.mapNotNull { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}

                var name = "file"
                var size = 0L
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: "file"
                            if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                        }
                    }
                } catch (_: Exception) {}
                val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
                FileItem(uri, name, size, mime)
            }
            selectedFiles = items
            errorMessage = null
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val service = getService()
            if (service != null) {
                launch { service.isRunning.collectLatest { isSharing = it; if (it) isStarting = false } }
                launch { service.serverUrl.collectLatest { serverUrl = it } }
                launch { service.clientCount.collectLatest { clientCount = it } }
                launch {
                    service.lastError.collectLatest { err ->
                        if (err != null) { errorMessage = err; isStarting = false }
                    }
                }
                break
            }
            delay(300)
        }
    }

    fun startShare() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(context, "ابتدا فایل انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        errorMessage = null
        isStarting = true
        ensureService {
            val service = getService()
            if (service == null) {
                isStarting = false
                errorMessage = "سرویس آماده نشد"
                return@ensureService
            }
            val ip = NetworkUtils.getLocalIpAddress(context)
            if (ip == null) {
                isStarting = false
                errorMessage = "آدرس IP پیدا نشد. به وای‌فای وصل باشید"
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                return@ensureService
            }
            val started = service.startSharing(selectedFiles)
            if (!started) {
                isStarting = false
                errorMessage = service.lastError.value ?: "خطا در راه‌اندازی سرور"
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = horizontalPadding)
    ) {
        if (errorMessage != null) {
            Surface(
                color = Error.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text(
                    text = errorMessage!!,
                    color = Error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp),
                    textAlign = TextAlign.Center
                )
            }
        }

        AnimatedContent(
            targetState = isSharing,
            transitionSpec = {
                fadeIn() + slideInVertically { it / 4 } togetherWith
                        fadeOut() + slideOutVertically { -it / 4 }
            },
            label = "send"
        ) { sharing ->
            if (!sharing) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, null)
                        Spacer(Modifier.width(10.dp))
                        Text("انتخاب فایل‌ها", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(Modifier.height(16.dp))

                    if (selectedFiles.isEmpty()) {
                        Box(Modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Rounded.InsertDriveFile, null,
                                    tint = TextSecondary.copy(alpha = 0.35f),
                                    modifier = Modifier.size(56.dp)
                                )
                                Spacer(Modifier.height(10.dp))
                                Text("فایلی انتخاب نشده", color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                    } else {
                        Text(
                            "${selectedFiles.size} فایل انتخاب شده",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(selectedFiles, key = { it.uri.toString() }) { file ->
                                FileRow(file, isCompact)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { startShare() },
                        enabled = selectedFiles.isNotEmpty() && !isStarting,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Secondary,
                            contentColor = Color.Black,
                            disabledContainerColor = SurfaceVariant
                        )
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(Modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("در حال راه‌اندازی…", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Rounded.Share, null)
                            Spacer(Modifier.width(10.dp))
                            Text("شروع اشتراک‌گذاری", fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Success.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier = Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Success))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (clientCount > 0) "$clientCount نفر متصل" else "در انتظار اتصال…",
                                color = Success, fontSize = 13.sp, fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    if (serverUrl != null) {
                        val qrBitmap = rememberQrImageBitmap(serverUrl!!, 512)
                        Box(
                            modifier = Modifier
                                .size(qrSize)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White)
                                .padding(10.dp)
                        ) {
                            Image(qrBitmap, "QR", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "QR را اسکن کنید یا آدرس را کپی کنید",
                            color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(10.dp))

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SurfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable {
                                clipboard.setText(AnnotatedString(serverUrl!!))
                                Toast.makeText(context, "آدرس کپی شد", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    serverUrl!!, color = PrimaryLight, fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Icon(Icons.Rounded.ContentCopy, "کپی", tint = TextSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))
                    Text(
                        "${selectedFiles.size} فایل در حال اشتراک‌گذاری",
                        color = TextSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    Button(
                        onClick = { getService()?.stopSharing() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Error.copy(alpha = 0.9f))
                    ) {
                        Icon(Icons.Rounded.Stop, null)
                        Spacer(Modifier.width(10.dp))
                        Text("توقف اشتراک‌گذاری", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReceiveScreen() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    val configuration = LocalConfiguration.current
    val horizontalPadding = if (configuration.screenWidthDp < 360) 16.dp else 24.dp

    var urlInput by remember { mutableStateOf("") }
    var loadedUrl by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("") }

    fun normalizeUrl(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) return u
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://$u"
        }
        return u
    }

    fun connect(url: String) {
        val normalized = normalizeUrl(url)
        if (normalized.isEmpty()) {
            Toast.makeText(context, "آدرس را وارد کنید", Toast.LENGTH_SHORT).show()
            return
        }
        loadedUrl = normalized
        isLoading = true
        focusManager.clearFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = horizontalPadding)
    ) {
        Spacer(Modifier.height(12.dp))

        if (loadedUrl == null) {
            Text(
                "به دستگاه مقابل وصل شوید",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Text(
                "آدرس نمایش‌داده‌شده روی گوشی فرستنده را وارد کنید\n(مثلاً http://192.168.1.42:8080)",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 16.dp),
                lineHeight = 18.sp
            )

            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("192.168.1.42:8080", color = TextSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = SurfaceVariant,
                    focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface,
                    cursorColor = PrimaryLight,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { connect(urlInput) }),
                leadingIcon = {
                    Icon(Icons.Rounded.Link, null, tint = TextSecondary)
                },
                trailingIcon = {
                    if (urlInput.isNotEmpty()) {
                        IconButton(onClick = { urlInput = "" }) {
                            Icon(Icons.Rounded.Close, null, tint = TextSecondary)
                        }
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            // Paste from clipboard
            TextButton(
                onClick = {
                    val text = clipboard.getText()?.text?.trim()
                    if (!text.isNullOrEmpty()) {
                        urlInput = text
                        Toast.makeText(context, "از کلیپ‌بورد جایگذاری شد", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "کلیپ‌بورد خالی است", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("جایگذاری از کلیپ‌بورد")
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { connect(urlInput) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Secondary,
                    contentColor = Color.Black
                )
            ) {
                Icon(Icons.Rounded.Link, null)
                Spacer(Modifier.width(10.dp))
                Text("اتصال", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.weight(1f))

            Text(
                "هر دو دستگاه باید به یک وای‌فای وصل باشند.\nبعد از اتصال می‌توانید فایل‌ها را دانلود کنید.",
                color = TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                lineHeight = 18.sp
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    loadedUrl = null
                    isLoading = false
                    pageTitle = ""
                }) {
                    Icon(Icons.Rounded.ArrowBack, "بازگشت", tint = TextPrimary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        pageTitle.ifBlank { "در حال بارگذاری…" },
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        loadedUrl!!,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = PrimaryLight
                    )
                }
            }

            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                pageTitle = view?.title ?: "Bobbit Share"
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                isLoading = newProgress < 100
                            }
                        }

                        setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                            try {
                                val request = android.app.DownloadManager.Request(Uri.parse(url))
                                request.setMimeType(mimeType)
                                val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                                request.setTitle(fileName)
                                request.setDescription("دانلود از Bobbit Share")
                                request.setNotificationVisibility(
                                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                                )
                                request.setDestinationInExternalPublicDir(
                                    android.os.Environment.DIRECTORY_DOWNLOADS, fileName
                                )
                                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                                dm.enqueue(request)
                                Toast.makeText(ctx, "دانلود شروع شد: $fileName", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "خطا در دانلود: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }

                        loadUrl(loadedUrl!!)
                    }
                },
                update = { webView ->
                    if (webView.url != loadedUrl) {
                        webView.loadUrl(loadedUrl!!)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
            )
        }
    }
}

@Composable
fun FileRow(file: FileItem, compact: Boolean) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.InsertDriveFile, null,
                tint = PrimaryLight,
                modifier = Modifier.size(if (compact) 20.dp else 24.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name, color = TextPrimary,
                    fontSize = if (compact) 13.sp else 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(file.formattedSize(), color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}
