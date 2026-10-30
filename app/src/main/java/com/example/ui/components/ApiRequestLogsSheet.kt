package com.example.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.api.ApiLogEntry
import com.example.data.api.ApiRequestLogger

@Composable
fun ApiRequestLogsSheet(
    onDismiss: () -> Unit,
    onOpenImportRedesign: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var logs by remember { mutableStateOf(emptyList<ApiLogEntry>()) }
    var selectedLog by remember { mutableStateOf<ApiLogEntry?>(null) }
    var fullScreenScreenshotLog by remember { mutableStateOf<ApiLogEntry?>(null) }

    LaunchedEffect(Unit) {
        logs = ApiRequestLogger.getLogs(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6141619))
            .clickable(onClick = onDismiss)
            .testTag("api_request_logs_sheet")
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(top = 36.dp, start = 14.dp, end = 14.dp, bottom = 18.dp)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2024))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
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
                                .background(Color(0xFF2A2E35)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = Color(0xFF64B5F6),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "API Logs & Payloads",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${logs.size} requests logged • Full payloads preserved",
                                color = Color(0xFF9E9E9E),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Quick Import button
                        IconButton(
                            onClick = {
                                onOpenImportRedesign()
                            },
                            modifier = Modifier.testTag("logs_import_redesign_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileUpload,
                                contentDescription = "Import Redesign (.json)",
                                tint = Color(0xFF81C784)
                            )
                        }

                        if (logs.isNotEmpty()) {
                            // Copy All
                            IconButton(onClick = {
                                val allText = logs.joinToString("\n\n" + "=".repeat(40) + "\n\n") { log ->
                                    "Timestamp: ${log.timestamp}\nType: ${log.type}\nWebsite: ${log.websiteName} (${log.websiteUrl})\nPrompt: ${log.userPrompt}\nSuccess: ${log.isSuccess}\nPayload:\n${log.requestPayload}\nResult:\n${log.responseResult}"
                                }
                                clipboardManager.setText(AnnotatedString(allText))
                                Toast.makeText(context, "All logs copied to clipboard", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy all logs",
                                    tint = Color.White
                                )
                            }

                            // Clear Logs
                            IconButton(onClick = {
                                ApiRequestLogger.clearLogs(context)
                                logs = emptyList()
                                Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = "Clear logs",
                                    tint = Color(0xFFE57373)
                                )
                            }
                        }

                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close logs",
                                tint = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No request logs recorded yet.",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Use the AI Styler on any website to inspect the full request payload here.",
                                color = Color.Gray,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onOpenImportRedesign,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5))
                            ) {
                                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Import External Redesign File")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(logs) { log ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedLog = log }
                                    .testTag("log_item_card"),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFF262A30)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = if (log.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                                contentDescription = null,
                                                tint = if (log.isSuccess) Color(0xFF4CAF50) else Color(0xFFF44336),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = log.type,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            text = log.timestamp,
                                            color = Color(0xFF9E9E9E),
                                            fontSize = 11.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Site: ${log.websiteName} (${log.websiteUrl})",
                                        color = Color(0xFFB0BEC5),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Prompt: \"${log.userPrompt}\"",
                                        color = Color(0xFFECEFF1),
                                        fontSize = 13.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    if (!log.screenshotPath.isNullOrBlank() || !log.screenshotBase64.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            val thumbFile = remember(log.screenshotPath) {
                                                if (!log.screenshotPath.isNullOrBlank()) java.io.File(log.screenshotPath) else null
                                            }
                                            if (thumbFile != null && thumbFile.exists()) {
                                                AsyncImage(
                                                    model = thumbFile,
                                                    contentDescription = "Sent rendered page thumbnail",
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(Color.Black),
                                                    contentScale = ContentScale.Crop
                                                )
                                            }
                                            Surface(
                                                color = Color(0x264CAF50),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Image,
                                                        contentDescription = null,
                                                        tint = Color(0xFF81C784),
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "Rendered Webpage Attached",
                                                        color = Color(0xFF81C784),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Action bar on each card: shows payload size + 1-tap Copy Payload button
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            color = Color(0xFF1B1E22),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "Payload: ${log.requestPayload.length} chars",
                                                color = Color(0xFF81D4FA),
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(log.requestPayload))
                                                Toast.makeText(context, "Entire request payload copied! Ready to paste into frontier model.", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = null,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Copy Payload", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Detail dialog if a log entry is selected
        selectedLog?.let { log ->
            AlertDialog(
                onDismissRequest = { selectedLog = null },
                containerColor = Color(0xFF1E2024),
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = log.type,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { selectedLog = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(text = "Timestamp: ${log.timestamp}", color = Color.Gray, fontSize = 12.sp)
                        Text(text = "Website: ${log.websiteName}", color = Color(0xFFB0BEC5), fontSize = 12.sp)
                        Text(text = "URL: ${log.websiteUrl}", color = Color(0xFF90A4AE), fontSize = 12.sp)
                        Text(text = "Prompt: \"${log.userPrompt}\"", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                        val screenshotFile = remember(log.screenshotPath) {
                            if (!log.screenshotPath.isNullOrBlank()) java.io.File(log.screenshotPath) else null
                        }
                        if ((screenshotFile != null && screenshotFile.exists()) || !log.screenshotBase64.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF14171A))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Image,
                                            contentDescription = null,
                                            tint = Color(0xFFFFD54F),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Rendered Webpage Screenshot (Sent to Review Agent)",
                                            color = Color(0xFFFFD54F),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                    Surface(
                                        color = Color(0x334CAF50),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "CLEAN RENDER",
                                            color = Color(0xFF81C784),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "Clean webpage viewport only • No obstruction from brush icons, pills, or sheets.",
                                    color = Color(0xFFB0BEC5),
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black)
                                        .clickable { fullScreenScreenshotLog = log },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (screenshotFile != null && screenshotFile.exists()) {
                                        AsyncImage(
                                            model = screenshotFile,
                                            contentDescription = "Review Agent Sent Screenshot",
                                            modifier = Modifier.fillMaxWidth(),
                                            contentScale = ContentScale.Fit
                                        )
                                    } else if (!log.screenshotBase64.isNullOrBlank()) {
                                        val bitmap = remember(log.screenshotBase64) {
                                            try {
                                                val decoded = android.util.Base64.decode(log.screenshotBase64, android.util.Base64.DEFAULT)
                                                android.graphics.BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                                            } catch (_: Exception) { null }
                                        }
                                        if (bitmap != null) {
                                            androidx.compose.foundation.Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = "Review Agent Sent Screenshot",
                                                modifier = Modifier.fillMaxWidth(),
                                                contentScale = ContentScale.Fit
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Tap image to inspect full size",
                                        color = Color(0xFF81D4FA),
                                        fontSize = 11.sp
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(
                                            onClick = { fullScreenScreenshotLog = log },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(12.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Enlarge", fontSize = 11.sp)
                                        }

                                        if (!log.screenshotBase64.isNullOrBlank()) {
                                            Button(
                                                onClick = {
                                                    clipboardManager.setText(AnnotatedString(log.screenshotBase64))
                                                    Toast.makeText(context, "Screenshot base64 copied to clipboard!", Toast.LENGTH_SHORT).show()
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Base64", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Request Payload Header & Actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Entire Request Payload (${log.requestPayload.length} chars):",
                                color = Color(0xFF81D4FA),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )

                            Row {
                                IconButton(
                                    onClick = {
                                        val sendIntent: Intent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, log.requestPayload)
                                            putExtra(Intent.EXTRA_TITLE, "Mydes Request Payload - ${log.type}")
                                            type = "text/plain"
                                        }
                                        val shareIntent = Intent.createChooser(sendIntent, "Share / Export Request Payload")
                                        context.startActivity(shareIntent)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share Payload", tint = Color(0xFF81D4FA), modifier = Modifier.size(16.dp))
                                }

                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(log.requestPayload))
                                        Toast.makeText(context, "Entire request payload copied to clipboard!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Payload", tint = Color(0xFF81D4FA), modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF121417),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = log.requestPayload,
                                color = Color(0xFFCFD8DC),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(10.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Response Result Header & Actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Response Result (${log.responseResult.length} chars):",
                                color = Color(0xFFA5D6A7),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )

                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(log.responseResult))
                                    Toast.makeText(context, "Response result copied to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Result", tint = Color(0xFFA5D6A7), modifier = Modifier.size(16.dp))
                            }
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF121417),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = log.responseResult,
                                color = Color(0xFFE8F5E9),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                onOpenImportRedesign()
                                selectedLog = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Import Result", fontSize = 12.sp)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val logText = "Timestamp: ${log.timestamp}\nType: ${log.type}\nWebsite: ${log.websiteName} (${log.websiteUrl})\nPrompt: ${log.userPrompt}\nSuccess: ${log.isSuccess}\nPayload:\n${log.requestPayload}\nResult:\n${log.responseResult}"
                                    clipboardManager.setText(AnnotatedString(logText))
                                    Toast.makeText(context, "Full log entry copied", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5))
                            ) {
                                Text("Copy All", fontSize = 12.sp)
                            }

                            Button(
                                onClick = { selectedLog = null },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242))
                            ) {
                                Text("Close", fontSize = 12.sp)
                            }
                        }
                    }
                }
            )
        }

        // Fullscreen Screenshot Inspection Dialog
        fullScreenScreenshotLog?.let { log ->
            val fsFile = remember(log.screenshotPath) {
                if (!log.screenshotPath.isNullOrBlank()) java.io.File(log.screenshotPath) else null
            }
            AlertDialog(
                onDismissRequest = { fullScreenScreenshotLog = null },
                containerColor = Color(0xFF141619),
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Sent Review Screenshot",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Clean rendered webpage analyzed by the review agent (no UI icons/sheets)",
                                color = Color(0xFF81C784),
                                fontSize = 11.sp
                            )
                        }
                        IconButton(onClick = { fullScreenScreenshotLog = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                },
                text = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (fsFile != null && fsFile.exists()) {
                            AsyncImage(
                                model = fsFile,
                                contentDescription = "Full Rendered Webpage Screenshot",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                contentScale = ContentScale.FillWidth
                            )
                        } else if (!log.screenshotBase64.isNullOrBlank()) {
                            val bitmap = remember(log.screenshotBase64) {
                                try {
                                    val decoded = android.util.Base64.decode(log.screenshotBase64, android.util.Base64.DEFAULT)
                                    android.graphics.BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                                } catch (_: Exception) { null }
                            }
                            if (bitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Full Rendered Webpage Screenshot",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState()),
                                    contentScale = ContentScale.FillWidth
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (fsFile != null && fsFile.exists()) {
                            Button(
                                onClick = {
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, "Mydes Review Agent Screenshot for ${log.websiteName}")
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share Screenshot Info"))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share", fontSize = 12.sp)
                            }
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }

                        Button(
                            onClick = { fullScreenScreenshotLog = null },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5))
                        ) {
                            Text("Done", fontSize = 12.sp)
                        }
                    }
                }
            )
        }
    }
}
