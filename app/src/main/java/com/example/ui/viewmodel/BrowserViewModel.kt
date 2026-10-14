package com.example.ui.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.api.GeminiStylerService
import com.example.data.model.BookmarkEntity
import com.example.data.model.HistoryEntity
import com.example.data.repository.BrowserRepository
import com.example.data.repository.WebsiteColorRepository
import com.example.ui.model.TabItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BrowserViewModel(
    application: Application,
    private val browserRepository: BrowserRepository,
    private val websiteColorRepository: WebsiteColorRepository
) : AndroidViewModel(application) {

    private val _tabs = MutableStateFlow<List<TabItem>>(listOf(TabItem()))
    val tabs: StateFlow<List<TabItem>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String>(_tabs.value.first().id)
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    val activeTab: StateFlow<TabItem?> = combine(_tabs, _activeTabId) { tabs, activeId ->
        tabs.find { it.id == activeId } ?: tabs.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _tabs.value.first())

    val isBookmarked: StateFlow<Boolean> = activeTab.map { tab ->
        tab?.url ?: ""
    }.combine(browserRepository.bookmarks) { url, bookmarks ->
        bookmarks.any { it.url == url }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val bookmarks: StateFlow<List<BookmarkEntity>> = browserRepository.bookmarks
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val history: StateFlow<List<HistoryEntity>> = browserRepository.history
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // UI Overlay states
    private val _isOptionsMenuExpanded = MutableStateFlow(false)
    val isOptionsMenuExpanded: StateFlow<Boolean> = _isOptionsMenuExpanded.asStateFlow()

    private val _isTabGridOpen = MutableStateFlow(false)
    val isTabGridOpen: StateFlow<Boolean> = _isTabGridOpen.asStateFlow()

    private val _isBookmarksOpen = MutableStateFlow(false)
    val isBookmarksOpen: StateFlow<Boolean> = _isBookmarksOpen.asStateFlow()

    private val _isHistoryOpen = MutableStateFlow(false)
    val isHistoryOpen: StateFlow<Boolean> = _isHistoryOpen.asStateFlow()

    private val _isDownloadsOpen = MutableStateFlow(false)
    val isDownloadsOpen: StateFlow<Boolean> = _isDownloadsOpen.asStateFlow()

    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    private val _isApiLogsOpen = MutableStateFlow(false)
    val isApiLogsOpen: StateFlow<Boolean> = _isApiLogsOpen.asStateFlow()

    fun toggleApiLogs() {
        _isApiLogsOpen.value = !_isApiLogsOpen.value
    }

    private val _isFindInPageOpen = MutableStateFlow(false)
    val isFindInPageOpen: StateFlow<Boolean> = _isFindInPageOpen.asStateFlow()

    private val _findInPageQuery = MutableStateFlow("")
    val findInPageQuery: StateFlow<String> = _findInPageQuery.asStateFlow()

    private val _isReadingMode = MutableStateFlow(false)
    val isReadingMode: StateFlow<Boolean> = _isReadingMode.asStateFlow()

    private val _isJavaScriptEnabled = MutableStateFlow(true)
    val isJavaScriptEnabled: StateFlow<Boolean> = _isJavaScriptEnabled.asStateFlow()

    private val _isTrackerBlockerEnabled = MutableStateFlow(true)
    val isTrackerBlockerEnabled: StateFlow<Boolean> = _isTrackerBlockerEnabled.asStateFlow()

    // AI Website Styler (Gemini) states & Website-Wide Theming
    private val geminiStylerService = GeminiStylerService()

    data class WebsiteTheme(
        val domain: String,
        val prompt: String,
        val baseScript: String,
        val pageScripts: Map<String, String> = emptyMap()
    )

    private val _websiteThemes = MutableStateFlow<Map<String, WebsiteTheme>>(emptyMap())
    val websiteThemes: StateFlow<Map<String, WebsiteTheme>> = _websiteThemes.asStateFlow()

    private val _isAiStylerPillVisible = MutableStateFlow(false)
    val isAiStylerPillVisible: StateFlow<Boolean> = _isAiStylerPillVisible.asStateFlow()

    private val _isAiStylerLoading = MutableStateFlow(false)
    val isAiStylerLoading: StateFlow<Boolean> = _isAiStylerLoading.asStateFlow()

    private val _isThemeUpdatingInBackground = MutableStateFlow(false)
    val isThemeUpdatingInBackground: StateFlow<Boolean> = _isThemeUpdatingInBackground.asStateFlow()

    private val _aiStylerErrorMessage = MutableStateFlow<String?>(null)
    val aiStylerErrorMessage: StateFlow<String?> = _aiStylerErrorMessage.asStateFlow()

    private val _pendingUserScriptToInject = MutableStateFlow<String?>(null)
    val pendingUserScriptToInject: StateFlow<String?> = _pendingUserScriptToInject.asStateFlow()

    val isCustomStyleApplied: StateFlow<Boolean> = combine(activeTab, _websiteThemes) { tab, themes ->
        val domain = extractDomain(tab?.url ?: "")
        domain.isNotBlank() && themes.containsKey(domain)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var backgroundAdaptationJob: kotlinx.coroutines.Job? = null
    private var lastAdaptedUrl: String = ""

    fun extractDomain(url: String): String {
        if (url.isBlank()) return ""
        return try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host?.lowercase() ?: ""
            if (host.startsWith("www.")) host.removePrefix("www.") else host
        } catch (_: Exception) {
            ""
        }
    }

    fun toggleAiStylerPill() {
        _isAiStylerPillVisible.value = !_isAiStylerPillVisible.value
        _aiStylerErrorMessage.value = null
    }

    fun closeAiStylerPill() {
        _isAiStylerPillVisible.value = false
    }

    fun applyAiStyle(prompt: String) {
        val tab = activeTab.value ?: return
        val domain = extractDomain(tab.url)
        _isAiStylerLoading.value = true
        _aiStylerErrorMessage.value = null

        viewModelScope.launch {
            val siteName = if (tab.title.isNotBlank()) tab.title else tab.url
            val result = geminiStylerService.generateUserScript(
                context = getApplication(),
                userPrompt = prompt,
                websiteName = siteName,
                websiteUrl = tab.url
            )
            _isAiStylerLoading.value = false
            result.onSuccess { script ->
                if (domain.isNotBlank()) {
                    val theme = WebsiteTheme(
                        domain = domain,
                        prompt = prompt,
                        baseScript = script,
                        pageScripts = mapOf(tab.url to script)
                    )
                    _websiteThemes.value = _websiteThemes.value + (domain to theme)
                }
                _pendingUserScriptToInject.value = script

                // Feedback loop: take screenshot after render delay and optimize layout/selectors
                val wv = activeWebViewRef
                if (wv != null) {
                    kotlinx.coroutines.delay(800L)
                    captureWebViewScreenshot(wv) { screenshotBase64 ->
                        if (!screenshotBase64.isNullOrBlank()) {
                            viewModelScope.launch {
                                val width = wv.width.coerceAtLeast(1080)
                                val height = wv.height.coerceAtLeast(1920)
                                val optimizedResult = geminiStylerService.generateUserScriptWithFeedback(
                                    context = getApplication(),
                                    userPrompt = prompt,
                                    websiteName = siteName,
                                    websiteUrl = tab.url,
                                    screenshotBase64 = screenshotBase64,
                                    screenWidth = width,
                                    screenHeight = height
                                )
                                optimizedResult.onSuccess { optScript ->
                                    val currentTheme = _websiteThemes.value[domain]
                                    if (currentTheme != null) {
                                        val updatedTheme = currentTheme.copy(
                                            baseScript = optScript,
                                            pageScripts = currentTheme.pageScripts + (tab.url to optScript)
                                        )
                                        _websiteThemes.value = _websiteThemes.value + (domain to updatedTheme)
                                    }
                                    _pendingUserScriptToInject.value = optScript
                                }
                            }
                        }
                    }
                }
            }.onFailure { err ->
                _aiStylerErrorMessage.value = err.message ?: "Failed to generate styling."
            }
        }
    }

    private var activeWebViewRef: android.webkit.WebView? = null

    fun registerActiveWebView(webView: android.webkit.WebView?) {
        activeWebViewRef = webView
    }

    private fun captureWebViewScreenshot(webView: android.webkit.WebView, onCaptured: (String?) -> Unit) {
        try {
            val width = if (webView.width > 0) webView.width else 1080
            val height = if (webView.height > 0) webView.height else 1920
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            webView.draw(canvas)
            val outputStream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, outputStream)
            val byteArray = outputStream.toByteArray()
            val base64 = android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP)
            bitmap.recycle()
            onCaptured(base64)
        } catch (e: Exception) {
            onCaptured(null)
        }
    }

    fun resetCustomStyle() {
        val domain = extractDomain(activeTab.value?.url ?: "")
        if (domain.isNotBlank()) {
            _websiteThemes.value = _websiteThemes.value - domain
        }
        backgroundAdaptationJob?.cancel()
        _isThemeUpdatingInBackground.value = false
        _pendingUserScriptToInject.value = """
            (function() {
                var existing = document.getElementById('mydes-ai-styler-style');
                if (existing) existing.remove();
            })();
        """.trimIndent()
    }

    fun onUserScriptInjected() {
        _pendingUserScriptToInject.value = null
    }

    /**
     * Called when any webpage finishes loading.
     * If the website domain has an active theme, immediately applies the theme,
     * and triggers asynchronous background page-variable adaptations while showing
     * a circular loader on the border of the brush icon without blocking user access.
     */
    fun onPageLoaded(tabId: String, url: String, title: String) {
        val domain = extractDomain(url)
        if (domain.isBlank()) return

        val activeTheme = _websiteThemes.value[domain] ?: return

        // 1. Immediately inject the cached or base theme script for seamless visual continuity
        val cachedPageScript = activeTheme.pageScripts[url]
        val initialScript = cachedPageScript ?: activeTheme.baseScript
        _pendingUserScriptToInject.value = initialScript

        // 2. Perform background variable/DOM adaptations for the new page
        if (cachedPageScript == null && url != lastAdaptedUrl) {
            lastAdaptedUrl = url
            backgroundAdaptationJob?.cancel()
            backgroundAdaptationJob = viewModelScope.launch {
                // Circular loader on the border of brush icon indicates background update
                _isThemeUpdatingInBackground.value = true
                try {
                    val pageName = if (title.isNotBlank()) title else url
                    val result = geminiStylerService.adaptUserScriptForWebpage(
                        context = getApplication(),
                        basePrompt = activeTheme.prompt,
                        baseScript = activeTheme.baseScript,
                        pageTitle = pageName,
                        pageUrl = url,
                        domain = domain
                    )

                    result.onSuccess { adaptedScript ->
                        // Cache the adapted script for this URL and update the website theme
                        val currentTheme = _websiteThemes.value[domain]
                        if (currentTheme != null) {
                            val updatedTheme = currentTheme.copy(
                                pageScripts = currentTheme.pageScripts + (url to adaptedScript),
                                baseScript = adaptedScript
                            )
                            _websiteThemes.value = _websiteThemes.value + (domain to updatedTheme)
                        }

                        // Load live asynchronously if user is on this domain/URL
                        val currentUrl = activeTab.value?.url ?: ""
                        if (extractDomain(currentUrl) == domain) {
                            _pendingUserScriptToInject.value = adaptedScript
                        }
                    }
                } finally {
                    _isThemeUpdatingInBackground.value = false
                }
            }
        }
    }

    fun openNewTab(url: String = "", isIncognito: Boolean = false) {
        val newTab = TabItem(
            url = url,
            isIncognito = isIncognito,
            title = if (url.isBlank()) (if (isIncognito) "Private Tab" else "New Tab") else url
        )
        _tabs.value = _tabs.value + newTab
        _activeTabId.value = newTab.id
        _isOptionsMenuExpanded.value = false
        _isTabGridOpen.value = false
        if (url.isNotBlank()) {
            resolveWebsiteColor(newTab.id, url)
        }
    }

    fun closeTab(tabId: String) {
        val currentTabs = _tabs.value
        if (currentTabs.size <= 1) {
            // Keep at least one tab
            val freshTab = TabItem()
            _tabs.value = listOf(freshTab)
            _activeTabId.value = freshTab.id
            return
        }

        val closingIndex = currentTabs.indexOfFirst { it.id == tabId }
        val remaining = currentTabs.filter { it.id != tabId }
        _tabs.value = remaining

        if (_activeTabId.value == tabId) {
            val newActiveIndex = if (closingIndex >= remaining.size) remaining.size - 1 else closingIndex
            _activeTabId.value = remaining[newActiveIndex].id
        }
    }

    fun selectTab(tabId: String) {
        _activeTabId.value = tabId
        _isTabGridOpen.value = false
        _isOptionsMenuExpanded.value = false
    }

    fun navigateActiveTab(input: String) {
        val active = activeTab.value ?: return
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        val targetUrl = formatUrl(trimmed)

        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == active.id) {
                tab.copy(url = targetUrl, title = targetUrl, primaryColor = null)
            } else tab
        }

        resolveWebsiteColor(active.id, targetUrl)
    }

    fun onUrlChanged(tabId: String, newUrl: String) {
        if (newUrl.isBlank()) return
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId && tab.url != newUrl) {
                tab.copy(url = newUrl)
            } else tab
        }
        resolveWebsiteColor(tabId, newUrl)
    }

    fun onTitleChanged(tabId: String, title: String) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId) {
                // Record history if title and URL are present
                if (!tab.isIncognito && tab.url.isNotBlank()) {
                    viewModelScope.launch {
                        browserRepository.addHistory(title, tab.url, false)
                    }
                }
                tab.copy(title = title)
            } else tab
        }
    }

    fun onProgressChanged(tabId: String, progress: Float) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId) tab.copy(progress = progress) else tab
        }
    }

    fun onLoadingStateChanged(tabId: String, isLoading: Boolean) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId) tab.copy(isLoading = isLoading) else tab
        }
    }

    fun onColorExtracted(tabId: String, colorHex: String) {
        val color = WebsiteColorRepository.parseHexColor(colorHex) ?: return
        val currentTab = _tabs.value.find { it.id == tabId } ?: return

        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId) tab.copy(primaryColor = color) else tab
        }

        websiteColorRepository.saveWebsiteColor(currentTab.url, colorHex)
    }

    private fun resolveWebsiteColor(tabId: String, url: String) {
        viewModelScope.launch {
            val cachedColor = websiteColorRepository.getPrimaryColorForUrl(url)
            if (cachedColor != null) {
                _tabs.value = _tabs.value.map { tab ->
                    if (tab.id == tabId) tab.copy(primaryColor = cachedColor) else tab
                }
            }
        }
    }

    fun isNearWhite(color: Color?): Boolean {
        if (color == null) return false
        return websiteColorRepository.isColorNearWhite(color)
    }

    fun toggleOptionsMenu() {
        _isOptionsMenuExpanded.value = !_isOptionsMenuExpanded.value
    }

    fun closeOptionsMenu() {
        _isOptionsMenuExpanded.value = false
    }

    fun toggleTabGrid() {
        _isTabGridOpen.value = !_isTabGridOpen.value
    }

    fun toggleBookmarks() {
        _isOptionsMenuExpanded.value = false
        _isBookmarksOpen.value = !_isBookmarksOpen.value
    }

    fun toggleHistory() {
        _isOptionsMenuExpanded.value = false
        _isHistoryOpen.value = !_isHistoryOpen.value
    }

    fun toggleDownloads() {
        _isOptionsMenuExpanded.value = false
        _isDownloadsOpen.value = !_isDownloadsOpen.value
    }

    fun toggleSettings() {
        _isOptionsMenuExpanded.value = false
        _isSettingsOpen.value = !_isSettingsOpen.value
    }

    fun toggleFindInPage() {
        _isOptionsMenuExpanded.value = false
        _isFindInPageOpen.value = !_isFindInPageOpen.value
    }

    fun updateFindInPageQuery(query: String) {
        _findInPageQuery.value = query
    }

    fun toggleReadingMode() {
        _isOptionsMenuExpanded.value = false
        _isReadingMode.value = !_isReadingMode.value
    }

    fun toggleDesktopSite() {
        _isOptionsMenuExpanded.value = false
        val active = activeTab.value ?: return
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == active.id) tab.copy(isDesktopMode = !tab.isDesktopMode) else tab
        }
    }

    fun toggleBookmarkActiveTab() {
        _isOptionsMenuExpanded.value = false
        val active = activeTab.value ?: return
        if (active.url.isBlank()) return

        viewModelScope.launch {
            if (isBookmarked.value) {
                browserRepository.removeBookmark(active.url)
            } else {
                browserRepository.addBookmark(active.title, active.url)
            }
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch {
            browserRepository.removeBookmark(bookmark.url)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            browserRepository.clearHistory()
        }
    }

    fun clearBrowsingData() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {}
    }

    fun toggleJavaScript(enabled: Boolean) {
        _isJavaScriptEnabled.value = enabled
    }

    fun toggleTrackerBlocker(enabled: Boolean) {
        _isTrackerBlockerEnabled.value = enabled
    }

    fun copyActiveLink(context: Context) {
        _isOptionsMenuExpanded.value = false
        val url = activeTab.value?.url ?: return
        if (url.isNotBlank()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("URL", url)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareActiveLink(context: Context) {
        _isOptionsMenuExpanded.value = false
        val tab = activeTab.value ?: return
        if (tab.url.isNotBlank()) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_TEXT, tab.url)
                putExtra(Intent.EXTRA_SUBJECT, tab.title)
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, "Share Link")
            context.startActivity(shareIntent)
        }
    }

    fun showHelp(context: Context) {
        _isOptionsMenuExpanded.value = false
        Toast.makeText(
            context,
            "Mydes Browser: Tap + for new tab, long-press + for incognito tab. Pull top notch down for tabs grid. Tap brush to redesign website with Gemini AI.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun formatUrl(input: String): String {
        return if (input.startsWith("http://") || input.startsWith("https://")) {
            input
        } else if (input.contains(".") && !input.contains(" ")) {
            "https://$input"
        } else {
            "https://www.google.com/search?q=${input.replace(" ", "+")}"
        }
    }

    companion object {
        fun provideFactory(
            application: Application,
            browserRepository: BrowserRepository,
            websiteColorRepository: WebsiteColorRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return BrowserViewModel(application, browserRepository, websiteColorRepository) as T
            }
        }
    }
}
