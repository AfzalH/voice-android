package com.srizonvoice.android.util

/**
 * Error surface ported from macOS `DictationError` (`Models.swift:472-480`).
 * Mirrors the user-visible message strings exactly so the two apps feel identical.
 */
sealed class DictationError(open val userMessage: String) : Exception() {
    data object InvalidUrl : DictationError("Invalid API URL.")
    data object InvalidResponse : DictationError("Invalid network response.")
    data object InvalidApiKey : DictationError("Invalid API key. Check Settings.")
    data object AudioFormatCreationFailed : DictationError("Could not configure audio capture.")
    data object TranscriptionFailed : DictationError("Transcription failed.")
    data class ServerError(val body: String) : DictationError(body.ifBlank { "Server error." })

    override val message: String get() = userMessage
}
