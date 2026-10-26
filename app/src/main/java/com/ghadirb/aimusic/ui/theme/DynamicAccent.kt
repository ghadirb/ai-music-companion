package com.ghadirb.aimusic.ui.theme

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dynamic Artwork Theme (spec §2): the currently playing track's cover art
 * drives the accent color used on the Now Playing screen and mini player.
 *
 * Design constraints from the spec, reflected here:
 *  - Never delays playback: extraction runs on [Dispatchers.IO], off the
 *    decode/playback path, and simply isn't awaited by anything time-critical.
 *  - Cached per artwork URI so revisiting a track never re-decodes it.
 *  - Falls back to the static theme accent when there's no artwork, the
 *    extraction fails, or the result would hurt text legibility.
 */
private val accentCache = object : LinkedHashMap<String, Color>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>): Boolean = size > 24
}

private suspend fun extractAccentColor(context: Context, artUri: String, fallback: Color): Color =
    accentCache[artUri] ?: withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(artUri)
                .allowHardware(false) // Palette needs a software bitmap
                .size(96, 96) // small decode target: this is only for a dominant color, not display
                .build()
            val result = context.imageLoader.execute(request)
            val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                ?: return@runCatching fallback
            val palette = Palette.from(bitmap).generate()
            val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch
                ?: palette.mutedSwatch ?: palette.dominantSwatch
            val extracted = swatch?.rgb?.let { Color(it) } ?: fallback
            // Guard against colors too dark/too light to read as an accent on either theme.
            if (extracted.luminance() < 0.06f || extracted.luminance() > 0.92f) fallback else extracted
        }.getOrDefault(fallback).also { accentCache[artUri] = it }
    }

/**
 * Animated accent color for [artUri] (a track's `albumArtUri`), smoothly
 * transitioning between tracks. Pass `enabled = false` to always use the
 * static theme accent (e.g. respecting a low-power/reduced-motion setting).
 */
@Composable
fun rememberDynamicAccent(artUri: String?, enabled: Boolean = true): State<Color> {
    val context = LocalContext.current
    val fallback = MaterialTheme.colorScheme.primary
    var target by remember { mutableStateOf(fallback) }
    LaunchedEffect(artUri, enabled, fallback) {
        target = if (enabled && artUri != null) extractAccentColor(context, artUri, fallback) else fallback
    }
    return animateColorAsState(target, animationSpec = tween(durationMillis = 500), label = "dynamicAccent")
}
