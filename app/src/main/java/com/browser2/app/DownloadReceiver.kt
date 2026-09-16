package com.browser2.app

import android.app.DownloadManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.text.format.Formatter
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handles the system DownloadManager broadcast:
 *  - ACTION_DOWNLOAD_COMPLETE -> success/error notification + "open" action.
 *  - ACTION_NOTIFICATION_CLICKED -> opens the finished file.
 */
class DownloadReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_NOTIFICATION_CLICKED = "com.browser2.app.DOWNLOAD_NTF_CLICK"
        private const val EXTRA_TITLE = "dl_title"

        fun buildRequest(
            ctx: Context,
            url: String,
            title: String,
            askPath: Boolean,
            cookie: String?
        ): DownloadManager.Request {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(title)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .addRequestHeader("Cookie", cookie ?: "")
                .setMimeType("application/octet-stream")

            if (Build.VERSION.SDK_INT >= 29 && !askPath) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, title)
            } else {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, title)
            }
            return request
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> handleComplete(context, intent)
            ACTION_NOTIFICATION_CLICKED -> {
                val title = intent.getStringExtra(EXTRA_TITLE)
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    title ?: "file"
                )
                openFile(context, file)
            }
        }
    }

    private fun handleComplete(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L) return
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(id)
        runCatching {
            val cursor = dm.query(query)
            if (cursor != null && cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                val localTitle = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_TITLE))
            val uriStr = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
                ?: cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME))
            val reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON))
            cursor.close()

            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> notifySuccess(context, id, localTitle, uriStr)
                DownloadManager.STATUS_FAILED -> notifyFailed(context, localTitle, reason)
                else -> Unit
            }
            } else cursor?.close()
        }
    }

    private fun notifySuccess(context: Context, id: Long, title: String?, uri: String?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Notify.createChannels(context)
        if (!Notify.canPost(context)) return

        val clickIntent = Intent(context, DownloadReceiver::class.java)
            .setAction(ACTION_NOTIFICATION_CLICKED)
            .putExtra(EXTRA_TITLE, title ?: "file")
        val clickPi = PendingIntent.getBroadcast(context, funId(id), clickIntent, pendingFlags())

        val file = try {
            File(java.net.URI(uri ?: ""))
        } catch (_: Exception) {
            null
        }
        val size = file?.takeIf { it.exists() }?.length() ?: 0L

        val ntf = NotificationCompat.Builder(context, Notify.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download_done)
            .setContentTitle(context.getString(R.string.download_complete))
            .setContentText(if (size > 0) "${Formatter.formatFileSize(context, size)} • $title" else title)
            .setContentIntent(clickPi)
            .setAutoCancel(true)
            .build()

        runCatching { nm.notify(id.toInt(), ntf) }
    }

    private fun notifyFailed(context: Context, title: String?, reason: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Notify.createChannels(context)
        if (!Notify.canPost(context)) return
        val ntf = NotificationCompat.Builder(context, Notify.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download_error)
            .setContentTitle(context.getString(R.string.download_failed))
            .setContentText("${title ?: ""} (${reason})")
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(reason + 10000, ntf) }
    }

    private fun openFile(context: Context, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, R.string.download_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = try {
            FileProvider.getUriForFile(context, "com.browser2.app.fileprovider", file)
        } catch (_: Exception) {
            Uri.fromFile(file)
        }
        val mime = ContentType.fromName(file.name)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show() }
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT

    private fun funId(l: Long): Int = (l and 0x7fffffffL).toInt()
}

/** Tiny MIME type guesser used for opening downloads with the right app. */
object ContentType {
    private val map = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "webp" to "image/webp", "svg" to "image/svg+xml",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg",
        "wav" to "audio/wav", "flac" to "audio/flac",
        "mp4" to "video/mp4", "webm" to "video/webm", "3gp" to "video/3gpp", "mkv" to "video/x-matroska",
        "pdf" to "application/pdf", "apk" to "application/vnd.android.package-archive",
        "zip" to "application/zip", "txt" to "text/plain", "html" to "text/html"
    )

    fun fromName(name: String): String =
        map[name.substringAfterLast('.', "").lowercase()] ?: "*/*"
}
