package com.srizonvoice.android.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.srizonvoice.android.util.DictationError
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.concurrent.Volatile

/**
 * Mic capture pipeline.
 *
 * - `MediaRecorder.AudioSource.VOICE_RECOGNITION` (applies AEC/NS).
 * - 16 kHz mono Int16 PCM, wrapped as WAV before sending to Gemini.
 * - Buffer = `AudioRecord.getMinBufferSize() * 2`.
 * - Runs on a dedicated single-thread executor; never touches the UI thread.
 *
 * Mirrors `AudioCaptureService` in `Sources/SrizonVoice/Services.swift:242-323`.
 */
class AudioCaptureEngine {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "srizon-audio-capture").apply { priority = Thread.MAX_PRIORITY - 1 }
    }

    @Volatile
    private var isRunning = false

    @Volatile
    private var record: AudioRecord? = null

    private val pcm = ByteArrayOutputStream(64 * 1024)

    /** Highest visualization-normalized level seen in the current session. */
    @Volatile
    var peakVisualLevel: Float = 0f
        private set

    /** Highest raw RMS seen in the current session — used for the silence drop check. */
    @Volatile
    var peakRawRms: Float = 0f
        private set

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onLevel: (Float) -> Unit) {
        if (isRunning) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
        if (minBuf <= 0) throw DictationError.AudioFormatCreationFailed
        val bufferSize = minBuf * 2

        val ar = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                ENCODING,
                bufferSize,
            )
        } catch (_: IllegalArgumentException) {
            throw DictationError.AudioFormatCreationFailed
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            throw DictationError.AudioFormatCreationFailed
        }

        synchronized(this) {
            pcm.reset()
            peakVisualLevel = 0f
            peakRawRms = 0f
            record = ar
            isRunning = true
        }
        ar.startRecording()

        executor.execute {
            val readBuffer = ShortArray(bufferSize / 2)
            try {
                while (isRunning) {
                    val read = ar.read(readBuffer, 0, readBuffer.size)
                    if (read <= 0) continue

                    val rms = RmsLevelMeter.rmsNormalized(readBuffer, read)
                    val visual = RmsLevelMeter.visualLevel(rms)
                    if (rms > peakRawRms) peakRawRms = rms
                    if (visual > peakVisualLevel) peakVisualLevel = visual
                    onLevel(visual)

                    appendPcm(readBuffer, read)
                }
            } catch (_: Throwable) {
                // Capture loop ended; the next call to stop() / release() will clean up.
            }
        }
    }

    /** Stops capture and returns the accumulated raw PCM (Int16, little-endian). */
    fun stopAndDrain(): CaptureResult {
        synchronized(this) {
            if (!isRunning) return CaptureResult(ByteArray(0), peakRawRms, peakVisualLevel)
            isRunning = false
        }
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped — ignore.
        }
        record?.release()
        record = null
        val bytes = synchronized(pcm) { pcm.toByteArray().also { pcm.reset() } }
        return CaptureResult(bytes, peakRawRms, peakVisualLevel)
    }

    /** Aborts capture without returning any audio (used for "drag to cancel"). */
    fun cancel() {
        synchronized(this) {
            if (!isRunning) return
            isRunning = false
        }
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
            // Ignore.
        }
        record?.release()
        record = null
        synchronized(pcm) { pcm.reset() }
    }

    private fun appendPcm(samples: ShortArray, count: Int) {
        val bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bb.putShort(samples[i])
        synchronized(pcm) { pcm.write(bb.array(), 0, count * 2) }
    }

    fun shutdown() {
        cancel()
        executor.shutdownNow()
    }

    data class CaptureResult(
        val pcm: ByteArray,
        val peakRawRms: Float,
        val peakVisualLevel: Float,
    ) {
        val isSilent: Boolean get() = peakRawRms < RmsLevelMeter.SILENCE_THRESHOLD
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
