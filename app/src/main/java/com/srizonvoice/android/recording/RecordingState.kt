package com.srizonvoice.android.recording

/** Sealed states observed by the bubble, tile, and tracer-bullet UI. */
sealed interface RecordingState {
    data object Idle : RecordingState
    data class Recording(val visualLevel: Float) : RecordingState
    data object Transcribing : RecordingState
    data class Error(val message: String) : RecordingState
}

/** One-shot transcript event published when a recording succeeds. */
data class TranscriptEvent(val text: String, val sourcePackage: String?)
