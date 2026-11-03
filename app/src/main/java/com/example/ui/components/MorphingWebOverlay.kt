package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.example.data.model.ThemeVisualIteration
import com.example.ui.model.MessageSender
import com.example.ui.model.ThemeChatMessage
import com.example.ui.viewmodel.AiThinkingState
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Redesigned MorphingWebOverlay (AiThinkingPanel):
 * 1. After the Concept Pass finishes and extracts `background` and `fg`, a translucent overlay
 *    with `background` color rises from the bottom of the screen behind all application buttons.
 * 2. Once the overlay has completely covered the website behind (`isCovered == true`), text in
 *    `fg` color begins typing on this overlay inside a scrollable, expandable/collapsible drop-down.
 * 3. Acts as a persistent Chat Panel for the active theme:
 *    - User request bubble
 *    - Agent reply: "Building <concept field from concept pass>"
 *    - Agent reply: Live thinking drop-down that auto-minimizes on completion.
 */
@Composable
fun MorphingWebOverlay(
    state: AiThinkingState,
    websiteName: String = "",
    onDismiss: () -> Unit,
    onRevertToIteration: (ThemeVisualIteration) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Only rise once the concept pass has provided morphBgColor (or when viewing saved chat history)
    val rawBg = state.morphBgColor ?: return

    val parsedBg = remember(rawBg) {
        try {
            val c = if (rawBg.startsWith("#")) rawBg else "#$rawBg"
            Color(android.graphics.Color.parseColor(c))
        } catch (_: Exception) {
            Color(0xFF10141D)
        }
    }

    val parsedFg = remember(state.morphFgColor) {
        try {
            val rawFg = state.morphFgColor ?: "#E6EDF3"
            val c = if (rawFg.startsWith("#")) rawFg else "#$rawFg"
            Color(android.graphics.Color.parseColor(c))
        } catch (_: Exception) {
            Color(0xFFE6EDF3)
        }
    }

    val heightFraction = remember { Animatable(0f) }
    var isCovered by remember { mutableStateOf(false) }
    var isVisualHistoryOpen by remember { mutableStateOf(false) }

    LaunchedEffect(rawBg) {
        if (!isCovered) {
            heightFraction.snapTo(0f)
            heightFraction.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 720, easing = FastOutSlowInEasing)
            )
            isCovered = true
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        if (heightFraction.value > 0.01f) {
            val fraction = heightFraction.value

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction)
                    .background(parsedBg.copy(alpha = 0.90f))
                    .testTag("morphing_web_overlay")
            ) {
                // Luminous morphing wave edge while rising from the bottom
                if (fraction < 0.99f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        parsedFg.copy(alpha = 0.75f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }

                // Only start displaying/typing the chat and live thinking statements AFTER
                // the overlay has completely covered the website behind
                AnimatedVisibility(
                    visible = isCovered,
                    enter = fadeIn(tween(240)),
                    exit = fadeOut(tween(150)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    ThemeChatPanel(
                        state = state,
                        bgColor = parsedBg,
                        fgColor = parsedFg,
                        onOpenVisualHistory = { isVisualHistoryOpen = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Requirement 4: Visual History Panel
                AnimatedVisibility(
                    visible = isVisualHistoryOpen,
                    enter = fadeIn(tween(200)) + slideInVertically(initialOffsetY = { it }),
                    exit = fadeOut(tween(150)) + slideOutVertically(targetOffsetY = { it }),
                    modifier = Modifier.fillMaxSize()
                ) {
                    val iterations = if (state.visualHistory.isNotEmpty()) {
                        state.visualHistory
                    } else if (!state.activeConcept.isNullOrBlank()) {
                        listOf(
                            ThemeVisualIteration(
                                iterationNumber = 1,
                                title = state.activeConcept,
                                screenshotPath = null,
                                script = "",
                                activeCss = "",
                                concept = state.activeConcept,
                                bg = state.morphBgColor ?: "#10141D",
                                fg = state.morphFgColor ?: "#E6EDF3",
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    } else {
                        emptyList()
                    }

                    VisualHistoryPanel(
                        iterations = iterations,
                        activeConcept = state.activeConcept,
                        bgColor = parsedBg,
                        fgColor = parsedFg,
                        onClose = { isVisualHistoryOpen = false },
                        onRevert = { iteration ->
                            onRevertToIteration(iteration)
                            isVisualHistoryOpen = false
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

// Store IDs of messages whose typewriter animation has already played to prevent replaying on re-opening
private val playedTypewriterMessageIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

@Composable
private fun ThemeChatPanel(
    state: AiThinkingState,
    bgColor: Color,
    fgColor: Color,
    onOpenVisualHistory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.chatHistory.size, state.currentStreamSnippet) {
        if (state.chatHistory.isNotEmpty()) {
            listState.animateScrollToItem(state.chatHistory.size - 1)
        }
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topPadding = (topInset + 76.dp).coerceAtLeast(124.dp)
    val iterationsCount = state.visualHistory.size

    Column(modifier = modifier.fillMaxSize()) {
        // Requirement 4: 'Visual History' button with 'History' icon in MorphingWebOverlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topPadding - 46.dp, start = 16.dp, end = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .shadow(4.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(fgColor.copy(alpha = 0.16f))
                    .border(1.dp, fgColor.copy(alpha = 0.36f), RoundedCornerShape(20.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = onOpenVisualHistory
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp)
                    .testTag("visual_history_button"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "Visual History",
                        tint = fgColor,
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = "Visual History" + if (iterationsCount > 1) " ($iterationsCount)" else "",
                        color = fgColor,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("theme_chat_messages_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 220.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.chatHistory, key = { it.id }) { message ->
                when {
                    message.sender == MessageSender.USER -> {
                        UserChatBubble(
                            message = message,
                            bgColor = bgColor,
                            fgColor = fgColor
                        )
                    }
                    message.isThinkingBubble -> {
                        AgentThinkingDropdownBubble(
                            message = message,
                            activeSnippet = if (message.isThinkingActive) state.currentStreamSnippet else "",
                            bgColor = bgColor,
                            fgColor = fgColor
                        )
                    }
                    else -> {
                        AgentConceptReplyBubble(
                            message = message,
                            bgColor = bgColor,
                            fgColor = fgColor
                        )
                    }
                }
            }
        }
    }
}

/**
 * Requirement 4: Panel showing all screenshots and previous iterations of this theme.
 * Allows user to see and select any visual to revert to that previous version.
 */
@Composable
private fun VisualHistoryPanel(
    iterations: List<ThemeVisualIteration>,
    activeConcept: String?,
    bgColor: Color,
    fgColor: Color,
    onClose: () -> Unit,
    onRevert: (ThemeVisualIteration) -> Unit,
    modifier: Modifier = Modifier
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topPadding = (topInset + 64.dp).coerceAtLeast(110.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor.copy(alpha = 0.96f))
            .padding(top = topPadding, bottom = 120.dp, start = 16.dp, end = 16.dp)
            .testTag("visual_history_panel")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .shadow(12.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(bgColor.copy(alpha = 0.92f))
                .border(1.5.dp, fgColor.copy(alpha = 0.32f), RoundedCornerShape(24.dp))
                .padding(18.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(fgColor.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = fgColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Visual History",
                            color = fgColor,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Select any iteration to revert",
                            color = fgColor.copy(alpha = 0.72f),
                            fontSize = 11.5.sp
                        )
                    }
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp).testTag("close_visual_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = fgColor.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (iterations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No previous iterations recorded for this theme yet.",
                        color = fgColor.copy(alpha = 0.65f),
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("visual_iterations_list"),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(iterations.reversed(), key = { it.iterationNumber }) { iteration ->
                        val isCurrent = (!iteration.concept.isNullOrBlank() && iteration.concept == activeConcept) ||
                                (activeConcept.isNullOrBlank() && iteration.iterationNumber == iterations.maxOf { it.iterationNumber })

                        VisualIterationCard(
                            iteration = iteration,
                            isCurrent = isCurrent,
                            bgColor = bgColor,
                            fgColor = fgColor,
                            onRevert = { onRevert(iteration) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VisualIterationCard(
    iteration: ThemeVisualIteration,
    isCurrent: Boolean,
    bgColor: Color,
    fgColor: Color,
    onRevert: () -> Unit
) {
    val dateStr = remember(iteration.timestamp) {
        try {
            val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            sdf.format(Date(iteration.timestamp))
        } catch (_: Exception) {
            "Previous version"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(fgColor.copy(alpha = if (isCurrent) 0.18f else 0.09f))
            .border(
                width = if (isCurrent) 1.5.dp else 1.dp,
                color = if (isCurrent) fgColor.copy(alpha = 0.60f) else fgColor.copy(alpha = 0.22f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(12.dp)
            .testTag("visual_iteration_card_${iteration.iterationNumber}")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(fgColor.copy(alpha = 0.22f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "v${iteration.iterationNumber}",
                            color = fgColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = dateStr,
                        color = fgColor.copy(alpha = 0.60f),
                        fontSize = 11.sp
                    )
                }

                if (isCurrent) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF22C55E).copy(alpha = 0.22f))
                            .border(1.dp, Color(0xFF22C55E).copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF22C55E),
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "ACTIVE",
                            color = Color(0xFF22C55E),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = iteration.title,
                color = fgColor,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Screenshot Thumbnail
            val hasValidFile = iteration.screenshotPath != null && File(iteration.screenshotPath).exists()
            if (hasValidFile) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, fgColor.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                ) {
                    AsyncImage(
                        model = File(iteration.screenshotPath!!),
                        contentDescription = "Iteration ${iteration.iterationNumber} screenshot",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(fgColor.copy(alpha = 0.12f))
                        .border(1.dp, fgColor.copy(alpha = 0.20f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(try { Color(android.graphics.Color.parseColor(iteration.bg ?: "#10141D")) } catch (_: Exception) { Color.DarkGray })
                        )
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(try { Color(android.graphics.Color.parseColor(iteration.fg ?: "#E6EDF3")) } catch (_: Exception) { Color.LightGray })
                        )
                        Text(
                            text = "Palette: ${iteration.bg ?: "#10141D"} / ${iteration.fg ?: "#E6EDF3"}",
                            color = fgColor.copy(alpha = 0.70f),
                            fontSize = 11.5.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Revert Button
            if (!isCurrent) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(fgColor.copy(alpha = 0.20f))
                        .border(1.dp, fgColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true),
                            onClick = onRevert
                        )
                        .padding(vertical = 9.dp)
                        .testTag("revert_iteration_button_${iteration.iterationNumber}"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = null,
                            tint = fgColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Revert to this Version",
                            color = fgColor,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserChatBubble(
    message: ThemeChatMessage,
    bgColor: Color,
    fgColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Box(
                modifier = Modifier
                    .shadow(4.dp, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(fgColor.copy(alpha = 0.16f))
                    .border(1.dp, fgColor.copy(alpha = 0.38f), RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("user_chat_bubble")
            ) {
                Text(
                    text = message.text,
                    color = fgColor,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 18.sp
                )
            }

            if (!message.modelLabel.isNullOrBlank()) {
                Text(
                    text = "via ${message.modelLabel}",
                    color = fgColor.copy(alpha = 0.65f),
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 4.dp, end = 4.dp)
                )
            }
        }
    }
}

/**
 * Agent reply showing: "Building <concept field from concept pass>"
 * with character-by-character typewriter effect in `fg` color.
 */
@Composable
private fun AgentConceptReplyBubble(
    message: ThemeChatMessage,
    bgColor: Color,
    fgColor: Color
) {
    val conceptDisplay = message.concept ?: message.text.removePrefix("Building ").trim()
    val fullText = "Building $conceptDisplay"

    val hasAlreadyPlayed = remember(message.id) {
        playedTypewriterMessageIds.contains(message.id)
    }

    var typedLength by remember(message.id, fullText) {
        mutableStateOf(if (hasAlreadyPlayed) fullText.length else 0)
    }

    LaunchedEffect(message.id, fullText) {
        if (!hasAlreadyPlayed) {
            typedLength = 0
            while (typedLength < fullText.length) {
                typedLength = (typedLength + 2).coerceAtMost(fullText.length)
                delay(16L)
            }
            playedTypewriterMessageIds.add(message.id)
        } else {
            typedLength = fullText.length
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp, end = 8.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(fgColor.copy(alpha = 0.20f))
                .border(1.dp, fgColor.copy(alpha = 0.40f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "AI Agent",
                tint = fgColor,
                modifier = Modifier.size(16.dp)
            )
        }

        Box(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .shadow(4.dp, RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .background(bgColor.copy(alpha = 0.88f))
                .border(1.dp, fgColor.copy(alpha = 0.32f), RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("agent_concept_reply_bubble")
        ) {
            Text(
                text = fullText.take(typedLength),
                color = fgColor,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp
            )
        }
    }
}

/**
 * Live Streaming Thinking Drop-Down Bubble:
 * - Types live streaming thinking statements in `fg` color.
 * - User can minimize/expand at any time.
 * - Scrollable to inspect previous steps.
 * - Automatically minimizes when thinking completes.
 */
@Composable
private fun AgentThinkingDropdownBubble(
    message: ThemeChatMessage,
    activeSnippet: String,
    bgColor: Color,
    fgColor: Color
) {
    var isExpanded by remember(message.id) { mutableStateOf(message.isThinkingActive) }

    // Automatically minimize when thinking finishes
    LaunchedEffect(message.isThinkingFinished) {
        if (message.isThinkingFinished) {
            delay(350L)
            isExpanded = false
        }
    }

    // Automatically expand when thinking becomes active
    LaunchedEffect(message.isThinkingActive) {
        if (message.isThinkingActive) {
            isExpanded = true
        }
    }

    // Smooth typewriter reveal for active streaming snippet
    var typedSnippet by remember { mutableStateOf("") }
    LaunchedEffect(activeSnippet) {
        if (activeSnippet.isEmpty()) {
            typedSnippet = ""
        } else if (activeSnippet.startsWith(typedSnippet)) {
            while (typedSnippet.length < activeSnippet.length) {
                val nextLen = (typedSnippet.length + 3).coerceAtMost(activeSnippet.length)
                typedSnippet = activeSnippet.substring(0, nextLen)
                delay(14L)
            }
        } else {
            typedSnippet = ""
            while (typedSnippet.length < activeSnippet.length) {
                val nextLen = (typedSnippet.length + 4).coerceAtMost(activeSnippet.length)
                typedSnippet = activeSnippet.substring(0, nextLen)
                delay(12L)
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse_and_spin")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val scrollState = rememberScrollState()
    LaunchedEffect(message.thinkingSteps.size, typedSnippet, isExpanded) {
        if (isExpanded) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Spacer(modifier = Modifier.width(38.dp))

        Box(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .shadow(6.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(bgColor.copy(alpha = 0.94f))
                .border(
                    width = 1.dp,
                    color = if (message.isThinkingActive) fgColor.copy(alpha = 0.55f) else fgColor.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(18.dp)
                )
                .padding(12.dp)
                .testTag("thinking_dropdown_bubble")
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header Row: Clickable to minimize/expand live statements
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { isExpanded = !isExpanded }
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            message.isThinkingActive -> {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "Thinking active",
                                    tint = fgColor,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .rotate(rotationAngle)
                                )
                            }
                            message.errorMessage != null -> {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = "Thinking error",
                                    tint = Color(0xFFFF6B6B),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Thinking complete",
                                    tint = fgColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    val titleText = when {
                        message.isThinkingActive -> message.thinkingPhase.ifBlank { "Reasoning & Compiling CSS..." }
                        message.errorMessage != null -> "Reasoning interrupted"
                        else -> "Thought process (${message.thinkingSteps.size} steps)"
                    }

                    Text(
                        text = titleText,
                        color = fgColor,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (message.durationSeconds > 0) {
                        Text(
                            text = "${message.durationSeconds}s",
                            color = fgColor.copy(alpha = 0.72f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Minimize thinking statements" else "Expand thinking statements",
                        tint = fgColor.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Expandable & Scrollable Body with `fg` colored typed statements
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn(tween(180)) + slideInVertically(tween(180)),
                    exit = fadeOut(tween(160)) + slideOutVertically(tween(160))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(fgColor.copy(alpha = 0.16f))
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            message.thinkingSteps.forEachIndexed { idx, step ->
                                val isLatest = idx == message.thinkingSteps.lastIndex && message.isThinkingActive && typedSnippet.isBlank()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 6.dp, end = 7.dp)
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(if (isLatest) fgColor else fgColor.copy(alpha = 0.5f))
                                    )
                                    Text(
                                        text = step,
                                        color = if (isLatest) fgColor else fgColor.copy(alpha = 0.86f),
                                        fontSize = 12.sp,
                                        fontWeight = if (isLatest) FontWeight.Medium else FontWeight.Normal,
                                        lineHeight = 16.sp
                                    )
                                }
                            }

                            if (message.isThinkingActive && typedSnippet.isNotBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 6.dp, end = 7.dp)
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(fgColor.copy(alpha = pulseAlpha))
                                    )
                                    Text(
                                        text = "$typedSnippet ▋",
                                        color = fgColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        lineHeight = 16.sp
                                    )
                                }
                            }

                            if (message.isThinkingActive && message.thinkingSteps.isEmpty() && typedSnippet.isBlank()) {
                                Text(
                                    text = "Synthesizing CSS rules ▋",
                                    color = fgColor.copy(alpha = 0.85f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
