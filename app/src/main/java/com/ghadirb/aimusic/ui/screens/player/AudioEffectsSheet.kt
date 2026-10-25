package com.ghadirb.aimusic.ui.screens.player

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.ghadirb.aimusic.audio.AbLoopLogic
import com.ghadirb.aimusic.audio.AudioEffectsController
import com.ghadirb.aimusic.audio.AudioEffectsSettings
import com.ghadirb.aimusic.audio.EqPresets

private fun fmtMs(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.padding(end = 4.dp).weight(0.28f), style = MaterialTheme.typography.labelMedium)
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, enabled = enabled, modifier = Modifier.weight(0.55f))
        Text(valueText, Modifier.weight(0.17f), style = MaterialTheme.typography.labelMedium)
    }
}

/** Equaliser, bass, surround, volume boost, skip silence, speed and A-B repeat for the music player. */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioEffectsSheet(
    speed: Float,
    positionMs: Long,
    onSpeed: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val ui by AudioEffectsController.state.collectAsState()
    val s = ui.settings
    val d = ui.device
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("صدا و اکولایزر", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(checked = s.enabled, onCheckedChange = AudioEffectsController::setEnabled)
            }
            Text(
                if (s.enabled) "افکت‌های صدا فعال است" else "برای شنیدن تغییرات، افکت‌ها را روشن کنید",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- equaliser ----
            SectionTitle("اکولایزر")
            if (!d.equalizer) {
                Text("اکولایزر روی این دستگاه یا با این خروجی صدا در دسترس نیست. اگر پخش را شروع کنید دوباره بررسی می‌شود.", style = MaterialTheme.typography.bodySmall)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EqPresets.ALL.forEach { p ->
                        FilterChip(selected = s.preset == p.id, onClick = { AudioEffectsController.setPreset(p.id) }, label = { Text(p.name) })
                    }
                    if (s.preset == EqPresets.CUSTOM) FilterChip(selected = true, onClick = {}, label = { Text("سفارشی") })
                }
                Spacer(Modifier.height(4.dp))
                d.centerFreqsHz.forEachIndexed { i, hz ->
                    val gain = ui.gainsMb.getOrElse(i) { 0 }
                    SliderRow(
                        label = if (hz >= 1000) "${hz / 1000}kHz" else "${hz}Hz",
                        value = gain.toFloat(), range = d.minMb.toFloat()..d.maxMb.toFloat(),
                        valueText = "%+.1f dB".format(gain / 100f)
                    ) { AudioEffectsController.setBand(i, it.toInt()) }
                }
            }

            // ---- other effects ----
            SectionTitle("افکت‌ها")
            SliderRow("بیس", s.bassStrength.toFloat(), 0f..1000f, "${s.bassStrength / 10}٪", enabled = d.bassBoost) { v ->
                AudioEffectsController.update { it.copy(enabled = true, bassStrength = v.toInt()) }
            }
            SliderRow("صدای فراگیر", s.virtualizerStrength.toFloat(), 0f..1000f, "${s.virtualizerStrength / 10}٪", enabled = d.virtualizer) { v ->
                AudioEffectsController.update { it.copy(enabled = true, virtualizerStrength = v.toInt()) }
            }
            SliderRow("تقویت صدا", s.loudnessMb.toFloat(), 0f..AudioEffectsSettings.MAX_LOUDNESS_MB.toFloat(), "+%.1f dB".format(s.loudnessMb / 100f), enabled = d.loudness) { v ->
                AudioEffectsController.update { it.copy(enabled = true, loudnessMb = v.toInt()) }
            }
            if (s.loudnessMb > 900) {
                Text("تقویت زیاد ممکن است باعث اعوجاج یا آسیب به شنوایی شود.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = AudioEffectsController::resetAll) { Text("بازنشانی افکت‌ها") }

            HorizontalDivider()

            // ---- playback ----
            SectionTitle("پخش")
            SliderRow("سرعت", speed, 0.5f..2f, "×%.2f".format(speed)) { v -> onSpeed((Math.round(v / 0.05f) * 0.05f)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("رد کردن سکوت‌ها")
                    Text("بخش‌های بی‌صدا (مثلاً در پادکست) پخش نمی‌شود", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s.skipSilence, onCheckedChange = { on -> AudioEffectsController.update { it.copy(skipSilence = on) } })
            }

            SectionTitle("تکرار قسمتی از آهنگ (A-B)")
            val loop = ui.abLoop
            Text(
                when {
                    loop.active -> "در حال تکرار از ${fmtMs(loop.aMs!!)} تا ${fmtMs(loop.bMs!!)}"
                    loop.aMs != null -> "شروع (A) در ${fmtMs(loop.aMs)}؛ حالا نقطهٔ پایان (B) را بزنید"
                    else -> "روی نقطهٔ شروع بزنید، کمی بعد روی نقطهٔ پایان"
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = AudioEffectsController::markA) { Text("شروع (A)  ${fmtMs(positionMs)}") }
                TextButton(enabled = loop.aMs != null, onClick = AudioEffectsController::markB) { Text("پایان (B)  ${fmtMs(positionMs)}") }
                TextButton(enabled = loop.aMs != null, onClick = AudioEffectsController::clearAbLoop) { Text("حذف") }
            }
            if (loop.aMs != null && !loop.active) {
                Text("پایان باید دست‌کم ${AbLoopLogic.MIN_LENGTH_MS / 1000.0} ثانیه بعد از شروع باشد.", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
