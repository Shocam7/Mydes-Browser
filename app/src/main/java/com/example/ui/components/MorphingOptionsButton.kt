package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Geometric Options Button with 3 vertically aligned shapes:
 * 1. Circle (top)
 * 2. Triangle (middle)
 * 3. Square (bottom)
 *
 * When active (expanded):
 * - Circle morphs into a horizontal line with semi-circular ends.
 * - Triangle morphs into a horizontal line with slanting ends.
 * - Square morphs into a horizontal line with flat ends.
 */
@Composable
fun MorphingOptionsButton(
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color(0xFFE4E6EA),
    iconColor: Color = Color(0xFF22252A)
) {
    val progress by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "morphProgress"
    )

    Box(
        modifier = modifier
            .size(52.dp)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .testTag("options_button")
            .semantics {
                contentDescription = if (isActive) "Close options menu" else "Open options menu"
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(32.dp)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f

            // Shape dimensions
            val baseRadius = 3.6.dp.toPx()
            val expandedHalfWidth = 11.dp.toPx()
            val currentHalfWidth = baseRadius + (expandedHalfWidth - baseRadius) * progress

            val lineHalfThickness = 1.8.dp.toPx()
            val circleHalfHeight = baseRadius - (baseRadius - lineHalfThickness) * progress
            val triHalfHeight = baseRadius - (baseRadius - lineHalfThickness) * progress
            val sqHalfHeight = baseRadius - (baseRadius - lineHalfThickness) * progress

            // Y centers for the 3 elements
            val y1 = h * 0.24f
            val y2 = h * 0.50f
            val y3 = h * 0.76f

            // 1. TOP SHAPE: Circle -> Line with semi-circular (rounded) ends
            val cornerRadius = circleHalfHeight
            val roundRectPath = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = cx - currentHalfWidth,
                        top = y1 - circleHalfHeight,
                        right = cx + currentHalfWidth,
                        bottom = y1 + circleHalfHeight,
                        cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                    )
                )
            }
            drawPath(path = roundRectPath, color = iconColor, style = Fill)

            // 2. MIDDLE SHAPE: Triangle -> Line with slanting (beveled) ends
            val triPath = Path()
            if (progress < 0.05f) {
                // Pure equilateral triangle pointing up
                val topY = y2 - baseRadius * 1.15f
                val botY = y2 + baseRadius * 0.95f
                triPath.moveTo(cx, topY)
                triPath.lineTo(cx - baseRadius * 1.15f, botY)
                triPath.lineTo(cx + baseRadius * 1.15f, botY)
                triPath.close()
            } else {
                // Interpolating from triangle to line with slanting ends (trapezoid/parallelogram)
                val slantOffset = (1f - progress) * (currentHalfWidth * 0.35f) + progress * (lineHalfThickness * 1.2f)
                val topY = y2 - triHalfHeight
                val botY = y2 + triHalfHeight

                triPath.moveTo(cx - currentHalfWidth + slantOffset, topY)
                triPath.lineTo(cx + currentHalfWidth, topY)
                triPath.lineTo(cx + currentHalfWidth - slantOffset, botY)
                triPath.lineTo(cx - currentHalfWidth, botY)
                triPath.close()
            }
            drawPath(path = triPath, color = iconColor, style = Fill)

            // 3. BOTTOM SHAPE: Square -> Line with flat (perpendicular) ends
            val sqLeft = cx - currentHalfWidth
            val sqRight = cx + currentHalfWidth
            val sqTop = y3 - sqHalfHeight
            val sqBot = y3 + sqHalfHeight

            drawRect(
                color = iconColor,
                topLeft = Offset(sqLeft, sqTop),
                size = Size(sqRight - sqLeft, sqBot - sqTop),
                style = Fill
            )
        }
    }
}
