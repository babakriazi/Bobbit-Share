package com.babakriazi.bobbitshare

import android.Manifest
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
        bindShareService()
        setContent {
            BobbitShareTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                    ShareScreen(
                        getService = { shareService },
                        ensureService = { onReady -> ensureServiceBound(onReady) }
                    )
                }
            }
        }
    }

    private fun bindShareService() {
        val intent = Intent(this, ShareService::class.java)
        try { ContextCompat.startForegroundService(this, intent) } catch (_: Exception) {}
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun ensureServiceBound(onReady: () -> Unit) {
        if (bound && shareService != null) onReady()
        else {
            serviceReadyCallback = onReady
            bindShareService()
            Handler(Looper.getMainLooper()).postDelayed({
                if (shareService != null) {
                    serviceReadyCallback?.invoke()
                    serviceReadyCallback = null
                } else {
                    Toast.makeText(this, "سرویس آماده نشد", Toast.LENGTH_SHORT).show()
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    getService: () -> ShareService?,
    ensureService: (onReady: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val isCompact = screenWidth < 360.dp
    val qrSize = if (screenWidth < 400.dp) 220.dp else 260.dp
    val titleSize = if (isCompact) 26.sp else 32.sp
    val horizontalPadding = if (isCompact) 16.dp else 24.dp

    var showExitDialog by remember { mutableStateOf(false) }
    var lastBackTime by remember { mutableStateOf(0L) }
    var selectedFiles by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isSharing by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var clientCount by remember { mutableStateOf(0) }
    var isStarting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackTime < 2000) showExitDialog = true
        else {
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
                    (context as? ComponentActivity)?.finish()
                }) { Text("خروج", color = Error) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("انصراف") }
            },
            containerColor = Surface
        )
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            selectedFiles = uris.mapNotNull { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                var name = "file"
                var size = 0L
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (ni >= 0) name = cursor.getString(ni) ?: "file"
                            if (si >= 0) size = cursor.getLong(si)
                        }
                    }
                } catch (_: Exception) {}
                val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
                FileItem(uri, name, size, mime)
            }
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
            if (NetworkUtils.getLocalIpAddress(context) == null) {
                isStarting = false
                errorMessage = "آدرس IP پیدا نشد. به وای‌فای وصل باشید"
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                return@ensureService
            }
            if (!service.startSharing(selectedFiles)) {
                isStarting = false
                errorMessage = service.lastError.value ?: "خطا در راه‌اندازی سرور"
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Bobbit Share",
                fontSize = titleSize,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                style = TextStyle(
                    brush = Brush.linearGradient(listOf(GradientStart, GradientEnd))
                )
            )
        }
        Text(
            text = "اشتراک‌گذاری سریع فایل  •  v1.0.2",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            textAlign = TextAlign.Center
        )
        if (errorMessage != null) {
            Surface(
                color = Error.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding).padding(bottom = 12.dp)
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
            label = "main"
        ) { sharing ->
            if (!sharing) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding)) {
                    Button(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("انتخاب فایل‌ها", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(20.dp))
                    if (selectedFiles.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("هنوز فایلی انتخاب نشده", color = TextSecondary, fontSize = 15.sp)
                        }
                    } else {
                        Text(
                            "${selectedFiles.size} فایل انتخاب شده",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(selectedFiles, key = { it.uri.toString() }) { file ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = Surface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.InsertDriveFile, null, tint = PrimaryLight)
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                file.name,
                                                color = TextPrimary,
                                                fontSize = 14.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(file.formattedSize(), color = TextSecondary, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { startShare() },
                        enabled = selectedFiles.isNotEmpty() && !isStarting,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Secondary,
                            contentColor = Color.Black,
                            disabledContainerColor = SurfaceVariant
                        )
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("در حال راه‌اندازی…", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Rounded.Share, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text("شروع اشتراک‌گذاری", fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (clientCount > 0) "$clientCount نفر متصل" else "در انتظار اتصال…",
                        color = Success,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    if (serverUrl != null) {
                        val qrBitmap = rememberQrImageBitmap(serverUrl!!, 512)
                        Box(
                            modifier = Modifier
                                .size(qrSize)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.White)
                                .padding(12.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = "QR",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "QR را اسکن کنید یا آدرس را کپی کنید",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = SurfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable {
                                clipboard.setText(AnnotatedString(serverUrl!!))
                                Toast.makeText(context, "آدرس کپی شد", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    serverUrl!!,
                                    color = PrimaryLight,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { getService()?.stopSharing() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Error.copy(alpha = 0.9f))
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("توقف اشتراک‌گذاری", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}
