package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Minimalistic pill-based text bar that swifts out at the bottom
 * when clicking the brush icon.
 *
 * Prompts Gemini to write a user-script for custom website design.
 */
@Composable
fun AiStylerPillBar(
    visible: Boolean,
    websiteName: String,
    isLoading: Boolean,
    isStyleApplied: Boolean,
    errorMessage: String?,
    onApplyStyle: (prompt: String) -> Unit,
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
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Suggestion chips row
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

            // Main Pill Bar
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
