package com.ghadirb.aimusic.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * نواسا corner-radius scale. Material3's default [Shapes] already maps these
 * onto components (Card/Button/Sheet/...), so most screens get the shared
 * look automatically just from [AiMusicCompanionTheme]; use the individual
 * values directly only where a component doesn't read from MaterialTheme.shapes.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

// Named aliases for one-off Modifier.clip(...) calls (album art, mini player, sheets).
val ShapeArtworkLarge = RoundedCornerShape(28.dp)
val ShapeArtworkSmall = RoundedCornerShape(10.dp)
val ShapeFloatingBar = RoundedCornerShape(20.dp)
