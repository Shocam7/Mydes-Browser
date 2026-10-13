package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Top Plateau Notch:
 * Subtle plateau centered at top of screen with 10% opacity.
 * Pull down to open the tabs grid.
 */
@Composable
fun TopPlateauNotch(
    onPullDown: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 0.10f,
    plateauColor: Color = Color.Black
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .clickable(onClick = onPullDown)
            .testTag("top_plateau_notch")
            .semantics { contentDescription = "Top notch, pull down or tap to view tabs" }
            .pointerInput(Unit) {
                var totalDragY = 0f
                detectDragGestures(
                    onDragStart = { totalDragY = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDragY += dragAmount.y
                    },
                    onDragEnd = {
                        if (totalDragY > 20f) {
                            onPullDown()
                        }
                    }
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Canvas(modifier = Modifier.size(width = 68.dp, height = 8.dp)) {
            val w = size.width
            val h = size.height
            val inset = 14.dp.toPx()

            // Inverted plateau hanging from top:
            // Top edge spans (0, 0) to (w, 0).
            // Bottom flat edge spans (inset, h) to (w - inset, h).
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(inset, h)
                lineTo(w - inset, h)
                lineTo(w, 0f)
                close()
            }

            drawPath(
                path = path,
                color = plateauColor.copy(alpha = opacity),
                style = Fill
            )
        }
    }
}
