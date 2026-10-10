package com.ghadirb.aimusic.ui.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Shared touch layer for both video engines: tap toggles the controls, and a vertical swipe on the
 * LEFT half changes screen brightness, on the RIGHT half the media volume (like MX Player).
 * A full-height swipe covers the whole range; a small indicator shows the current level.
 */
@Composable
fun PlayerGestureLayer(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var hudText by remember { mutableStateOf<String?>(null) }
    var hudTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(hudTick) {
        if (hudTick > 0) { delay(900); hudText = null }
    }

    fun currentBrightness(): Float {
        val set = activity?.window?.attributes?.screenBrightness ?: -1f
        if (set >= 0f) return set
        val system = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (system / 255f).coerceIn(0.05f, 1f)
    }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            .pointerInput(Unit) {
                var leftSide = true
                var startBrightness = 0f
                var startVolume = 0f
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        leftSide = offset.x < size.width / 2f
                        startBrightness = currentBrightness()
                        startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                        travelled = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        // Up = more. A full-height swipe moves the whole range (x1.2 so it is easy to reach the ends).
                        travelled += (-dragAmount / size.height.toFloat()) * 1.2f
                        if (leftSide) {
                            val level = (startBrightness + travelled).coerceIn(0.02f, 1f)
                            activity?.window?.let { w ->
                                val lp = w.attributes
                                lp.screenBrightness = level
                                w.attributes = lp
                            }
                            hudText = "☀  ${(level * 100).roundToInt()}٪"
                        } else {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val v = (startVolume + travelled * max).roundToInt().coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                            hudText = (if (v == 0) "🔇  " else "🔊  ") + "${(v * 100f / max).roundToInt()}٪"
                        }
                        hudTick++
                    }
                )
            }
    ) {
        content()
        hudText?.let { text ->
            Text(
                text,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xB0000000))
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            )
        }
    }
}
