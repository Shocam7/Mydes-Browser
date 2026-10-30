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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.viewmodel.AiThinkingState
import kotlinx.coroutines.delay

/**
 * Morphing Web Overlay:
 *
 * When the concept phase completes, extracts 'bg' and 'fg' colors.
 * A translucent overlay with 'bg' color rises from the bottom of the screen,
 * situated behind all browser buttons and controls, making the website appear
 * to morph into the new thematic world.
 *
 * When the overlay completely covers the website, live streaming thinking statements
 * type out in 'fg' color inside an expandable/collapsible dropdown.
 * The statements are scrollable to view earlier steps.
 * When thinking is complete, the dropdown automatically collapses, remaining
 * expandable on tap so the user can inspect all steps.
 */
@Composable
fun MorphingWebOverlay(
    state: AiThinkingState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rawBg = state.morphBgColor
    if (rawBg == null && !state.isActive) return

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

    // Overlay height rises from bottom: 0f -> 1f
    val heightFraction = remember { Animatable(0f) }
    var isCovered by remember { mutableStateOf(false) }

    LaunchedEffect(rawBg) {
        if (rawBg != null) {
            heightFraction.snapTo(0f)
            isCovered = false
            heightFraction.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing)
            )
            isCovered = true
        } else {
            heightFraction.snapTo(0f)
            isCovered = false
        }
    }

    // Outer full-screen container (aligned behind buttons, over WebView)
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        if (heightFraction.value > 0.01f) {
            val fraction = heightFraction.value

            // Rising translucent background
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction)
                    .background(parsedBg.copy(alpha = 0.88f))
                    .testTag("morphing_web_overlay")
            ) {
                // Top luminous gradient wave while rising
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

                // Once completely covered, start typing out thinking statements
                if (isCovered) {
                    MorphingThinkingDropdown(
                        state = state,
                        bgColor = parsedBg,
                        fgColor = parsedFg,
                        onDismiss = onDismiss,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 20.dp, vertical = 72.dp)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * Dropdown housing live streaming thinking statements with typing effect,
 * expand/collapse toggle, scrollable step history, and auto-minimize on completion.
 */
@Composable
private fun MorphingThinkingDropdown(
    state: AiThinkingState,
    bgColor: Color,
    fgColor: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }

    // Auto-minimize when thinking completes
    LaunchedEffect(state.isFinished) {
        if (state.isFinished) {
            delay(600L)
            isExpanded = false
        }
    }

    // Re-expand when a new generation starts
    LaunchedEffect(state.isActive) {
        if (state.isActive) {
            isExpanded = true
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse_and_type")
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
    // Auto-scroll to bottom as new stream chunks or summarized statements arrive
    LaunchedEffect(state.summarizedStatements.size, state.currentStreamSnippet) {
        if (isExpanded) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    // Dropdown container styled in extracted theme colors
    Box(
        modifier = modifier
            .shadow(12.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(bgColor.copy(alpha = 0.94f))
            .border(
                width = 1.dp,
                color = if (state.isActive) fgColor.copy(alpha = 0.5f) else fgColor.copy(alpha = 0.25f),
                shape = RoundedCornerShape(22.dp)
            )
            .padding(14.dp)
            .testTag("morphing_thinking_dropdown")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Dropdown Header (Click to toggle expand/collapse)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { isExpanded = !isExpanded }
                    )
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Animated Status Icon
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        state.isActive -> {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Morphing thoughts",
                                tint = fgColor,
                                modifier = Modifier
                                    .size(18.dp)
                                    .rotate(rotationAngle)
                            )
                        }
                        state.errorMessage != null -> {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "Synthesis error",
                                tint = Color(0xFFFF6B6B),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Synthesis complete",
                                tint = fgColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Phase Title
                val headerTitle = when {
                    state.isActive -> state.phase.ifBlank { "Morphing Website World..." }
                    state.errorMessage != null -> "Synthesis interrupted"
                    else -> "Website Morphed (${state.summarizedStatements.size} reasoning steps)"
                }

                Text(
                    text = headerTitle,
                    color = fgColor,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Elapsed time badge
                if (state.durationSeconds > 0) {
                    Text(
                        text = "${state.durationSeconds}s",
                        color = fgColor.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }

                // Expand/Collapse Chevron
                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("toggle_thinking_dropdown_button")
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Minimize thinking dropdown" else "Expand thinking dropdown",
                        tint = fgColor.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Close overlay button (allows user to dismiss overlay when desired)
                if (state.isFinished || state.errorMessage != null) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("dismiss_morph_overlay_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close morph overlay",
                            tint = fgColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Expandable dropdown body
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(tween(200)) + slideInVertically(tween(200)),
                exit = fadeOut(tween(180)) + slideOutVertically(tween(180))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    // Divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(fgColor.copy(alpha = 0.18f))
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Scrollable list of thinking statements
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Completed statements
                        state.summarizedStatements.forEachIndexed { idx, statement ->
                            val isLatest = idx == state.summarizedStatements.lastIndex &&
                                state.isActive && state.currentStreamSnippet.isBlank()

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 5.dp, end = 8.dp)
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isLatest) fgColor else fgColor.copy(alpha = 0.45f))
                                )
                                Text(
                                    text = statement,
                                    color = if (isLatest) fgColor else fgColor.copy(alpha = 0.85f),
                                    fontSize = 12.5.sp,
                                    fontWeight = if (isLatest) FontWeight.SemiBold else FontWeight.Normal,
                                    lineHeight = 17.sp,
                                    fontFamily = FontFamily.Default
                                )
                            }
                        }

                        // Live typing snippet for streaming tokens
                        if (state.isActive && state.currentStreamSnippet.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 5.dp, end = 8.dp)
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(fgColor.copy(alpha = pulseAlpha))
                                )
                                Text(
                                    text = buildString {
                                        append(state.currentStreamSnippet)
                                        append(" ▋") // animated typing cursor
                                    },
                                    color = fgColor,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    lineHeight = 17.sp,
                                    fontFamily = FontFamily.Default
                                )
                            }
                        }

                        // Initial loading indicator before first statement arrives
                        if (state.isActive && state.summarizedStatements.isEmpty() && state.currentStreamSnippet.isBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(fgColor.copy(alpha = pulseAlpha))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Analyzing webpage architecture & synthesizing palette ▋",
                                    color = fgColor.copy(alpha = 0.85f),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
