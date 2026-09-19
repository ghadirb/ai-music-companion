package com.ghadirb.aimusic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The doc explicitly asks for Material 3 + Dark Mode as the primary look, so
 * dark is the default scheme even on light-mode devices; light is still wired
 * up for completeness.
 */
private val DarkScheme = darkColorScheme(
    primary = PurplePrimary,
    secondary = Teal,
    background = BackgroundDark,
    surface = SurfaceDark,
    onBackground = OnDark,
    onSurface = OnDark
)

private val LightScheme = lightColorScheme(
    primary = PurplePrimary,
    secondary = Teal
)

@Composable
fun AiMusicCompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
