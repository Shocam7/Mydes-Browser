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
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.model.MessageSender
import com.example.ui.model.ThemeChatMessage
import com.example.ui.viewmodel.AiThinkingState
import kotlinx.coroutines.delay

/**
 * Morphing Web Overlay:
 *
 * Translucent overlay with extracted theme 'bg' rising from the bottom of the screen,
 * positioned behind browser navigation/controls and over the WebView.
 *
 * Acts as an interactive Chat Panel:
 * 1. Shows user requests as messages sent by the user.
 * 2. Agent replies with "Building <concept field from concept pass>".
 * 3. Another agent reply contains the live thinking drop-down (streaming tokens / summarized steps).
 * 4. Persists and displays the specific chat history for the currently applied design.
 * 5. Subsequent messages sent in AiStylerPillBar appear as continuations in this chat.
 */
@Composable
fun MorphingWebOverlay(
    state: AiThinkingState,
    websiteName: String = "",
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rawBg = state.morphBgColor
    if (rawBg == null && !state.isActive && state.chatHistory.isEmpty()) return

    val parsedBg = remember(rawBg) {
        try {
            val c = rawBg ?: "#10141D"
            val parsed = android.graphics.Color.parseColor(if (c.startsWith("#")) c else "#$c")
            Color(parsed)
        } catch (_: Exception) {
            Color(0xFF10141D)
        }
    }

    val parsedFg = remember(state.morphFgColor) {
        try {
            val c = state.morphFgColor ?: "#E6EDF3"
            val parsed = android.graphics.Color.parseColor(if (c.startsWith("#")) c else "#$c")
            Color(parsed)
        } catch (_: Exception) {
            Color(0xFFE6EDF3)
        }
    }

    // Overlay height fraction rises from bottom: 0f -> 1f
    val heightFraction = remember { Animatable(0f) }
    var isCovered by remember { mutableStateOf(false) }

    LaunchedEffect(rawBg, state.isActive) {
        if (rawBg != null || state.isActive) {
            heightFraction.snapTo(0f)
            isCovered = false
            heightFraction.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing)
            )
            isCovered = true
        } else {
            heightFraction.snapTo(0f)
            isCovered = false
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
                // Top luminous gradient edge while rising
                if (fraction < 0.99f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        parsedFg.copy(alpha = 0.65f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }

                // Chat panel view once overlay has risen
                if (isCovered) {
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

/**
 * Scrollable Chat Panel displaying the full conversation history for the active design:
 * User message -> Agent "Building <concept>" -> Agent live thinking dropdown.
 */
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

    // Auto-scroll to the bottom when new messages or streaming chunks arrive
    LaunchedEffect(state.chatHistory.size, state.currentStreamSnippet) {
        if (state.chatHistory.isNotEmpty()) {
            listState.animateScrollToItem(state.chatHistory.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Chat Panel Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(fgColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Design Studio",
                        tint = fgColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column {
                    Text(
                        text = if (websiteName.isNotBlank()) "Design Studio • $websiteName" else "Design Studio Chat",
                        color = fgColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (state.isActive) "Synthesizing design..." else "Active Theme Conversation",
                        color = fgColor.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }

            // Close button: allows dismissing overlay to inspect webpage underneath
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(bgColor.copy(alpha = 0.8f))
                    .border(1.dp, fgColor.copy(alpha = 0.3f), CircleShape)
                    .testTag("dismiss_morph_overlay_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss chat overlay",
                    tint = fgColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Horizontal subtle divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(fgColor.copy(alpha = 0.15f))
        )

        // Chat messages list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("theme_chat_messages_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
 * Bubble 1 (User): Displays user prompt / requested design modification.
 */
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
                    .background(fgColor.copy(alpha = 0.18f))
                    .border(1.dp, fgColor.copy(alpha = 0.35f), RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("user_chat_bubble")
            ) {
                Column {
                    Text(
                        text = message.text,
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 18.sp
                    )
                }
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
 * Bubble 2 (Agent): Displays "Building <concept field from concept pass>".
 */
@Composable
private fun AgentConceptReplyBubble(
    message: ThemeChatMessage,
    bgColor: Color,
    fgColor: Color
) {
    val conceptDisplay = message.concept ?: message.text.removePrefix("Building ").trim()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Agent Sparkle Avatar
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
                .background(bgColor.copy(alpha = 0.85f))
                .border(1.dp, fgColor.copy(alpha = 0.30f), RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("agent_concept_reply_bubble")
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Building ",
                        color = fgColor.copy(alpha = 0.8f),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Normal
                    )
                    Text(
                        text = conceptDisplay,
                        color = fgColor,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Bubble 3 (Agent Dropdown): Live thinking drop-down with expand/collapse,
 * scrollable steps, and streaming tokens typed in real time.
 */
@Composable
private fun AgentThinkingDropdownBubble(
    message: ThemeChatMessage,
    activeSnippet: String,
    bgColor: Color,
    fgColor: Color
) {
    var isExpanded by remember { mutableStateOf(message.isThinkingActive) }

    // Auto-minimize when thinking completes
    LaunchedEffect(message.isThinkingFinished) {
        if (message.isThinkingFinished) {
            delay(500L)
            isExpanded = false
        }
    }

    // Auto-expand when active
    LaunchedEffect(message.isThinkingActive) {
        if (message.isThinkingActive) {
            isExpanded = true
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
    LaunchedEffect(message.thinkingSteps.size, activeSnippet) {
        if (isExpanded) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        // Indented below agent message
        Spacer(modifier = Modifier.width(38.dp))

        Box(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .shadow(6.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(bgColor.copy(alpha = 0.92f))
                .border(
                    width = 1.dp,
                    color = if (message.isThinkingActive) fgColor.copy(alpha = 0.5f) else fgColor.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(18.dp)
                )
                .padding(12.dp)
                .testTag("thinking_dropdown_bubble")
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Dropdown Header (Click to toggle)
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
                    // Status icon
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
                        else -> "Thinking complete (${message.thinkingSteps.size} steps)"
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
                            color = fgColor.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse steps" else "Expand steps",
                        tint = fgColor.copy(alpha = 0.75f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Expanded steps body
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
                                .background(fgColor.copy(alpha = 0.15f))
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
                                val isLatest = idx == message.thinkingSteps.lastIndex && message.isThinkingActive && activeSnippet.isBlank()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 5.dp, end = 6.dp)
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(if (isLatest) fgColor else fgColor.copy(alpha = 0.45f))
                                    )
                                    Text(
                                        text = step,
                                        color = if (isLatest) fgColor else fgColor.copy(alpha = 0.85f),
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }

                            // Active streaming typed tokens
                            if (message.isThinkingActive && activeSnippet.isNotBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 5.dp, end = 6.dp)
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(fgColor.copy(alpha = pulseAlpha))
                                    )
                                    Text(
                                        text = "$activeSnippet ▋",
                                        color = fgColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        lineHeight = 16.sp
                                    )
                                }
                            }

                            if (message.isThinkingActive && message.thinkingSteps.isEmpty() && activeSnippet.isBlank()) {
                                Text(
                                    text = "Analyzing CSS architecture ▋",
                                    color = fgColor.copy(alpha = 0.8f),
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
