package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Plus button placed below the brush icon to add a new tab.
 * - Tap: opens a standard new tab.
 * - Long press: pulls out a grey background incognito button downwards.
 * - Swiping downwards in that direction opens an incognito tab.
 */
@Composable
fun NewTabPlusButton(
    onNewTab: () -> Unit,
    onNewIncognitoTab: () -> Unit,
    modifier: Modifier = Modifier,
    buttonColor: Color = Color(0xFFE4E6EA),
    iconColor: Color = Color(0xFF33373D),
    incognitoButtonColor: Color = Color(0xFFD5D8DC)
) {
    var isIncognitoPulloutVisible by remember { mutableStateOf(false) }
    var totalDragY by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main Plus button
        Box(
            modifier = Modifier
                .size(44.dp)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(buttonColor)
                .testTag("add_tab_button")
                .semantics { contentDescription = "Add new tab. Long press for incognito tab." }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (isIncognitoPulloutVisible) {
                                isIncognitoPulloutVisible = false
                            } else {
                                onNewTab()
                            }
                        },
                        onLongPress = {
                            isIncognitoPulloutVisible = true
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            isIncognitoPulloutVisible = true
                            totalDragY = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            totalDragY += dragAmount.y
                            // Swiping downwards past threshold triggers incognito tab
                            if (totalDragY > 35f) {
                                isIncognitoPulloutVisible = false
                                totalDragY = 0f
                                onNewIncognitoTab()
                            }
                        },
                        onDragEnd = {
                            if (totalDragY > 30f) {
                                onNewIncognitoTab()
                            }
                            totalDragY = 0f
                        },
                        onDragCancel = {
                            totalDragY = 0f
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
        }

        // Incognito pullout button expanding downwards below the plus button
        AnimatedVisibility(
            visible = isIncognitoPulloutVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 })
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(44.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(incognitoButtonColor)
                    .clickable {
                        isIncognitoPulloutVisible = false
                        onNewIncognitoTab()
                    }
                    .testTag("incognito_pullout_button")
                    .semantics { contentDescription = "Open new incognito tab" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = null,
                    tint = Color(0xFF22252A),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
