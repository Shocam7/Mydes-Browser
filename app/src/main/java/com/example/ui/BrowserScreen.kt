package com.example.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ui.components.AiStylerPillBar
import com.example.ui.components.ApiRequestLogsSheet
import com.example.ui.components.BookmarksSheet
import com.example.ui.components.DownloadsSheet
import com.example.ui.components.FindInPageBar
import com.example.ui.components.HistorySheet
import com.example.ui.components.MorphingOptionsButton
import com.example.ui.components.NewTabPlusButton
import com.example.ui.components.OptionsPillMenu
import com.example.ui.components.SettingsSheet
import com.example.ui.components.SlimTopProgressBar
import com.example.ui.components.StartPage
import com.example.ui.components.TabGridSheet
import com.example.ui.components.TopHeaderPill
import com.example.ui.components.TopPlateauNotch
import com.example.ui.components.WebContainer
import com.example.ui.viewmodel.BrowserViewModel
import kotlinx.coroutines.delay

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val tabs by viewModel.tabs.collectAsState()
    val activeTabId by viewModel.activeTabId.collectAsState()
    val activeTab by viewModel.activeTab.collectAsState()
    val isBookmarked by viewModel.isBookmarked.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val history by viewModel.history.collectAsState()

    val isOptionsMenuExpanded by viewModel.isOptionsMenuExpanded.collectAsState()
    val isTabGridOpen by viewModel.isTabGridOpen.collectAsState()
    val isBookmarksOpen by viewModel.isBookmarksOpen.collectAsState()
    val isHistoryOpen by viewModel.isHistoryOpen.collectAsState()
    val isDownloadsOpen by viewModel.isDownloadsOpen.collectAsState()
    val isSettingsOpen by viewModel.isSettingsOpen.collectAsState()
    val isApiLogsOpen by viewModel.isApiLogsOpen.collectAsState()
    val isFindInPageOpen by viewModel.isFindInPageOpen.collectAsState()
    val findInPageQuery by viewModel.findInPageQuery.collectAsState()
    val isReadingMode by viewModel.isReadingMode.collectAsState()
    val isJavaScriptEnabled by viewModel.isJavaScriptEnabled.collectAsState()
    val isTrackerBlockerEnabled by viewModel.isTrackerBlockerEnabled.collectAsState()
    val customApiKey by viewModel.customApiKey.collectAsState()

    // AI Website Styler states
    val isAiStylerPillVisible by viewModel.isAiStylerPillVisible.collectAsState()
    val isAiStylerLoading by viewModel.isAiStylerLoading.collectAsState()
    val aiStylerErrorMessage by viewModel.aiStylerErrorMessage.collectAsState()
    val pendingUserScriptToInject by viewModel.pendingUserScriptToInject.collectAsState()
    val isCustomStyleApplied by viewModel.isCustomStyleApplied.collectAsState()
    val isThemeUpdatingInBackground by viewModel.isThemeUpdatingInBackground.collectAsState()
    val previousGenerations by viewModel.previousGenerationsForCurrentDomain.collectAsState()
    val websiteThemes by viewModel.websiteThemes.collectAsState()

    var activeWebView by remember { mutableStateOf<WebView?>(null) }

    // Execute generated user-script on the active WebView
    LaunchedEffect(pendingUserScriptToInject) {
        val script = pendingUserScriptToInject
        if (script != null && activeWebView != null) {
            activeWebView?.evaluateJavascript(script, null)
            viewModel.onUserScriptInjected()
        }
    }

    val currentTab = activeTab ?: return
    val isStartPage = currentTab.isStartPage
    val isIncognito = currentTab.isIncognito
    val primaryColor = currentTab.primaryColor
    val isNearWhite = viewModel.isNearWhite(primaryColor)

    // Sliding visibility state for buttons:
    // Only an upward scroll will reveal the buttons.
    // Hides on downwards scroll or after >10 seconds without upward scrolling on web pages.
    // Buttons always stay visible on the start page or when AI styler is active.
    var controlsVisible by remember { mutableStateOf(true) }
    var scrollUpKey by remember { mutableLongStateOf(0L) }

    fun onUpwardScrollDetected() {
        controlsVisible = true
        scrollUpKey++
    }

    LaunchedEffect(scrollUpKey, isOptionsMenuExpanded, isStartPage, isAiStylerPillVisible) {
        if (!isOptionsMenuExpanded && !isStartPage && !isAiStylerPillVisible) {
            delay(10_000L) // 10 seconds of inactivity without upward scroll
            controlsVisible = false
        } else {
            controlsVisible = true
        }
    }

    // Buttons always stay visible on the start page, or when options menu or AI styler is expanded
    val shouldShowButtons = isStartPage || controlsVisible || isOptionsMenuExpanded || isAiStylerPillVisible

    val settingsOffsetX by animateDpAsState(
        targetValue = if (shouldShowButtons) 0.dp else (-80).dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "settingsOffsetX"
    )

    val brushOffsetX by animateDpAsState(
        targetValue = if (shouldShowButtons) 0.dp else 80.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "brushOffsetX"
    )

    val optionsOffsetX by animateDpAsState(
        targetValue = if (shouldShowButtons) 0.dp else 80.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "optionsOffsetX"
    )

    // Back handler: pop modals first, then navigate back in WebView
    BackHandler {
        when {
            isAiStylerPillVisible -> viewModel.closeAiStylerPill()
            isFindInPageOpen -> viewModel.toggleFindInPage()
            isBookmarksOpen -> viewModel.toggleBookmarks()
            isHistoryOpen -> viewModel.toggleHistory()
            isDownloadsOpen -> viewModel.toggleDownloads()
            isSettingsOpen -> viewModel.toggleSettings()
            isTabGridOpen -> viewModel.toggleTabGrid()
            isOptionsMenuExpanded -> viewModel.closeOptionsMenu()
            activeWebView?.canGoBack() == true -> activeWebView?.goBack()
            tabs.size > 1 -> viewModel.closeTab(currentTab.id)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (isIncognito) Color(0xFF171717) else Color(0xFFF7F8FA))
    ) {
        // 1. Content Area (Web page or Start page)
        Box(modifier = Modifier.fillMaxSize()) {
            if (currentTab.isStartPage) {
                StartPage(
                    isIncognito = isIncognito,
                    onNavigate = { viewModel.navigateActiveTab(it) }
                )
            } else {
                key(currentTab.id) {
                    WebContainer(
                        url = currentTab.url,
                        isIncognito = isIncognito,
                        isDesktopMode = currentTab.isDesktopMode,
                        isJavaScriptEnabled = isJavaScriptEnabled,
                        isTrackerBlockerEnabled = isTrackerBlockerEnabled,
                        isReadingMode = isReadingMode,
                        onUrlChanged = { viewModel.onUrlChanged(currentTab.id, it) },
                        onTitleChanged = { viewModel.onTitleChanged(currentTab.id, it) },
                        onProgressChanged = { viewModel.onProgressChanged(currentTab.id, it) },
                        onLoadingStateChanged = { viewModel.onLoadingStateChanged(currentTab.id, it) },
                        onNavigationStateChanged = { _, _ -> },
                        onColorExtracted = { viewModel.onColorExtracted(currentTab.id, it) },
                        onWebViewCreated = { activeWebView = it; viewModel.registerActiveWebView(it) },
                        onPageLoaded = { url, title -> viewModel.onPageLoaded(currentTab.id, url, title) },
                        onScrollDown = {
                            if (controlsVisible) {
                                if (!isOptionsMenuExpanded && !isStartPage && !isAiStylerPillVisible) {
                                    controlsVisible = false
                                }
                            }
                        },
                        onScrollUp = {
                            onUpwardScrollDetected()
                        }
                    )
                }
            }
        }

        // 2. Slim Top Progress Line at the very top of screen
        SlimTopProgressBar(
            isLoading = currentTab.isLoading,
            progress = currentTab.progress,
            primaryColor = primaryColor,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 3. Top Plateau Notch (10% opacity) - pull down to view tabs grid
        TopPlateauNotch(
            onPullDown = { viewModel.toggleTabGrid() },
            opacity = 0.10f,
            plateauColor = if (isIncognito) Color.White else Color.Black,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
        )

        // 4. Top Header Controls:
        // When options menu is collapsed: Standalone Top-Left Settings & Top-Right Column (Brush + Plus button)
        // When options menu is expanded: Unified Encapsulated Top Header Pill with website's primary color
        if (!isOptionsMenuExpanded) {
            // Standalone top-left Settings (slides out left on inactivity/scroll down on web pages)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 8.dp)
                    .offset(x = settingsOffsetX)
                    .size(44.dp)
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color(0xFFE4E6EA))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = { viewModel.toggleSettings() }
                    )
                    .testTag("standalone_settings_button")
                    .semantics { contentDescription = "Open Settings" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = Color(0xFF33373D),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Top-right Column: Brush button & Plus button below it (slides out right)
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 16.dp, top = 8.dp)
                    .offset(x = brushOffsetX),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Standalone top-right Brush (Click to open AI Website Styler)
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isThemeUpdatingInBackground) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = Color(0xFFF6C343),
                            strokeWidth = 2.5.dp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .shadow(3.dp, CircleShape)
                            .clip(CircleShape)
                            .background(if (isAiStylerPillVisible || isCustomStyleApplied) Color(0xFF1E2024) else Color(0xFFE4E6EA))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true),
                                onClick = { viewModel.toggleAiStylerPill() }
                            )
                            .testTag("standalone_brush_button")
                            .semantics { contentDescription = "AI Website Styler brush" },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Brush,
                            contentDescription = null,
                            tint = if (isAiStylerPillVisible || isCustomStyleApplied) Color(0xFFF6C343) else Color(0xFF33373D),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Plus button below the brush icon (Tap: new tab; Long-press: pulls out incognito button)
                NewTabPlusButton(
                    onNewTab = { viewModel.openNewTab(isIncognito = false) },
                    onNewIncognitoTab = { viewModel.openNewTab(isIncognito = true) }
                )
            }
        } else {
            // Encapsulated Top Header Pill
            TopHeaderPill(
                title = currentTab.title,
                currentUrl = currentTab.url,
                primaryColor = primaryColor,
                isNearWhite = isNearWhite,
                onNavigate = { viewModel.navigateActiveTab(it) },
                onOpenSettings = { viewModel.toggleSettings() },
                onOpenBrush = { viewModel.toggleAiStylerPill() },
                isThemeUpdatingInBackground = isThemeUpdatingInBackground,
                isCustomStyleApplied = isCustomStyleApplied,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
            )
        }

        // 5. Find In Page Overlay
        AnimatedVisibility(
            visible = isFindInPageOpen,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 54.dp)
        ) {
            FindInPageBar(
                query = findInPageQuery,
                onQueryChange = {
                    viewModel.updateFindInPageQuery(it)
                    activeWebView?.findAllAsync(it)
                },
                onFindNext = { activeWebView?.findNext(true) },
                onFindPrevious = { activeWebView?.findNext(false) },
                onClose = {
                    activeWebView?.clearMatches()
                    viewModel.toggleFindInPage()
                }
            )
        }

        // 6. Expandable Options Pill Menu (above the options button)
        OptionsPillMenu(
            expanded = isOptionsMenuExpanded,
            isIncognito = isIncognito,
            isBookmarked = isBookmarked,
            isDesktopSite = currentTab.isDesktopMode,
            isReadingMode = isReadingMode,
            onCopyLink = { viewModel.copyActiveLink(context) },
            onHistory = { viewModel.toggleHistory() },
            onDownloads = { viewModel.toggleDownloads() },
            onBookmarks = {
                if (isBookmarked) {
                    viewModel.toggleBookmarkActiveTab()
                } else {
                    viewModel.toggleBookmarks()
                }
            },
            onShare = { viewModel.shareActiveLink(context) },
            onFindInPage = { viewModel.toggleFindInPage() },
            onReadingMode = { viewModel.toggleReadingMode() },
            onDesktopSite = { viewModel.toggleDesktopSite() },
            onHelp = { viewModel.showHelp(context) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .offset(x = optionsOffsetX)
                .padding(end = 16.dp, bottom = 68.dp)
        )

        // 7. Geometric Options Button (Bottom-Right, slides out right on inactivity/scroll down on web pages)
        MorphingOptionsButton(
            isActive = isOptionsMenuExpanded,
            onClick = { viewModel.toggleOptionsMenu() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .offset(x = optionsOffsetX)
                .padding(end = 16.dp, bottom = 10.dp)
        )

        // 8. AI Website Styler Pill Bar (swifts out at bottom on clicking brush icon)
        val currentDomain = remember(currentTab.url) { viewModel.extractDomain(currentTab.url) }
        val currentAppliedPrompt = websiteThemes[currentDomain]?.prompt

        AiStylerPillBar(
            visible = isAiStylerPillVisible,
            websiteName = currentTab.title,
            isLoading = isAiStylerLoading,
            isStyleApplied = isCustomStyleApplied,
            errorMessage = aiStylerErrorMessage,
            previousGenerations = previousGenerations,
            currentAppliedPrompt = currentAppliedPrompt,
            onApplyStyle = { viewModel.applyAiStyle(it) },
            onApplySavedTheme = { viewModel.applySavedTheme(it) },
            onDeleteSavedTheme = { viewModel.deleteSavedTheme(it) },
            onResetStyle = { viewModel.resetCustomStyle() },
            onDismiss = { viewModel.closeAiStylerPill() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        )

        // 9. Fullscreen Modals and Sheets
        if (isTabGridOpen) {
            TabGridSheet(
                tabs = tabs,
                activeTabId = activeTabId,
                onSelectTab = { viewModel.selectTab(it) },
                onCloseTab = { viewModel.closeTab(it) },
                onNewTab = { viewModel.openNewTab(isIncognito = false) },
                onNewIncognitoTab = { viewModel.openNewTab(isIncognito = true) },
                onDismiss = { viewModel.toggleTabGrid() }
            )
        }

        if (isBookmarksOpen) {
            BookmarksSheet(
                bookmarks = bookmarks,
                onSelectBookmark = {
                    viewModel.navigateActiveTab(it)
                    viewModel.toggleBookmarks()
                },
                onDeleteBookmark = { viewModel.deleteBookmark(it) },
                onDismiss = { viewModel.toggleBookmarks() }
            )
        }

        if (isHistoryOpen) {
            HistorySheet(
                historyList = history,
                onSelectUrl = {
                    viewModel.navigateActiveTab(it)
                    viewModel.toggleHistory()
                },
                onClearHistory = { viewModel.clearHistory() },
                onDismiss = { viewModel.toggleHistory() }
            )
        }

        if (isDownloadsOpen) {
            DownloadsSheet(
                onDismiss = { viewModel.toggleDownloads() }
            )
        }

        if (isSettingsOpen) {
            SettingsSheet(
                isJavaScriptEnabled = isJavaScriptEnabled,
                onToggleJavaScript = { viewModel.toggleJavaScript(it) },
                isTrackerBlockerEnabled = isTrackerBlockerEnabled,
                onToggleTrackerBlocker = { viewModel.toggleTrackerBlocker(it) },
                onClearBrowsingData = { viewModel.clearBrowsingData() },
                onOpenApiLogs = { viewModel.toggleApiLogs() },
                customApiKey = customApiKey,
                onUpdateCustomApiKey = { viewModel.updateCustomApiKey(it) },
                onDismiss = { viewModel.toggleSettings() }
            )
        }

        if (isApiLogsOpen) {
            ApiRequestLogsSheet(
                onDismiss = { viewModel.toggleApiLogs() }
            )
        }
    }
}
