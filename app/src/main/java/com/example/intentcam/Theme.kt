package com.example.intentcam

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4F8CFF),
    secondary = Color(0xFF37D399),
    background = Color(0xFF0B1021),
    surface = Color(0xFF161C2E)
)

private val LightColors = darkColorScheme(
    primary = Color(0xFF2C6BE0),
    secondary = Color(0xFF119E6E)
)

/**
 * [2026-07-15 UI polish] Provides both the Material 3 color
 * scheme and the [IntentCamPalette] via CompositionLocal.
 *
 * The Material color scheme is kept for any third-party M3 widget
 * that reads it (AlertDialog, TopAppBar, etc.).  All IntentCam-
 * owned composables should read from [IntentCamTheme.palette]
 * (which is just `LocalIntentCamPalette.current`) instead of
 * `MaterialTheme.colorScheme` so the named slots stay
 * semantically meaningful.
 */
@Composable
fun IntentCamTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    // Single palette instance for both branches today; see
    // Palette.kt's LightPalette docstring for the future
    // light-mode pass.
    val palette = DarkPalette
    CompositionLocalProvider(
        LocalIntentCamPalette provides palette,
    ) {
        MaterialTheme(
            colorScheme = colors,
            content = content,
        )
    }
}

/**
 * Accessor for the active palette from any composable inside
 * [IntentCamTheme].  Equivalent to `LocalIntentCamPalette.current`
 * but reads more cleanly at call sites:
 *
 *   val palette = IntentCamTheme.palette
 *   Text("...", color = palette.warning)
 */
object IntentCamTheme {
    val palette: IntentCamPalette
        @Composable
        get() = LocalIntentCamPalette.current
}
