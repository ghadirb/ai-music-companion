package com.ghadirb.aimusic.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * نواسا Design System — warm "premium tape-gold" identity (not a Spotify/Apple
 * Music clone). Dark is the primary surface language; Light is a fully
 * independent, non-inverted palette (see Theme.kt doc).
 *
 * Naming: Background -> Surface -> SurfaceElevated is the visual hierarchy
 * asked for by the UI spec; every screen should pick from this scale rather
 * than inventing new dark/light values inline.
 */

// ---- Brand accent (static fallback; screens may override with the
// artwork-derived Dynamic Accent color — see DynamicAccent.kt) ----
val AccentGold = Color(0xFFD9B66F)
val AccentGoldBright = Color(0xFFEAC98A)
val AccentGoldDeep = Color(0xFFB4863A) // used on Light where the bright gold fails contrast
val AccentTeal = Color(0xFF8FBFA6)
val AccentTealDeep = Color(0xFF3F8F76)

// ---- Dark hierarchy: Background < Surface < SurfaceElevated ----
val BackgroundDark = Color(0xFF14110C)
val SurfaceDark = Color(0xFF1E1911)
val SurfaceElevatedDark = Color(0xFF2A2318)
val OutlineDark = Color(0xFF3A3324)
val OnBackgroundDark = Color(0xFFF5EFE2)
val OnSurfaceVariantDark = Color(0xFFC7BBA3)
val ErrorDark = Color(0xFFE7A199)

// ---- Light hierarchy: independent tuning, not an inversion of Dark ----
val BackgroundLight = Color(0xFFFDFBF6)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceElevatedLight = Color(0xFFF3EBDA)
val OutlineLight = Color(0xFFE2D8C2)
val OnBackgroundLight = Color(0xFF241E13)
val OnSurfaceVariantLight = Color(0xFF6B6152)
val ErrorLight = Color(0xFFB3261E)

// ---- Legacy aliases kept so any older reference keeps compiling ----
@Deprecated("Use AccentGold", ReplaceWith("AccentGold"))
val PurplePrimary = AccentGold
@Deprecated("Use AccentTeal", ReplaceWith("AccentTeal"))
val Teal = AccentTeal
@Deprecated("Use OnBackgroundDark", ReplaceWith("OnBackgroundDark"))
val OnDark = OnBackgroundDark
