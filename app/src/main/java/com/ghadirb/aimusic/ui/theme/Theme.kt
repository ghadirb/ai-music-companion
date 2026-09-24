package com.ghadirb.aimusic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The doc explicitly asks for Material 3 + Dark Mode as the primary look, so
 * dark is the default scheme even on light-mode devices; Light is fully
 * independent below (not just Dark's colors inverted).
 *
 * Elevation hierarchy: background -> surface -> surfaceVariant/elevated
 * follows the token scale defined in Color.kt for both schemes.
 */
private val DarkScheme = darkColorScheme(
    primary = AccentGold,
    onPrimary = BackgroundDark,
    primaryContainer = SurfaceElevatedDark,
    onPrimaryContainer = AccentGoldBright,
    secondary = AccentTeal,
    onSecondary = BackgroundDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnBackgroundDark,
    surfaceVariant = SurfaceElevatedDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    secondaryContainer = SurfaceElevatedDark,
    outline = OutlineDark,
    outlineVariant = OutlineDark,
    error = ErrorDark,
    onError = BackgroundDark
)

private val LightScheme = lightColorScheme(
    primary = AccentGoldDeep,
    onPrimary = SurfaceLight,
    primaryContainer = SurfaceElevatedLight,
    onPrimaryContainer = AccentGoldDeep,
    secondary = AccentTealDeep,
    onSecondary = SurfaceLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnBackgroundLight,
    surfaceVariant = SurfaceElevatedLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    secondaryContainer = SurfaceElevatedLight,
    outline = OutlineLight,
    outlineVariant = OutlineLight,
    error = ErrorLight,
    onError = SurfaceLight
)

@Composable
fun AiMusicCompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
