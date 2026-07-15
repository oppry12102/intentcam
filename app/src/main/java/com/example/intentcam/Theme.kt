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

/**
 * [2026-07-15 UI polish] Drop the `isSystemInDarkTheme()` branch.
 * The previous version declared a `LightColors` scheme that was
 * the same Material colorScheme as dark but with different
 * primary/secondary hex values — but the Theme refactor (commit
 * 10) painted the entire app with `IntentCamPalette`, which is
 * dark-only.  A `lightTheme` branch would have produced a
 * black-text-on-white-bubble UI in light mode, so the branch
 * was effectively dead.  Force `DarkColors` always; the
 * `darkTheme` parameter is kept for API compatibility but no
 * longer drives the color scheme.  A real light-mode pass will
 * need to fill `LightPalette` first.
 */
@Composable
fun IntentCamTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = DarkPalette
    CompositionLocalProvider(
        LocalIntentCamPalette provides palette,
    ) {
        MaterialTheme(
            colorScheme = DarkColors,
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
