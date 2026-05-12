package com.srizonvoice.android.recording

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.srizonvoice.android.api.GeminiClient
import com.srizonvoice.android.audio.AudioCaptureEngine
import com.srizonvoice.android.audio.WavEncoder
import com.srizonvoice.android.settings.SecureKeyStore
import com.srizonvoice.android.settings.SettingsRepository
import com.srizonvoice.android.util.DictationError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates the recording -> Gemini transcribe/transform -> emit-transcript flow.
 *
 * Mirrors macOS `DictationCoordinator` (`Services.swift:103-211`) but emits state via
 * Kotlin Flows so multiple consumers (in-app UI, bubble overlay, accessibility service,
 * tile) can observe without holding direct references to each other.
 */
class RecordingCoordinator(
    private val appContext: Context,
    private val settings: SettingsRepository,
    private val keys: SecureKeyStore,
    private val gemini: GeminiClient = GeminiClient(),
    private val scope: CoroutineScope = MainScope(),
) {
    private val engine = AudioCaptureEngine()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _transcripts = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 1)
    val transcripts: SharedFlow<TranscriptEvent> = _transcripts.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var transcribeJob: Job? = null
    private var autoStopJob: Job? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(autoStop: Boolean = false) {
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Transcribing) return
        try {
            cancelAutoStop()
            engine.start { level -> _state.value = RecordingState.Recording(level) }
            _state.value = RecordingState.Recording(visualLevel = 0f)
            if (autoStop) startAutoStop()
        } catch (e: DictationError) {
            emitError(e.userMessage)
        } catch (_: Throwable) {
            emitError(DictationError.AudioFormatCreationFailed.userMessage)
        }
    }

    /**
     * Stop, encode, send to Gemini with the selected output mode, and emit a transcript event.
     */
    fun stopAndTranscribe(sourcePackage: String? = null, translate: Boolean = false) {
        if (_state.value !is RecordingState.Recording) {
            engine.cancel()
            return
        }
        cancelAutoStop()
        val capture = engine.stopAndDrain()

        // Empty / silence-only recordings: no API call, no error, return to idle.
        if (capture.isSilent || capture.pcm.isEmpty()) {
            _state.value = RecordingState.Idle
            return
        }

        _state.value = RecordingState.Transcribing
        transcribeJob?.cancel()
        transcribeJob = scope.launch {
            try {
                val text = transcribePipeline(capture.pcm, sourcePackage, translate)
                if (text.isNotBlank()) {
                    _transcripts.tryEmit(TranscriptEvent(text, sourcePackage))
                }
                _state.value = RecordingState.Idle
            } catch (e: DictationError) {
                emitError(e.userMessage)
            } catch (e: Throwable) {
                emitError(e.message ?: DictationError.TranscriptionFailed.userMessage)
            }
        }
    }

    /** Drop in-flight audio without transcribing (e.g. user dragged to cancel). */
    fun cancel() {
        engine.cancel()
        transcribeJob?.cancel()
        cancelAutoStop()
        _state.value = RecordingState.Idle
    }

    private suspend fun transcribePipeline(
        pcm: ByteArray,
        sourcePackage: String?,
        translate: Boolean,
    ): String {
        val wav = withContext(Dispatchers.Default) { WavEncoder.encode(pcm) }
        val current = settings.current()
        val geminiKey = keys.geminiApiKey
        if (geminiKey.isBlank()) throw DictationError.InvalidApiKey

        val outputMode = if (translate) current.translationOutputMode else current.dictationOutputMode
        return gemini.transcribe(
            apiKey = geminiKey,
            wav = wav,
            outputMode = outputMode,
            customPrompt = current.customPrompt,
            targetLanguage = current.translationLanguage,
            targetAppName = sourcePackage,
        ).trim()
    }

    private fun startAutoStop() {
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            val seconds = settings.current().handsfreeMaxSeconds
            delay(seconds * 1_000L)
            if (_state.value is RecordingState.Recording) {
                stopAndTranscribe()
            }
        }
    }

    private fun cancelAutoStop() {
        autoStopJob?.cancel()
        autoStopJob = null
    }

    private fun emitError(message: String) {
        _state.value = RecordingState.Error(message)
        _errors.tryEmit(message)
        // Auto-return to idle so the UI doesn't get stuck in error.
        scope.launch {
            delay(2_500)
            if (_state.value is RecordingState.Error) _state.value = RecordingState.Idle
        }
    }
}
