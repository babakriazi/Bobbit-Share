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
import android.os.IBinder
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.babakriazi.bobbitshare.ui.rememberQrImageBitmap
import com.babakriazi.bobbitshare.ui.theme.*
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private var shareService: ShareService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ShareService.LocalBinder
            shareService = binder.getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shareService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        setContent {
            BobbitShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Background
                ) {
                    ShareScreen(
                        onBindService = { bindShareService() },
                        onUnbindService = { unbindShareService() },
                        getService = { shareService }
                    )
                }
            }
        }
    }

    private fun bindShareService() {
        val intent = Intent(this, ShareService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindShareService() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    override fun onDestroy() {
        unbindShareService()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    onBindService: () -> Unit,
    onUnbindService: () -> Unit,
    getService: () -> ShareService?
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    // Responsive sizing based on screen
    val screenWidth = configuration.screenWidthDp.dp
    val isCompact = screenWidth < 360.dp
    val qrSize = when {
        screenWidth < 340.dp -> 200.dp
        screenWidth < 400.dp -> 240.dp
        else -> 280.dp
    }
    val titleSize = if (isCompact) 26.sp else 32.sp
    val horizontalPadding = if (isCompact) 16.dp else 24.dp

    var selectedFiles by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isSharing by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var clientCount by remember { mutableStateOf(0) }

    // File picker
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val items = uris.mapNotNull { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}

                var name = "file"
                var size = 0L
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: "file"
                        if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                    }
                }
                val mime = context.contentResolver.getType(uri)
                FileItem(uri, name, size, mime)
            }
            selectedFiles = items
        }
    }

    // Observe service state
    LaunchedEffect(Unit) {
        onBindService()
    }

    LaunchedEffect(isSharing) {
        val service = getService()
        if (service != null) {
            service.isRunning.collectLatest { isSharing = it }
        }
    }

    LaunchedEffect(isSharing) {
        val service = getService()
        if (service != null) {
            service.serverUrl.collectLatest { serverUrl = it }
        }
    }

    LaunchedEffect(isSharing) {
        val service = getService()
        if (service != null) {
            service.clientCount.collectLatest { clientCount = it }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ── Header with graphical logo ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Bobbit Share",
                fontSize = titleSize,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                style = LocalTextStyle.current.copy(
                    brush = Brush.linearGradient(
                        colors = listOf(GradientStart, GradientEnd)
                    )
                )
            )
        }

        Text(
            text = "اشتراک‌گذاری سریع فایل در شبکه محلی",
            color = TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            textAlign = TextAlign.Center
        )

        // ── Main content ──
        AnimatedContent(
            targetState = isSharing,
            transitionSpec = {
                fadeIn() + slideInVertically { it / 4 } togetherWith
                        fadeOut() + slideOutVertically { -it / 4 }
            },
            label = "main_content"
        ) { sharing ->
            if (!sharing) {
                // ── Selection Mode ──
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = horizontalPadding)
                ) {
                    // Select files button
                    Button(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary
                        )
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "انتخاب فایل‌ها",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    // File list
                    if (selectedFiles.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Rounded.InsertDriveFile,
                                    contentDescription = null,
                                    tint = TextSecondary.copy(alpha = 0.4f),
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "هنوز فایلی انتخاب نشده",
                                    color = TextSecondary,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "${selectedFiles.size} فایل انتخاب شده",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(selectedFiles, key = { it.uri.toString() }) { file ->
                                FileRow(file = file, compact = isCompact)
                            }
                        }
                    }

                    // Start button
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (selectedFiles.isEmpty()) {
                                Toast.makeText(context, "ابتدا فایل انتخاب کنید", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (!NetworkUtils.isWifiConnected(context)) {
                                Toast.makeText(context, "به وای‌فای وصل باشید", Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            val service = getService()
                            if (service != null) {
                                val started = service.startSharing(selectedFiles)
                                if (!started) {
                                    Toast.makeText(context, "خطا در راه‌اندازی سرور", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                // Start service first
                                val intent = Intent(context, ShareService::class.java)
                                ContextCompat.startForegroundService(context, intent)
                                onBindService()
                                // Retry shortly
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    getService()?.startSharing(selectedFiles)
                                }, 400)
                            }
                        },
                        enabled = selectedFiles.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Secondary,
                            contentColor = Color.Black,
                            disabledContainerColor = SurfaceVariant
                        )
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "شروع اشتراک‌گذاری",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            } else {
                // ── Sharing Mode ──
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = horizontalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Status chip
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Success.copy(alpha = 0.15f),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Success)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (clientCount > 0) "$clientCount نفر متصل" else "در انتظار اتصال…",
                                color = Success,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // QR Code
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
                                contentDescription = "QR Code",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            text = "این QR را اسکن کنید\nیا آدرس را در مرورگر وارد کنید",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )

                        Spacer(Modifier.height(12.dp))

                        // URL box
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = SurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboard.setText(AnnotatedString(serverUrl!!))
                                    Toast.makeText(context, "آدرس کپی شد", Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = serverUrl!!,
                                    color = PrimaryLight,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    contentDescription = "کپی",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // File count info
                    Text(
                        text = "${selectedFiles.size} فایل در حال اشتراک‌گذاری",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Stop button
                    Button(
                        onClick = {
                            getService()?.stopSharing()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Error.copy(alpha = 0.9f)
                        )
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "توقف اشتراک‌گذاری",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun FileRow(file: FileItem, compact: Boolean) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.InsertDriveFile,
                contentDescription = null,
                tint = PrimaryLight,
                modifier = Modifier.size(if (compact) 22.dp else 26.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    color = TextPrimary,
                    fontSize = if (compact) 13.sp else 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = file.formattedSize(),
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}
