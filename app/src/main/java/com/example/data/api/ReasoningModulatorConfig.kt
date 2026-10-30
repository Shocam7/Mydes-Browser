package com.example.data.api

data class ReasoningMode(
    val key: String,
    val label: String,
    val description: String
)

object ReasoningModulatorConfig {
    // Nemotron 3 Ultra (NIM) modes:
    // 1. OFF: {"chat_template_kwargs": {"enable_thinking": False}}
    // 2. Medium: {"chat_template_kwargs": {"enable_thinking": True, "medium_effort": True}}
    // 3. Full: {"chat_template_kwargs": {"enable_thinking": True}}
    val NEMOTRON_MODES = listOf(
        ReasoningMode("off", "OFF", "OFF-thinking mode (rapid responses, no reasoning)"),
        ReasoningMode("medium", "Medium", "Medium-thinking mode (balanced effort)"),
        ReasoningMode("full", "Full", "Full-thinking mode (deep reasoning exploration)")
    )

    // Gemini 3.8 Flash modes:
    // generation_config: { thinking_level: "low/medium/high" }
    val GEMINI_38_MODES = listOf(
        ReasoningMode("low", "Low", "Low reasoning level"),
        ReasoningMode("medium", "Medium", "Medium reasoning level"),
        ReasoningMode("high", "High", "High reasoning level")
    )

    // Gemini 3.5 Flash Lite modes:
    // generation_config: { thinking_level: "minimal/low/medium/high" }
    val GEMINI_35_LITE_MODES = listOf(
        ReasoningMode("minimal", "Minimal", "Minimal reasoning level (fastest)"),
        ReasoningMode("low", "Low", "Low reasoning level"),
        ReasoningMode("medium", "Medium", "Medium reasoning level"),
        ReasoningMode("high", "High", "High reasoning level")
    )

    fun getModesForModel(model: String): List<ReasoningMode> {
        return when {
            GeminiStylerService.isNvidiaModel(model) -> NEMOTRON_MODES
            model == GeminiStylerService.MODEL_GEMINI_38_FLASH -> GEMINI_38_MODES
            else -> GEMINI_35_LITE_MODES
        }
    }

    fun getDefaultModeForModel(model: String): String {
        return when {
            GeminiStylerService.isNvidiaModel(model) -> "medium"
            model == GeminiStylerService.MODEL_GEMINI_38_FLASH -> "medium"
            else -> "minimal"
        }
    }
}
