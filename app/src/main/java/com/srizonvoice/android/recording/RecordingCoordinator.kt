package com.srizonvoice.android.recording

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.srizonvoice.android.api.GeminiClient
import com.srizonvoice.android.api.GroqClient
import com.srizonvoice.android.audio.AudioCaptureEngine
import com.srizonvoice.android.audio.WavEncoder
import com.srizonvoice.android.settings.SecureKeyStore
import com.srizonvoice.android.settings.SettingsRepository
import com.srizonvoice.android.util.DictationError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates the recording → transcribe → (post-process) → emit-transcript flow.
 *
 * Mirrors macOS `DictationCoordinator` (`Services.swift:103-211`) but emits state via
 * Kotlin Flows so multiple consumers (in-app UI, bubble overlay, accessibility service,
 * tile) can observe without holding direct references to each other.
 */
class RecordingCoordinator(
    private val appContext: Context,
    private val settings: SettingsRepository,
    private val keys: SecureKeyStore,
    private val groq: GroqClient = GroqClient(),
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

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording() {
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Transcribing) return
        try {
            engine.start { level -> _state.value = RecordingState.Recording(level) }
            _state.value = RecordingState.Recording(visualLevel = 0f)
        } catch (e: DictationError) {
            emitError(e.userMessage)
        } catch (_: Throwable) {
            emitError(DictationError.AudioFormatCreationFailed.userMessage)
        }
    }

    /** Stop, encode, transcribe, optionally post-process, and emit a transcript event. */
    fun stopAndTranscribe(sourcePackage: String? = null) {
        if (_state.value !is RecordingState.Recording) {
            engine.cancel()
            return
        }
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
                val text = transcribePipeline(capture.pcm, sourcePackage)
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
        _state.value = RecordingState.Idle
    }

    private suspend fun transcribePipeline(pcm: ByteArray, sourcePackage: String?): String {
        val wav = withContext(Dispatchers.Default) { WavEncoder.encode(pcm) }
        val current = settings.current()
        val groqKey = keys.groqApiKey
        if (groqKey.isBlank()) throw DictationError.InvalidApiKey

        val rawTranscript = groq.transcribe(
            apiKey = groqKey,
            wav = wav,
            model = current.transcriptionModel,
            languageCode = current.language.code,
        ).trim()
        if (rawTranscript.isBlank()) return ""

        if (!current.postProcessingEnabled) return rawTranscript

        val context = sourcePackage?.let { " Note: The dictation was triggered in the context of: $it." }.orEmpty()
        val systemPrompt = current.postProcessingPrompt + context

        return if (current.useGemini) {
            val key = keys.geminiApiKey
            if (key.isBlank()) rawTranscript
            else runCatching { gemini.cleanup(key, systemPrompt, rawTranscript).trim() }
                .getOrElse { rawTranscript }
        } else {
            // Default Groq model for cleanup matches the macOS default (`openai/gpt-oss-120b`).
            runCatching {
                groq.postProcessChat(
                    apiKey = groqKey,
                    modelId = "openai/gpt-oss-120b",
                    systemPrompt = systemPrompt,
                    transcript = rawTranscript,
                ).trim()
            }.getOrElse { rawTranscript }
        }
    }

    private fun emitError(message: String) {
        _state.value = RecordingState.Error(message)
        _errors.tryEmit(message)
        // Auto-return to idle so the UI doesn't get stuck in error.
        scope.launch {
            kotlinx.coroutines.delay(2_500)
            if (_state.value is RecordingState.Error) _state.value = RecordingState.Idle
        }
    }
}
