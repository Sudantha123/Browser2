package com.browser2.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Metadata about a media element, captured by BrowserJsBridge.onMediaDetected. */
data class MavInfo(
    val url: String,
    val mime: String,
    val title: String,
    val artist: String,
    val artUrl: String,
    val duration: Double,
    val canPrev: Boolean = false,
    val canNext: Boolean = false,
    val tag: String = ""
)

/**
 * A tiny WebView whose only job is to host the streaming media element off-screen.
 * It is created after the user confirms "play in background", so it behaves like a
 * plain audio/video viewer rather than a second full browser.
 */
class MediaSurfaceView(
    context: Context,
    private val handler: Handler,
    private val onEvent: (State, Double) -> Unit
) : WebView(context) {

    enum class State { IDLE, PLAYING, PAUSED, ENDED }

    init {
        setBackgroundColor(0x00000000)
        settings.javaScriptEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = "${settings.userAgentString} Browser2Playback"

        webViewClient = object : WebViewClient() {}
        webChromeClient = object : WebChromeClient() {}

        addJavascriptInterface(
            object {
                @JavascriptInterface
                @Suppress("unused")
                fun onTime(state: String, current: Double, duration: Double) {
                    val s = when (state) {
                        "playing" -> State.PLAYING
                        "paused" -> State.PAUSED
                        "ended" -> State.ENDED
                        else -> State.IDLE
                    }
                    handler.post { onEvent(s, duration) }
                }
            },
            "mav"
        )
    }

    fun load(mav: MavInfo) {
        val url = mav.url
        if (mav.mime.startsWith("audio/") || mav.mime.isBlank()) {
            post {
                loadUrl("data:text/html;charset=utf-8," + Uri.encode(audioPageWith(url)))
            }
        } else {
            post { loadUrl(url, mapOf("Referer" to refererOf(url))) }
            postDelayed({
                runCatching { evaluateJavascript("(function(){try{document.querySelector('video').play();}catch(e){}})();", null) }
            }, 1500)
        }
    }

    private fun audioPageWith(src: String): String {
        val safeSrc = src.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{background:#000;margin:0}audio,video{width:100%}</style></head>
            <body><audio controls autoplay crossorigin="anonymous"></audio>
            <script>
            var a=document.getElementsByTagName('audio')[0];
            a.src="$safeSrc";
            var last='';
            function report(s){if(last!==s){last=s;mav.onTime(s,a.currentTime||0,a.duration||0);}}
            function sync(){if(a.readyState>1){report('playing');}}
            a.addEventListener('playing',function(){report('playing');});
            a.addEventListener('pause',function(){report('paused');});
            a.addEventListener('ended',function(){report('ended');});
            a.addEventListener('error',function(){report('ended');});
            a.addEventListener('timeupdate',sync);
            a.play().then(sync)['catch'](function(e){report('ended');});
            </script></body></html>
        """.trimIndent()
    }

    private fun refererOf(url: String): String =
        try { val u = Uri.parse(url); "${u.scheme}://${u.host}" } catch (_: Exception) { url }

    fun play() {
        post { runCatching { evaluateJavascript("(function(){var m=document.querySelector('audio,video');if(m){m.play();}})();", null) } }
    }

    fun pause() {
        post { runCatching { evaluateJavascript("(function(){var m=document.querySelector('audio,video');if(m){m.pause();}})();", null) } }
    }

    /** Detaches and destroys the view. Safe to call more than once. */
    fun release() {
        runCatching {
            (parent as? ViewGroup)?.removeView(this)
        }
        runCatching {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
    }
}

/**
 * Sits inside each user-facing tab WebView. It receives media events fired by the
 * injected page script and forwards them to whatever listener the MainActivity set.
 */
object BrowserJsBridge {
    var listener: ((MavInfo) -> Unit)? = null

    @SuppressLint("JavascriptInterface")
    fun attach(view: WebView) {
        view.addJavascriptInterface(object {
            @JavascriptInterface
            @Suppress("unused")
            fun onMediaDetected(
                url: String, mime: String, title: String,
                artist: String, art: String, duration: Double
            ) {
                if (url.isBlank() || !url.startsWith("http")) return
                val l = listener ?: return
                Handler(android.os.Looper.getMainLooper()).post {
                    l.invoke(MavInfo(url, mime, title, artist, art, duration))
                }
            }
        }, "b2")
    }
}

/** Helper that pulls a network bitmap on a background thread (no large deps). */
object HttpBitmap {
    fun get(ctx: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
            conn.instanceFollowRedirects = true
            val input: InputStream = conn.inputStream
            input.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }
}

/** Routes hardware volume keys to the music stream. */
object AudioFocusHelper {
    fun setVolumeControlStream(activity: android.app.Activity) {
        activity.volumeControlStream = AudioManager.STREAM_MUSIC
    }
}
