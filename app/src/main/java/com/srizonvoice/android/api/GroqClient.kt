package com.srizonvoice.android.api

import com.srizonvoice.android.data.TranscriptionModel
import com.srizonvoice.android.util.DictationError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Groq Whisper transcription + post-processing client.
 *
 * Mirrors `GroqTranscriptionClient` and `LLMClient.postProcessWithGroq` in
 * `Sources/SrizonVoice/Services.swift`. Validation rule (non-401/403 = OK)
 * matches Services.swift:366-378. Multipart fields match :403-405.
 */
class GroqClient(private val http: OkHttpClient = HttpClients.shared) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun transcribe(
        apiKey: String,
        wav: ByteArray,
        model: TranscriptionModel,
        languageCode: String,
    ): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "recording.wav",
                wav.toRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", model.id)
            .addFormDataPart("language", languageCode)
            .addFormDataPart("response_format", "json")
            .build()

        val request = Request.Builder()
            .url(WHISPER_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        http.newCall(request).awaitResponse().use { response ->
            if (response.code == 401 || response.code == 403) throw DictationError.InvalidApiKey
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw DictationError.ServerError(raw.ifBlank { "Transcription failed." })
            }
            val parsed = runCatching { json.parseToJsonElement(raw).jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                .getOrNull()
            parsed ?: throw DictationError.ServerError(raw.ifBlank { "Transcription failed." })
        }
    }

    suspend fun postProcessChat(
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        transcript: String,
    ): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", modelId)
            put("temperature", 0.3)
            put("messages", buildJsonArray {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", transcript)
                }
            })
        }.toString()
        val request = Request.Builder()
            .url(CHAT_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).awaitResponse().use { response ->
            if (response.code == 401 || response.code == 403) throw DictationError.InvalidApiKey
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw DictationError.ServerError(raw.ifBlank { "Post-processing failed." })
            val content = runCatching {
                val root = json.parseToJsonElement(raw).jsonObject
                val choices = root["choices"]?.jsonArray ?: return@runCatching null
                val first = choices.firstOrNull()?.jsonObject ?: return@runCatching null
                first["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            content ?: throw DictationError.ServerError(raw.ifBlank { "Post-processing failed." })
        }
    }

    /** Returns true unless Groq responds 401/403 — same rule as macOS `validateAPIKey`. */
    suspend fun validateKey(apiKey: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(MODELS_URL)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        try {
            http.newCall(request).awaitResponse().use { response ->
                response.code != 401 && response.code != 403
            }
        } catch (_: IOException) {
            false
        }
    }

    private companion object {
        const val WHISPER_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
        const val MODELS_URL = "https://api.groq.com/openai/v1/models"
    }
}

internal suspend fun Call.awaitResponse(): Response =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) = cont.resume(response)
        })
    }
