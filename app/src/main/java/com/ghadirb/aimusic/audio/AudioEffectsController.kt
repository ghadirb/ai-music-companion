package com.ghadirb.aimusic.audio

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Equaliser, bass boost, surround ("virtualizer"), volume boost, skip-silence and A-B repeat for the music player.
 * Lives with the playback service (same process as the UI, like SleepTimerController), so the sound keeps its
 * settings when the screen is closed. Every call must happen on the main thread.
 */
@UnstableApi
object AudioEffectsController {

    data class Device(
        val centerFreqsHz: List<Int> = emptyList(),
        val minMb: Int = -1500,
        val maxMb: Int = 1500,
        val equalizer: Boolean = false,
        val bassBoost: Boolean = false,
        val virtualizer: Boolean = false,
        val loudness: Boolean = false
    )

    data class UiState(
        val settings: AudioEffectsSettings = AudioEffectsSettings(),
        val device: Device = Device(),
        /** Effective band gains (millibel) currently applied. */
        val gainsMb: List<Int> = emptyList(),
        val abLoop: AbLoop = AbLoop()
    )

    private const val FILE = "audio_effects"
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var player: ExoPlayer? = null
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null

    private val sessionListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) = rebuild()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { clearAbLoop() }
    }

    private val abTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val loop = _state.value.abLoop
            if (!loop.active) return
            AbLoopLogic.seekTarget(loop, p.currentPosition)?.let { p.seekTo(it) }
            handler.postDelayed(this, 150L)
        }
    }

    fun attach(context: Context, player: ExoPlayer) {
        appContext = context.applicationContext
        this.player = player
        val saved = AudioEffectsSettings.decode(
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("settings", null)
        )
        _state.value = _state.value.copy(settings = saved)
        player.addListener(sessionListener)
        player.skipSilenceEnabled = saved.skipSilence
        rebuild()
    }

    fun detach() {
        handler.removeCallbacks(abTick)
        player?.removeListener(sessionListener)
        releaseEffects()
        player = null
        appContext = null
        _state.value = _state.value.copy(abLoop = AbLoop())
    }

    // ---- settings ----

    fun update(transform: (AudioEffectsSettings) -> AudioEffectsSettings) {
        val next = transform(_state.value.settings).sanitized()
        _state.value = _state.value.copy(settings = next)
        appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()?.putString("settings", next.encode())?.apply()
        player?.skipSilenceEnabled = next.skipSilence
        applySettings()
    }

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }

    fun setPreset(id: Int) {
        val d = _state.value.device
        val preset = EqPresets.byId(id) ?: return
        update { it.copy(enabled = true, preset = id, bandGainsMb = EqPresets.gainsMb(preset, d.centerFreqsHz, d.minMb, d.maxMb)) }
    }

    fun setBand(index: Int, gainMb: Int) {
        val d = _state.value.device
        update {
            val current = _state.value.gainsMb.ifEmpty { it.gainsFor(d.centerFreqsHz, d.minMb, d.maxMb) }.toMutableList()
            if (index in current.indices) current[index] = gainMb.coerceIn(d.minMb, d.maxMb)
            it.copy(enabled = true, preset = EqPresets.CUSTOM, bandGainsMb = current)
        }
    }

    fun resetAll() = update { AudioEffectsSettings(enabled = it.enabled, skipSilence = it.skipSilence) }

    // ---- A-B repeat ----

    fun markA() { val p = player ?: return; setLoop(AbLoopLogic.setA(_state.value.abLoop, p.currentPosition)) }
    fun markB() { val p = player ?: return; setLoop(AbLoopLogic.setB(_state.value.abLoop, p.currentPosition)) }
    fun clearAbLoop() = setLoop(AbLoop())

    private fun setLoop(loop: AbLoop) {
        _state.value = _state.value.copy(abLoop = loop)
        handler.removeCallbacks(abTick)
        if (loop.active) handler.post(abTick)
    }

    // ---- effects plumbing ----

    private fun releaseEffects() {
        runCatching { eq?.release() }; runCatching { bass?.release() }
        runCatching { virt?.release() }; runCatching { loud?.release() }
        eq = null; bass = null; virt = null; loud = null
    }

    private fun rebuild() {
        releaseEffects()
        val sid = player?.audioSessionId ?: return
        if (sid == 0 || sid == -1) return
        eq = runCatching { Equalizer(0, sid) }.getOrNull()
        bass = runCatching { BassBoost(0, sid) }.getOrNull()
        virt = runCatching { Virtualizer(0, sid) }.getOrNull()
        loud = runCatching { LoudnessEnhancer(sid) }.getOrNull()
        val e = eq
        val device = if (e != null) {
            val range = e.bandLevelRange
            Device(
                centerFreqsHz = (0 until e.numberOfBands).map { e.getCenterFreq(it.toShort()) / 1000 },
                minMb = range[0].toInt(), maxMb = range[1].toInt(), equalizer = true,
                bassBoost = bass?.strengthSupported == true, virtualizer = virt?.strengthSupported == true, loudness = loud != null
            )
        } else Device(bassBoost = bass?.strengthSupported == true, virtualizer = virt?.strengthSupported == true, loudness = loud != null)
        _state.value = _state.value.copy(device = device)
        applySettings()
    }

    private fun applySettings() {
        val s = _state.value.settings
        val d = _state.value.device
        val gains = s.gainsFor(d.centerFreqsHz, d.minMb, d.maxMb)
        _state.value = _state.value.copy(gainsMb = gains)
        runCatching {
            eq?.let { e ->
                gains.forEachIndexed { i, g -> e.setBandLevel(i.toShort(), g.toShort()) }
                e.enabled = s.enabled
            }
        }
        runCatching {
            bass?.let { b -> if (b.strengthSupported) b.setStrength(s.bassStrength.toShort()); b.enabled = s.enabled && s.bassStrength > 0 }
        }
        runCatching {
            virt?.let { v -> if (v.strengthSupported) v.setStrength(s.virtualizerStrength.toShort()); v.enabled = s.enabled && s.virtualizerStrength > 0 }
        }
        runCatching {
            loud?.let { l -> l.setTargetGain(s.loudnessMb); l.enabled = s.enabled && s.loudnessMb > 0 }
        }
    }
}
