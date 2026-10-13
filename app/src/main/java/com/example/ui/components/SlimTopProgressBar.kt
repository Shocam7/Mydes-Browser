package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Top Slim Progress Line:
 * - Fixed at top of screen
 * - Solid line in the website's primary color when known
 * - Animated magical multicolour shimmer when color is unknown
 * - Instantly switches as soon as color is extracted
 */
@Composable
fun SlimTopProgressBar(
    isLoading: Boolean,
    progress: Float,
    primaryColor: Color?,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmerTransition")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    AnimatedVisibility(
        visible = isLoading && progress in 0.01f..0.99f,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(250)),
        modifier = modifier
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
        ) {
            val totalWidth = size.width
            val progressWidth = (totalWidth * progress.coerceIn(0.05f, 1f))

            if (primaryColor != null) {
                // Known primary brand color
                drawRect(
                    color = primaryColor,
                    topLeft = Offset.Zero,
                    size = Size(progressWidth, size.height)
                )
            } else {
                // Unknown color: magical animated multicolour gradient
                val rainbowColors = listOf(
                    Color(0xFFFF0055),
                    Color(0xFFFF7700),
                    Color(0xFFFFD500),
                    Color(0xFF00E676),
                    Color(0xFF00B0FF),
                    Color(0xFF651FFF),
                    Color(0xFFFF0055)
                )
                val brush = Brush.linearGradient(
                    colors = rainbowColors,
                    start = Offset(shimmerOffset % totalWidth - totalWidth, 0f),
                    end = Offset(shimmerOffset % totalWidth + totalWidth, 0f)
                )
                drawRect(
                    brush = brush,
                    topLeft = Offset.Zero,
                    size = Size(progressWidth, size.height)
                )
            }
        }
    }
}
