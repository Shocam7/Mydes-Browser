package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.api.GeminiStylerService
import com.example.data.api.ReasoningModulatorConfig
import com.example.data.model.GeneratedThemeEntity
import com.example.data.model.ThemeVisualIteration
import com.example.ui.viewmodel.AiThinkingState
import kotlinx.coroutines.delay
import java.io.File
import com.example.ui.viewmodel.YoloInspectionState


@Composable
fun AiStylerPillBar(
    visible: Boolean,
    websiteName: String,
    isLoading: Boolean,
    isStyleApplied: Boolean,
    errorMessage: String?,
    thinkingState: AiThinkingState = AiThinkingState(),
    previousGenerations: List<GeneratedThemeEntity> = emptyList(),
    currentAppliedPrompt: String? = null,
    currentAppliedConcept: String? = null,
    onApplyStyle: (prompt: String) -> Unit,
    onApplySavedTheme: (GeneratedThemeEntity) -> Unit = {},
    onDeleteSavedTheme: (GeneratedThemeEntity) -> Unit = {},
    onRevertToVisualIteration: (ThemeVisualIteration) -> Unit = {},
    onResetStyle: () -> Unit,
    onOpenImportRedesign: () -> Unit = {},
    selectedSynthesisModel: String = GeminiStylerService.MODEL_GEMINI_35_FLASH_LITE,
    onToggleSynthesisModel: () -> Unit = {},
    selectedReasoningLevel: String = "low",
    onCycleReasoningLevel: () -> Unit = {},
    onSelectReasoningLevel: (String) -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var promptInput by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    val cleanSiteName = remember(websiteName) {
        if (websiteName.isNotBlank() && websiteName != "New Tab" && websiteName != "Private Tab") {
            val titleClean = websiteName
                .split(" - ", " – ", " — ", " | ", ": ", " • ", ",")
                .firstOrNull()?.trim() ?: websiteName
            if (titleClean.length > 20) {
                titleClean.take(20).trim()
            } else {
                titleClean
            }
        } else {
            "website"
        }
    }

    val placeholders = remember(cleanSiteName, isStyleApplied) {
        if (isStyleApplied) {
            listOf(
                "Tweak this style (e.g., make buttons neon pink)",
                "Modify active design (e.g., darker headers, rounded cards)",
                "Mold current theme (e.g., glassmorphic cards, gold trims)",
                "Adjust colors or typography for $cleanSiteName"
            )
        } else {
            listOf(
                "Make my $cleanSiteName look like a masonry art wall",
                "Make my $cleanSiteName look like the solar system",
                "Make my $cleanSiteName look like a cyberpunk terminal",
                "Make my $cleanSiteName look like a vintage newspaper"
            )
        }
    }

    var placeholderIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible, placeholders) {
        while (visible) {
            delay(4000L)
            placeholderIndex = (placeholderIndex + 1) % placeholders.size
        }
    }

    val currentPlaceholder = placeholders.getOrElse(placeholderIndex) { placeholders.first() }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
        exit = fadeOut(tween(180)) +
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(220)
                ),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // MorphingWebOverlay is part of AiStylerPillBar: appears & exits together
            if (thinkingState.morphBgColor != null || thinkingState.chatHistory.isNotEmpty() || thinkingState.isActive) {
                MorphingWebOverlay(
                    state = thinkingState,
                    websiteName = websiteName,
                    onDismiss = onDismiss,
                    onRevertToIteration = onRevertToVisualIteration,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            // Requirement 1: Deactivation Cross Button on the right side just above AiStylerPillBar when a theme is active
            AnimatedVisibility(
                visible = isStyleApplied,
                enter = fadeIn(tween(200)) + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut(tween(150)) + slideOutVertically(targetOffsetY = { it / 2 })
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp, end = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .shadow(6.dp, CircleShape)
                            .clip(CircleShape)
                            .background(Color(0xFF1E2024))
                            .border(1.5.dp, Color(0xFFFF6B6B), CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true),
                                onClick = onResetStyle
                            )
                            .testTag("deactivate_theme_cross_button")
                            .semantics { contentDescription = "Deactivate theme and restore original website" },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = Color(0xFFFF6B6B),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // 1. CAROUSEL OF PREVIOUS GENERATIONS FOR THIS WEBSITE
            if (previousGenerations.isNotEmpty()) {
                PreviousGenerationsSection(
                    generations = previousGenerations,
                    currentAppliedPrompt = currentAppliedPrompt,
                    currentAppliedConcept = currentAppliedConcept,
                    onApply = onApplySavedTheme,
                    onDelete = onDeleteSavedTheme
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 2. Controls & Suggestion Chips Row (Model Switcher + Reasoning Modulator + Presets)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 2A. 1-Tap Quick Model Switcher
                val isNemotron = selectedSynthesisModel == GeminiStylerService.MODEL_NVIDIA_NEMOTRON_3_ULTRA
                val isGemini38 = selectedSynthesisModel == GeminiStylerService.MODEL_GEMINI_38_FLASH

                val modelLabel = when {
                    isNemotron -> "Nemotron 3 Ultra (NIM)"
                    isGemini38 -> "Gemini 3.8 Flash"
                    else -> "Gemini 3.5 Flash Lite"
                }
                val dotColor = when {
                    isNemotron -> Color(0xFF76B900)
                    isGemini38 -> Color(0xFFA855F7)
                    else -> Color(0xFF4285F4)
                }
                val bgColor = when {
                    isNemotron -> Color(0xFF1B3818)
                    isGemini38 -> Color(0xFF281438)
                    else -> Color(0xFF182845)
                }
                val borderColor = when {
                    isNemotron -> Color(0xFF76B900)
                    isGemini38 -> Color(0xFFA855F7)
                    else -> Color(0xFF4285F4)
                }
                val switchColor = when {
                    isNemotron -> Color(0xFF76B900)
                    isGemini38 -> Color(0xFFD8B4FE)
                    else -> Color(0xFF8AB4F8)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(bgColor)
                        .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                        .clickable(onClick = onToggleSynthesisModel)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("toggle_synthesis_model_chip"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Text(
                            text = modelLabel,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "⇄ Switch",
                            color = switchColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // 2B. Reasoning Modulator Pill
                val availableModes = remember(selectedSynthesisModel) {
                    ReasoningModulatorConfig.getAvailableModesForModel(selectedSynthesisModel)
                }
                val effectiveReasoningMode = if (isStyleApplied) {
                    ReasoningModulatorConfig.getLowestModeForModel(selectedSynthesisModel)
                } else {
                    selectedReasoningLevel
                }

                ReasoningModulatorBar(
                    model = selectedSynthesisModel,
                    availableModes = availableModes,
                    selectedMode = effectiveReasoningMode,
                    isLockedLowestForModification = isStyleApplied,
                    onSelectMode = onSelectReasoningLevel,
                    onCycleMode = onCycleReasoningLevel
                )

                if (isStyleApplied) {
                    StylerSuggestionChip(
                        text = "Neon pink buttons",
                        onClick = {
                            promptInput = "Make all buttons neon pink with glowing hover effect"
                            keyboardController?.hide()
                            onApplyStyle(promptInput)
                            promptInput = ""
                        }
                    )
                    StylerSuggestionChip(
                        text = "Frosted glass header",
                        onClick = {
                            promptInput = "Make the header frosted glass backdrop with subtle border"
                            keyboardController?.hide()
                            onApplyStyle(promptInput)
                            promptInput = ""
                        }
                    )
                    StylerSuggestionChip(
                        text = "Thinner borders",
                        onClick = {
                            promptInput = "Refine cards with ultra-thin 1px borders and sleek shadows"
                            keyboardController?.hide()
                            onApplyStyle(promptInput)
                            promptInput = ""
                        }
                    )
                    StylerSuggestionChip(
                        text = "Gold accents",
                        onClick = {
                            promptInput = "Add metallic gold accents to badges and highlights"
                            keyboardController?.hide()
                            onApplyStyle(promptInput)
                            promptInput = ""
                        }
                    )
                } else {
                    StylerSuggestionChip(
                        text = "Import Redesign (.json)",
                        isHighlight = true,
                        onClick = onOpenImportRedesign
                    )
                    StylerSuggestionChip(
                        text = "Masonry art wall",
                        onClick = {
                            val p = "Make my $cleanSiteName look like a masonry art wall"
                            keyboardController?.hide()
                            onApplyStyle(p)
                        }
                    )
                    StylerSuggestionChip(
                        text = "Solar system",
                        onClick = {
                            val p = "Make my $cleanSiteName look like the solar system"
                            keyboardController?.hide()
                            onApplyStyle(p)
                        }
                    )
                    StylerSuggestionChip(
                        text = "Cyberpunk terminal",
                        onClick = {
                            val p = "Make my $cleanSiteName look like a cyberpunk terminal"
                            keyboardController?.hide()
                            onApplyStyle(p)
                        }
                    )
                    StylerSuggestionChip(
                        text = "Vintage newspaper",
                        onClick = {
                            val p = "Make my $cleanSiteName look like a vintage newspaper"
                            keyboardController?.hide()
                            onApplyStyle(p)
                        }
                    )
                }
            }

            // 3. Main Pill Bar for Prompt / Chat Continuation Input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(12.dp, RoundedCornerShape(30.dp))
                    .clip(RoundedCornerShape(30.dp))
                    .background(Color(0xFFF2F4F7))
                    .border(1.dp, Color(0xFF1E2024), RoundedCornerShape(30.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("ai_styler_pill_bar")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF22252A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Website Styler",
                            tint = Color(0xFFF6C343),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = promptInput,
                            onValueChange = { promptInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_styler_prompt_input"),
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Color(0xFF1E2024),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            cursorBrush = SolidColor(Color(0xFF1E2024)),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    val target = promptInput.ifBlank { currentPlaceholder }
                                    keyboardController?.hide()
                                    onApplyStyle(target)
                                    promptInput = ""
                                }
                            ),
                            decorationBox = { innerTextField ->
                                if (promptInput.isEmpty()) {
                                    Text(
                                        text = currentPlaceholder,
                                        color = Color(0xFF75787E),
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }

                    IconButton(
                        onClick = onOpenImportRedesign,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2C323D))
                            .testTag("ai_styler_import_file_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = "Import external redesign file",
                            tint = Color(0xFF81D4FA),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = Color(0xFF1E2024)
                        )
                    } else {
                        IconButton(
                            onClick = {
                                val target = promptInput.ifBlank { currentPlaceholder }
                                keyboardController?.hide()
                                onApplyStyle(target)
                                promptInput = ""
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E2024))
                                .testTag("ai_styler_submit_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Submit prompt",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss AI styler",
                            tint = Color(0xFF55585D),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (!errorMessage.isNullOrBlank() && !thinkingState.isActive) {
                Text(
                    text = errorMessage,
                    color = Color(0xFFD32F2F),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}
}

/**
 * Segmented Reasoning Modulator Pill:
 * Displays available reasoning levels for the currently selected model:
 * - Nemotron: OFF | Medium | Full
 * - Gemini 3.8 Flash: Low | Medium | High
 * - Gemini 3.5 Flash Lite: Minimal | Low | Medium | High
 */
@Composable
private fun ReasoningModulatorBar(
    model: String,
    availableModes: List<String>,
    selectedMode: String,
    isLockedLowestForModification: Boolean,
    onSelectMode: (String) -> Unit,
    onCycleMode: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1E2024))
            .border(1.dp, Color(0xFF3A3F4B), RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("reasoning_modulator_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Psychology,
            contentDescription = "Reasoning Modulator",
            tint = Color(0xFFF6C343),
            modifier = Modifier
                .size(15.dp)
                .clickable(onClick = onCycleMode)
        )
        Text(
            text = "Think:",
            color = Color(0xFFCFD5DE),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onCycleMode)
        )

        if (isLockedLowestForModification) {
            val label = ReasoningModulatorConfig.getDisplayLabel(model, selectedMode)
            val isNemotron = GeminiStylerService.isNvidiaModel(model)
            val badgeBg = if (isNemotron) Color(0xFF76B900).copy(alpha = 0.25f) else Color(0xFFF6C343).copy(alpha = 0.2f)
            val badgeBorder = if (isNemotron) Color(0xFF76B900) else Color(0xFFF6C343)
            val badgeText = if (isNemotron) Color(0xFF9BE61D) else Color(0xFFF6C343)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(badgeBg)
                    .border(0.8.dp, badgeBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "$label (Lowest)",
                    color = badgeText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            availableModes.forEach { mode ->
                val isSelected = selectedMode.equals(mode, ignoreCase = true)
                val label = ReasoningModulatorConfig.getDisplayLabel(model, mode)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) Color(0xFFF6C343) else Color(0xFF2B2F38)
                        )
                        .clickable { onSelectMode(mode) }
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                        .testTag("reasoning_mode_option_$mode"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color(0xFF141619) else Color(0xFF9EA6B4),
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviousGenerationsSection(
    generations: List<GeneratedThemeEntity>,
    currentAppliedPrompt: String?,
    currentAppliedConcept: String? = null,
    onApply: (GeneratedThemeEntity) -> Unit,
    onDelete: (GeneratedThemeEntity) -> Unit
) {
    var isExpanded by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF1E2024))
            .border(1.dp, Color(0xFF33373F), RoundedCornerShape(24.dp))
            .padding(vertical = 12.dp)
            .testTag("previous_themes_carousel_container")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = { isExpanded = !isExpanded }
                    )
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .testTag("saved_designs_header_toggle"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = Color(0xFFF6C343),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Saved Designs",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF32363E))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${generations.size}",
                            color = Color(0xFFD0D3DA),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (isExpanded) "Tap to apply & view chat" else "Tap to expand",
                        color = Color(0xFF9EACB9),
                        fontSize = 11.sp
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2C3038)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Minimize Saved Designs" else "Expand Saved Designs",
                            tint = Color(0xFFF6C343),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("previous_themes_carousel"),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
                ) {
                    items(generations, key = { it.id }) { theme ->
                        val isActive = (currentAppliedPrompt != null && currentAppliedPrompt == theme.prompt) ||
                            (currentAppliedConcept != null && (currentAppliedConcept == theme.concept || currentAppliedConcept == theme.themeName))

                        ThemeCarouselCard(
                            theme = theme,
                            isActive = isActive,
                            onApply = { onApply(theme) },
                            onDelete = { onDelete(theme) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeCarouselCard(
    theme: GeneratedThemeEntity,
    isActive: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val screenshotModel: Any? = remember(theme.screenshotPath) {
        val path = theme.screenshotPath
        if (path.isNullOrBlank()) null
        else {
            val fileName = java.io.File(path).name
            val themeFilesDir = java.io.File(context.filesDir, "theme_screenshots")
            val reviewFilesDir = java.io.File(context.filesDir, "review_screenshots")
            val f1 = java.io.File(themeFilesDir, fileName)
            val f2 = java.io.File(reviewFilesDir, fileName)
            val direct = java.io.File(path)
            when {
                f1.exists() && f1.length() > 0L -> f1
                f2.exists() && f2.length() > 0L -> f2
                direct.exists() && direct.length() > 0L -> direct
                else -> {
                    try {
                        themeFilesDir.mkdirs()
                        context.assets.open("files/theme_screenshots/$fileName").use { input ->
                            java.io.FileOutputStream(f1).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (f1.exists() && f1.length() > 0L) f1 else "file:///android_asset/files/theme_screenshots/$fileName"
                    } catch (_: Exception) {
                        try {
                            reviewFilesDir.mkdirs()
                            context.assets.open("files/review_screenshots/$fileName").use { input ->
                                java.io.FileOutputStream(f2).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (f2.exists() && f2.length() > 0L) f2 else "file:///android_asset/files/review_screenshots/$fileName"
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .width(160.dp)
            .height(115.dp)
            .shadow(6.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF272A30))
            .border(
                width = if (isActive) 2.dp else 1.dp,
                color = if (isActive) Color(0xFFF6C343) else Color(0xFF3F444E),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onApply)
            .semantics { contentDescription = "Saved design: ${theme.prompt}" }
    ) {
        if (screenshotModel != null) {
            AsyncImage(
                model = screenshotModel,
                contentDescription = "Theme screenshot preview",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF2C3E50), Color(0xFF000000))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Color(0xFFF6C343).copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.4f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.85f)
                        )
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF2E7D32))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "APPLIED",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E2024).copy(alpha = 0.85f))
                        .border(0.5.dp, Color(0xFFF6C343), RoundedCornerShape(10.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "APPLY",
                        color = Color(0xFFF6C343),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete saved theme",
                    tint = Color(0xFFE57373),
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = theme.prompt,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatTimeAgo(theme.createdAt),
                color = Color(0xFFCFD5DE),
                fontSize = 9.sp
            )
        }
    }
}

private fun formatTimeAgo(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> "Earlier"
    }
}

@Composable
private fun StylerSuggestionChip(
    text: String,
    isHighlight: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .shadow(2.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(if (isHighlight) Color(0xFF1E2024) else Color(0xFFFFFFFF))
            .border(1.dp, Color(0xFFE2E4E8), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = "Preset: $text" }
    ) {
        Text(
            text = text,
            color = if (isHighlight) Color.White else Color(0xFF22252A),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private val YOLO_ROLE_OPTIONS = listOf(
    "card" to Color(0xFF00E676),
    "header" to Color(0xFF00E5FF),
    "bottom-bar" to Color(0xFF00B8D4),
    "search" to Color(0xFFFFD600),
    "chip" to Color(0xFFFF4081),
    "media-box" to Color(0xFFB388FF),
    "heading" to Color(0xFFFF9100),
    "title" to Color(0xFFFF9100),
    "btn" to Color(0xFFFF5252),
    "nav-item" to Color(0xFF64FFDA),
    "none" to Color(0xFF9E9E9E)
)

@Composable
fun YoloInspectorApprovalBar(
    state: YoloInspectionState,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onAssignRole: (String) -> Unit,
    onStepSelection: (String) -> Unit,
    onTogglePropagateSiblings: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.isActive) return

    var isRoleDropdownExpanded by remember { mutableStateOf(false) }
    val selected = state.selectedElement

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .shadow(16.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF14171C).copy(alpha = 0.96f))
            .border(1.5.dp, Color(0xFF00E676).copy(alpha = 0.7f), RoundedCornerShape(24.dp))
            .padding(14.dp)
            .testTag("yolo_inspector_approval_bar")
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Top Status & Role Counts Summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676))
                    )
                    Column {
                        Text(
                            text = "YOLO Element Inspector",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = state.statusToast ?: "Tap any element on the page to inspect or change its tag",
                            color = Color(0xFFB0BEC5),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Total mapped badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF263238))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${state.roleCounts.values.sum()} tagged",
                        color = Color(0xFF00E676),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 2. Selected Element Inspector & Role Dropdown (Choices 3 & 4)
            if (selected != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1E232B))
                        .border(1.dp, Color(0xFF37474F), RoundedCornerShape(14.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "<${selected.tag}> (${selected.rectW}×${selected.rectH}px)",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val curRole = selected.assignedRole.ifBlank { "unassigned" }
                            Text(
                                text = "Current tag: [$curRole] • ${selected.matchingSiblings} matching siblings",
                                color = Color(0xFF90A4AE),
                                fontSize = 10.5.sp
                            )
                        }

                        // Step Up / Step Down buttons for Ancestor-Snapping refinement
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected.canStepUp) Color(0xFF2C333F) else Color(0xFF1A1E24))
                                    .clickable(enabled = selected.canStepUp) { onStepSelection("up") }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "↑ Parent",
                                    color = if (selected.canStepUp) Color(0xFF80D8FF) else Color(0xFF546E7A),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected.canStepDown) Color(0xFF2C333F) else Color(0xFF1A1E24))
                                    .clickable(enabled = selected.canStepDown) { onStepSelection("down") }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "↓ Child",
                                    color = if (selected.canStepDown) Color(0xFF80D8FF) else Color(0xFF546E7A),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Dropdown trigger + Sibling Propagation toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF28303D))
                                .border(1.dp, Color(0xFFF6C343), RoundedCornerShape(10.dp))
                                .clickable { isRoleDropdownExpanded = !isRoleDropdownExpanded }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val currentLabel = selected.assignedRole.ifBlank { "Assign Tag..." }
                                Text(
                                    text = "Tag: $currentLabel",
                                    color = Color(0xFFF6C343),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    imageVector = if (isRoleDropdownExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Select Role",
                                    tint = Color(0xFFF6C343),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Sibling Propagation Toggle
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (state.propagateToSiblings) Color(0xFF00E676).copy(alpha = 0.2f)
                                    else Color(0xFF262B33)
                                )
                                .border(
                                    1.dp,
                                    if (state.propagateToSiblings) Color(0xFF00E676) else Color(0xFF455A64),
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { onTogglePropagateSiblings() }
                                .padding(horizontal = 9.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (state.propagateToSiblings) "⚡ Siblings: ON" else "Siblings: OFF",
                                color = if (state.propagateToSiblings) Color(0xFF00E676) else Color(0xFF90A4AE),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Expandable Role Picker Grid/Row
                    AnimatedVisibility(visible = isRoleDropdownExpanded) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            YOLO_ROLE_OPTIONS.forEach { (roleName, roleColor) ->
                                val isCurrent = selected.assignedRole == roleName ||
                                    (roleName == "none" && selected.assignedRole.isBlank())
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isCurrent) roleColor else roleColor.copy(alpha = 0.16f))
                                        .border(1.dp, roleColor, RoundedCornerShape(8.dp))
                                        .clickable {
                                            onAssignRole(roleName)
                                            isRoleDropdownExpanded = false
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = if (roleName == "none") "✕ Clear" else roleName,
                                        color = if (isCurrent) Color.Black else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Approve (Choice 1) & Decline (Choice 2) Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF2B1D22))
                        .border(1.dp, Color(0xFFFF5252), RoundedCornerShape(14.dp))
                        .clickable { onDecline() }
                        .padding(vertical = 10.dp)
                        .testTag("yolo_decline_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Decline",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Decline & Cancel",
                            color = Color(0xFFFF5252),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1.4f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF00E676))
                        .clickable { onApprove() }
                        .padding(vertical = 10.dp)
                        .testTag("yolo_approve_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Approve",
                            tint = Color(0xFF0A0E12),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Approve & Synthesize",
                            color = Color(0xFF0A0E12),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}
