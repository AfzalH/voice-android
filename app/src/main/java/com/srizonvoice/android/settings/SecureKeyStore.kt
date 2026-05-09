package com.srizonvoice.android.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * EncryptedSharedPreferences-backed store for API keys.
 * Mirrors the macOS choice to keep secrets out of plain UserDefaults.
 */
class SecureKeyStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var groqApiKey: String
        get() = prefs.getString(KEY_GROQ, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_GROQ, value).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_GEMINI, value).apply()

    fun hasGroqKey(): Boolean = groqApiKey.isNotBlank()
    fun hasGeminiKey(): Boolean = geminiApiKey.isNotBlank()

    private companion object {
        const val FILE = "srizon_secure_keys"
        const val KEY_GROQ = "groq.apiKey"
        const val KEY_GEMINI = "gemini.apiKey"
    }
}
