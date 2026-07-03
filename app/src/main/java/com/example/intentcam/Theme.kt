package com.example.intentcam

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4F8CFF),
    secondary = Color(0xFF37D399),
    background = Color(0xFF0B1021),
    surface = Color(0xFF161C2E)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2C6BE0),
    secondary = Color(0xFF119E6E)
)

@Composable
fun IntentCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
