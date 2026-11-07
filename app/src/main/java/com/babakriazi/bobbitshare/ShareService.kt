package com.babakriazi.bobbitshare

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ShareService : Service() {

    private val binder = LocalBinder()
    private var server: FileServer? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _receivedCount = MutableStateFlow(0)
    val receivedCount: StateFlow<Int> = _receivedCount.asStateFlow()

    private val _lastReceivedName = MutableStateFlow<String?>(null)
    val lastReceivedName: StateFlow<String?> = _lastReceivedName.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): ShareService = this@ShareService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSharing()
        }
        return START_STICKY
    }

    fun startSharing(files: List<FileItem>, port: Int = 8080): Boolean {
        if (_isRunning.value) {
            Log.d(TAG, "Already running")
            return true
        }

        val ip = NetworkUtils.getLocalIpAddress(this)
        if (ip == null) {
            _lastError.value = "IP پیدا نشد. مطمئن شوید به وای‌فای وصل هستید"
            Log.e(TAG, "Could not get local IP")
            return false
        }

        Log.d(TAG, "Starting server on $ip:$port with ${files.size} files")

        return try {
            try { server?.stop() } catch (_: Exception) {}

            server = FileServer(this, files, port).also { s ->
                s.onClientConnected = { count ->
                    _clientCount.value = count
                }
                s.onFileReceived = { name ->
                    _receivedCount.value = s.getReceivedFiles().size
                    _lastReceivedName.value = name
                }
                s.start()
            }

            val url = "http://$ip:$port"
            _serverUrl.value = url
            _isRunning.value = true
            _lastError.value = null
            _clientCount.value = 0

            startForeground(NOTIFICATION_ID, buildNotification())
            Log.d(TAG, "Server started successfully: $url")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            _lastError.value = "خطا: ${e.message ?: "نامشخص"}"
            _isRunning.value = false
            _serverUrl.value = null
            false
        }
    }

    fun stopSharing() {
        Log.d(TAG, "Stopping share")
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
        server = null
        _isRunning.value = false
        _serverUrl.value = null
        _clientCount.value = 0
        _lastError.value = null
        _receivedCount.value = 0
        _lastReceivedName.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ShareService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, BobbitShareApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "توقف", stopPending)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        stopSharing()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ShareService"
        const val ACTION_STOP = "com.babakriazi.bobbitshare.STOP"
        const val NOTIFICATION_ID = 1001
    }
}
