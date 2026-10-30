package com.example.data.api

import android.content.Context
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@JsonClass(generateAdapter = true)
data class ApiLogEntry(
    val timestamp: String,
    val type: String,
    val websiteName: String,
    val websiteUrl: String,
    val userPrompt: String,
    val requestPayload: String,
    val responseResult: String,
    val isSuccess: Boolean,
    val screenshotPath: String? = null,
    val screenshotBase64: String? = null
)

object ApiRequestLogger {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    
    private val listType = com.squareup.moshi.Types.newParameterizedType(List::class.java, ApiLogEntry::class.java)
    private val adapter = moshi.adapter<List<ApiLogEntry>>(listType)

    private fun getLogFile(context: Context): File {
        return File(context.filesDir, "request_logs.json")
    }

    @Synchronized
    fun logRequest(
        context: Context,
        type: String,
        websiteName: String,
        websiteUrl: String,
        userPrompt: String,
        requestPayload: String,
        responseResult: String,
        isSuccess: Boolean,
        screenshotBase64: String? = null,
        screenshotPath: String? = null
    ) {
        try {
            var resolvedScreenshotPath = screenshotPath
            if (resolvedScreenshotPath == null && !screenshotBase64.isNullOrBlank()) {
                try {
                    val dir = File(context.filesDir, "review_screenshots")
                    if (!dir.exists()) dir.mkdirs()
                    val fileName = "review_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(6)}.jpg"
                    val file = File(dir, fileName)
                    val bytes = android.util.Base64.decode(screenshotBase64, android.util.Base64.DEFAULT)
                    file.writeBytes(bytes)
                    resolvedScreenshotPath = file.absolutePath
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val file = getLogFile(context)
            val currentLogs = mutableListOf<ApiLogEntry>()
            if (file.exists()) {
                val json = file.readText()
                val parsed = adapter.fromJson(json)
                if (parsed != null) {
                    currentLogs.addAll(parsed)
                }
            }
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val entry = ApiLogEntry(
                timestamp = timeFormat.format(Date()),
                type = type,
                websiteName = websiteName,
                websiteUrl = websiteUrl,
                userPrompt = userPrompt,
                requestPayload = requestPayload,
                responseResult = responseResult,
                isSuccess = isSuccess,
                screenshotPath = resolvedScreenshotPath,
                screenshotBase64 = screenshotBase64
            )
            currentLogs.add(0, entry) // Add new logs at top
            if (currentLogs.size > 100) {
                currentLogs.subList(100, currentLogs.size).clear()
            }
            file.writeText(adapter.toJson(currentLogs))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getLogs(context: Context): List<ApiLogEntry> {
        try {
            val file = getLogFile(context)
            if (!file.exists()) return emptyList()
            val json = file.readText()
            return adapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
    }

    fun getLogFilePath(context: Context): String {
        return getLogFile(context).absolutePath
    }

    @Synchronized
    fun clearLogs(context: Context) {
        try {
            val file = getLogFile(context)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
