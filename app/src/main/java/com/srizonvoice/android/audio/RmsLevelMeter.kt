package com.srizonvoice.android.audio

import kotlin.math.sqrt

/**
 * Audio level computation ported from macOS `Services.swift`.
 *
 * - Raw RMS over Int16 PCM, normalized to [0, 1].
 * - Visualization formula matches `How-it-works.md:230` — `clamp(rms * 6, 0.02, 1.0)` —
 *   to give the waveform bars enough visual punch at low input levels.
 * - Silence threshold `0.008` (Services.swift:250) is used to drop empty recordings
 *   before they hit the API.
 */
object RmsLevelMeter {

    const val SILENCE_THRESHOLD = 0.008f
    private const val INT16_MAX = 32_768f

    /** Returns RMS of the given PCM-16 chunk normalized into [0, 1] (raw, no shaping). */
    fun rmsNormalized(samples: ShortArray, sampleCount: Int = samples.size): Float {
        if (sampleCount == 0) return 0f
        var sum = 0.0
        for (i in 0 until sampleCount) {
            val v = samples[i] / INT16_MAX
            sum += (v * v).toDouble()
        }
        return sqrt(sum / sampleCount).toFloat().coerceIn(0f, 1f)
    }

    /** Visualization-shaped level for the recording island bars. */
    fun visualLevel(rms: Float): Float =
        (rms * 6f).coerceIn(0.02f, 1f)
}
