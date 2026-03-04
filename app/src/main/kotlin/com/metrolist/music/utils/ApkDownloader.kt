/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    data class Completed(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

object ApkDownloader {
    private const val APK_FILE_NAME = "metrolist_update.apk"
    private const val BUFFER_SIZE = 8192

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun getInstallPermissionIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: ((DownloadState) -> Unit)? = null
    ): Flow<DownloadState> = flow {
        emit(DownloadState.Downloading(0f, 0, 0))

        try {
            val cacheDir = context.externalCacheDir ?: context.cacheDir
            val apkFile = File(cacheDir, APK_FILE_NAME)

            // Delete existing file if any
            if (apkFile.exists()) {
                apkFile.delete()
            }

            val url = URL(downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/vnd.android.package-archive")
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                emit(DownloadState.Error("Server returned HTTP ${connection.responseCode}"))
                return@flow
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val progress = if (totalBytes > 0) {
                            downloadedBytes.toFloat() / totalBytes.toFloat()
                        } else {
                            0f
                        }

                        emit(DownloadState.Downloading(progress, downloadedBytes, totalBytes))
                        onProgress?.invoke(DownloadState.Downloading(progress, downloadedBytes, totalBytes))
                    }
                }
            }

            emit(DownloadState.Completed(apkFile))
        } catch (e: Exception) {
            emit(DownloadState.Error(e.message ?: "Unknown error occurred"))
        }
    }.flowOn(Dispatchers.IO)

    fun installApk(context: Context, apkFile: File) {
        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.FileProvider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        context.startActivity(installIntent)
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun clearDownloadedApk(context: Context) {
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        val apkFile = File(cacheDir, APK_FILE_NAME)
        if (apkFile.exists()) {
            apkFile.delete()
        }
    }

    fun getDownloadedApk(context: Context): File? {
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        val apkFile = File(cacheDir, APK_FILE_NAME)
        return if (apkFile.exists()) apkFile else null
    }
}
