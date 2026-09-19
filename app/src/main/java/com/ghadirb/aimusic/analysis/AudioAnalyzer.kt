package com.ghadirb.aimusic.analysis

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * On-device audio feature extraction — this is the piece the README's "AI
 * ROADMAP" listed as not-yet-built ("AudioAnalyzer ... کدی نوشته نشده").
 *
 * No network, no cloud model, nothing leaves the device: it decodes the
 * local file with MediaCodec, measures short-time loudness (RMS) to derive
 * an energy score, and estimates tempo (BPM) via autocorrelation of the
 * loudness envelope — a standard lightweight beat-tracking approach that
 * needs no ML model or TensorFlow Lite/ONNX runtime. Those remain valid
 * future upgrades (see MoodTag doc + README) for more accurate mood/genre
 * detection; this gives the app *something real* to filter Home's "night"
 * and "driving" cards on today.
 *
 * Trade-off, stated plainly: this is a heuristic, not a trained classifier.
 * BPM estimation on percussion-light tracks (ambient, solo vocal, spoken
 * word) can be inconclusive — [analyze] returns bpm = null in that case
 * rather than guessing.
 */
object AudioAnalyzer {

    private const val TAG = "AudioAnalyzer"

    /** Only the first N seconds are decoded — plenty for a stable energy/tempo estimate, keeps battery/CPU cost low. */
    private const val MAX_ANALYSIS_MS = 45_000L
    private const val WINDOW_MS = 100L // energy-envelope resolution

    data class Result(
        /** 0f (very calm) .. 1f (very energetic). */
        val energyLevel: Float,
        /** Estimated tempo in BPM, or null if the envelope had no clear periodicity. */
        val bpm: Int?,
        val moodTag: String
    )

    /** Coarse mood buckets used to drive Home's smart cards and the AI DJ / recommendation filters. */
    object MoodTag {
        const val CALM = "calm"
        const val ENERGETIC = "energetic"
        const val NEUTRAL = "neutral"

        /** Moods considered suitable for the "مناسب شب" (night) card. */
        val NIGHT_SUITABLE = listOf(CALM)

        /** Moods considered suitable for the "مناسب رانندگی" (driving) card. */
        val DRIVING_SUITABLE = listOf(ENERGETIC)
    }

    /**
     * Decodes [uri] to mono PCM (bounded to [MAX_ANALYSIS_MS]) and derives energy/tempo/mood.
     * Returns null if the file couldn't be decoded at all (corrupt/unsupported/DRM).
     * Runs on the calling thread — callers (AudioAnalysisWorker) are responsible for using Dispatchers.IO/Default.
     */
    fun analyze(context: Context, uri: Uri): Result? {
        val envelope = try {
            extractEnergyEnvelope(context, uri)
        } catch (e: Exception) {
            Log.w(TAG, "decode failed for $uri: ${e.message}")
            null
        } ?: return null

        if (envelope.isEmpty()) return null

        val energy = normalizedEnergy(envelope)
        val bpm = estimateBpm(envelope, windowMs = WINDOW_MS)
        val mood = classifyMood(energy, bpm)
        return Result(energyLevel = energy, bpm = bpm, moodTag = mood)
    }

    /**
     * Decodes audio and returns a short-time RMS "loudness envelope": one value
     * per [WINDOW_MS] window, in raw PCM amplitude units (not yet normalized).
     */
    private fun extractEnergyEnvelope(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            return FloatArray(0)
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val samplesPerWindow = max(1, (sampleRate * channelCount * WINDOW_MS / 1000).toInt())
        val envelope = mutableListOf<Float>()
        var windowSumSquares = 0.0
        var windowSampleCount = 0

        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var decodedDurationUs = 0L
        val maxDurationUs = MAX_ANALYSIS_MS * 1000

        try {
            while (!sawOutputEos && decodedDurationUs < maxDurationUs) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = inputBuffer?.let { extractor.readSampleData(it, 0) } ?: -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            // 16-bit PCM samples, little-endian.
                            var i = 0
                            while (i + 1 < bufferInfo.size) {
                                val lo = outputBuffer.get().toInt() and 0xFF
                                val hi = outputBuffer.get().toInt()
                                val sample = ((hi shl 8) or lo).toShort().toInt()
                                windowSumSquares += (sample.toDouble() * sample.toDouble())
                                windowSampleCount++
                                if (windowSampleCount >= samplesPerWindow) {
                                    envelope.add(sqrt(windowSumSquares / windowSampleCount).toFloat())
                                    windowSumSquares = 0.0
                                    windowSampleCount = 0
                                }
                                i += 2
                            }
                        }
                        decodedDurationUs = bufferInfo.presentationTimeUs
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Ignore — we only care about raw PCM amplitude, not the exact output format.
                }
            }
        } finally {
            if (windowSampleCount > 0) {
                envelope.add(sqrt(windowSumSquares / windowSampleCount).toFloat())
            }
            codec.stop()
            codec.release()
            extractor.release()
        }

        return envelope.toFloatArray()
    }

    /**
     * Maps average RMS loudness to a 0..1 energy score using a log scale (loudness
     * perception is roughly logarithmic) against 16-bit PCM's max amplitude.
     */
    private fun normalizedEnergy(envelope: FloatArray): Float {
        val meanRms = envelope.average()
        if (meanRms <= 1.0) return 0f
        // ln(1)=0 .. ln(32768)=~10.4 — clamp/scale to 0..1.
        val logScore = ln(meanRms) / ln(32768.0)
        return logScore.toFloat().coerceIn(0f, 1f)
    }

    /**
     * Simple autocorrelation-based tempo estimate over the loudness envelope.
     * Searches lag distances corresponding to 60–180 BPM and returns the lag
     * with the strongest self-similarity, or null if no lag stands out
     * meaningfully above the average (i.e. no clear beat, e.g. ambient/spoken audio).
     */
    private fun estimateBpm(envelope: FloatArray, windowMs: Long): Int? {
        if (envelope.size < 20) return null

        // Remove DC offset so autocorrelation reflects rhythmic variation, not raw loudness.
        val mean = envelope.average().toFloat()
        val centered = FloatArray(envelope.size) { envelope[it] - mean }

        val windowsPerSecond = 1000.0 / windowMs
        val minLag = (60.0 / 180.0 * windowsPerSecond).toInt().coerceAtLeast(1) // 180 BPM
        val maxLag = (60.0 / 60.0 * windowsPerSecond).toInt().coerceAtMost(centered.size - 1) // 60 BPM
        if (minLag >= maxLag) return null

        var bestLag = -1
        var bestScore = 0.0
        var totalScore = 0.0
        var count = 0

        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until centered.size - lag) {
                sum += centered[i] * centered[i + lag]
            }
            val score = abs(sum)
            totalScore += score
            count++
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        if (bestLag <= 0 || count == 0) return null
        val avgScore = totalScore / count
        // Require the best lag to clearly stand out — otherwise there's no strong beat to report.
        if (avgScore <= 0.0 || bestScore < avgScore * 1.5) return null

        val bpm = (60.0 / (bestLag * windowMs / 1000.0)).toInt()
        return bpm.coerceIn(50, 200)
    }

    private fun classifyMood(energy: Float, bpm: Int?): String = when {
        energy >= 0.62f && (bpm == null || bpm >= 100) -> MoodTag.ENERGETIC
        energy <= 0.42f && (bpm == null || bpm <= 110) -> MoodTag.CALM
        else -> MoodTag.NEUTRAL
    }
}
