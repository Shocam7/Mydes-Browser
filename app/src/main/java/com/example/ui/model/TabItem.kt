package com.example.ui.model

import androidx.compose.ui.graphics.Color
import java.util.UUID

data class TabItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String = "",
    val title: String = "New Tab",
    val isIncognito: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Float = 0f,
    val primaryColor: Color? = null,
    val isDesktopMode: Boolean = false
) {
    val isStartPage: Boolean
        get() = url.isBlank()
}
