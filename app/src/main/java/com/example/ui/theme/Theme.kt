package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE0E0E0),
    secondary = Color(0xFFAAAAAA),
    background = MydesIncognitoDark,
    surface = MydesSurfaceDark,
    onPrimary = MydesBlack,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = MydesBlack,
    secondary = Color(0xFF555555),
    background = MydesLightBackground,
    surface = MydesLightSurface,
    onPrimary = Color.White,
    onSecondary = MydesBlack,
    onBackground = MydesTextPrimaryDark,
    onSurface = MydesTextPrimaryDark
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
