package com.srizonvoice.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.srizonvoice.android.data.DEFAULT_CUSTOM_PROMPT
import com.srizonvoice.android.data.LanguageOption
import com.srizonvoice.android.data.RecordingMode
import com.srizonvoice.android.data.TranscriptionOutputMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "srizon_settings")

/**
 * Settings keys mirror macOS `Sources/SrizonVoice/Models.swift:368-381` so the two
 * apps stay conceptually aligned. API keys live in `SecureKeyStore`.
 */
class SettingsRepository(private val context: Context) {

    val state: Flow<SettingsState> = context.dataStore.data.map { prefs ->
        SettingsState(
            dictationOutputMode = normalizeDictationOutputMode(
                TranscriptionOutputMode.fromRaw(prefs[Keys.DICTATION_OUTPUT_MODE]),
            ),
            customPrompt = (prefs[Keys.CUSTOM_PROMPT] ?: DEFAULT_CUSTOM_PROMPT)
                .ifBlank { DEFAULT_CUSTOM_PROMPT },
            showTranslateButton = prefs[Keys.SHOW_TRANSLATE_BUTTON]
                ?: prefs[Keys.LEGACY_SHOW_TRANSLATE_BUTTON]
                ?: false,
            translationOutputMode = readTranslationOutputMode(prefs),
            translationLanguage = LanguageOption.fromCode(
                prefs[Keys.TRANSLATION_LANGUAGE]
                    ?: prefs[Keys.LEGACY_TRANSLATION_LANGUAGE]
                    ?: prefs[Keys.LEGACY_TARGET_LANGUAGE],
            ),
            recordingMode = RecordingMode.fromRaw(prefs[Keys.RECORDING_MODE]),
            handsfreeMaxSeconds = readHandsfreeMaxSeconds(prefs),
            bubbleOpacity = (prefs[Keys.BUBBLE_OPACITY] ?: DEFAULT_BUBBLE_OPACITY).coerceIn(MIN_OPACITY, 1f),
            showBubbleOnlyWhenKeyboard = prefs[Keys.SHOW_BUBBLE_ONLY_WHEN_KEYBOARD] ?: true,
            bubbleX = prefs[Keys.BUBBLE_X] ?: -1,
            bubbleY = prefs[Keys.BUBBLE_Y] ?: -1,
            onboardingComplete = prefs[Keys.ONBOARDING_COMPLETE] ?: false,
            setupBannerDismissed = prefs[Keys.SETUP_BANNER_DISMISSED] ?: false,
        )
    }

    suspend fun current(): SettingsState = state.first()

    suspend fun setDictationOutputMode(mode: TranscriptionOutputMode) = edit {
        it[Keys.DICTATION_OUTPUT_MODE] = normalizeDictationOutputMode(mode).rawValue
    }

    suspend fun setCustomPrompt(prompt: String) = edit {
        it[Keys.CUSTOM_PROMPT] = prompt.ifBlank { DEFAULT_CUSTOM_PROMPT }
    }

    suspend fun setShowTranslateButton(show: Boolean) = edit {
        it[Keys.SHOW_TRANSLATE_BUTTON] = show
    }

    suspend fun setTranslationOutputMode(mode: TranscriptionOutputMode) = edit {
        it[Keys.TRANSLATION_OUTPUT_MODE] = normalizeTranslationOutputMode(mode).rawValue
    }

    suspend fun setTranslationLanguage(language: LanguageOption) = edit {
        it[Keys.TRANSLATION_LANGUAGE] = language.code
    }

    suspend fun setRecordingMode(mode: RecordingMode) = edit {
        it[Keys.RECORDING_MODE] = mode.rawValue
    }

    suspend fun setHandsfreeMaxSeconds(seconds: Int) = edit {
        it[Keys.HANDSFREE_MAX_SECONDS] = clampHandsfreeSeconds(seconds)
    }

    suspend fun setBubbleOpacity(opacity: Float) = edit {
        it[Keys.BUBBLE_OPACITY] = opacity.coerceIn(MIN_OPACITY, 1f)
    }

    suspend fun setShowBubbleOnlyWhenKeyboard(only: Boolean) = edit {
        it[Keys.SHOW_BUBBLE_ONLY_WHEN_KEYBOARD] = only
    }

    suspend fun setBubblePosition(x: Int, y: Int) = edit {
        it[Keys.BUBBLE_X] = x
        it[Keys.BUBBLE_Y] = y
    }

    suspend fun setOnboardingComplete(complete: Boolean) = edit {
        it[Keys.ONBOARDING_COMPLETE] = complete
    }

    suspend fun setSetupBannerDismissed(dismissed: Boolean) = edit {
        it[Keys.SETUP_BANNER_DISMISSED] = dismissed
    }

    private suspend fun edit(transform: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { prefs -> transform(prefs) }
    }

    private fun readHandsfreeMaxSeconds(prefs: Preferences): Int {
        val seconds = prefs[Keys.HANDSFREE_MAX_SECONDS]
        if (seconds != null) return clampHandsfreeSeconds(seconds)
        val legacyMinutes = prefs[Keys.LEGACY_HANDSFREE_MAX_MINUTES]
        if (legacyMinutes != null) return clampHandsfreeSeconds(legacyMinutes * 60)
        return DEFAULT_HANDSFREE_SECONDS
    }

    private fun readTranslationOutputMode(prefs: Preferences): TranscriptionOutputMode {
        val saved = prefs[Keys.TRANSLATION_OUTPUT_MODE]
        if (saved != null) return normalizeTranslationOutputMode(TranscriptionOutputMode.fromRaw(saved))
        return if (prefs[Keys.LEGACY_TRANSLATION_INCLUDE_SOURCE] == true) {
            TranscriptionOutputMode.ORIGINAL_AND_TRANSLATION
        } else {
            TranscriptionOutputMode.TRANSLATED
        }
    }

    private fun normalizeTranslationOutputMode(mode: TranscriptionOutputMode): TranscriptionOutputMode =
        if (mode.requiresTargetLanguage) mode else TranscriptionOutputMode.TRANSLATED

    private fun normalizeDictationOutputMode(mode: TranscriptionOutputMode): TranscriptionOutputMode =
        if (mode.requiresTargetLanguage) TranscriptionOutputMode.CORRECTED else mode

    private object Keys {
        val DICTATION_OUTPUT_MODE = stringPreferencesKey("dictation.outputMode")
        val CUSTOM_PROMPT = stringPreferencesKey("dictation.customPrompt")
        val SHOW_TRANSLATE_BUTTON = booleanPreferencesKey("bubble.showTranslateButton")
        val LEGACY_SHOW_TRANSLATE_BUTTON = booleanPreferencesKey("llm.translationEnabled")
        val TRANSLATION_OUTPUT_MODE = stringPreferencesKey("translation.outputMode")
        val TRANSLATION_LANGUAGE = stringPreferencesKey("translation.language")
        val LEGACY_TRANSLATION_LANGUAGE = stringPreferencesKey("dictation.translationLanguage")
        val LEGACY_TARGET_LANGUAGE = stringPreferencesKey("llm.targetLanguage")
        val LEGACY_TRANSLATION_INCLUDE_SOURCE = booleanPreferencesKey("llm.translationIncludeSource")
        val RECORDING_MODE = stringPreferencesKey("app.recordingMode")
        val HANDSFREE_MAX_SECONDS = intPreferencesKey("app.handsfreeMaxSeconds")
        val LEGACY_HANDSFREE_MAX_MINUTES = intPreferencesKey("app.handsfreeMaxMinutes")
        val BUBBLE_OPACITY = floatPreferencesKey("bubble.opacity")
        val SHOW_BUBBLE_ONLY_WHEN_KEYBOARD = booleanPreferencesKey("bubble.showOnlyWhenKeyboard")
        val BUBBLE_X = intPreferencesKey("bubble.x")
        val BUBBLE_Y = intPreferencesKey("bubble.y")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("app.onboardingComplete")
        val SETUP_BANNER_DISMISSED = booleanPreferencesKey("app.setupBannerDismissed")
    }

    companion object {
        const val MIN_HANDSFREE_SECONDS = 30
        const val MAX_HANDSFREE_SECONDS = 7 * 60
        const val DEFAULT_HANDSFREE_SECONDS = 60
        const val DEFAULT_BUBBLE_OPACITY = 0.7f
        const val MIN_OPACITY = 0.1f

        fun clampHandsfreeSeconds(seconds: Int): Int =
            seconds.coerceIn(MIN_HANDSFREE_SECONDS, MAX_HANDSFREE_SECONDS)
    }
}

data class SettingsState(
    val dictationOutputMode: TranscriptionOutputMode,
    val customPrompt: String,
    val showTranslateButton: Boolean,
    val translationOutputMode: TranscriptionOutputMode,
    val translationLanguage: LanguageOption,
    val recordingMode: RecordingMode,
    val handsfreeMaxSeconds: Int,
    val bubbleOpacity: Float,
    val showBubbleOnlyWhenKeyboard: Boolean,
    /** Persisted bubble window position. -1 means "never set, use default". */
    val bubbleX: Int,
    val bubbleY: Int,
    val onboardingComplete: Boolean,
    val setupBannerDismissed: Boolean,
)
