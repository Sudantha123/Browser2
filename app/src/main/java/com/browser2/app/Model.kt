package com.browser2.app

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Really small key/value store (SharedPreferences) with an all-in-memory Session store. */
object Prefs {
    private const val NAME = "browser2_prefs"
    private const val KEY_HOME = "home_url"
    private const val KEY_ENGINE = "search_engine"
    private const val KEY_DESKTOP = "desktop_mode"
    private const val KEY_JS = "javascript_enabled"
    private const val KEY_LAST_TAB = "last_tab_index"
    private const val KEY_INCOGNITO_ON = "incognito_on"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun home(ctx: Context): String =
        sp(ctx).getString(KEY_HOME, "https://www.google.com") ?: "https://www.google.com"

    fun setHome(ctx: Context, url: String) = sp(ctx).edit().putString(KEY_HOME, url).apply()

    fun searchEngine(ctx: Context): String =
        sp(ctx).getString(KEY_ENGINE, "https://www.google.com/search?q=%s")
            ?: "https://www.google.com/search?q=%s"

    fun setSearchEngine(ctx: Context, template: String) =
        sp(ctx).edit().putString(KEY_ENGINE, template).apply()

    fun desktopModeDefault(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_DESKTOP, false)
    fun setDesktopModeDefault(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_DESKTOP, v).apply()

    fun javascriptEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_JS, true)
    fun setJavascriptEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_JS, v).apply()

    fun lastTabIndex(ctx: Context): Int = sp(ctx).getInt(KEY_LAST_TAB, 0)
    fun setLastTabIndex(ctx: Context, i: Int) = sp(ctx).edit().putInt(KEY_LAST_TAB, i).apply()

    fun incognitoOn(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_INCOGNITO_ON, false)
    fun setIncognitoOn(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_INCOGNITO_ON, v).apply()
}

/** Session objects that exist only while the app process is alive (and never touch disk). */
object Session {
    /** Normal (non-incognito) tabs. An empty list is a legal state. */
    val tabs = ArrayList<Tab>()
    /** Incognito tabs. Kept separate from normal tabs. */
    val incognitoTabs = ArrayList<Tab>()
    /** Currently active tab model shared with the controls/JS bridge. */
    var activeTab: Tab? = null

    private var seq = 0
    fun nextTabId(): Long = ++seq.toLong()

    /** Clears absolutely everything in memory, used when leaving incognito mode. */
    fun clear() {
        activeTab = null
        tabs.clear()
        incognitoTabs.clear()
    }
}

/** A single in-memory tab. Contains no WebView reference: the owner fragment creates one. */
data class Tab(
    val id: Long,
    val incognito: Boolean = false,
    var url: String = "",
    var title: String = "New tab",
    var restored: Boolean = false
) {
    /** Deep, quickly-restorable state of the tab's history. */
    var scrollX: Int = 0
    var scrollY: Int = 0
    var history: ArrayList<HistoryEntry> = ArrayList()
    var themeColor: Int = -1
    var isDesktop: Boolean = false
    var canGoBack: Boolean = false
    var canGoForward: Boolean = false

    companion object {
        fun new(incognito: Boolean): Tab = Tab(id = Session.nextTabId(), incognito = incognito)
    }
}

data class HistoryEntry(val url: String, val title: String, val time: Long = System.currentTimeMillis())

class Settings private constructor(data: Map<String, Any>) {
    val enableJavaScript: Boolean = data["js"] as Boolean
    val enableDomStorage: Boolean = data["dom"] as Boolean
    val enableThirdPartyCookies: Boolean = data["tpCookies"] as Boolean
    val enableSafeBrowsing: Boolean = data["safe"] as Boolean
    val enableCache: Boolean = data["cache"] as Boolean
    val desktopByDefault: Boolean = data["desktop"] as Boolean
    val downloadAskPath: Boolean = data["downloadAsk"] as Boolean
    val autoplayMedia: Boolean = data["autoplay"] as Boolean

    /** Reads settings from the UI controls. Returns an immutable snapshot used when a WebView is built. */
    companion object {
        fun collect(
            js: Boolean, dom: Boolean, tpCookies: Boolean, safe: Boolean,
            cache: Boolean, desktop: Boolean, downloadAsk: Boolean, autoplay: Boolean
        ): Settings = Settings(
            mapOf(
                "js" to js, "dom" to dom, "tpCookies" to tpCookies, "safe" to safe,
                "cache" to cache, "desktop" to desktop, "downloadAsk" to downloadAsk,
                "autoplay" to autoplay
            )
        )
    }
}

object Helpers {
    /** Tries to build a well-formed URL out of whatever the user typed in the address bar. */
    fun toUrl(input: String, searchTemplate: String): String {
        val text = input.trim().removePrefix("view-source:")
        if (text.isEmpty()) return searchTemplate.replace("%s", "")

        val looksLikeUrl =
            text.contains('.') && !text.contains(' ') ||
                text.startsWith("http://") || text.startsWith("https://") ||
                text.startsWith("file://") || text.startsWith("about:") ||
                text.startsWith("data:")
        if (looksLikeUrl) {
            val withScheme = if (text.contains("://")) text else "https://$text"
            return withScheme
        }
        // Otherwise treat it as a search query.
        return searchTemplate.replace("%s", java.net.URLEncoder.encode(text, "UTF-8"))
    }

    fun prettyTitle(url: String): String {
        if (!url.contains("://")) return url
        return try {
            url.substringAfter("://").substringBefore('/')
        } catch (_: Exception) {
            url
        }
    }

    private val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
    fun formatTime(ms: Long): String = try { fmt.format(Date(ms)) } catch (_: Exception) { ms.toString() }
}
