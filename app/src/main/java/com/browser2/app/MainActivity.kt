package com.browser2.app

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity(), TabFragment.Host {

    companion object {
        private const val REQ_NOTIFICATIONS = 1002
    }

    private lateinit var container: FrameLayout
    private lateinit var hsc: HorizontalScrollView
    private lateinit var tabStrip: LinearLayout
    private lateinit var addressInput: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var homeButton: ImageButton
    private lateinit var tabButton: ImageButton
    private lateinit var menuButton: ImageButton
    private lateinit var incognitoDot: TextView

    private var currentFragment: TabFragment? = null
    private var currentTab: Tab? = null
    private var incognito = false
    private var fullscreenContent: View? = null
    private var confirmExitToast: Toast? = null
    private var backHandledAt = 0L

    private val isTablet: Boolean
        get() = (resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >=
            Configuration.SCREENLAYOUT_SIZE_LARGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = findViewById(R.id.web_container)
        hsc = findViewById(R.id.tab_scroll)
        tabStrip = findViewById(R.id.tab_strip)
        addressInput = findViewById(R.id.address_input)
        progressBar = findViewById(R.id.progress_bar)
        backButton = findViewById(R.id.btn_back)
        forwardButton = findViewById(R.id.btn_forward)
        homeButton = findViewById(R.id.btn_home)
        tabButton = findViewById(R.id.btn_tabs)
        menuButton = findViewById(R.id.btn_menu)
        incognitoDot = findViewById(R.id.incognito_dot)

        Notify.createChannels(this)
        if (Notify.needsPermissionRequest(this)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }

        BrowserJsBridge.listener = { mav ->
            currentFragment?.let { onMediaDetected(it, mav) }
        }

        incognito = Prefs.incognitoOn(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressed()
            }
        })

        backButton.setOnClickListener { handleBackPressed() }
        forwardButton.setOnClickListener { currentFragment?.webView?.goForward() }
        homeButton.setOnClickListener { navigateTo(Prefs.home(this)) }
        tabButton.setOnClickListener { if (isTablet) openNewTab(true) else showTabSwitcher() }
        menuButton.setOnClickListener { openMenu() }

        addressInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                navigateFromAddressInput()
                true
            } else false
        }

        applyIncognitoTheme()

        if (Session.tabs.isEmpty() && Session.incognitoTabs.isEmpty()) {
            openNewTab(true)
        } else {
            switchToStoredTab()
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.dataString?.let { navigateTo(it) }
        }
    }

    private fun switchToStoredTab() {
        val list = activeList()
        if (list.isEmpty()) {
            openNewTab(true)
            return
        }
        val idx = Prefs.lastTabIndex(this).coerceIn(0, list.lastIndex)
        showTab(list[idx])
    }

    // ---------------------------------------------------------------- tabs ---

    private fun activeList(): ArrayList<Tab> = if (incognito) Session.incognitoTabs else Session.tabs

    private fun openNewTab(focusAddress: Boolean) {
        val tab = Tab.new(incognito)
        activeList().add(tab)
        showTab(tab)
        if (focusAddress) {
            addressInput.requestFocus()
            addressInput.selectAll()
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
    }

    private fun showTab(tab: Tab) {
        currentTab = tab
        Session.activeTab = tab
        val fragment = TabFragment(tab, collectSettings(), Prefs.searchEngine(this), this)
        val old = currentFragment
        currentFragment = fragment

        // Destroy every other WebView so exactly one exists at a time (0 RAM/CPU
        // held for background tabs — they keep only their URL as a String).
        supportFragmentManager.fragments.filterIsInstance<TabFragment>().forEach {
            if (it !== fragment) it.destroyWebView()
        }

        val ft = supportFragmentManager.beginTransaction()
        if (old != null) ft.remove(old)
        ft.replace(R.id.web_container, fragment, "tab_${tab.id}")
        ft.commitAllowingStateLoss()

        hsc.post {
            hsc.fullScroll(View.FOCUS_RIGHT)
            rebuildTabStrip()
            refreshAll()
        }
    }

    private fun closeTab(tab: Tab?, fragment: TabFragment?): Boolean {
        val list = activeList()
        if (list.isEmpty()) return false
        val idx = list.indexOfFirst { it.id == tab?.id }
        if (idx < 0) return false
        if (list.size == 1) {
            // Never close the last remaining tab; treat as home/blank instead.
            navigateTo(Prefs.home(this))
            return true
        }
        list.removeAt(idx)
        fragment?.destroyWebView()
        Session.activeTab = null
        val next = list[(idx - 1).coerceAtLeast(0)]
        showTab(next)
        return true
    }

    /** Unified back button / back key handler. */
    private fun handleBackPressed() {
        if (fullscreenContent != null) {
            exitFullscreen()
            return
        }
        val wv = currentFragment?.webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }
        if (closeTab(currentTab, currentFragment)) return

        val now = System.currentTimeMillis()
        if (now - backHandledAt < 2200) {
            confirmExitToast?.cancel()
            if (incognito) {
                // leave incognito instead of killing the app
                toggleIncognito()
            } else {
                finish()
            }
        } else {
            backHandledAt = now
            confirmExitToast?.cancel()
            confirmExitToast = Toast.makeText(
                this@MainActivity,
                if (incognito) getString(R.string.exit_incognito_hint) else getString(R.string.exit_app_hint),
                Toast.LENGTH_SHORT
            )
            confirmExitToast?.show()
        }
    }

    private fun showTabSwitcher() {
        val list = activeList()
        val titles = list.mapIndexed { i, t ->
            "「${t.title.ifBlank { Helpers.prettyTitle(t.url.takeIf { it.isNotBlank() } ?: "new") }}」" +
                if (t.id == currentTab?.id) "  •" else ""
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.tabs))
            .setItems(titles) { _, which ->
                showTab(list[which])
            }
            .show()
    }

    // ------------------------------------------------------------ navigation --

    private fun navigateTo(url: String) {
        val raw = url.ifBlank { Prefs.home(this) }
        val final = if (raw.contains("://")) raw else "https://$raw"
        currentFragment?.loadUrl(final)
        addressInput.setText(final)
        addressInput.clearFocus()
        hideKeyboard()
    }

    private fun navigateFromAddressInput() {
        val typed = addressInput.text.toString()
        val final = Helpers.toUrl(typed, Prefs.searchEngine(this))
        navigateTo(final)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(addressInput.windowToken, 0)
    }

    // -------------------------------------------------------------- settings --

    private fun collectSettings(): Settings = Settings.collect(
        js = Prefs.javascriptEnabled(this),
        dom = true,
        tpCookies = true,
        safe = false,
        cache = true,
        desktop = Prefs.desktopModeDefault(this),
        downloadAsk = false,
        autoplay = true
    )

    private fun applyIncognitoTheme() {
        window.statusBarColor = if (incognito) Color.rgb(38, 38, 46) else Color.rgb(246, 246, 248)
        window.navigationBarColor = window.statusBarColor
        updateIncognitoIndicator()
    }

    private fun updateIncognitoIndicator() {
        incognitoDot.visibility = if (incognito) View.VISIBLE else View.GONE
        addressInput.setBackgroundResource(if (incognito) R.drawable.bg_address_incognito else R.drawable.bg_address)
    }

    private fun refreshAll() {
        val tab = currentTab
        val url = tab?.url.orEmpty()
        if (addressInput.text.toString() != url) addressInput.setText(url)
        backButton.alpha = if (tab?.canGoBack == true) 1f else 0.35f
        forwardButton.alpha = if (tab?.canGoForward == true) 1f else 0.35f
        rebuildTabStrip()
    }

    private fun openMenu() {
        val items = arrayOf(
            getString(R.string.new_tab),
            getString(R.string.incognito_mode) + if (incognito) "  ✓" else "",
            getString(R.string.downloads),
            getString(R.string.desktop_mode) + if (currentTab?.isDesktop == true) "  ✓" else "",
            getString(R.string.reload),
            getString(R.string.share),
            getString(R.string.settings)
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openNewTab(false)
                    1 -> toggleIncognito()
                    2 -> openDownloads()
                    3 -> currentFragment?.toggleDesktopMode()
                    4 -> currentFragment?.reload()
                    5 -> shareCurrent()
                    6 -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            .show()
    }

    private fun toggleIncognito() {
        if (!incognito) {
            incognito = true
            Prefs.setIncognitoOn(this, true)
            applyIncognitoTheme()
            if (Session.incognitoTabs.isEmpty()) openNewTab(true)
            else showTab(Session.incognitoTabs[Session.incognitoTabs.lastIndex])
            Toast.makeText(this, R.string.incognito_enabled, Toast.LENGTH_SHORT).show()
        } else {
            // Leaving incognito: drop every incognito tab from memory (secrets never hit disk).
            Session.incognitoTabs.clear()
            supportFragmentManager.fragments.filterIsInstance<TabFragment>().forEach {
                if (it.isIncognito) it.destroyWebView()
            }
            if (currentTab?.incognito == true) currentTab = null
            incognito = false
            Prefs.setIncognitoOn(this, false)
            applyIncognitoTheme()
            if (Session.tabs.isEmpty()) openNewTab(true)
            else showTab(Session.tabs[Session.tabs.lastIndex])
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            Toast.makeText(this, R.string.incognito_disabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun exitFullscreen() {
        val view = fullscreenContent
        fullscreenContent = null
        runCatching { (view?.parent as? ViewGroup)?.removeView(view) }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun shareCurrent() {
        val url = currentTab?.url ?: return
        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    },
                    getString(R.string.share)
                )
            )
        }
    }

    private fun openDownloads() {
        runCatching {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        }.onFailure {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("content://downloads/public_downloads"))) }
        }
    }
    // ---------------------------------------------------------- tab strip ----

    private fun rebuildTabStrip() {
        tabStrip.removeAllViews()
        val list = activeList()
        list.forEachIndexed { i, tab ->
            val title = tab.title.takeUnless { it.isBlank() || it == "New tab" }
                ?: Helpers.prettyTitle(tab.url.takeIf { it.isNotBlank() } ?: "…")
            val chip = TextView(this).apply {
                text = title
                setSingleLine(true)
                setTextSize(12f)
                maxWidth = dp(150)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setAllCaps(false)
                setTextColor(if (currentTab?.id == tab.id) Color.WHITE else Color.rgb(96, 96, 108))
                setBackgroundResource(if (currentTab?.id == tab.id) R.drawable.bg_tab_active else R.drawable.bg_tab)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = dp(5)
                layoutParams = lp
                setOnClickListener { showTab(list[i]) }
                setOnLongClickListener {
                    closeTab(tab, supportFragmentManager.findFragmentByTag("tab_${tab.id}") as? TabFragment)
                    true
                }
            }
            tabStrip.addView(chip)
        }
        val plus = TextView(this).apply {
            text = "＋"
            setTextSize(16f)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(120, 120, 132))
            setPadding(dp(12), dp(3), dp(12), dp(3))
            setOnClickListener { openNewTab(false) }
        }
        tabStrip.addView(plus)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // --------------------------------------------------------- Host events ----

    override fun onPageStarted(fragment: TabFragment, url: String) {
        if (fragment !== currentFragment) return
        addressInput.setText(url)
        progressBar.progress = 8
        progressBar.visibility = View.VISIBLE
    }

    override fun onPageFinished(fragment: TabFragment, url: String) {
        if (fragment !== currentFragment) return
        addressInput.setText(url)
        progressBar.visibility = View.GONE
        refreshAll()
    }

    override fun onProgress(fragment: TabFragment, progress: Int) {
        if (fragment !== currentFragment) return
        progressBar.progress = progress.coerceIn(4, 100)
    }

    override fun onTitle(fragment: TabFragment, title: String) {
        if (fragment !== currentFragment) return
        currentTab?.title = title
        rebuildTabStrip()
    }

    override fun onNavigationChanged(fragment: TabFragment) {
        if (fragment !== currentFragment) return
        refreshAll()
    }

    override fun onPopupRequest(fragment: TabFragment, view: WebView, resultMsg: android.os.Message) {
        // Popup / target=_blank: give the engine a fresh WebView and show it as a new tab.
        val newWebView = WebView(this)
        runCatching {
            (resultMsg.obj as? WebView.WebViewTransport)?.webView = newWebView
            resultMsg.sendToTarget()
        }
        val tab = Tab.new(incognito)
        activeList().add(tab)
        showTabWithProvidedView(tab, newWebView)
    }

    /** Like [showTab] but reuses an existing WebView (used for popup windows). */
    private fun showTabWithProvidedView(tab: Tab, webView: WebView) {
        currentTab = tab
        Session.activeTab = tab
        val fragment = TabFragment(tab, collectSettings(), Prefs.searchEngine(this), this, webView)
        val old = currentFragment
        currentFragment = fragment

        supportFragmentManager.fragments.filterIsInstance<TabFragment>().forEach {
            if (it !== fragment) it.destroyWebView()
        }

        val ft = supportFragmentManager.beginTransaction()
        if (old != null) ft.remove(old)
        ft.replace(R.id.web_container, fragment, "tab_${tab.id}")
        ft.commitAllowingStateLoss()

        hsc.post {
            rebuildTabStrip()
            refreshAll()
        }
    }

    override fun onMediaDetected(fragment: TabFragment, mav: MavInfo) {
        if (fragment !== currentFragment) return
        if (mav.url.isBlank()) return
        val title = mav.title.ifBlank { Helpers.prettyTitle(mav.url) }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.play_in_background))
            .setMessage(title + "\n\n" + getString(R.string.play_bg_hint))
            .setPositiveButton(getString(R.string.yes_continue)) { _, _ ->
                if (Notify.canPost(this)) {
                    playInBackground(mav)
                } else {
                    pendingMav = mav
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
                }
            }
            .setNegativeButton(getString(R.string.no_thanks), null)
            .show()
    }

    private var pendingMav: MavInfo? = null

    /** Starts background playback: hidden 1x1 media WebView + foreground service (notification). */
    private fun playInBackground(mav: MavInfo) {
        pendingMav = null
        MediaRuntime.release()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val mediaView = MediaSurfaceView(this, handler) { state, _ ->
            when (state) {
                MediaSurfaceView.State.PLAYING -> MediaPlaybackService.updatePlaying(true, 0L)
                MediaSurfaceView.State.PAUSED -> MediaPlaybackService.updatePlaying(false, 0L)
                MediaSurfaceView.State.ENDED -> {
                    MediaPlaybackService.stop()
                    MediaRuntime.release()
                }
                else -> Unit
            }
        }
        val root = findViewById<ViewGroup>(android.R.id.content)
        root.addView(
            mediaView,
            FrameLayout.LayoutParams(2, 2).apply { gravity = Gravity.TOP or Gravity.START }
        )
        MediaRuntime.activeView = mediaView
        mediaView.load(mav)
        MediaPlaybackService.start(this, mav)
    }

    override fun onFullScreenRequested(fragment: TabFragment, view: View?) {
        if (view != null) {
            fullscreenContent = view
            (view.parent as? ViewGroup)?.removeView(view)
            container.addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        } else {
            (fullscreenContent?.parent as? ViewGroup)?.removeView(fullscreenContent)
            fullscreenContent = null
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // ------------------------------------------------------------ lifecycle ----

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.bg_play_enabled, Toast.LENGTH_SHORT).show()
            pendingMav?.let { playInBackground(it) }
        }
    }

    override fun onPause() {
        currentFragment?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        AudioFocusHelper.setVolumeControlStream(this)
        currentFragment?.onResume()
        if (incognito) applyIncognitoTheme()
    }

    override fun onDestroy() {
        Prefs.setIncognitoOn(this, incognito)
        currentTab?.let { t ->
            val list = if (t.incognito) Session.incognitoTabs else Session.tabs
            val idx = list.indexOfFirst { it.id == t.id }
            if (idx >= 0) Prefs.setLastTabIndex(this, idx)
        }
        // Only release media here if the activity is really finishing (not a config change).
        if (isFinishing) MediaRuntime.release()
        super.onDestroy()
    }
}
