package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView

const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebContainer(
    url: String,
    isIncognito: Boolean,
    isDesktopMode: Boolean,
    isJavaScriptEnabled: Boolean,
    isTrackerBlockerEnabled: Boolean,
    isReadingMode: Boolean,
    onUrlChanged: (String) -> Unit,
    onTitleChanged: (String) -> Unit,
    onProgressChanged: (Float) -> Unit,
    onLoadingStateChanged: (Boolean) -> Unit,
    onNavigationStateChanged: (canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    onColorExtracted: (String) -> Unit,
    onWebViewCreated: (WebView) -> Unit,
    onPageLoaded: (url: String, title: String) -> Unit = { _, _ -> },
    onScrollDown: () -> Unit = {},
    onScrollUp: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var lastLoadedUrl by remember { mutableStateOf("") }

    // Clean up WebView resources and codecs when leaving composition
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                try {
                    wv.stopLoading()
                    wv.onPause()
                    wv.clearHistory()
                    wv.removeAllViews()
                    wv.destroy()
                } catch (_: Exception) {
                    // Ignore destruction exceptions
                }
            }
            webViewRef = null
        }
    }

    // Only load a new URL when it genuinely changes, avoiding reload loops on recomposition
    LaunchedEffect(url, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (url.isNotBlank() && url != lastLoadedUrl) {
            lastLoadedUrl = url
            wv.loadUrl(url)
        }
    }

    var dragTotalX by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webViewRef = this
                    onWebViewCreated(this)
                    // LAYER_TYPE_NONE allows WebView's internal Chromium GPU compositor to draw
                    // directly into the window surface without an expensive intermediate Android HardwareLayer,
                    // achieving 60/120fps Chrome browser parity for 3D CSS transforms and WebGL.
                    setLayerType(View.LAYER_TYPE_NONE, null)

                settings.apply {
                    javaScriptEnabled = isJavaScriptEnabled
                    domStorageEnabled = true
                    loadsImagesAutomatically = true
                    offscreenPreRaster = true // Pre-rasterize offscreen tiles on GPU like Chrome
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    setSupportZoom(true)
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    cacheMode = if (isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
                    userAgentString = if (isDesktopMode) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
                    mediaPlaybackRequiresUserGesture = true
                }

                CookieManager.getInstance().setAcceptCookie(!isIncognito)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, !isIncognito)

                setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                    val dy = scrollY - oldScrollY
                    if (dy > 30) {
                        onScrollDown()
                    } else if (dy < -30) {
                        onScrollUp()
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgressChanged(newProgress / 100f)
                        if (newProgress in 1..99) {
                            onLoadingStateChanged(true)
                        } else if (newProgress == 100) {
                            onLoadingStateChanged(false)
                        }
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        if (!title.isNullOrBlank()) {
                            onTitleChanged(title)
                        }
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val reqUrl = request?.url?.toString() ?: return false
                        if (reqUrl.startsWith("http://") || reqUrl.startsWith("https://")) {
                            return false // Allow WebView to load
                        }
                        // Handle external app schemes (mailto:, tel:, market:, etc.)
                        return try {
                            val intent = Intent(Intent.ACTION_VIEW, request.url)
                            view?.context?.startActivity(intent)
                            true
                        } catch (_: Exception) {
                            true
                        }
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        if (!url.isNullOrBlank()) {
                            lastLoadedUrl = url
                            onUrlChanged(url)
                        }
                        onLoadingStateChanged(true)
                        onNavigationStateChanged(view?.canGoBack() == true, view?.canGoForward() == true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!url.isNullOrBlank()) {
                            lastLoadedUrl = url
                            onUrlChanged(url)
                            onPageLoaded(url, view?.title ?: "")
                        }
                        onLoadingStateChanged(false)
                        onNavigationStateChanged(view?.canGoBack() == true, view?.canGoForward() == true)

                        // Extract theme-color from HTML meta tag
                        evaluateJavascript(
                            """
                            (function() {
                                var meta = document.querySelector('meta[name="theme-color"]');
                                var bannerFix = document.createElement('style');
                                bannerFix.textContent = 'div[class*="banner"], div[class*="popup"], div[class*="overlay"], shreddit-app-banner { backdrop-filter: none !important; -webkit-backdrop-filter: none !important; background-color: rgba(26, 26, 27, 0.98) !important; color: #fff !important; }';
                                document.head.appendChild(bannerFix);
                                if (meta && meta.content) return meta.content;
                                var header = document.querySelector('header') || document.querySelector('nav');
                                if (header) {
                                    var bg = window.getComputedStyle(header).backgroundColor;
                                    if (bg && bg !== 'rgba(0, 0, 0, 0)' && bg !== 'transparent') return bg;
                                }
                                return '';
                            })();
                            """.trimIndent()
                        ) { result ->
                            val cleanResult = result?.replace("\"", "")?.trim() ?: ""
                            if (cleanResult.isNotBlank() && cleanResult != "null") {
                                onColorExtracted(cleanResult)
                            }
                        }

                        // Apply reader mode if toggled
                        if (isReadingMode) {
                            evaluateJavascript(
                                """
                                (function() {
                                    document.body.style.maxWidth = '720px';
                                    document.body.style.margin = '0 auto';
                                    document.body.style.padding = '20px';
                                    document.body.style.fontSize = '18px';
                                    document.body.style.lineHeight = '1.7';
                                    document.body.style.fontFamily = 'Georgia, serif';
                                    var ads = document.querySelectorAll('aside, footer, .ad, .ads, [id*="ad"]');
                                    ads.forEach(function(el) { el.style.display = 'none'; });
                                })();
                                """.trimIndent(),
                                null
                            )
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (isTrackerBlockerEnabled) {
                            val reqUrl = request?.url?.toString() ?: ""
                            val blockedKeywords = listOf(
                                "doubleclick.net",
                                "googlesyndication.com",
                                "google-analytics.com",
                                "adnxs.com",
                                "scorecardresearch.com"
                            )
                            if (blockedKeywords.any { reqUrl.contains(it) }) {
                                return WebResourceResponse("text/plain", "UTF-8", null)
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: android.webkit.RenderProcessGoneDetail?
                    ): Boolean {
                        // Prevent app crashes if WebView renderer terminates in virtualized environment
                        try {
                            view?.destroy()
                        } catch (_: Exception) {}
                        return true
                    }
                }

                webViewRef = this
                onWebViewCreated(this)
                if (url.isNotBlank()) {
                    lastLoadedUrl = url
                    loadUrl(url)
                }
            }
        },
        update = { webView ->
            webView.settings.javaScriptEnabled = isJavaScriptEnabled
            val targetUserAgent = if (isDesktopMode) DESKTOP_USER_AGENT else null
            if (webView.settings.userAgentString != targetUserAgent) {
                webView.settings.userAgentString = targetUserAgent
                webView.reload()
            }
        }
    )
    }
}
