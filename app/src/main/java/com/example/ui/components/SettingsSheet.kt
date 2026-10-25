package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Javascript
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsSheet(
    isJavaScriptEnabled: Boolean,
    onToggleJavaScript: (Boolean) -> Unit,
    isTrackerBlockerEnabled: Boolean,
    onToggleTrackerBlocker: (Boolean) -> Unit,
    isFullScreenMode: Boolean,
    onToggleFullScreenMode: (Boolean) -> Unit,
    onClearBrowsingData: () -> Unit,
    onOpenApiLogs: () -> Unit,
    onOpenImportRedesign: () -> Unit = {},
    customApiKey: String,
    onUpdateCustomApiKey: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var clearDataSuccess by remember { mutableStateOf(false) }
    var apiKeyInput by remember(customApiKey) { mutableStateOf(customApiKey) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xE6141619))
            .clickable(onClick = onDismiss)
            .testTag("settings_sheet")
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 50.dp, start = 16.dp, end = 16.dp, bottom = 40.dp)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2024))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Settings",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close settings",
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Section: Search Engine
                SettingsItemRow(
                    icon = Icons.Default.Search,
                    title = "Search Engine",
                    subtitle = "Google Search (Default)"
                )

                HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(vertical = 12.dp))

                // Section: Fullscreen Mode
                SettingsSwitchRow(
                    icon = Icons.Default.Fullscreen,
                    title = "Fullscreen Mode",
                    subtitle = "Immersive fullscreen (hides system bars, on by default)",
                    checked = isFullScreenMode,
                    onCheckedChange = onToggleFullScreenMode
                )

                HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(vertical = 12.dp))

                // Section: Gemini API Key (Testing Phase)
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Gemini API Key (Testing Phase)",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Enter your custom API key for AI website styling",
                                color = Color(0xFF8E9297),
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            placeholder = { Text("AIzaSy...", color = Color(0xFF666666)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF4285F4),
                                unfocusedBorderColor = Color(0xFF3A3D44),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF4285F4),
                                focusedContainerColor = Color(0xFF282B30),
                                unfocusedContainerColor = Color(0xFF282B30)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("api_key_input")
                        )

                        Button(
                            onClick = { onUpdateCustomApiKey(apiKeyInput) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4285F4),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("save_api_key_button")
                        ) {
                            Text("Save", fontSize = 12.sp)
                        }
                    }
                }

                HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(vertical = 12.dp))

                // Section: Privacy & Security
                SettingsSwitchRow(
                    icon = Icons.Default.Security,
                    title = "Smart Tracker Blocker",
                    subtitle = "Blocks known advertising and tracking scripts",
                    checked = isTrackerBlockerEnabled,
                    onCheckedChange = onToggleTrackerBlocker
                )

                HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(vertical = 12.dp))

                // Section: Web Engine
                SettingsSwitchRow(
                    icon = Icons.Default.Javascript,
                    title = "JavaScript",
                    subtitle = "Required for dynamic modern websites",
                    checked = isJavaScriptEnabled,
                    onCheckedChange = onToggleJavaScript
                )

                HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(vertical = 12.dp))

                // Section: Clear Data
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Clear Browsing Data",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (clearDataSuccess) "Cache and cookies cleared!" else "Removes local cache, cookies, and temporary data",
                            color = if (clearDataSuccess) Color(0xFF34A853) else Color(0xFF8E9297),
                            fontSize = 12.sp
                        )
                    }

                    Button(
                        onClick = {
                            onClearBrowsingData()
                            clearDataSuccess = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3A3D44),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear", fontSize = 12.sp)
                    }
                }

                HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(vertical = 12.dp))

                // Section: API Request Logs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenApiLogs)
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "API Request & Result Logs",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "View logs of sent requests and received results",
                                color = Color(0xFF8E9297),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(vertical = 12.dp))

                // Section: Import Frontier Redesign
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = {
                            onOpenImportRedesign()
                            onDismiss()
                        })
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = null,
                            tint = Color(0xFF81C784),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Import & Apply Redesign (.json)",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Load external UIBlueprint from Claude, GPT-4.5, or Gemini Web",
                                color = Color(0xFF8E9297),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(vertical = 12.dp))

                // Section: Gestures Guide
                SettingsItemRow(
                    icon = Icons.Default.Info,
                    title = "Controls & Gestures",
                    subtitle = "• Tap + button: Open new tab\n• Long-press + & pull down: Open incognito tab\n• Pull top notch down: View tab grid\n• Tap brush: Restyle website with Gemini AI"
                )

                Spacer(modifier = Modifier.height(20.dp))

                // About
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF282B30))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Mydes Browser v1.0",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Lightweight, distraction-free web browser",
                            color = Color(0xFF8E9297),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsItemRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFFCCCCCC),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, color = Color(0xFF8E9297), fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFFCCCCCC),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(text = subtitle, color = Color(0xFF8E9297), fontSize = 12.sp)
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF4285F4),
                uncheckedThumbColor = Color(0xFFAAAAAA),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }
}
