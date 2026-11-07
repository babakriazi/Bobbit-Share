package com.babakriazi.bobbitshare

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ShareService : Service() {

    private val binder = LocalBinder()
    private var server: FileServer? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl

    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount

    private var currentFiles: List<FileItem> = emptyList()

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
        if (_isRunning.value) return true

        val ip = NetworkUtils.getLocalIpAddress(this) ?: return false
        currentFiles = files

        return try {
            server = FileServer(this, files, port).also {
                it.onClientConnected = { count ->
                    _clientCount.value = count
                }
                it.start()
            }
            _serverUrl.value = "http://$ip:$port"
            _isRunning.value = true
            startForeground(NOTIFICATION_ID, buildNotification())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun stopSharing() {
        try {
            server?.stop()
        } catch (_: Exception) {}
        server = null
        _isRunning.value = false
        _serverUrl.value = null
        _clientCount.value = 0
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
        const val ACTION_STOP = "com.babakriazi.bobbitshare.STOP"
        const val NOTIFICATION_ID = 1001
    }
}
