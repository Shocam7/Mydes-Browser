package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a historical visual iteration of a website theme,
 * preserving its screenshot, script, pure CSS, concept, and palette
 * so users can visually inspect and revert to previous versions.
 */
data class ThemeVisualIteration(
    val id: String = java.util.UUID.randomUUID().toString(),
    val iterationNumber: Int = 1,
    val title: String = "Theme Iteration",
    val screenshotPath: String? = null,
    val script: String = "",
    val activeCss: String? = null,
    val concept: String? = null,
    val bg: String? = null,
    val fg: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("iterationNumber", iterationNumber)
            put("title", title)
            put("screenshotPath", screenshotPath ?: "")
            put("script", script)
            put("activeCss", activeCss ?: "")
            put("concept", concept ?: "")
            put("bg", bg ?: "")
            put("fg", fg ?: "")
            put("timestamp", timestamp)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ThemeVisualIteration {
            return ThemeVisualIteration(
                id = json.optString("id", java.util.UUID.randomUUID().toString()),
                iterationNumber = json.optInt("iterationNumber", 1),
                title = json.optString("title", "Theme Iteration"),
                screenshotPath = json.optString("screenshotPath").takeIf { it.isNotBlank() },
                script = json.optString("script", ""),
                activeCss = json.optString("activeCss").takeIf { it.isNotBlank() },
                concept = json.optString("concept").takeIf { it.isNotBlank() },
                bg = json.optString("bg").takeIf { it.isNotBlank() },
                fg = json.optString("fg").takeIf { it.isNotBlank() },
                timestamp = json.optLong("timestamp", System.currentTimeMillis())
            )
        }

        fun listToJson(list: List<ThemeVisualIteration>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(jsonStr: String?, fallbackTheme: GeneratedThemeEntity? = null): List<ThemeVisualIteration> {
            val list = mutableListOf<ThemeVisualIteration>()
            if (!jsonStr.isNullOrBlank()) {
                try {
                    val arr = JSONArray(jsonStr)
                    for (i in 0 until arr.length()) {
                        list.add(fromJson(arr.getJSONObject(i)))
                    }
                } catch (_: Exception) {}
            }
            if (list.isEmpty() && fallbackTheme != null) {
                list.add(
                    ThemeVisualIteration(
                        iterationNumber = 1,
                        title = (fallbackTheme.concept?.takeIf { it.isNotBlank() }
                            ?: fallbackTheme.themeName.takeIf { it.isNotBlank() }
                            ?: fallbackTheme.prompt),
                        screenshotPath = fallbackTheme.screenshotPath,
                        script = fallbackTheme.script,
                        activeCss = fallbackTheme.activeCss,
                        concept = fallbackTheme.concept,
                        bg = fallbackTheme.bg,
                        fg = fallbackTheme.fg,
                        timestamp = fallbackTheme.createdAt
                    )
                )
            }
            return list
        }
    }
}
