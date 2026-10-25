package com.example.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.api.UIBlueprint

private fun getFileNameFromUri(context: Context, uri: Uri): String {
    var name = "external_redesign.json"
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                val displayName = cursor.getString(nameIndex)
                if (!displayName.isNullOrBlank()) {
                    name = displayName
                }
            }
        }
    } catch (_: Exception) {}
    return name
}

private fun parseHexColor(hex: String, fallback: Color = Color.DarkGray): Color {
    return try {
        val clean = hex.removePrefix("#")
        val colorInt = clean.toLong(16).toInt()
        if (clean.length == 6) {
            Color(0xFF000000 or colorInt.toLong())
        } else {
            Color(colorInt)
        }
    } catch (_: Exception) {
        fallback
    }
}

/**
 * Modal dialog for importing an externally generated UIBlueprint redesign
 * (e.g. from Claude 3.7, GPT-4.5 / o3, Gemini Pro/Ultra frontier models)
 * from an external file (.json / .txt / .js) or directly from clipboard.
 */
@Composable
fun ImportRedesignDialog(
    onDismiss: () -> Unit,
    onApplyRedesign: (jsonContent: String, sourceFileName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var loadedContent by remember { mutableStateOf("") }
    var sourceLabel by remember { mutableStateOf("") }
    var parsedBlueprint by remember { mutableStateOf<UIBlueprint?>(null) }
    var parseErrorMessage by remember { mutableStateOf<String?>(null) }

    // Re-parse whenever content changes
    LaunchedEffect(loadedContent) {
        if (loadedContent.isBlank()) {
            parsedBlueprint = null
            parseErrorMessage = null
        } else {
            val bp = UIBlueprint.parse(loadedContent, "Frontier Redesign")
            if (bp != null) {
                parsedBlueprint = bp
                parseErrorMessage = null
            } else {
                parsedBlueprint = null
                parseErrorMessage = "Could not parse valid UIBlueprint JSON. Ensure your file contains 'name', 'bg', 'customCss', 'backdropHtml', and 'customJs'."
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val fileName = getFileNameFromUri(context, uri)
                val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                } ?: ""
                if (text.isNotBlank()) {
                    sourceLabel = fileName
                    loadedContent = text
                    Toast.makeText(context, "Loaded $fileName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Selected file is empty", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xE6141619))
            .clickable(onClick = onDismiss)
            .testTag("import_redesign_dialog"),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .padding(vertical = 24.dp)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2024))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF3F51B5)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileUpload,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Import Frontier Redesign",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Apply externally generated UIBlueprint JSON",
                                color = Color(0xFF9E9E9E),
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Tabs: Pick File vs Paste JSON
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = Color(0xFF141619),
                    contentColor = Color.White,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                            color = Color(0xFF4285F4)
                        )
                    }
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Choose File", fontSize = 13.sp)
                            }
                        }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Paste JSON", fontSize = 13.sp)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Tab Content Area
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (selectedTabIndex == 0) {
                        // File picker view
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF282B30))
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Select a .json, .txt, or .js redesign file generated by Claude, ChatGPT, or Gemini Web.",
                                color = Color(0xFFCFD8DC),
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    filePickerLauncher.launch(
                                        arrayOf("application/json", "text/*", "*/*")
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("choose_redesign_file_button")
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Browse Files on Device")
                            }

                            if (sourceLabel.isNotBlank()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Surface(
                                    color = Color(0xFF1E2024),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Loaded: $sourceLabel (${loadedContent.length} characters)",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Paste text view
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Paste UIBlueprint JSON:",
                                color = Color(0xFFB0BEC5),
                                fontSize = 12.sp
                            )
                            Button(
                                onClick = {
                                    val clipText = clipboardManager.getText()?.text ?: ""
                                    if (clipText.isNotBlank()) {
                                        loadedContent = clipText
                                        sourceLabel = "Pasted from Clipboard"
                                        Toast.makeText(context, "Pasted ${clipText.length} characters", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Paste from Clipboard", fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = loadedContent,
                            onValueChange = {
                                loadedContent = it
                                sourceLabel = "Direct Input"
                            },
                            placeholder = {
                                Text(
                                    text = "{\n  \"name\": \"Cyberpunk Neon\",\n  \"dark\": true,\n  \"bg\": \"#080b12\",\n  \"surface\": \"#121722\",\n  \"accent\": \"#00e5ff\",\n  \"customCss\": \"...\",\n  \"backdropHtml\": \"...\",\n  \"customJs\": \"...\"\n}",
                                    color = Color(0xFF546E7A),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .testTag("paste_json_text_field"),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF4285F4),
                                unfocusedBorderColor = Color(0xFF37474F),
                                focusedContainerColor = Color(0xFF141619),
                                unfocusedContainerColor = Color(0xFF141619)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Live Blueprint Validation & Preview Card
                    if (parsedBlueprint != null) {
                        val bp = parsedBlueprint!!
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A261D)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = Color(0xFF4CAF50),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Valid UIBlueprint Detected",
                                            color = Color(0xFF81C784),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }
                                    Text(
                                        text = "ID: ${bp.id}",
                                        color = Color(0xFF81C784).copy(alpha = 0.7f),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Name: ${bp.name}",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (bp.concept.isNotBlank()) {
                                    Text(
                                        text = "Concept: ${bp.concept}",
                                        color = Color(0xFFCFD8DC),
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = Color(0x334CAF50))
                                Spacer(modifier = Modifier.height(8.dp))

                                // Color Palette Preview
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(text = "Palette:", color = Color.Gray, fontSize = 11.sp)
                                    ColorSwatch(label = "BG", hex = bp.bg)
                                    ColorSwatch(label = "Surface", hex = bp.surface)
                                    ColorSwatch(label = "Accent", hex = bp.accent)
                                    ColorSwatch(label = "Text", hex = bp.fg)
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = "CSS: ${bp.customCss.length} chars",
                                        color = Color(0xFFB0BEC5),
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = "Backdrop: ${if (bp.backdropHtml.isNotBlank()) "Yes (${bp.backdropHtml.length} chars)" else "None"}",
                                        color = Color(0xFFB0BEC5),
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = "Custom JS: ${if (bp.customJs.isNotBlank()) "Yes" else "None"}",
                                        color = Color(0xFFB0BEC5),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    } else if (parseErrorMessage != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2C1919)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = null,
                                    tint = Color(0xFFE57373),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = parseErrorMessage!!,
                                    color = Color(0xFFFFCDD2),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2F36))
                    ) {
                        Text("Cancel", color = Color(0xFFCFD8DC))
                    }

                    Button(
                        onClick = {
                            if (parsedBlueprint != null && loadedContent.isNotBlank()) {
                                onApplyRedesign(loadedContent, sourceLabel.ifBlank { "External File" })
                            }
                        },
                        enabled = parsedBlueprint != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50),
                            disabledContainerColor = Color(0xFF2C3E2D)
                        ),
                        modifier = Modifier.testTag("apply_imported_redesign_button")
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Apply Redesign to Page", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(label: String, hex: String) {
    val color = parseHexColor(hex)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, color = Color.LightGray, fontSize = 10.sp)
    }
}
