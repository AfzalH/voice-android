package com.srizonvoice.android.data

/** Two interaction modes ported from macOS `RecordingMode`.
 *
 * Default is [HANDSFREE]: tap to start, tap again to stop. Better for long
 * dictations than push-to-talk on a phone, where holding a finger on a tiny
 * floating bubble for 30+ seconds gets fatiguing. */
enum class RecordingMode(val rawValue: String, val displayName: String) {
    HANDSFREE("handsfree", "Handsfree"),
    PUSH_TO_TALK("pushToTalk", "Push to Talk"),
    ;

    companion object {
        fun fromRaw(value: String?): RecordingMode =
            entries.firstOrNull { it.rawValue == value } ?: HANDSFREE
    }
}

/** Gemini output behavior, matching macOS `TranscriptionOutputMode`. */
enum class TranscriptionOutputMode(val rawValue: String, val displayName: String) {
    AS_IS("asIs", "As is"),
    CORRECTED("corrected", "Correct things"),
    CUSTOM_PROMPT("customPrompt", "Custom prompt"),
    TRANSLATED("translated", "Translate to target language"),
    ORIGINAL_AND_TRANSLATION("originalAndTranslation", "Original + target translation"),
    ;

    val requiresTargetLanguage: Boolean
        get() = this == TRANSLATED || this == ORIGINAL_AND_TRANSLATION

    companion object {
        fun fromRaw(value: String?): TranscriptionOutputMode =
            entries.firstOrNull { it.rawValue == value } ?: CORRECTED
    }
}

const val DEFAULT_CUSTOM_PROMPT: String =
    "Transcribe the speech into clean, grammatically correct sentences. " +
        "Remove filler sounds and hesitation words such as um, uh, ah, er, hmm, like, and you know when they do not add meaning. " +
        "Remove stutters, repeated words, false starts, mumbling artifacts, and partial phrases. " +
        "Correct obvious transcription errors, grammar, punctuation, capitalization, and formatting. " +
        "Preserve the speaker's intended meaning, language, and tone. " +
        "Return only polished final sentences."
