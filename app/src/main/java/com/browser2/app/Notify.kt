package com.browser2.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notify {
    const val CHANNEL_MEDIA = "media_playback"
    const val CHANNEL_DOWNLOADS = "downloads"
    const val CHANNEL_GENERAL = "general"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MEDIA,
                "Media playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Media controls shown while audio/video plays" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOADS,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "File download progress and completion" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GENERAL,
                "General",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Other browser notifications" }
        )
    }

    fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return true
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    /** Returns true if Android 13+ still needs the runtime POST_NOTIFICATIONS permission. */
    fun needsPermissionRequest(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val granted = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return !granted
    }
}
