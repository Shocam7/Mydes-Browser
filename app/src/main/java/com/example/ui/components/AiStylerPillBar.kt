package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.data.model.GeneratedThemeEntity
import kotlinx.coroutines.delay
import java.io.File

/**
 * Minimalistic pill-based text bar that swifts out at the bottom
 * when clicking the brush icon.
 *
 * Displays a carousel of previous theme generations for this website along with
 * their screenshots, allowing instantaneous re-application to save AI inference,
 * plus an input bar to generate new 3D/imaginative styles.
 */
@Composable
fun AiStylerPillBar(
    visible: Boolean,
    websiteName: String,
    isLoading: Boolean,
    isStyleApplied: Boolean,
    errorMessage: String?,
    previousGenerations: List<GeneratedThemeEntity> = emptyList(),
    currentAppliedPrompt: String? = null,
    onApplyStyle: (prompt: String) -> Unit,
    onApplySavedTheme: (GeneratedThemeEntity) -> Unit = {},
    onDeleteSavedTheme: (GeneratedThemeEntity) -> Unit = {},
    onResetStyle: () -> Unit,
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

    // Dynamic rotating placeholder examples
    val placeholders = remember(cleanSiteName) {
        listOf(
            "Make my $cleanSiteName look like a masonry art wall",
            "Make my $cleanSiteName look like the solar system",
            "Make my $cleanSiteName look like a cyberpunk terminal",
            "Make my $cleanSiteName look like a vintage newspaper"
        )
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. CAROUSEL OF PREVIOUS GENERATIONS FOR THIS WEBSITE
            if (previousGenerations.isNotEmpty()) {
                PreviousGenerationsSection(
                    generations = previousGenerations,
                    currentAppliedPrompt = currentAppliedPrompt,
                    onApply = onApplySavedTheme,
                    onDelete = onDeleteSavedTheme
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 2. Suggestion chips row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StylerSuggestionChip(
                    text = "Masonry art wall",
                    onClick = {
                        promptInput = "Make my $cleanSiteName look like a masonry art wall"
                        keyboardController?.hide()
                        onApplyStyle(promptInput)
                    }
                )
                StylerSuggestionChip(
                    text = "Solar system",
                    onClick = {
                        promptInput = "Make my $cleanSiteName look like the solar system"
                        keyboardController?.hide()
                        onApplyStyle(promptInput)
                    }
                )
                StylerSuggestionChip(
                    text = "Cyberpunk terminal",
                    onClick = {
                        promptInput = "Make my $cleanSiteName look like a cyberpunk terminal"
                        keyboardController?.hide()
                        onApplyStyle(promptInput)
                    }
                )
                StylerSuggestionChip(
                    text = "Vintage newspaper",
                    onClick = {
                        promptInput = "Make my $cleanSiteName look like a vintage newspaper"
                        keyboardController?.hide()
                        onApplyStyle(promptInput)
                    }
                )

                if (isStyleApplied) {
                    StylerSuggestionChip(
                        text = "Reset style",
                        isHighlight = true,
                        onClick = onResetStyle
                    )
                }
            }

            // 3. Main Pill Bar for New Prompt Input
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
                    // Left Brush / Sparkle emblem
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

                    // Text Field with rotating placeholder
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

                    Spacer(modifier = Modifier.width(6.dp))

                    // Action: Loading spinner OR Submit button
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
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E2024))
                                .testTag("ai_styler_submit_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Redesign website with Gemini",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Close pill button
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

            // Error notice if generation had an issue
            if (!errorMessage.isNullOrBlank()) {
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

/**
 * Horizontal Carousel displaying saved designs with screenshots for the current website.
 */
@Composable
private fun PreviousGenerationsSection(
    generations: List<GeneratedThemeEntity>,
    currentAppliedPrompt: String?,
    onApply: (GeneratedThemeEntity) -> Unit,
    onDelete: (GeneratedThemeEntity) -> Unit
) {
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
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
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

                Text(
                    text = "Tap to apply (saves AI inference)",
                    color = Color(0xFF9EACB9),
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Carousel List
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("previous_themes_carousel"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
            ) {
                items(generations, key = { it.id }) { theme ->
                    val isActive = currentAppliedPrompt == theme.prompt

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

@Composable
private fun ThemeCarouselCard(
    theme: GeneratedThemeEntity,
    isActive: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit
) {
    val screenshotFile = remember(theme.screenshotPath) {
        if (!theme.screenshotPath.isNullOrBlank()) File(theme.screenshotPath) else null
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
        // Screenshot Preview Image or Fallback Gradient
        if (screenshotFile != null && screenshotFile.exists()) {
            AsyncImage(
                model = screenshotFile,
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

        // Dark gradient scrim overlay on bottom for high contrast readability
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

        // Top badges row: Status (Active / Apply) & Delete button
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
                            text = "ACTIVE",
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

            // Delete generation button
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

        // Bottom Title & Time
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
