package com.example.data.api

import org.json.JSONObject

/**
 * Model-specific Reasoning Modulator configuration & wire syntax builder.
 *
 * Syntax rules enforced:
 * 1. Nemotron (`nvidia/nemotron-3-ultra-550b-a55b`):
 *    - OFF:    {"chat_template_kwargs": {"enable_thinking": false}}
 *    - MEDIUM: {"chat_template_kwargs": {"enable_thinking": true, "medium_effort": true}}
 *    - FULL:   {"chat_template_kwargs": {"enable_thinking": true}}
 * 2. Gemini 3.8 Flash (`gemini-3.8-flash`):
 *    - Modes: "low", "medium", "high"
 *    - Syntax: generation_config: { thinking_level: "low/medium/high" }
 * 3. Gemini 3.5 Flash Lite (`gemini-3.5-flash-lite`):
 *    - Modes: "minimal", "low", "medium", "high"
 *    - Syntax: generation_config: { thinking_level: "minimal/low/medium/high" }
 */
object ReasoningModulatorConfig {

    const val MODE_OFF = "off"
    const val MODE_MEDIUM = "medium"
    const val MODE_FULL = "full"

    const val MODE_MINIMAL = "minimal"
    const val MODE_LOW = "low"
    const val MODE_HIGH = "high"

    fun getAvailableModesForModel(model: String): List<String> {
        return when {
            GeminiStylerService.isNvidiaModel(model) -> listOf(MODE_OFF, MODE_MEDIUM, MODE_FULL)
            model == GeminiStylerService.MODEL_GEMINI_38_FLASH -> listOf(MODE_LOW, MODE_MEDIUM, MODE_HIGH)
            else -> listOf(MODE_MINIMAL, MODE_LOW, MODE_MEDIUM, MODE_HIGH)
        }
    }

    fun getDefaultModeForModel(model: String, isContinuationChat: Boolean = false): String {
        return when {
            GeminiStylerService.isNvidiaModel(model) -> {
                // In continued chat modifications, Nemotron defaults to OFF-thinking for rapid CSS tweaks
                if (isContinuationChat) MODE_OFF else MODE_MEDIUM
            }
            model == GeminiStylerService.MODEL_GEMINI_38_FLASH -> MODE_MEDIUM
            else -> MODE_LOW
        }
    }

    fun getDisplayLabel(model: String, mode: String): String {
        val m = mode.lowercase().trim()
        return if (GeminiStylerService.isNvidiaModel(model)) {
            when (m) {
                MODE_OFF -> "OFF"
                MODE_MEDIUM -> "Medium"
                MODE_FULL -> "Full"
                else -> "Medium"
            }
        } else {
            when (m) {
                MODE_MINIMAL -> "Minimal"
                MODE_LOW -> "Low"
                MODE_MEDIUM -> "Medium"
                MODE_HIGH -> "High"
                else -> m.replaceFirstChar { it.uppercase() }
            }
        }
    }

    fun nextMode(model: String, currentMode: String): String {
        val modes = getAvailableModesForModel(model)
        val idx = modes.indexOf(currentMode.lowercase().trim())
        return if (idx in modes.indices) {
            modes[(idx + 1) % modes.size]
        } else {
            modes.first()
        }
    }

    /**
     * Builds the exact `chat_template_kwargs` JSON object for Nemotron.
     * Note: When `forceNoReasoningForContinuation` is true (continued chat modifications),
     * it strictly sets `{"enable_thinking": false}` with no reasoning effort.
     */
    fun buildNemotronChatTemplateKwargs(
        mode: String,
        forceNoReasoningForContinuation: Boolean = false
    ): JSONObject {
        if (forceNoReasoningForContinuation) {
            return JSONObject().put("enable_thinking", false)
        }
        return when (mode.lowercase().trim()) {
            MODE_OFF -> JSONObject().put("enable_thinking", false)
            MODE_MEDIUM -> JSONObject()
                .put("enable_thinking", true)
                .put("medium_effort", true)
            MODE_FULL -> JSONObject().put("enable_thinking", true)
            else -> JSONObject()
                .put("enable_thinking", true)
                .put("medium_effort", true)
        }
    }

    /**
     * Normalizes the `thinking_level` value for Gemini 3.8 Flash and Gemini 3.5 Flash Lite
     * to be placed directly inside `generation_config: { thinking_level: "..." }`.
     */
    fun resolveGeminiThinkingLevel(model: String, mode: String): String {
        val validModes = getAvailableModesForModel(model)
        val clean = mode.lowercase().trim()
        return if (clean in validModes) clean else getDefaultModeForModel(model)
    }
}
