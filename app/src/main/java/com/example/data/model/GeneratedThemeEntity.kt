package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists an AI-generated website theme alongside its rendered screenshot,
 * allowing instant re-application to eliminate redundant AI inference costs.
 */
@Entity(tableName = "generated_themes")
data class GeneratedThemeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val domain: String,
    val websiteUrl: String,
    val websiteTitle: String,
    val prompt: String,
    val themeName: String,
    val script: String,
    val screenshotPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val activeCss: String? = null,
    val concept: String? = null,
    val chatHistoryJson: String? = null,
    val bg: String? = null,
    val fg: String? = null
)
