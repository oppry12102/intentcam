package com.example.intentcam

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * [2026-07-15 UI polish] Semantic color palette for IntentCam.
 *
 * Replaces ~27 hardcoded `Color(0xFFxxxxxx)` literals scattered
 * across MainActivity.kt with named slots in one place.  Each
 * slot has an "intent" (what the color *means*) so adding a new
 * screen never has to introduce a new hex code — you pick the
 * existing slot whose meaning matches.
 *
 * Slots chosen against the actual usage in MainActivity:
 *  - `background`              — full-screen black backdrop
 *  - `surface`                 — bubble card / sheet surface
 *  - `surfaceOverlay`          — semi-transparent overlay (top bar, sheet, sheet header)
 *  - `surfaceMuted`            — debug log panel bg
 *  - `onSurface`               — primary text on dark surface
 *  - `onSurfaceMuted`          — secondary text (sheet headers, table cells)
 *  - `onSurfaceSubtle`         — tertiary text (table kind column, debug row)
 *  - `accentExecute`           — pink — consent-gated chip (dial_number / scan_to_pay / redact_id)
 *  - `accentDelegate`          — blue — OS-handoff chip (open_in_maps / share)
 *  - `accentClarify`           — gray — info-only chip / unknown
 *  - `success`                 — green — location dot, debug toggle on, completed badge
 *  - `warning`                 — orange — "需要补充信息" hint
 *  - `danger`                  — red — error banner border, "已替换" sup badge is `onSurfaceMuted`
 *  - `divider`                 — table row separator
 *
 * [Dark-only build]: LightPalette is defined for completeness but
 * the device fleet is overwhelmingly dark-mode; switching
 * `isSystemInDarkTheme()` is a no-op for now.  The data class
 * stays so a future light-mode pass only needs to fill the
 * `LightPalette` instance.
 */
data class IntentCamPalette(
    val background: Color,
    val surface: Color,
    val surfaceOverlay: Color,
    val surfaceMuted: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val onSurfaceSubtle: Color,
    val accentExecute: Color,
    val accentDelegate: Color,
    val accentClarify: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val divider: Color,
)

/**
 * Default dark palette.  Hex values copied verbatim from the
 * pre-refactor MainActivity.kt so the visual output is byte-for-byte
 * identical to the previous version — this commit is a pure
 * refactor, no visual change.  Listed in the same order as the
 * data-class slots above.
 *
 * `internal` so [Theme.kt] can hand it to `CompositionLocalProvider`
 * while keeping the surface narrow for app-internal composables.
 */
internal val DarkPalette = IntentCamPalette(
    background = Color(0xFF000000),
    surface = Color(0xE6161C2E),
    surfaceOverlay = Color(0xE6111828),
    surfaceMuted = Color(0xCC0B1021),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceMuted = Color(0xFFB9C4DE),
    onSurfaceSubtle = Color(0xFF7B8FB8),
    accentExecute = Color(0xFFE64A8C),
    accentDelegate = Color(0xFF4F8CFF),
    accentClarify = Color(0xFF888888),
    success = Color(0xFF37D399),
    warning = Color(0xFFFFAF54),
    danger = Color(0xFFE64A8C),
    divider = Color(0xFF2A3050),
)

/**
 * Light-mode stub.  Mirrors the dark palette as a placeholder
 * until a future light-mode pass designs a contrasting light
 * surface.  Until then, the dark UI is rendered under both
 * system themes — the alternative (re-styling for light mode)
 * is out of scope for this batch.
 */
@Suppress("unused")
private val LightPalette_internal = DarkPalette

/**
 * CompositionLocal for the active palette.  Defaulted to
 * [DarkPalette] via the static initializer so a composable
 * called outside [IntentCamTheme] still resolves (e.g. preview
 * composables during Compose tooling).
 */
val LocalIntentCamPalette = staticCompositionLocalOf { DarkPalette }
