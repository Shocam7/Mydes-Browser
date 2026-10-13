package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class QuickAccessShortcut(
    val name: String,
    val url: String,
    val initial: String,
    val brandColor: Color,
    val textColor: Color = Color.White
)

val DEFAULT_SHORTCUTS = listOf(
    QuickAccessShortcut("Google", "https://www.google.com", "G", Color(0xFF4285F4)),
    QuickAccessShortcut("YouTube", "https://www.youtube.com", "▶", Color(0xFFFF0000)),
    QuickAccessShortcut("GitHub", "https://github.com", "GH", Color(0xFF24292F)),
    QuickAccessShortcut("Reddit", "https://www.reddit.com", "rd", Color(0xFFFF4500)),
    QuickAccessShortcut("Wikipedia", "https://www.wikipedia.org", "W", Color(0xFF333333)),
    QuickAccessShortcut("X", "https://x.com", "𝕏", Color(0xFF14171A)),
    QuickAccessShortcut("Amazon", "https://www.amazon.com", "a", Color(0xFFFF9900)),
    QuickAccessShortcut("DuckDuckGo", "https://duckduckgo.com", "🦆", Color(0xFFDE5833)),
    QuickAccessShortcut("Spotify", "https://open.spotify.com", "♫", Color(0xFF1DB954)),
    QuickAccessShortcut("Netflix", "https://www.netflix.com", "N", Color(0xFFE50914))
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartPage(
    isIncognito: Boolean,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val bgColor = if (isIncognito) Color(0xFF121214) else Color(0xFFF7F8FA)
    val cardBgColor = if (isIncognito) Color(0xFF1E2024) else Color(0xFFFFFFFF)
    val textPrimary = if (isIncognito) Color(0xFFE6E6E6) else Color(0xFF1A1A1A)
    val textSecondary = if (isIncognito) Color(0xFF8E9297) else Color(0xFF75787E)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Simple text logo
            Text(
                text = if (isIncognito) "Mydes Private" else "Mydes",
                color = textPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Clean Search Bar Pill in the center
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = if (isIncognito) 2.dp else 4.dp, shape = RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .background(cardBgColor)
                    .border(
                        width = 1.dp,
                        color = if (isIncognito) Color(0xFF2D3035) else Color(0xFFE2E4E8),
                        shape = RoundedCornerShape(28.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = textSecondary,
                        modifier = Modifier.size(20.dp)
                    )

                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                            .testTag("start_page_search_input"),
                        singleLine = true,
                        textStyle = TextStyle(
                            color = textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(textPrimary),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                if (searchQuery.isNotBlank()) {
                                    focusManager.clearFocus()
                                    onNavigate(searchQuery)
                                }
                            }
                        ),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search Google or type URL",
                                    color = textSecondary,
                                    fontSize = 15.sp
                                )
                            }
                            innerTextField()
                        }
                    )

                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Surrounding randomized/curated grid of icon-only buttons
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                maxItemsInEachRow = 5
            ) {
                DEFAULT_SHORTCUTS.forEach { shortcut ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .size(52.dp)
                            .shadow(2.dp, CircleShape)
                            .clip(CircleShape)
                            .background(shortcut.brandColor)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true),
                                onClick = { onNavigate(shortcut.url) }
                            )
                            .testTag("shortcut_${shortcut.name.lowercase()}")
                            .semantics { contentDescription = "Open ${shortcut.name}" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = shortcut.initial,
                            color = shortcut.textColor,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
