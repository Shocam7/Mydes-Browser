package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Vertical pill menu expanding out of the options button.
 * Series from bottom to top:
 * 1. copy link
 * 2. history
 * 3. downloads
 * 4. bookmarks
 * 5. share
 * 6. find in page
 * 7. reading mode
 * 8. desktop site
 * 9. help
 *
 * Theme rules:
 * - White (#FFFFFF) in light mode, #171717 in incognito mode.
 * - Distinct dark black border around the pill (no border around the icons inside).
 * - The buttons inside have black (#000000) accent in light mode.
 */
@Composable
fun OptionsPillMenu(
    expanded: Boolean,
    isIncognito: Boolean,
    isBookmarked: Boolean,
    isDesktopSite: Boolean,
    isReadingMode: Boolean,
    onCopyLink: () -> Unit,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
    onBookmarks: () -> Unit,
    onShare: () -> Unit,
    onFindInPage: () -> Unit,
    onReadingMode: () -> Unit,
    onDesktopSite: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillBg = if (isIncognito) Color(0xFF171717) else Color(0xFFFFFFFF)
    val iconColor = if (isIncognito) Color(0xFFE2E2E2) else Color(0xFF000000)
    val pillBorderColor = Color(0xFF000000)

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                slideInVertically(
                    initialOffsetY = { it / 3 },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
        exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)) +
                slideOutVertically(
                    targetOffsetY = { it / 4 },
                    animationSpec = spring(stiffness = Spring.StiffnessMedium)
                ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .width(52.dp)
                .shadow(8.dp, RoundedCornerShape(26.dp))
                .clip(RoundedCornerShape(26.dp))
                .background(pillBg)
                .border(BorderStroke(1.5.dp, pillBorderColor), RoundedCornerShape(26.dp))
                .padding(vertical = 6.dp)
                .testTag("options_pill_menu")
        ) {
            // Render from top to bottom on screen:
            // Top: 9. help, 8. desktop site, 7. reading mode, 6. find in page,
            // 5. share, 4. bookmarks, 3. downloads, 2. history, 1. copy link (bottom-most)
            Column(
                modifier = Modifier.width(52.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // 9. Help
                OptionPillItem(
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    label = "Help",
                    iconColor = iconColor,
                    testTag = "pill_item_help",
                    onClick = onHelp
                )
                // 8. Desktop Site
                OptionPillItem(
                    icon = Icons.Default.DesktopWindows,
                    label = if (isDesktopSite) "Mobile site" else "Desktop site",
                    iconColor = if (isDesktopSite) Color(0xFF4285F4) else iconColor,
                    testTag = "pill_item_desktop",
                    onClick = onDesktopSite
                )
                // 7. Reading Mode
                OptionPillItem(
                    icon = Icons.Default.AutoStories,
                    label = if (isReadingMode) "Exit reader" else "Reading mode",
                    iconColor = if (isReadingMode) Color(0xFF34A853) else iconColor,
                    testTag = "pill_item_reader",
                    onClick = onReadingMode
                )
                // 6. Find in Page
                OptionPillItem(
                    icon = Icons.Default.FindInPage,
                    label = "Find in page",
                    iconColor = iconColor,
                    testTag = "pill_item_find",
                    onClick = onFindInPage
                )
                // 5. Share
                OptionPillItem(
                    icon = Icons.Default.Share,
                    label = "Share",
                    iconColor = iconColor,
                    testTag = "pill_item_share",
                    onClick = onShare
                )
                // 4. Bookmarks
                OptionPillItem(
                    icon = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    label = if (isBookmarked) "Bookmarked" else "Bookmarks",
                    iconColor = if (isBookmarked) Color(0xFFFBBC04) else iconColor,
                    testTag = "pill_item_bookmarks",
                    onClick = onBookmarks
                )
                // 3. Downloads
                OptionPillItem(
                    icon = Icons.Default.Download,
                    label = "Downloads",
                    iconColor = iconColor,
                    testTag = "pill_item_downloads",
                    onClick = onDownloads
                )
                // 2. History
                OptionPillItem(
                    icon = Icons.Default.History,
                    label = "History",
                    iconColor = iconColor,
                    testTag = "pill_item_history",
                    onClick = onHistory
                )
                // 1. Copy Link (bottom-most)
                OptionPillItem(
                    icon = Icons.Default.ContentCopy,
                    label = "Copy link",
                    iconColor = iconColor,
                    testTag = "pill_item_copy_link",
                    onClick = onCopyLink
                )
            }
        }
    }
}

@Composable
private fun OptionPillItem(
    icon: ImageVector,
    label: String,
    iconColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .testTag(testTag)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(20.dp)
        )
    }
}
