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
import com.example.data.api.ApiRequestLogger
import com.example.data.api.GeminiStylerService
import com.example.data.api.MydesRuntime
import com.example.data.api.ReasoningModulatorConfig
import com.example.data.api.ThoughtSummarizer
import com.example.data.api.UIBlueprint
import com.example.data.api.evaluateJsSuspend
import com.example.data.model.BookmarkEntity
import com.example.data.model.GeneratedThemeEntity
import com.example.data.model.HistoryEntity
import com.example.data.repository.BrowserRepository
import com.example.data.repository.WebsiteColorRepository
import com.example.ui.model.MessageSender
import com.example.ui.model.TabItem
import com.example.ui.model.ThemeChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AiThinkingState(
    val isActive: Boolean = false,
    val phase: String = "",
    val summarizedStatements: List<String> = emptyList(),
    val currentStreamSnippet: String = "",
    val durationSeconds: Int = 0,
    val isFinished: Boolean = false,
    val errorMessage: String? = null,
    val morphBgColor: String? = null,
    val morphFgColor: String? = null,
    val activeConcept: String? = null,
    val chatHistory: List<ThemeChatMessage> = emptyList()
)

class BrowserViewModel(
    application: Application,
    private val browserRepository: BrowserRepository,
    private val websiteColorRepository: WebsiteColorRepository
) : AndroidViewModel(application) {

    private val _customApiKey = MutableStateFlow("")
    val customApiKey: StateFlow<String> = _customApiKey.asStateFlow()

    private val _customNvidiaApiKey = MutableStateFlow("")
    val customNvidiaApiKey: StateFlow<String> = _customNvidiaApiKey.asStateFlow()

    private val _synthesisModel = MutableStateFlow(GeminiStylerService.MODEL_GEMINI_35_FLASH_LITE)
    val synthesisModel: StateFlow<String> = _synthesisModel.asStateFlow()

    private val _reasoningLevel = MutableStateFlow(
        ReasoningModulatorConfig.getDefaultModeForModel(GeminiStylerService.MODEL_GEMINI_35_FLASH_LITE)
    )
    val reasoningLevel: StateFlow<String> = _reasoningLevel.asStateFlow()

    init {
        val prefs = application.getSharedPreferences("mydes_prefs", Context.MODE_PRIVATE)
        _customApiKey.value = prefs.getString("custom_api_key", "") ?: ""
        _customNvidiaApiKey.value = prefs.getString("custom_nvidia_api_key", "") ?: ""
        val savedModel = prefs.getString("synthesis_model", GeminiStylerService.MODEL_GEMINI_35_FLASH_LITE)
            ?: GeminiStylerService.MODEL_GEMINI_35_FLASH_LITE
        _synthesisModel.value = savedModel
        _reasoningLevel.value = GeminiStylerService.getReasoningLevel(application, savedModel)
    }

    fun updateCustomApiKey(key: String) {
        _customApiKey.value = key
        val prefs = getApplication<Application>().getSharedPreferences("mydes_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("custom_api_key", key.trim()).apply()
        Toast.makeText(getApplication(), "Gemini API Key saved", Toast.LENGTH_SHORT).show()
    }

    fun updateCustomNvidiaApiKey(key: String) {
        _customNvidiaApiKey.value = key
        val prefs = getApplication<Application>().getSharedPreferences("mydes_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("custom_nvidia_api_key", key.trim()).apply()
        Toast.makeText(getApplication(), "NVIDIA API Key saved", Toast.LENGTH_SHORT).show()
    }

    fun updateSynthesisModel(model: String) {
        _synthesisModel.value = model
        val prefs = getApplication<Application>().getSharedPreferences("mydes_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("synthesis_model", model).apply()
        _reasoningLevel.value = GeminiStylerService.getReasoningLevel(getApplication(), model)
        val displayName = when (model) {
            GeminiStylerService.MODEL_NVIDIA_NEMOTRON_3_ULTRA -> "Nemotron 3 Ultra 550B (NIM)"
            GeminiStylerService.MODEL_GEMINI_38_FLASH -> "Gemini 3.8 Flash"
            else -> "Gemini 3.5 Flash Lite"
        }
        Toast.makeText(getApplication(), "Synthesis Model: $displayName", Toast.LENGTH_SHORT).show()
    }

    fun toggleSynthesisModel() {
        val next = when (_synthesisModel.value) {
            GeminiStylerService.MODEL_GEMINI_35_FLASH_LITE ->
                GeminiStylerService.MODEL_GEMINI_38_FLASH
            GeminiStylerService.MODEL_GEMINI_38_FLASH ->
                GeminiStylerService.MODEL_NVIDIA_NEMOTRON_3_ULTRA
            else ->
                GeminiStylerService.MODEL_GEMINI_35_FLASH_LITE
        }
        updateSynthesisModel(next)
    }

    fun updateReasoningLevel(level: String) {
        val model = _synthesisModel.value
        val validModes = ReasoningModulatorConfig.getAvailableModesForModel(model)
        val normalized = if (level.lowercase().trim() in validModes) {
            level.lowercase().trim()
        } else {
            ReasoningModulatorConfig.getDefaultModeForModel(model)
        }
        _reasoningLevel.value = normalized
        GeminiStylerService.setReasoningLevel(getApplication(), model, normalized)
    }

    fun cycleReasoningLevel() {
        val model = _synthesisModel.value
        val next = ReasoningModulatorConfig.nextMode(model, _reasoningLevel.value)
        updateReasoningLevel(next)
    }

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

    private val _isImportRedesignDialogOpen = MutableStateFlow(false)
    val isImportRedesignDialogOpen: StateFlow<Boolean> = _isImportRedesignDialogOpen.asStateFlow()

    fun openImportRedesignDialog() {
        _isImportRedesignDialogOpen.value = true
    }

    fun closeImportRedesignDialog() {
        _isImportRedesignDialogOpen.value = false
    }

    fun toggleImportRedesignDialog() {
        _isImportRedesignDialogOpen.value = !_isImportRedesignDialogOpen.value
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

    private val _isFullScreenMode = MutableStateFlow(false)
    val isFullScreenMode: StateFlow<Boolean> = _isFullScreenMode.asStateFlow()

    // AI Website Styler (Gemini) states & Website-Wide Theming
    private val geminiStylerService = GeminiStylerService()

    data class WebsiteTheme(
        val domain: String,
        val prompt: String,
        val baseScript: String,
        val pageScripts: Map<String, String> = emptyMap(),
        val isImported: Boolean = false,
        val activeCss: String = "",
        val activeConcept: String = "",
        val chatHistory: List<ThemeChatMessage> = emptyList(),
        val bg: String = "#10141D",
        val fg: String = "#E6EDF3"
    )

    private val _websiteThemes = MutableStateFlow>(emptyMap())
    val websiteThemes: StateFlow> = _websiteThemes.asStateFlow()

    private val _isAiStylerPillVisible = MutableStateFlow(false)
    val isAiStylerPillVisible: StateFlow<Boolean> = _isAiStylerPillVisible.asStateFlow()

    private val _isAiStylerLoading = MutableStateFlow(false)
    val isAiStylerLoading: StateFlow<Boolean> = _isAiStylerLoading.asStateFlow()

    private val _isThemeUpdatingInBackground = MutableStateFlow(false)
    val isThemeUpdatingInBackground: StateFlow<Boolean> = _isThemeUpdatingInBackground.asStateFlow()

    private val _aiStylerErrorMessage = MutableStateFlow<String?>(null)
    val aiStylerErrorMessage: StateFlow<String?> = _aiStylerErrorMessage.asStateFlow()

    private val _aiThinkingState = MutableStateFlow(AiThinkingState())
    val aiThinkingState: StateFlow<AiThinkingState> = _aiThinkingState.asStateFlow()

    private var thinkingTimerJob: kotlinx.coroutines.Job? = null

    private val _pendingUserScriptToInject = MutableStateFlow<String?>(null)
    val pendingUserScriptToInject: StateFlow<String?> = _pendingUserScriptToInject.asStateFlow()

    /**
     * Requirement 2: As long as a theme is applied and active on the current domain,
     * the brush icon is in active state. When removed or not yet applied, it is inactive.
     */
    val isCustomStyleApplied: StateFlow<Boolean> = combine(activeTab, _websiteThemes) { tab, themes ->
        val domain = extractDomain(tab?.url ?: "")
        domain.isNotBlank() && themes.containsKey(domain)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val previousGenerationsForCurrentDomain: StateFlow<List<GeneratedThemeEntity>> = browserRepository.getAllGeneratedThemes()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private var backgroundAdaptationJob: kotlinx.coroutines.Job? = null
    private var lastAdaptedUrl: String = ""

    fun extractDomain(url: String): String {
        if (url.isBlank()) return ""
        return try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host?.lowercase() ?: ""
            var clean = if (host.startsWith("www.")) host.removePrefix("www.") else host
            if (clean.startsWith("m.")) clean = clean.removePrefix("m.")
            clean
        } catch (_: Exception) {
            ""
        }
    }

    fun toggleAiStylerPill() {
        val nextVisible = !_isAiStylerPillVisible.value
        _isAiStylerPillVisible.value = nextVisible
        _aiStylerErrorMessage.value = null
        if (nextVisible) {
            val domain = extractDomain(activeTab.value?.url ?: "")
            val activeTheme = _websiteThemes.value[domain]
            if (activeTheme != null) {
                _aiThinkingState.update {
                    it.copy(
                        morphBgColor = activeTheme.bg,
                        morphFgColor = activeTheme.fg,
                        activeConcept = activeTheme.activeConcept,
                        chatHistory = activeTheme.chatHistory
                    )
                }
            }
        }
    }

    fun closeAiStylerPill() {
        _isAiStylerPillVisible.value = false
    }

    fun applySavedTheme(theme: GeneratedThemeEntity) {
        val tab = activeTab.value ?: return
        val domain = theme.domain
        if (domain.isBlank()) return

        // Restore specific chat history for this saved design
        val restoredHistory = ThemeChatMessage.listFromJson(theme.chatHistoryJson).ifEmpty {
            val conceptName = theme.concept?.ifBlank { theme.themeName } ?: theme.themeName
            listOf(
                ThemeChatMessage(
                    sender = MessageSender.USER,
                    text = theme.prompt,
                    modelLabel = getModelDisplayName(_synthesisModel.value)
                ),
                ThemeChatMessage(
                    sender = MessageSender.AGENT,
                    text = "Building $conceptName",
                    concept = conceptName
                ),
                ThemeChatMessage(
                    sender = MessageSender.AGENT,
                    isThinkingBubble = true,
                    thinkingPhase = "Theme Restored",
                    isThinkingFinished = true,
                    thinkingSteps = listOf("Design applied from saved themes")
                )
            )
        }

        val websiteTheme = WebsiteTheme(
            domain = domain,
            prompt = theme.prompt,
            baseScript = theme.script,
            pageScripts = mapOf(tab.url to theme.script),
            activeCss = theme.activeCss ?: "",
            activeConcept = theme.concept ?: theme.themeName,
            chatHistory = restoredHistory,
            bg = theme.bg ?: "#10141D",
            fg = theme.fg ?: "#E6EDF3"
        )
        _websiteThemes.value = _websiteThemes.value + (domain to websiteTheme)
        _pendingUserScriptToInject.value = theme.script

        // Requirement 5: Default model for continued chats is gemini-3.5-flash-lite
        setDefaultModelForContinuedChat()

        _aiThinkingState.value = AiThinkingState(
            isActive = false,
            isFinished = true,
            morphBgColor = theme.bg ?: "#10141D",
            morphFgColor = theme.fg ?: "#E6EDF3",
            activeConcept = theme.concept ?: theme.themeName,
            chatHistory = restoredHistory
        )

        Toast.makeText(getApplication(), "Applied: ${theme.prompt}", Toast.LENGTH_SHORT).show()
    }

    private fun setDefaultModelForContinuedChat() {
        _synthesisModel.value = GeminiStylerService.MODEL_GEMINI_35_FLASH_LITE
        GeminiStylerService.setSynthesisModel(getApplication(), GeminiStylerService.MODEL_GEMINI_35_FLASH_LITE)
        _reasoningLevel.value = GeminiStylerService.getReasoningLevel(
            getApplication(),
            GeminiStylerService.MODEL_GEMINI_35_FLASH_LITE
        )
    }

    fun deleteSavedTheme(theme: GeneratedThemeEntity) {
        viewModelScope.launch {
            theme.screenshotPath?.let { path ->
                try { java.io.File(path).delete() } catch (_: Exception) {}
            }
            browserRepository.deleteGeneratedTheme(theme)
            Toast.makeText(getApplication(), "Theme removed", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Imports an externally generated UIBlueprint from a JSON file or pasted string,
     * applies it immediately to the active webpage, and initializes its chat history.
     */
    fun importAndApplyRedesign(jsonContent: String, sourceName: String = "External File"): Result<UIBlueprint> {
        val tab = activeTab.value ?: return Result.failure(IllegalStateException("No active tab open"))
        val domain = extractDomain(tab.url)
        if (domain.isBlank()) {
            val err = "Please navigate to a website first before applying an external redesign."
            Toast.makeText(getApplication(), err, Toast.LENGTH_LONG).show()
            return Result.failure(IllegalStateException(err))
        }

        val parsedBlueprint = UIBlueprint.parse(jsonContent, "Imported Frontier Redesign")
            ?: return Result.failure(IllegalArgumentException("Could not parse valid UIBlueprint JSON. Ensure fields match the generative blueprint schema."))

        val script = MydesRuntime.script(parsedBlueprint)
        val promptName = parsedBlueprint.name.ifBlank { parsedBlueprint.concept }.ifBlank { "Imported Frontier Redesign" }
        val conceptName = parsedBlueprint.concept.ifBlank { promptName }

        val importedHistory = listOf(
            ThemeChatMessage(
                sender = MessageSender.USER,
                text = "Import: $promptName",
                modelLabel = sourceName
            ),
            ThemeChatMessage(
                sender = MessageSender.AGENT,
                text = "Building $conceptName",
                concept = conceptName
            ),
            ThemeChatMessage(
                sender = MessageSender.AGENT,
                isThinkingBubble = true,
                thinkingPhase = "Imported Blueprint Applied",
                isThinkingFinished = true,
                thinkingSteps = listOf("Parsed external UIBlueprint JSON", "Compiled and injected 60fps stylesheet")
            )
        )

        val websiteTheme = WebsiteTheme(
            domain = domain,
            prompt = promptName,
            baseScript = script,
            pageScripts = mapOf(tab.url to script),
            isImported = true,
            activeCss = parsedBlueprint.customCss,
            activeConcept = conceptName,
            chatHistory = importedHistory,
            bg = parsedBlueprint.bg,
            fg = parsedBlueprint.fg
        )
        _websiteThemes.value = _websiteThemes.value + (domain to websiteTheme)
        _pendingUserScriptToInject.value = script

        setDefaultModelForContinuedChat()

        val siteName = if (tab.title.isNotBlank()) tab.title else tab.url

        viewModelScope.launch {
            val initialThemeEntity = GeneratedThemeEntity(
                domain = domain,
                websiteUrl = tab.url,
                websiteTitle = siteName,
                prompt = "Imported: $promptName",
                themeName = parsedBlueprint.name,
                script = script,
                screenshotPath = null,
                activeCss = parsedBlueprint.customCss,
                concept = conceptName,
                chatHistoryJson = ThemeChatMessage.listToJson(importedHistory),
                bg = parsedBlueprint.bg,
                fg = parsedBlueprint.fg
            )
            val insertedId = browserRepository.saveGeneratedTheme(initialThemeEntity)

            val wv = activeWebViewRef
            if (wv != null) {
                delay(4000L)
                val bitmap = captureWebViewBitmap(wv)
                val screenshotPath = if (bitmap != null) saveScreenshotToFile(bitmap) else null
                if (screenshotPath != null) {
                    browserRepository.updateGeneratedTheme(
                        initialThemeEntity.copy(id = insertedId, screenshotPath = screenshotPath)
                    )
                }
            }

            ApiRequestLogger.logRequest(
                context = getApplication(),
                type = "importedRedesign (frontier)",
                websiteName = siteName,
                websiteUrl = tab.url,
                userPrompt = "Imported: $promptName",
                requestPayload = "=== IMPORT SOURCE ===\n$sourceName\n\n=== BLUEPRINT METADATA ===\nID: ${parsedBlueprint.id}\nName: ${parsedBlueprint.name}\nConcept: ${parsedBlueprint.concept}\nColors: bg=${parsedBlueprint.bg}, surface=${parsedBlueprint.surface}, accent=${parsedBlueprint.accent}\nRaw content length: ${jsonContent.length} chars",
                responseResult = parsedBlueprint.toJson(),
                isSuccess = true
            )
        }

        Toast.makeText(getApplication(), "Applied imported redesign: $promptName", Toast.LENGTH_SHORT).show()
        return Result.success(parsedBlueprint)
    }

    private fun getModelDisplayName(model: String): String = when {
        GeminiStylerService.isNvidiaModel(model) -> "Nemotron 3 Ultra (NIM)"
        model == GeminiStylerService.MODEL_GEMINI_38_FLASH -> "Gemini 3.8 Flash"
        else -> "Gemini 3.5 Flash Lite"
    }

    /**
     * Requirements 4 & 5:
     * - Runs the Concept Pass first.
     * - Once Concept Pass returns `background` (`bg`), `fg`, and `concept`, `onConceptReady` triggers
     *   the rising translucent `MorphingWebOverlay` with `bg` color and adds:
     *   1) User message bubble
     *   2) Agent reply: "Building <concept field from concept pass>"
     *   3) Agent reply: Live streaming thinking drop-down in `fg` color.
     * - If a theme is already active for this domain, treats the request as a continued chat
     *   modification with the currently active CSS included in the system prompt.
     */
    fun applyAiStyle(prompt: String) {
        val tab = activeTab.value ?: return
        val domain = extractDomain(tab.url)
        _isAiStylerLoading.value = true
        _aiStylerErrorMessage.value = null

        val activeTheme = _websiteThemes.value[domain]
        val isModification = activeTheme != null
        val currentModel = _synthesisModel.value
        val isNemotron = GeminiStylerService.isNvidiaModel(currentModel)

        val effectiveReasoningLabel = if (isModification && isNemotron) {
            "OFF"
        } else {
            ReasoningModulatorConfig.getDisplayLabel(currentModel, _reasoningLevel.value)
        }
        val modelDisplayName = "${getModelDisplayName(currentModel)} • $effectiveReasoningLabel"

        val userMsg = ThemeChatMessage(
            sender = MessageSender.USER,
            text = prompt,
            modelLabel = modelDisplayName
        )
        val agentConceptMsgId = java.util.UUID.randomUUID().toString()
        val agentThinkingMsgId = java.util.UUID.randomUUID().toString()
        val initialPhase = if (isModification) {
            "Pass 1: Directing modification concept..."
        } else {
            "Pass 1: Visual Architecture..."
        }

        // Before Concept Pass finishes on a brand-new theme, morphBgColor is null so the overlay
        // waits to rise until `background` and `fg` are extracted from the concept result.
        val startingHistory = if (isModification) {
            activeTheme!!.chatHistory + listOf(userMsg)
        } else {
            listOf(userMsg)
        }

        thinkingTimerJob?.cancel()
        _aiThinkingState.value = AiThinkingState(
            isActive = true,
            phase = initialPhase,
            summarizedStatements = listOf(
                if (isModification) "Analyzing modification request" else "Running concept pass..."
            ),
            durationSeconds = 0,
            isFinished = false,
            morphBgColor = activeTheme?.bg,
            morphFgColor = activeTheme?.fg,
            activeConcept = activeTheme?.activeConcept,
            chatHistory = startingHistory
        )

        thinkingTimerJob = viewModelScope.launch {
            while (isActive && _aiThinkingState.value.isActive && !_aiThinkingState.value.isFinished) {
                delay(1000L)
                _aiThinkingState.update { current ->
                    val updatedSec = current.durationSeconds + 1
                    val updatedChat = current.chatHistory.map { msg ->
                        if (msg.id == agentThinkingMsgId) msg.copy(durationSeconds = updatedSec) else msg
                    }
                    current.copy(durationSeconds = updatedSec, chatHistory = updatedChat)
                }
            }
        }

        viewModelScope.launch {
            val siteName = if (tab.title.isNotBlank()) tab.title else tab.url
            val thoughtBuffer = StringBuilder()

            val onThinkingProgress: (phase: String, chunk: String, isComplete: Boolean) -> Unit = { phase, chunk, isComplete ->
                if (isComplete) {
                    val (summarized, _) = ThoughtSummarizer.summarize(thoughtBuffer.toString(), isComplete = true)
                    _aiThinkingState.update { current ->
                        val updatedChat = current.chatHistory.map { msg ->
                            if (msg.id == agentThinkingMsgId) {
                                msg.copy(
                                    thinkingPhase = phase,
                                    thinkingSteps = if (summarized.isNotEmpty()) summarized else msg.thinkingSteps,
                                    activeStreamSnippet = "",
                                    isThinkingFinished = true,
                                    isThinkingActive = false
                                )
                            } else msg
                        }
                        current.copy(
                            phase = phase,
                            summarizedStatements = if (summarized.isNotEmpty()) summarized else current.summarizedStatements,
                            currentStreamSnippet = "",
                            isFinished = true,
                            chatHistory = updatedChat
                        )
                    }
                } else {
                    if (chunk.isNotEmpty()) {
                        thoughtBuffer.append(chunk)
                        val (summarized, pending) = ThoughtSummarizer.summarize(thoughtBuffer.toString(), isComplete = false)
                        _aiThinkingState.update { current ->
                            val updatedChat = current.chatHistory.map { msg ->
                                if (msg.id == agentThinkingMsgId) {
                                    msg.copy(
                                        thinkingPhase = phase,
                                        thinkingSteps = if (summarized.isNotEmpty()) summarized else msg.thinkingSteps,
                                        activeStreamSnippet = pending,
                                        isThinkingActive = true
                                    )
                                } else msg
                            }
                            current.copy(
                                phase = phase,
                                summarizedStatements = if (summarized.isNotEmpty()) summarized else current.summarizedStatements,
                                currentStreamSnippet = pending,
                                chatHistory = updatedChat
                            )
                        }
                    } else {
                        _aiThinkingState.update { current ->
                            val updatedChat = current.chatHistory.map { msg ->
                                if (msg.id == agentThinkingMsgId) msg.copy(thinkingPhase = phase) else msg
                            }
                            current.copy(phase = phase, chatHistory = updatedChat)
                        }
                    }
                }
            }

            // Called as soon as Pass 1 (Concept Pass) finishes and extracts `background` (bg), `fg`, and `concept`
            val onConceptReady: (bg: String, fg: String, concept: String) -> Unit = { bg, fg, concept ->
                val agentConceptMsg = ThemeChatMessage(
                    id = agentConceptMsgId,
                    sender = MessageSender.AGENT,
                    text = "Building $concept",
                    concept = concept
                )
                val (currentSummaries, currentPending) = ThoughtSummarizer.summarize(
                    thoughtBuffer.toString(),
                    isComplete = false
                )
                val agentThinkingMsg = ThemeChatMessage(
                    id = agentThinkingMsgId,
                    sender = MessageSender.AGENT,
                    isThinkingBubble = true,
                    thinkingPhase = "Pass 2: Compiling 60fps Pure CSS...",
                    thinkingSteps = if (currentSummaries.isNotEmpty()) {
                        currentSummaries
                    } else {
                        listOf("Extracted palette (bg $bg, fg $fg)", "Synthesizing CSS rules...")
                    },
                    activeStreamSnippet = currentPending,
                    durationSeconds = _aiThinkingState.value.durationSeconds,
                    isThinkingActive = true,
                    isThinkingFinished = false
                )

                _aiThinkingState.update { current ->
                    val withoutDuplicates = current.chatHistory.filterNot {
                        it.id == agentConceptMsgId || it.id == agentThinkingMsgId
                    }
                    current.copy(
                        morphBgColor = bg,
                        morphFgColor = fg,
                        activeConcept = concept,
                        phase = "Pass 2: Compiling 60fps Pure CSS...",
                        chatHistory = withoutDuplicates + listOf(agentConceptMsg, agentThinkingMsg)
                    )
                }
            }

            if (isModification) {
                // Continued chat modification of active design
                val currentCss = activeTheme!!.activeCss.ifBlank { activeTheme.baseScript }
                val currentConcept = activeTheme.activeConcept.ifBlank { activeTheme.prompt }

                val result = geminiStylerService.modifyUserScript(
                    context = getApplication(),
                    userModificationPrompt = prompt,
                    activeCss = currentCss,
                    activeConcept = currentConcept,
                    websiteName = siteName,
                    websiteUrl = tab.url,
                    onThinkingUpdate = onThinkingProgress,
                    onConceptReady = onConceptReady
                )
                _isAiStylerLoading.value = false
                result.onSuccess { blueprint ->
                    thinkingTimerJob?.cancel()
                    val (finalSummaries, _) = ThoughtSummarizer.summarize(thoughtBuffer.toString(), isComplete = true)
                    val script = MydesRuntime.script(blueprint)

                    _aiThinkingState.update { current ->
                        val updatedChat = current.chatHistory.map { msg ->
                            if (msg.id == agentThinkingMsgId) {
                                msg.copy(
                                    isThinkingActive = false,
                                    isThinkingFinished = true,
                                    thinkingPhase = "Modification Applied",
                                    thinkingSteps = if (finalSummaries.isNotEmpty()) finalSummaries else msg.thinkingSteps,
                                    activeStreamSnippet = ""
                                )
                            } else msg
                        }
                        current.copy(
                            isActive = false,
                            isFinished = true,
                            phase = "Modification Applied",
                            summarizedStatements = if (finalSummaries.isNotEmpty()) finalSummaries else current.summarizedStatements,
                            currentStreamSnippet = "",
                            chatHistory = updatedChat
                        )
                    }

                    val finalHistory = _aiThinkingState.value.chatHistory
                    val updatedTheme = activeTheme.copy(
                        prompt = prompt,
                        baseScript = script,
                        pageScripts = activeTheme.pageScripts + (tab.url to script),
                        activeCss = blueprint.customCss,
                        activeConcept = blueprint.concept,
                        chatHistory = finalHistory,
                        bg = blueprint.bg,
                        fg = blueprint.fg
                    )
                    _websiteThemes.value = _websiteThemes.value + (domain to updatedTheme)
                    _pendingUserScriptToInject.value = script

                    val entity = GeneratedThemeEntity(
                        domain = domain,
                        websiteUrl = tab.url,
                        websiteTitle = siteName,
                        prompt = prompt,
                        themeName = blueprint.name,
                        script = script,
                        activeCss = blueprint.customCss,
                        concept = blueprint.concept,
                        chatHistoryJson = ThemeChatMessage.listToJson(finalHistory),
                        bg = blueprint.bg,
                        fg = blueprint.fg
                    )
                    browserRepository.saveGeneratedTheme(entity)
                }.onFailure { err ->
                    thinkingTimerJob?.cancel()
                    val errMsg = err.message ?: "Failed to apply modification."
                    _aiStylerErrorMessage.value = errMsg
                    _aiThinkingState.update { current ->
                        val updatedChat = current.chatHistory.map { msg ->
                            if (msg.id == agentThinkingMsgId) {
                                msg.copy(
                                    isThinkingActive = false,
                                    isThinkingFinished = true,
                                    errorMessage = errMsg,
                                    thinkingPhase = "Modification issue"
                                )
                            } else msg
                        }
                        current.copy(
                            isActive = false,
                            isFinished = true,
                            phase = "Modification Encountered an Issue",
                            currentStreamSnippet = "",
                            errorMessage = errMsg,
                            chatHistory = updatedChat
                        )
                    }
                }
            } else {
                // Brand-new theme creation
                val preOutline = try {
                    activeWebViewRef?.evaluateJsSuspend(MydesRuntime.OUTLINE_PROBE_JS)
                } catch (_: Exception) {
                    null
                }
                val initialBitmap = activeWebViewRef?.let { captureWebViewBitmap(it) }
                val initialScreenshotBase64 = if (initialBitmap != null) bitmapToBase64(initialBitmap) else null

                val result = geminiStylerService.themeSpec(
                    context = getApplication(),
                    prompt = prompt,
                    websiteUrl = tab.url,
                    pageOutlineJson = preOutline,
                    screenshotBase64 = initialScreenshotBase64,
                    forceFresh = true,
                    onThinkingUpdate = onThinkingProgress,
                    onConceptReady = onConceptReady
                )
                _isAiStylerLoading.value = false
                result.onSuccess { blueprint ->
                    thinkingTimerJob?.cancel()
                    val (finalSummaries, _) = ThoughtSummarizer.summarize(thoughtBuffer.toString(), isComplete = true)
                    val script = MydesRuntime.script(blueprint)

                    _aiThinkingState.update { current ->
                        val updatedChat = current.chatHistory.map { msg ->
                            if (msg.id == agentThinkingMsgId) {
                                msg.copy(
                                    isThinkingActive = false,
                                    isThinkingFinished = true,
                                    thinkingPhase = "Theme Synthesized & Applied",
                                    thinkingSteps = if (finalSummaries.isNotEmpty()) finalSummaries else msg.thinkingSteps,
                                    activeStreamSnippet = ""
                                )
                            } else msg
                        }
                        current.copy(
                            isActive = false,
                            isFinished = true,
                            phase = "Theme Synthesized & Applied",
                            summarizedStatements = if (finalSummaries.isNotEmpty()) finalSummaries else current.summarizedStatements,
                            currentStreamSnippet = "",
                            chatHistory = updatedChat
                        )
                    }

                    val finalHistory = _aiThinkingState.value.chatHistory

                    if (domain.isNotBlank()) {
                        val theme = WebsiteTheme(
                            domain = domain,
                            prompt = prompt,
                            baseScript = script,
                            pageScripts = mapOf(tab.url to script),
                            activeCss = blueprint.customCss,
                            activeConcept = blueprint.concept,
                            chatHistory = finalHistory,
                            bg = blueprint.bg,
                            fg = blueprint.fg
                        )
                        _websiteThemes.value = _websiteThemes.value + (domain to theme)
                    }
                    _pendingUserScriptToInject.value = script

                    // Requirement 5: Default model for subsequent continued chats is gemini-3.5-flash-lite
                    setDefaultModelForContinuedChat()

                    val wv = activeWebViewRef
                    if (wv != null && domain.isNotBlank()) {
                        delay(5000L)
                        val bitmap = captureWebViewBitmap(wv)
                        val screenshotPath = if (bitmap != null) saveScreenshotToFile(bitmap) else null

                        val initialThemeEntity = GeneratedThemeEntity(
                            domain = domain,
                            websiteUrl = tab.url,
                            websiteTitle = siteName,
                            prompt = prompt,
                            themeName = blueprint.name,
                            script = script,
                            screenshotPath = screenshotPath,
                            activeCss = blueprint.customCss,
                            concept = blueprint.concept,
                            chatHistoryJson = ThemeChatMessage.listToJson(finalHistory),
                            bg = blueprint.bg,
                            fg = blueprint.fg
                        )
                        browserRepository.saveGeneratedTheme(initialThemeEntity)
                    } else if (domain.isNotBlank()) {
                        val fallbackTheme = GeneratedThemeEntity(
                            domain = domain,
                            websiteUrl = tab.url,
                            websiteTitle = siteName,
                            prompt = prompt,
                            themeName = blueprint.name,
                            script = script,
                            screenshotPath = null,
                            activeCss = blueprint.customCss,
                            concept = blueprint.concept,
                            chatHistoryJson = ThemeChatMessage.listToJson(finalHistory),
                            bg = blueprint.bg,
                            fg = blueprint.fg
                        )
                        browserRepository.saveGeneratedTheme(fallbackTheme)
                    }
                }.onFailure { err ->
                    thinkingTimerJob?.cancel()
                    val errMsg = err.message ?: "Failed to generate styling."
                    _aiStylerErrorMessage.value = errMsg
                    _aiThinkingState.update { current ->
                        val updatedChat = current.chatHistory.map { msg ->
                            if (msg.id == agentThinkingMsgId) {
                                msg.copy(
                                    isThinkingActive = false,
                                    isThinkingFinished = true,
                                    errorMessage = errMsg,
                                    thinkingPhase = "Synthesis issue"
                                )
                            } else msg
                        }
                        current.copy(
                            isActive = false,
                            isFinished = true,
                            phase = "Synthesis Encountered an Issue",
                            currentStreamSnippet = "",
                            errorMessage = errMsg,
                            chatHistory = updatedChat
                        )
                    }
                }
            }
        }
    }

    private var activeWebViewRef: android.webkit.WebView? = null

    fun registerActiveWebView(webView: android.webkit.WebView?) {
        activeWebViewRef = webView
    }

    private suspend fun captureWebViewBitmap(webView: android.webkit.WebView): android.graphics.Bitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val initialBmp = captureViaDraw(webView)
        if (initialBmp != null && !isMostlyBlank(initialBmp)) {
            return@withContext initialBmp
        }
        delay(300L)
        val retryBmp = captureViaDraw(webView)
        if (retryBmp != null && !isMostlyBlank(retryBmp)) {
            initialBmp?.recycle()
            return@withContext retryBmp
        }
        return@withContext retryBmp ?: initialBmp
    }

    private fun captureViaDraw(webView: android.webkit.WebView): android.graphics.Bitmap? {
        return try {
            val width = if (webView.width > 0) webView.width else 1080
            val height = if (webView.height > 0) webView.height else 1920
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            val wasVert = webView.isVerticalScrollBarEnabled
            val wasHoriz = webView.isHorizontalScrollBarEnabled
            try {
                webView.isVerticalScrollBarEnabled = false
                webView.isHorizontalScrollBarEnabled = false
                val scrollX = webView.scrollX
                val scrollY = webView.scrollY
                if (scrollX != 0 || scrollY != 0) {
                    canvas.save()
                    canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
                    webView.draw(canvas)
                    canvas.restore()
                } else {
                    webView.draw(canvas)
                }
            } finally {
                webView.isVerticalScrollBarEnabled = wasVert
                webView.isHorizontalScrollBarEnabled = wasHoriz
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun isMostlyBlank(bmp: android.graphics.Bitmap): Boolean {
        var lo = 255
        var hi = 0
        val sx = (bmp.width / 24).coerceAtLeast(1)
        val sy = (bmp.height / 48).coerceAtLeast(1)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                val l = (android.graphics.Color.red(p) * 299 + android.graphics.Color.green(p) * 587 + android.graphics.Color.blue(p) * 114) / 1000
                if (l < lo) lo = l
                if (l > hi) hi = l
                x += sx
            }
            y += sy
        }
        return hi - lo < 14
    }

    private fun saveScreenshotToFile(bitmap: android.graphics.Bitmap): String? {
        return try {
            val dir = java.io.File(getApplication<Application>().filesDir, "theme_screenshots")
            if (!dir.exists()) dir.mkdirs()
            val fileName = "theme_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(6)}.jpg"
            val file = java.io.File(dir, fileName)
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun bitmapToBase64(bitmap: android.graphics.Bitmap): String {
        val maxWidth = 720
        val toEncode = if (bitmap.width > maxWidth) {
            val ratio = maxWidth.toFloat() / bitmap.width
            android.graphics.Bitmap.createScaledBitmap(
                bitmap,
                maxWidth,
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else bitmap
        val outputStream = java.io.ByteArrayOutputStream()
        toEncode.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, outputStream)
        if (toEncode !== bitmap) toEncode.recycle()
        val byteArray = outputStream.toByteArray()
        return android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP)
    }

    fun dismissMorphOverlay() {
        _aiThinkingState.update {
            it.copy(
                morphBgColor = null,
                morphFgColor = null,
                isActive = false,
                isFinished = false,
                currentStreamSnippet = ""
            )
        }
    }

    /**
     * Requirement 1 & 2:
     * Deactivates the active theme, sets the brush icon back to inactive state,
     * and runs a staggered exit animation on each styled element before restoring the original webpage.
     */
    fun resetCustomStyle() {
        val domain = extractDomain(activeTab.value?.url ?: "")
        if (domain.isNotBlank()) {
            _websiteThemes.value = _websiteThemes.value - domain
        }
        backgroundAdaptationJob?.cancel()
        _isThemeUpdatingInBackground.value = false
        dismissMorphOverlay()
        _pendingUserScriptToInject.value = """
            (function() {
                if (window.__mydes && window.__mydes.exit) {
                    try { window.__mydes.exit(); return; } catch (e) {}
                }
                var styledEls = document.querySelectorAll('[data-mydes], header, footer');
                styledEls.forEach(function(el, idx) {
                    var stagger = Math.min(idx * 18, 220);
                    el.style.setProperty('transition', 'all 0.42s cubic-bezier(0.22, 1, 0.36, 1) ' + stagger + 'ms', 'important');
                    el.style.setProperty('transform', 'scale(0.96) translateY(8px)', 'important');
                    el.style.setProperty('opacity', '0.15', 'important');
                    el.style.setProperty('filter', 'blur(2px)', 'important');
                });
                setTimeout(function() {
                    var existing = document.getElementById('__mydes_style') || document.getElementById('mydes-ai-styler-style');
                    if (existing) existing.remove();
                    styledEls.forEach(function(el) {
                        el.style.setProperty('transform', 'scale(1) translateY(0)', 'important');
                        el.style.setProperty('opacity', '1', 'important');
                        el.style.setProperty('filter', 'none', 'important');
                    });
                    setTimeout(function() {
                        if (window.__mydes && window.__mydes.clear) { try { window.__mydes.clear(); } catch (e) {} }
                        styledEls.forEach(function(el) {
                            el.removeAttribute('data-mydes');
                            el.style.removeProperty('transition');
                            el.style.removeProperty('transform');
                            el.style.removeProperty('opacity');
                            el.style.removeProperty('filter');
                        });
                    }, 320);
                }, 380);
            })();
        """.trimIndent()
    }

    fun onUserScriptInjected() {
        _pendingUserScriptToInject.value = null
    }

    fun onPageLoaded(tabId: String, url: String, title: String) {
        val domain = extractDomain(url)
        if (domain.isBlank()) return

        val activeTheme = _websiteThemes.value[domain] ?: return

        val cachedPageScript = activeTheme.pageScripts[url]
        val initialScript = cachedPageScript ?: activeTheme.baseScript
        _pendingUserScriptToInject.value = initialScript

        if (!activeTheme.isImported && cachedPageScript == null && url != lastAdaptedUrl) {
            lastAdaptedUrl = url
            backgroundAdaptationJob?.cancel()
            backgroundAdaptationJob = viewModelScope.launch {
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
                        val currentTheme = _websiteThemes.value[domain]
                        if (currentTheme != null) {
                            val updatedTheme = currentTheme.copy(
                                pageScripts = currentTheme.pageScripts + (url to adaptedScript),
                                baseScript = adaptedScript
                            )
                            _websiteThemes.value = _websiteThemes.value + (domain to updatedTheme)
                        }

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

    fun toggleFullScreenMode(enabled: Boolean) {
        _isFullScreenMode.value = enabled
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
