package com.example.ui.model

import org.json.JSONArray
import org.json.JSONObject

enum class MessageSender {
    USER,
    AGENT
}

/**
 * Represents a single message in the Design Studio Chat Panel.
 * Each turn consists of:
 * 1. User message with their requested prompt/modification
 * 2. Agent reply with "Building <concept field from concept pass>"
 * 3. Agent reply with the live thinking drop-down (streaming tokens / summarized steps)
 */
data class ThemeChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String = "",
    val concept: String? = null,
    val isThinkingBubble: Boolean = false,
    val thinkingPhase: String = "",
    val thinkingSteps: List<String> = emptyList(),
    val activeStreamSnippet: String = "",
    val durationSeconds: Int = 0,
    val isThinkingActive: Boolean = false,
    val isThinkingFinished: Boolean = false,
    val errorMessage: String? = null,
    val modelLabel: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("sender", sender.name)
        obj.put("text", text)
        if (concept != null) obj.put("concept", concept)
        obj.put("isThinkingBubble", isThinkingBubble)
        obj.put("thinkingPhase", thinkingPhase)
        val stepsArray = JSONArray()
        thinkingSteps.forEach { stepsArray.put(it) }
        obj.put("thinkingSteps", stepsArray)
        obj.put("durationSeconds", durationSeconds)
        obj.put("isThinkingActive", isThinkingActive)
        obj.put("isThinkingFinished", isThinkingFinished)
        if (errorMessage != null) obj.put("errorMessage", errorMessage)
        if (modelLabel != null) obj.put("modelLabel", modelLabel)
        obj.put("timestamp", timestamp)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): ThemeChatMessage {
            val steps = mutableListOf<String>()
            val stepsArray = obj.optJSONArray("thinkingSteps")
            if (stepsArray != null) {
                for (i in 0 until stepsArray.length()) {
                    steps.add(stepsArray.optString(i))
                }
            }
            return ThemeChatMessage(
                id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                sender = try {
                    MessageSender.valueOf(obj.optString("sender", MessageSender.USER.name))
                } catch (_: Exception) {
                    MessageSender.USER
                },
                text = obj.optString("text", ""),
                concept = if (obj.has("concept")) obj.optString("concept") else null,
                isThinkingBubble = obj.optBoolean("isThinkingBubble", false),
                thinkingPhase = obj.optString("thinkingPhase", ""),
                thinkingSteps = steps,
                activeStreamSnippet = "",
                durationSeconds = obj.optInt("durationSeconds", 0),
                isThinkingActive = obj.optBoolean("isThinkingActive", false),
                isThinkingFinished = obj.optBoolean("isThinkingFinished", true),
                errorMessage = if (obj.has("errorMessage")) obj.optString("errorMessage") else null,
                modelLabel = if (obj.has("modelLabel")) obj.optString("modelLabel") else null,
                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            )
        }

        fun listToJson(list: List<ThemeChatMessage>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(jsonStr: String?): List<ThemeChatMessage> {
            if (jsonStr.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(jsonStr)
                val list = mutableListOf<ThemeChatMessage>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    if (item != null) {
                        list.add(fromJson(item))
                    }
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
