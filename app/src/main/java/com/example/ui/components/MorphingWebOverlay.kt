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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import com.example.ui.model.MessageSender
import com.example.ui.model.ThemeChatMessage
import com.example.ui.viewmodel.AiThinkingState
import kotlinx.coroutines.delay

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
                        websiteName = websiteName,
                        bgColor = parsedBg,
                        fgColor = parsedFg,
                        onDismiss = onDismiss,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeChatPanel(
    state: AiThinkingState,
    websiteName: String,
    bgColor: Color,
    fgColor: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.chatHistory.size, state.currentStreamSnippet) {
        if (state.chatHistory.isNotEmpty()) {
            listState.animateScrollToItem(state.chatHistory.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Header positioned cleanly so standalone top-left Settings and top-right Brush stay unobstructed
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 64.dp, start = 72.dp, end = 72.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(fgColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Design Studio",
                        tint = fgColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Column {
                    Text(
                        text = if (websiteName.isNotBlank()) websiteName else "Design Studio",
                        color = fgColor,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (state.isActive) "Morphing website..." else "Theme Chat History (Tap X to view page)",
                        color = fgColor.copy(alpha = 0.72f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(bgColor.copy(alpha = 0.85f))
                    .border(1.dp, fgColor.copy(alpha = 0.35f), CircleShape)
                    .testTag("dismiss_morph_overlay_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Minimize chat overlay",
                    tint = fgColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(fgColor.copy(alpha = 0.15f))
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("theme_chat_messages_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 190.dp),
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

    var typedLength by remember(fullText) { mutableStateOf(0) }
    LaunchedEffect(fullText) {
        typedLength = 0
        while (typedLength < fullText.length) {
            typedLength = (typedLength + 2).coerceAtMost(fullText.length)
            delay(16L)
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
