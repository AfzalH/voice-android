package com.srizonvoice.android.api

import com.srizonvoice.android.util.DictationError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Gemini post-processing client. Mirrors `LLMClient.postProcessWithGemini` in
 * `Sources/SrizonVoice/Services.swift:494-546`.
 *
 * Default model is `gemini-3.1-flash-lite-preview` (Services.swift:492). Falls back
 * to a stable model on 404 since the preview tier rotates.
 */
class GeminiClient(private val http: OkHttpClient = HttpClients.shared) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun cleanup(
        apiKey: String,
        systemPrompt: String,
        transcript: String,
    ): String {
        return runCatching { request(apiKey, PRIMARY_MODEL, systemPrompt, transcript) }
            .recoverCatching { error ->
                if (error is DictationError.ServerError && error.body.contains("\"code\": 404")) {
                    request(apiKey, FALLBACK_MODEL, systemPrompt, transcript)
                } else throw error
            }
            .getOrThrow()
    }

    suspend fun validateKey(apiKey: String): Boolean = withContext(Dispatchers.IO) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models".toHttpUrl()
            .newBuilder()
            .addQueryParameter("key", apiKey)
            .build()
        val request = Request.Builder().url(url).get().build()
        try {
            http.newCall(request).awaitResponse().use { response -> response.isSuccessful }
        } catch (_: IOException) {
            false
        }
    }

    private suspend fun request(
        apiKey: String,
        model: String,
        systemPrompt: String,
        transcript: String,
    ): String = withContext(Dispatchers.IO) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("key", apiKey)
            .build()

        val payload = buildJsonObject {
            put("system_instruction", buildJsonObject {
                put("parts", buildJsonArray {
                    addJsonObject { put("text", systemPrompt) }
                })
            })
            put("contents", buildJsonArray {
                addJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        addJsonObject { put("text", transcript) }
                    })
                }
            })
            put("generationConfig", buildJsonObject { put("temperature", 0.3) })
        }.toString()

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).awaitResponse().use { response ->
            val raw = response.body?.string().orEmpty()
            if (response.code in setOf(400, 401, 403)) {
                if (raw.contains("API_KEY_INVALID") || raw.contains("PERMISSION_DENIED")) {
                    throw DictationError.InvalidApiKey
                }
                throw DictationError.ServerError(raw.ifBlank { "Gemini post-processing failed." })
            }
            if (!response.isSuccessful) {
                throw DictationError.ServerError(raw.ifBlank { "Gemini post-processing failed." })
            }

            val text = runCatching {
                val root = json.parseToJsonElement(raw).jsonObject
                val candidates = root["candidates"]?.jsonArray ?: return@runCatching null
                val first = candidates.firstOrNull()?.jsonObject ?: return@runCatching null
                val parts = first["content"]?.jsonObject?.get("parts")?.jsonArray ?: return@runCatching null
                parts.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            }.getOrNull()

            text ?: throw DictationError.ServerError(raw.ifBlank { "Gemini post-processing failed." })
        }
    }

    private companion object {
        const val PRIMARY_MODEL = "gemini-3.1-flash-lite-preview"
        const val FALLBACK_MODEL = "gemini-1.5-flash"
    }
}
