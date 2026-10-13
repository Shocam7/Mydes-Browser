package com.example.data.repository

import android.net.Uri
import androidx.compose.ui.graphics.Color
import com.example.data.dao.MydesDao
import com.example.data.model.DomainColorEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class WebsiteColorRepository(
    private val mydesDao: MydesDao,
    private val scope: CoroutineScope
) {
    // In-memory cache for fast UI retrieval
    private val domainCache = mutableMapOf<String, Color>()

    init {
        // Pre-store famous website primary colors
        PRESET_COLORS.forEach { (domain, hex) ->
            parseHexColor(hex)?.let { color ->
                domainCache[domain] = color
            }
        }
    }

    suspend fun getPrimaryColorForUrl(url: String): Color? {
        val domain = extractDomain(url) ?: return null
        // 1. Check in-memory cache
        domainCache[domain]?.let { return it }

        // 2. Check Room database
        return withContext(Dispatchers.IO) {
            val entity = mydesDao.getDomainColor(domain)
            if (entity != null) {
                parseHexColor(entity.colorHex)?.also { color ->
                    domainCache[domain] = color
                }
            } else {
                null
            }
        }
    }

    fun saveWebsiteColor(url: String, colorHex: String) {
        val domain = extractDomain(url) ?: return
        val parsedColor = parseHexColor(colorHex) ?: return

        domainCache[domain] = parsedColor

        scope.launch(Dispatchers.IO) {
            try {
                mydesDao.insertDomainColor(
                    DomainColorEntity(
                        domain = domain,
                        colorHex = colorHex
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    fun isColorNearWhite(color: Color): Boolean {
        // WCAG relative luminance estimation
        val r = color.red
        val g = color.green
        val b = color.blue
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        return luminance > 0.82f
    }

    fun extractDomain(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (host.startsWith("www.")) host.substring(4) else host
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val PRESET_COLORS = mapOf(
            "google.com" to "#4285F4",
            "youtube.com" to "#FF0000",
            "github.com" to "#24292F",
            "reddit.com" to "#FF4500",
            "wikipedia.org" to "#000000",
            "twitter.com" to "#000000",
            "x.com" to "#000000",
            "amazon.com" to "#FF9900",
            "duckduckgo.com" to "#DE5833",
            "facebook.com" to "#1877F2",
            "instagram.com" to "#E1306C",
            "linkedin.com" to "#0A66C2",
            "netflix.com" to "#E50914",
            "twitch.tv" to "#9146FF",
            "spotify.com" to "#1DB954",
            "stackoverflow.com" to "#F48024",
            "medium.com" to "#000000",
            "cnn.com" to "#CC0000",
            "bbc.com" to "#BB1919",
            "nytimes.com" to "#121212",
            "apple.com" to "#555555",
            "microsoft.com" to "#0078D4"
        )

        fun parseHexColor(colorString: String): Color? {
            val trimmed = colorString.trim()
            if (trimmed.isEmpty()) return null

            return try {
                if (trimmed.startsWith("#")) {
                    val hex = trimmed.substring(1)
                    when (hex.length) {
                        3 -> { // #RGB -> #RRGGBB
                            val r = hex[0].toString().repeat(2).toInt(16)
                            val g = hex[1].toString().repeat(2).toInt(16)
                            val b = hex[2].toString().repeat(2).toInt(16)
                            Color(r, g, b)
                        }
                        6 -> {
                            val colorInt = hex.toLong(16) or 0x00000000FF000000L
                            Color(colorInt)
                        }
                        8 -> {
                            Color(hex.toLong(16))
                        }
                        else -> null
                    }
                } else if (trimmed.startsWith("rgb", ignoreCase = true)) {
                    // e.g. rgb(255, 0, 0)
                    val values = trimmed.replace(Regex("[^0-9,]"), "")
                        .split(",")
                        .mapNotNull { it.trim().toIntOrNull() }
                    if (values.size >= 3) {
                        Color(values[0], values[1], values[2])
                    } else null
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
