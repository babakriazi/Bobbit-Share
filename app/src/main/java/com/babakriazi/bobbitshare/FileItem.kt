package com.babakriazi.bobbitshare

import android.net.Uri

data class FileItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String?
) {
    fun formattedSize(): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024))
            else -> String.format("%.2f GB", size / (1024.0 * 1024 * 1024))
        }
    }
}
