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
    val isSuccess: Boolean
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
        isSuccess: Boolean
    ) {
        try {
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
                isSuccess = isSuccess
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
}
