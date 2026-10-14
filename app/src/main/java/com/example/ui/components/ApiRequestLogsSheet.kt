package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.api.ApiLogEntry
import com.example.data.api.ApiRequestLogger

@Composable
fun ApiRequestLogsSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(emptyList<ApiLogEntry>()) }
    var selectedLog by remember { mutableStateOf<ApiLogEntry?>(null) }

    LaunchedEffect(Unit) {
        logs = ApiRequestLogger.getLogs(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6141619))
            .clickable(onClick = onDismiss)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(top = 40.dp, start = 16.dp, end = 16.dp, bottom = 20.dp)
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
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "API Request & Result Logs",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Saved in internal storage file",
                                color = Color.Gray,
                                fontSize = 11.sp
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

                Spacer(modifier = Modifier.height(16.dp))

                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No request logs recorded yet.\nTry using the AI Styler on a website!",
                            color = Color.Gray,
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(logs) { log ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedLog = log },
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                                fontSize = 14.sp
                                            )
                                        }
                                        Text(
                                            text = log.timestamp,
                                            color = Color.Gray,
                                            fontSize = 12.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Site: ${log.websiteName} (${log.websiteUrl})",
                                        color = Color(0xFFB0BEC5),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Prompt: \"${log.userPrompt}\"",
                                        color = Color(0xFFE0E0E0),
                                        fontSize = 13.sp,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Detail dialog if log is selected
        selectedLog?.let { log ->
            AlertDialog(
                onDismissRequest = { selectedLog = null },
                containerColor = Color(0xFF1E2024),
                title = {
                    Text(
                        text = "${log.type} Details",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(text = "Timestamp: ${log.timestamp}", color = Color.Gray, fontSize = 12.sp)
                        Text(text = "Website: ${log.websiteName}", color = Color.Gray, fontSize = 12.sp)
                        Text(text = "URL: ${log.websiteUrl}", color = Color.Gray, fontSize = 12.sp)
                        Text(text = "Prompt: ${log.userPrompt}", color = Color.White, fontSize = 13.sp)
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Request Payload:", color = Color(0xFF81D4FA), fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                                modifier = Modifier.padding(8.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Response Result / Script:", color = Color(0xFFA5D6A7), fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { selectedLog = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5))
                    ) {
                        Text("Close")
                    }
                }
            )
        }
    }
}
