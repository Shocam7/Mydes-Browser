package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Top Encapsulated Header Pill:
 * Displays when options button is active, encapsulating:
 * - Settings (top-left) in light grey
 * - Page Title (middle) with primary website background; text white, or black if near-white
 * - Brush (top-right) with circular border loader when background theme updates occur
 * Tapping title toggles address input.
 */
@Composable
fun TopHeaderPill(
    title: String,
    currentUrl: String,
    primaryColor: Color?,
    isNearWhite: Boolean,
    onNavigate: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBrush: () -> Unit = {},
    isThemeUpdatingInBackground: Boolean = false,
    isCustomStyleApplied: Boolean = false,
    modifier: Modifier = Modifier
) {
    var isEditingUrl by remember { mutableStateOf(false) }
    var urlInput by remember(currentUrl) { mutableStateOf(currentUrl) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    val pillBackground = primaryColor ?: Color(0xFF22252A)
    val titleTextColor = if (isNearWhite) Color(0xFF000000) else Color(0xFFFFFFFF)
    val buttonBgColor = Color(0xFFE4E6EA)
    val buttonIconColor = Color(0xFF33373D)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .shadow(6.dp, RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp))
            .background(pillBackground)
            .border(
                width = 1.dp,
                color = if (isNearWhite) Color(0x33000000) else Color(0x33FFFFFF),
                shape = RoundedCornerShape(26.dp)
            )
            .testTag("top_header_pill")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Settings icon (staying exactly light grey)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(buttonBgColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = onOpenSettings
                    )
                    .testTag("settings_button")
                    .semantics { contentDescription = "Open browser settings" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = buttonIconColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 2. Middle Title / Address bar
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .height(40.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = isEditingUrl,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "titleOrUrlEditor"
                ) { editing ->
                    if (editing) {
                        LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    if (isNearWhite) Color(0x1A000000) else Color(0x26FFFFFF)
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = urlInput,
                                onValueChange = { urlInput = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester)
                                    .testTag("url_text_input"),
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = titleTextColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                cursorBrush = SolidColor(titleTextColor),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Go
                                ),
                                keyboardActions = KeyboardActions(
                                    onGo = {
                                        focusManager.clearFocus()
                                        isEditingUrl = false
                                        onNavigate(urlInput)
                                    }
                                ),
                                decorationBox = { innerTextField ->
                                    if (urlInput.isEmpty()) {
                                        Text(
                                            text = "Search or type URL...",
                                            color = titleTextColor.copy(alpha = 0.6f),
                                            fontSize = 13.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            )

                            if (urlInput.isNotEmpty()) {
                                IconButton(
                                    onClick = { urlInput = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear URL",
                                        tint = titleTextColor.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    isEditingUrl = false
                                    onNavigate(urlInput)
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Navigate to URL",
                                    tint = titleTextColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    } else {
                        // Display title with click to edit
                        Text(
                            text = if (title.isBlank()) "New Tab" else title,
                            color = titleTextColor,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        urlInput = currentUrl
                                        isEditingUrl = true
                                    }
                                )
                                .testTag("page_title_text")
                                .semantics { contentDescription = "Webpage title, tap to edit URL" }
                        )
                    }
                }
            }

            // 3. Brush icon (AI Website Styler)
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isThemeUpdatingInBackground) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(44.dp),
                        color = Color(0xFFF6C343),
                        strokeWidth = 2.5.dp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isCustomStyleApplied) Color(0xFF1E2024) else buttonBgColor)
                        .border(
                            width = if (isCustomStyleApplied) 1.5.dp else 0.dp,
                            color = if (isCustomStyleApplied) Color(0xFFF6C343) else Color.Transparent,
                            shape = CircleShape
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true),
                            onClick = onOpenBrush
                        )
                        .testTag("brush_button")
                        .semantics { contentDescription = if (isCustomStyleApplied) "AI Website Styler brush (Theme active)" else "AI Website Styler brush" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Brush,
                        contentDescription = null,
                        tint = if (isCustomStyleApplied) Color(0xFFF6C343) else buttonIconColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
