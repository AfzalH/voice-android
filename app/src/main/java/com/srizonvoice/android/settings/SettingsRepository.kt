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
import com.srizonvoice.android.data.DEFAULT_POST_PROCESSING_PROMPT
import com.srizonvoice.android.data.LanguageOption
import com.srizonvoice.android.data.RecordingMode
import com.srizonvoice.android.data.TranscriptionModel
import com.srizonvoice.android.data.TriggerMode
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
            language = LanguageOption.fromCode(prefs[Keys.LANGUAGE]),
            recentLanguages = decodeRecent(prefs[Keys.RECENT_LANGUAGES]),
            transcriptionModel = TranscriptionModel.fromId(prefs[Keys.TRANSCRIPTION_MODEL]),
            postProcessingEnabled = prefs[Keys.POST_PROCESSING_ENABLED] ?: true,
            useGemini = prefs[Keys.USE_GEMINI] ?: false,
            postProcessingPrompt = prefs[Keys.POST_PROCESSING_PROMPT] ?: DEFAULT_POST_PROCESSING_PROMPT,
            translationEnabled = prefs[Keys.TRANSLATION_ENABLED] ?: false,
            targetLanguage = LanguageOption.fromCode(prefs[Keys.TARGET_LANGUAGE]),
            recentTargetLanguages = decodeRecent(prefs[Keys.RECENT_TARGET_LANGUAGES]),
            translationIncludeSource = prefs[Keys.TRANSLATION_INCLUDE_SOURCE] ?: false,
            recordingMode = RecordingMode.fromRaw(prefs[Keys.RECORDING_MODE]),
            handsfreeMaxMinutes = prefs[Keys.HANDSFREE_MAX_MINUTES] ?: 1,
            triggerMode = TriggerMode.fromRaw(prefs[Keys.TRIGGER_MODE]),
            bubbleOpacity = (prefs[Keys.BUBBLE_OPACITY] ?: DEFAULT_BUBBLE_OPACITY).coerceIn(MIN_OPACITY, 1f),
            showBubbleOnlyWhenKeyboard = prefs[Keys.SHOW_BUBBLE_ONLY_WHEN_KEYBOARD] ?: true,
            bubbleX = prefs[Keys.BUBBLE_X] ?: -1,
            bubbleY = prefs[Keys.BUBBLE_Y] ?: -1,
            onboardingComplete = prefs[Keys.ONBOARDING_COMPLETE] ?: false,
            setupBannerDismissed = prefs[Keys.SETUP_BANNER_DISMISSED] ?: false,
        )
    }

    suspend fun current(): SettingsState = state.first()

    suspend fun setLanguage(language: LanguageOption) = edit {
        // Stack semantics from macOS `AppModel.swift:85-96`: drop the new language
        // from the recent list, prepend the previous one, cap at 3.
        val previous = LanguageOption.fromCode(it[Keys.LANGUAGE])
        val recent = decodeRecent(it[Keys.RECENT_LANGUAGES]).filterNot { lang -> lang == language }
        val updated = (listOf(previous) + recent)
            .filterNot { lang -> lang == language }
            .take(MAX_RECENT)
        it[Keys.LANGUAGE] = language.code
        it[Keys.RECENT_LANGUAGES] = encodeRecent(updated)
    }

    suspend fun setTranscriptionModel(model: TranscriptionModel) = edit {
        it[Keys.TRANSCRIPTION_MODEL] = model.id
    }

    suspend fun setPostProcessingEnabled(enabled: Boolean) = edit {
        it[Keys.POST_PROCESSING_ENABLED] = enabled
    }

    suspend fun setUseGemini(enabled: Boolean) = edit {
        it[Keys.USE_GEMINI] = enabled
    }

    suspend fun setPostProcessingPrompt(prompt: String) = edit {
        it[Keys.POST_PROCESSING_PROMPT] = prompt
    }

    suspend fun setTranslationEnabled(enabled: Boolean) = edit {
        it[Keys.TRANSLATION_ENABLED] = enabled
    }

    suspend fun setTranslationIncludeSource(include: Boolean) = edit {
        it[Keys.TRANSLATION_INCLUDE_SOURCE] = include
    }

    suspend fun setTargetLanguage(language: LanguageOption) = edit {
        // Stack semantics: drop the new pick, prepend the previous selection,
        // cap at MAX_RECENT — same shape as `setLanguage` for the dictation list.
        val previous = LanguageOption.fromCode(it[Keys.TARGET_LANGUAGE])
        val recent = decodeRecent(it[Keys.RECENT_TARGET_LANGUAGES]).filterNot { lang -> lang == language }
        val updated = (listOf(previous) + recent)
            .filterNot { lang -> lang == language }
            .take(MAX_RECENT)
        it[Keys.TARGET_LANGUAGE] = language.code
        it[Keys.RECENT_TARGET_LANGUAGES] = encodeRecent(updated)
    }

    suspend fun setRecordingMode(mode: RecordingMode) = edit {
        it[Keys.RECORDING_MODE] = mode.rawValue
    }

    suspend fun setHandsfreeMaxMinutes(minutes: Int) = edit {
        it[Keys.HANDSFREE_MAX_MINUTES] = minutes.coerceAtLeast(1)
    }

    suspend fun setTriggerMode(mode: TriggerMode) = edit {
        it[Keys.TRIGGER_MODE] = mode.rawValue
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

    private fun decodeRecent(raw: String?): List<LanguageOption> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",")
            .filter { it.isNotBlank() }
            .map { LanguageOption.fromCode(it) }
            .distinct()
            .take(MAX_RECENT)
    }

    private fun encodeRecent(list: List<LanguageOption>): String =
        list.joinToString(",") { it.code }

    private object Keys {
        val LANGUAGE = stringPreferencesKey("dictation.language")
        val RECENT_LANGUAGES = stringPreferencesKey("dictation.recentLanguages")
        val TRANSCRIPTION_MODEL = stringPreferencesKey("groq.transcriptionModel")
        val POST_PROCESSING_ENABLED = booleanPreferencesKey("llm.postProcessingEnabled")
        val USE_GEMINI = booleanPreferencesKey("llm.useGemini")
        val POST_PROCESSING_PROMPT = stringPreferencesKey("llm.postProcessingSystemPrompt")
        val TRANSLATION_ENABLED = booleanPreferencesKey("llm.translationEnabled")
        val TARGET_LANGUAGE = stringPreferencesKey("llm.targetLanguage")
        val RECENT_TARGET_LANGUAGES = stringPreferencesKey("llm.recentTargetLanguages")
        val TRANSLATION_INCLUDE_SOURCE = booleanPreferencesKey("llm.translationIncludeSource")
        val RECORDING_MODE = stringPreferencesKey("app.recordingMode")
        val HANDSFREE_MAX_MINUTES = intPreferencesKey("app.handsfreeMaxMinutes")
        val TRIGGER_MODE = stringPreferencesKey("trigger.mode")
        val BUBBLE_OPACITY = floatPreferencesKey("bubble.opacity")
        val SHOW_BUBBLE_ONLY_WHEN_KEYBOARD = booleanPreferencesKey("bubble.showOnlyWhenKeyboard")
        val BUBBLE_X = intPreferencesKey("bubble.x")
        val BUBBLE_Y = intPreferencesKey("bubble.y")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("app.onboardingComplete")
        val SETUP_BANNER_DISMISSED = booleanPreferencesKey("app.setupBannerDismissed")
    }

    companion object {
        const val MAX_RECENT = 3
        const val DEFAULT_BUBBLE_OPACITY = 0.7f
        const val MIN_OPACITY = 0.1f
    }
}

data class SettingsState(
    val language: LanguageOption,
    val recentLanguages: List<LanguageOption>,
    val transcriptionModel: TranscriptionModel,
    val postProcessingEnabled: Boolean,
    val useGemini: Boolean,
    val postProcessingPrompt: String,
    val translationEnabled: Boolean,
    val targetLanguage: LanguageOption,
    val recentTargetLanguages: List<LanguageOption>,
    val translationIncludeSource: Boolean,
    val recordingMode: RecordingMode,
    val handsfreeMaxMinutes: Int,
    val triggerMode: TriggerMode,
    val bubbleOpacity: Float,
    val showBubbleOnlyWhenKeyboard: Boolean,
    /** Persisted bubble window position. -1 means "never set, use default". */
    val bubbleX: Int,
    val bubbleY: Int,
    val onboardingComplete: Boolean,
    val setupBannerDismissed: Boolean,
)
