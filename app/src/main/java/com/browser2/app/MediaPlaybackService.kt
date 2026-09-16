package com.browser2.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaMetadataCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/**
 * Foreground service that keeps music / video playing after the browser is closed.
 *
 * The actual media element lives in a hidden 1x1 WebView inside [MainActivity] (see
 * [MediaRuntime]). This service only hosts the MediaSession + notification so Android grants
 * the process foreground priority and shows thumbnails with prev / play-pause / next controls.
 */
class MediaPlaybackService : Service() {

    companion object {
        const val ACTION_START = "com.browser2.app.action.START_MEDIA"
        const val ACTION_STOP = "com.browser2.app.action.STOP_MEDIA"
        private const val NOTIFICATION_ID = 77

        @Volatile
        private var instance: MediaPlaybackService? = null

        private fun startInternal(ctx: Context, mav: MavInfo) {
            val intent = Intent(ctx, MediaPlaybackService::class.java)
                .setAction(ACTION_START)
                .putExtra("url", mav.url)
                .putExtra("mime", mav.mime)
                .putExtra("title", mav.title)
                .putExtra("artist", mav.artist)
                .putExtra("art", mav.artUrl)
                .putExtra("duration", mav.duration)
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun start(ctx: Context, mav: MavInfo) {
            try {
                startInternal(ctx, mav)
            } catch (_: Exception) {
                // Fallback: plain startService works when the app is already in the foreground.
                runCatching {
                    ctx.startService(
                        Intent(ctx, MediaPlaybackService::class.java).setAction(ACTION_START)
                            .putExtra("url", mav.url)
                            .putExtra("mime", mav.mime)
                            .putExtra("title", mav.title)
                            .putExtra("artist", mav.artist)
                            .putExtra("art", mav.artUrl)
                            .putExtra("duration", mav.duration)
                    )
                }
            }
        }

        fun stop() {
            instance?.handler?.post { instance?.stopSelf() }
        }

        /** Called by the activity's media view whenever playback state changes. */
        fun updatePlaying(playing: Boolean, durationMs: Long) {
            instance?.handler?.post { instance?.onBridgeState(playing, durationMs) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var session: MediaSessionCompat? = null
    private var reportedPlaying = true
    private var currentTitle = ""
    private var currentArtist = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notify.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        Notify.createChannels(this)
        val mav = MavInfo(
            url = intent?.getStringExtra("url").orEmpty(),
            mime = intent?.getStringExtra("mime").orEmpty(),
            title = intent?.getStringExtra("title").orEmpty(),
            artist = intent?.getStringExtra("artist").orEmpty(),
            artUrl = intent?.getStringExtra("art").orEmpty(),
            duration = intent?.getDoubleExtra("duration", 0.0) ?: 0.0
        )
        if (mav.url.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        initSession()
        currentTitle = mav.title.ifBlank { Helpers.prettyTitle(mav.url) }
        currentArtist = mav.artist
        reportedPlaying = true

        updateMetadata(mav)
        updatePlaybackState()
        startFg(NOTIFICATION_ID, buildNotification(playing = true))

        return START_NOT_STICKY
    }

    private fun startFg(id: Int, ntf: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(id, ntf, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(id, ntf)
            }
        }.onFailure {
            runCatching { startForeground(id, ntf) }
        }
    }

    private fun initSession() {
        if (session != null) return
        val s = MediaSessionCompat(this, "Browser2Media")
        s.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        s.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                reportedPlaying = true
                MediaRuntime.activeView?.play()
                updatePlaybackState()
            }

            override fun onPause() {
                reportedPlaying = false
                MediaRuntime.activeView?.pause()
                updatePlaybackState()
            }

            override fun onSkipToNext() {
                stopSelf()
            }

            override fun onSkipToPrevious() {
                stopSelf()
            }

            override fun onStop() {
                stopSelf()
            }
        })
        s.isActive = true
        session = s
    }

    private fun onBridgeState(playing: Boolean, durationMs: Long) {
        reportedPlaying = playing
        updatePlaybackState()
    }

    private fun updateMetadata(mav: MavInfo) {
        val s = session ?: return
        val b = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, mav.url)
        if (mav.duration > 0) {
            b.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (mav.duration * 1000).toLong())
        }
        s.setMetadata(b.build())

        Thread {
            runCatching {
                val bmp = HttpBitmap.get(this, mav.artUrl)
                if (bmp != null) {
                    handler.post {
                        session?.setMetadata(
                            MediaMetadataCompat.Builder(session?.controller?.metadata)
                                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bmp)
                                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bmp)
                                .build()
                        )
                        refreshNotification()
                    }
                }
            }
        }.start()
    }

    private fun updatePlaybackState() {
        val s = session ?: return
        val state = if (reportedPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val sb = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
        runCatching { s.setPlaybackState(sb.build()) }
        refreshNotification()
    }

    private fun refreshNotification() {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(playing = reportedPlaying))
        }
    }

    private fun buildNotification(playing: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendFlags()
        )
        val stopIntent = PendingIntent.getService(
            this, 5,
            Intent(this, MediaPlaybackService::class.java).setAction(ACTION_STOP),
            pendFlags()
        )
        val token = session?.sessionToken
        val meta = session?.controller?.metadata

        val title = meta?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
            ?.takeUnless { it.isBlank() }
            ?: currentTitle.ifBlank { getString(R.string.now_playing) }
        val text = meta?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)
            ?.takeUnless { it.isBlank() }
            ?: currentArtist.ifBlank { Helpers.prettyTitle("") }

        var b: NotificationCompat.Builder = NotificationCompat.Builder(this, Notify.CHANNEL_MEDIA)
            .setSmallIcon(R.drawable.ic_stat_media)
            .setContentTitle(title)
            .setContentText(text.ifBlank { getString(R.string.now_playing) })
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setDeleteIntent(stopIntent)
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_prev, getString(R.string.previous),
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            )
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.pause else R.string.play),
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            )
            .addAction(
                R.drawable.ic_next, getString(R.string.next),
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, KeyEvent.KEYCODE_MEDIA_NEXT)
            )

        val art = meta?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
        if (art != null) b = b.setLargeIcon(art)

        runCatching { b = b.setStyle(MediaStyle().setMediaSession(token)) }

        return b.build()
    }

    private fun pendFlags(): Int =
        if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    override fun onDestroy() {
        instance = null
        runCatching {
            session?.isActive = false
            session?.release()
            session = null
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped away: also release the hidden media view (activity is being destroyed).
        MediaRuntime.release()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}

/** Holds the single hidden media WebView while it plays, so services can reach it. */
object MediaRuntime {
    @Volatile
    var activeView: MediaSurfaceView? = null

    fun release() {
        activeView?.release()
        activeView = null
    }
}
