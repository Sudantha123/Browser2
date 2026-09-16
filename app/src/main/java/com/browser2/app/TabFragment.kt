package com.browser2.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * One tab. Owns a real WebView and wires up navigation, downloads, inline media and the
 * media-detection bridge. The [tab] model carries all the state the MainActivity needs so a
 * fresh WebView can be built on demand (zero WebViews held for background tabs).
 */
class TabFragment(
    private val tab: Tab,
    private val settings: Settings,
    @Suppress("unused") private val searchTemplate: String,
    private val host: Host,
    private val providedWebView: WebView? = null
) : Fragment() {

    interface Host {
        fun onPageStarted(fragment: TabFragment, url: String)
        fun onPageFinished(fragment: TabFragment, url: String)
        fun onProgress(fragment: TabFragment, progress: Int)
        fun onTitle(fragment: TabFragment, title: String)
        fun onNavigationChanged(fragment: TabFragment)
        fun onMediaDetected(fragment: TabFragment, mav: MavInfo)
        fun onFullScreenRequested(fragment: TabFragment, view: View?)
        fun onPopupRequest(fragment: TabFragment, view: WebView, resultMsg: android.os.Message)
    }

    var webView: WebView? = null
        private set
    private var fullscreenView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var lastTitle = ""

    val isIncognito get() = tab.incognito

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val frame = FrameLayout(requireContext())
        if (providedWebView != null) frame.addView(providedWebView)
        return frame
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureWebView()
    }

    private fun ensureWebView() {
        if (webView != null) return
        val wv = providedWebView ?: WebView(requireContext()).also {
            it.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            it.setBackgroundColor(if (tab.incognito) 0xFF14141A.toInt() else 0xFFFFFFFF.toInt())
            it.isVerticalScrollBarEnabled = false
            it.isHorizontalScrollBarEnabled = false
        }
        configure(wv)
        webView = wv
        if (wv.parent == null) {
            val root = view
            if (root != null && root is FrameLayout) root.addView(wv)
        }
        if (providedWebView == null) {
            if (tab.url.isNotBlank()) wv.loadUrl(tab.url) else wv.loadUrl("about:blank")
        }
    }

    /** We keep one config path so popup-provided WebViews behave identically. */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(wv: WebView) {
        val ws = wv.settings
        ws.javaScriptEnabled = settings.enableJavaScript
        ws.domStorageEnabled = settings.enableDomStorage
        ws.javaScriptCanOpenWindowsAutomatically = true
        ws.setSupportMultipleWindows(true)
        ws.loadsImagesAutomatically = true
        ws.defaultTextEncodingName = "UTF-8"
        ws.useWideViewPort = true
        ws.loadWithOverviewMode = true
        ws.builtInZoomControls = true
        ws.displayZoomControls = false
        ws.setSupportZoom(true)
        ws.allowFileAccess = false
        ws.allowContentAccess = false
        ws.cacheMode = WebSettings.LOAD_DEFAULT
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        ws.mediaPlaybackRequiresUserGesture = false
        ws.setTextZoom(100)
        ws.setGeolocationEnabled(false)

        if (settings.desktopByDefault) tab.isDesktop = true
        applyUserAgent(wv)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, settings.enableThirdPartyCookies)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return when {
                    url.startsWith("http://") || url.startsWith("https://") -> false
                    url.startsWith("blob:") || url.startsWith("data:") || url.startsWith("about:") -> false
                    url.startsWith("intent://") || url.startsWith("tel:") || url.startsWith("mailto:") ||
                        url.startsWith("sms:") || url.startsWith("geo:") -> {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        true
                    }
                    else -> false
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = null

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                tab.url = url
                tab.themeColor = -1
                host.onPageStarted(this@TabFragment, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                tab.url = url
                tab.canGoBack = view.canGoBack()
                tab.canGoForward = view.canGoForward()
                host.onPageFinished(this@TabFragment, url)
                injectMediaProbe(view)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                tab.url = url
                tab.canGoBack = view.canGoBack()
                tab.canGoForward = view.canGoForward()
                host.onNavigationChanged(this@TabFragment)
            }
        }

        wv.setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleDownload(url, contentDisposition ?: "", mimeType ?: "")
        })

        BrowserJsBridge.attach(wv)

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                host.onProgress(this@TabFragment, newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String) {
                if (title.isNotBlank() && !title.startsWith("http")) {
                    tab.title = title
                    lastTitle = title
                } else {
                    tab.title = lastTitle.ifBlank { Helpers.prettyTitle(tab.url) }
                }
                host.onTitle(this@TabFragment, tab.title)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                fullscreenView = view
                customViewCallback = callback
                host.onFullScreenRequested(this@TabFragment, view)
            }

            override fun onHideCustomView() {
                fullscreenView = null
                customViewCallback = null
                host.onFullScreenRequested(this@TabFragment, null)
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                host.onPopupRequest(this@TabFragment, view, resultMsg)
                return true
            }
        }
    }

    private fun handleDownload(url: String, contentDisposition: String, mimeType: String) {
        val ctx = requireContext()
        val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val isAttachment = contentDisposition.contains("attachment", ignoreCase = true)

        if (mimeType.startsWith("text/html") && !isAttachment) {
            webView?.loadUrl(url)
            return
        }
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cookie = CookieManager.getInstance().getCookie(url)
        val request = DownloadReceiver.buildRequest(ctx, url, guessedName, false, cookie)
        runCatching {
            dm.enqueue(request)
            Toast.makeText(ctx, ctx.getString(R.string.download_started), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(ctx, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyUserAgent(wv: WebView) {
        val desktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        val mobile = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        wv.settings.userAgentString = if (tab.isDesktop) desktop else mobile
    }

    fun toggleDesktopMode() {
        tab.isDesktop = !tab.isDesktop
        webView?.let {
            applyUserAgent(it)
            it.reload()
        }
    }

    private fun injectMediaProbe(view: WebView) {
        val script =
            "(function(){" +
                "function probe(el){" +
                "var t=(el.tagName||'').toLowerCase();" +
                "var u=el.currentSrc||el.src||'';" +
                "if(!u||u.indexOf('blob:')===0||u.indexOf('http')!==0)return;" +
                "var art='';" +
                "var meta=document.querySelector('meta[property=\\\"og:image\\\"]');" +
                "if(meta&&meta.content)art=meta.content;" +
                "if(!art){var link=document.querySelector('link[rel=\\\"image_src\\\"]');if(link&&link.href)art=link.href;}" +
                "var title=document.title||'';" +
                "var dur=0;if(!isNaN(el.duration)&&isFinite(el.duration))dur=el.duration;" +
                "var host=(location.host||'').toLowerCase();" +
                "if(t==='video'&&(host.indexOf('youtube')>=0||host.indexOf('youtu.be')>=0)){" +
                "if(location.href.indexOf('/watch')<0)return;" +
                "}" +
                "b2.onMediaDetected(u,(t==='audio'?'audio/mpeg':'video/mp4'),title,'',art,dur);" +
                "}" +
                "['playing','play','loadedmetadata'].forEach(function(ev){" +
                "document.addEventListener(ev,function(e){var t=e.target||document;var tg=(t.tagName||'').toLowerCase();if(tg==='video'||tg==='audio')probe(t);},true);" +
                "});" +
                "})();"
        runCatching { view.evaluateJavascript(script, null) }
    }

    fun goBack() = webView?.goBack()
    fun goForward() = webView?.goForward()
    fun reload() = webView?.reload()
    fun stop() = webView?.stopLoading()

    fun loadUrl(url: String) {
        val wv = webView ?: return
        wv.loadUrl(url)
    }

    fun evaluate(script: String, cb: ValueCallback<String>?) {
        runCatching { webView?.evaluateJavascript(script, cb) }
    }

    override fun onPause() {
        // Note: do NOT call pauseTimers() here — if background media is playing, pausing
        // JS timers would stop YouTube Music etc. WebView.onPause() only affects the
        // rendering thread of THIS tab, which is safe.
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.resumeTimers()
        webView?.onResume()
    }

    override fun onDestroyView() {
        destroyWebView()
        super.onDestroyView()
    }

    /** Removes and destroys the WebView so background tabs use zero memory/CPU. */
    fun destroyWebView() {
        val wv = webView
        webView = null
        if (fullscreenView != null) {
            host.onFullScreenRequested(this, null)
            fullscreenView = null
            customViewCallback = null
        }
        runCatching {
            wv?.apply {
                stopLoading()
                onPause()
                (parent as? ViewGroup)?.removeView(this)
                removeAllViews()
                destroy()
            }
        }
    }
}
