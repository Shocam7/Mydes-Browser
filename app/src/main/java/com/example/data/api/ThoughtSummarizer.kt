package com.example.data.api

import java.util.Locale

/**
 * Intelligent summarizer that transforms raw streaming thinking tokens from Gemini
 * into concise, readable statements for display in the UI (chat-bot style).
 */
object ThoughtSummarizer {

    /**
     * Cleans raw thinking text by stripping markdown asterisks, block markers, backticks,
     * and leading bullet/numbering patterns.
     */
    fun cleanText(raw: String): String {
        return raw
            .replace(Regex("""[*_`#]"""), "")
            .replace(Regex("""^\s*[-•*]\s*"""), "")
            .replace(Regex("""^\s*\d+[\.\)]\s*"""), "")
            .trim()
    }

    /**
     * Splits accumulated thoughts into readable, concise summarized statements
     * and extracts the trailing active thought snippet.
     *
     * @return Pair of (completed summarized statements, active streaming snippet)
     */
    fun summarize(accumulatedThoughts: String): Pair<List<String>, String> {
        val clean = accumulatedThoughts
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()

        if (clean.isEmpty()) {
            return Pair(emptyList(), "")
        }

        // Split by paragraph or sentence boundaries
        val rawSentences = clean.split(Regex("""(?<=[.!?\n])\s+"""))
            .map { cleanText(it) }
            .filter { it.isNotBlank() }

        if (rawSentences.isEmpty()) {
            return Pair(emptyList(), cleanText(clean).takeLast(120))
        }

        val hasCompletedTrailing = clean.endsWith(".") || clean.endsWith("\n") || clean.endsWith("!") || clean.endsWith("?")
        val pendingSnippet = if (!hasCompletedTrailing) {
            rawSentences.last()
        } else {
            ""
        }

        val candidates = if (pendingSnippet.isNotEmpty()) {
            rawSentences.dropLast(1)
        } else {
            rawSentences
        }

        val completed = mutableListOf<String>()
        for (s in candidates) {
            val formatted = formatStatement(s)
            if (formatted.length >= 10 && !completed.contains(formatted)) {
                completed.add(formatted)
            }
        }

        return Pair(completed.takeLast(6), pendingSnippet.takeLast(140))
    }

    private fun formatStatement(s: String): String {
        var text = s.trim()
        if (text.isEmpty()) return ""
        // Capitalize first letter
        text = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        // Truncate overly verbose sentences to keep them crisp, scannable, and readable
        if (text.length > 110) {
            val commaIdx = text.indexOfAny(charArrayOf(',', ';', ':', '—', '-'), 50)
            if (commaIdx in 50..100) {
                text = text.substring(0, commaIdx).trim() + "..."
            } else {
                text = text.take(100).trim() + "..."
            }
        }
        return text
    }
}
