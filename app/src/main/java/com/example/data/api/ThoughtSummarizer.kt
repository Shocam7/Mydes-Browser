package com.example.data.api

import java.util.Locale

/**
 * Intelligent summarizer that transforms raw streaming thinking and synthesis tokens from Gemini
 * and NVIDIA NIM into concise, readable statements for display in the UI (chat-bot style).
 */
object ThoughtSummarizer {

    /**
     * Cleans raw thinking or synthesis text by stripping markdown asterisks, block markers, backticks,
     * CSS comments, JSON syntax wrappers, and leading bullet/numbering patterns.
     */
    fun cleanText(raw: String): String {
        return raw
            .replace(Regex("""/\*+|\*+/"""), "")
            .replace(Regex("""[*_`#]"""), "")
            .replace(Regex("""^\s*[-•*]\s*"""), "")
            .replace(Regex("""^\s*\d+[\.\)]\s*"""), "")
            .replace(Regex("""^[\{\}\[\],;]+\s*"""), "")
            .replace(Regex("""[\{\}\[\],;]+\s*$"""), "")
            .replace(Regex("""^"(?:name|concept|idea|notes|rationale|css|rules)":\s*"""), "")
            .replace(Regex("""^"+|"+$"""), "")
            .trim()
    }

    /**
     * Splits accumulated thoughts into readable, concise summarized statements
     * and extracts the trailing active thought snippet.
     *
     * @param accumulatedThoughts The raw text buffer accumulated so far from thought and output streams
     * @param isComplete Whether the current streaming call has completely finished
     * @return Pair of (completed summarized statements, active streaming snippet)
     */
    fun summarize(accumulatedThoughts: String, isComplete: Boolean = false): Pair<List<String>, String> {
        val clean = accumulatedThoughts
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()

        if (clean.isEmpty()) {
            return Pair(emptyList(), "")
        }

        // Split by paragraph, newline, or sentence boundaries
        val rawSentences = clean.split(Regex("""(?<=[.!?\n])\s+"""))
            .map { cleanText(it) }
            .filter { it.isNotBlank() }

        if (rawSentences.isEmpty()) {
            val fallback = cleanText(clean)
            return if (isComplete) {
                val stmt = formatStatement(fallback)
                Pair(if (stmt.isNotBlank()) listOf(stmt) else emptyList(), "")
            } else {
                Pair(emptyList(), fallback.takeLast(140))
            }
        }

        val hasCompletedTrailing = clean.endsWith(".") || clean.endsWith("\n") || clean.endsWith("!") || clean.endsWith("?")
        val pendingSnippet: String
        val candidates: List<String>
        if (isComplete) {
            pendingSnippet = ""
            candidates = rawSentences
        } else if (!hasCompletedTrailing) {
            val lastRaw = rawSentences.last()
            pendingSnippet = cleanText(lastRaw).trim('"', '{', '}', '[', ']', ',', ';', ' ')
            candidates = rawSentences.dropLast(1)
        } else {
            // Actively streaming with trailing newline or punctuation: keep the latest phrase visible as live snippet
            val lastRaw = rawSentences.last()
            pendingSnippet = cleanText(lastRaw).trim('"', '{', '}', '[', ']', ',', ';', ' ')
            candidates = rawSentences.dropLast(1)
        }

        val completed = mutableListOf<String>()
        for (s in candidates) {
            val formatted = formatStatement(s)
            if (formatted.length >= 8 && !completed.contains(formatted)) {
                completed.add(formatted)
            }
        }

        return Pair(completed.takeLast(6), pendingSnippet.takeLast(140))
    }

    private fun formatStatement(s: String): String {
        var text = cleanText(s).trim()
        if (text.isEmpty()) return ""
        text = text.trim('"', '{', '}', '[', ']', ',', ';', ' ')
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
