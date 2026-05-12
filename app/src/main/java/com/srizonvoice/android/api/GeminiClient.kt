package com.srizonvoice.android.api

import android.util.Base64
import com.srizonvoice.android.data.DEFAULT_CUSTOM_PROMPT
import com.srizonvoice.android.data.LanguageOption
import com.srizonvoice.android.data.TranscriptionOutputMode
import com.srizonvoice.android.util.DictationError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Gemini audio transcription client matching the macOS 3.0 pipeline. */
class GeminiClient(private val http: OkHttpClient = HttpClients.shared) {

    private data class UploadedFile(val uri: String, val mimeType: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun transcribe(
        apiKey: String,
        wav: ByteArray,
        outputMode: TranscriptionOutputMode,
        customPrompt: String,
        targetLanguage: LanguageOption,
        targetAppName: String?,
    ): String = withContext(Dispatchers.IO) {
        val prompt = buildPrompt(outputMode, customPrompt, targetLanguage, targetAppName)
        val audioPart = if (wav.size <= INLINE_AUDIO_LIMIT_BYTES) {
            buildJsonObject {
                put(
                    "inline_data",
                    buildJsonObject {
                        put("mime_type", "audio/wav")
                        put("data", Base64.encodeToString(wav, Base64.NO_WRAP))
                    },
                )
            }
        } else {
            val uploaded = uploadAudio(apiKey, wav)
            buildJsonObject {
                put(
                    "file_data",
                    buildJsonObject {
                        put("mime_type", uploaded.mimeType)
                        put("file_uri", uploaded.uri)
                    },
                )
            }
        }

        generateContent(apiKey, prompt, audioPart)
    }

    suspend fun validateKey(apiKey: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(MODELS_URL)
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        try {
            http.newCall(request).awaitResponse().use { response -> response.isSuccessful }
        } catch (_: IOException) {
            false
        }
    }

    private suspend fun generateContent(
        apiKey: String,
        prompt: String,
        audioPart: kotlinx.serialization.json.JsonObject,
    ): String {
        val requestBody = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    addJsonObject {
                        put("role", "user")
                        put(
                            "parts",
                            buildJsonArray {
                                add(audioPart)
                                addJsonObject { put("text", prompt) }
                            },
                        )
                    }
                },
            )
            put(
                "generation_config",
                buildJsonObject {
                    put("temperature", 0)
                },
            )
        }.toString()

        val request = Request.Builder()
            .url(GENERATE_CONTENT_URL)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).awaitResponse().use { response ->
            val raw = response.body?.string().orEmpty()
            handleGeminiErrorIfNeeded(response.code, raw)

            val text = runCatching {
                val root = json.parseToJsonElement(raw).jsonObject
                val candidates = root["candidates"]?.jsonArray ?: return@runCatching null
                val first = candidates.firstOrNull()?.jsonObject ?: return@runCatching null
                val parts = first["content"]?.jsonObject?.get("parts")?.jsonArray ?: return@runCatching null
                parts.firstNotNullOfOrNull { part ->
                    part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                }
            }.getOrNull()

            return text ?: throw DictationError.ServerError(raw.ifBlank { "Transcription failed." })
        }
    }

    private suspend fun uploadAudio(apiKey: String, wav: ByteArray): UploadedFile {
        val startBody = buildJsonObject {
            put(
                "file",
                buildJsonObject {
                    put("display_name", "SrizonVoice recording")
                },
            )
        }.toString()

        val startRequest = Request.Builder()
            .url(UPLOAD_URL)
            .header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", wav.size.toString())
            .header("X-Goog-Upload-Header-Content-Type", "audio/wav")
            .header("Content-Type", "application/json")
            .post(startBody.toRequestBody("application/json".toMediaType()))
            .build()

        val uploadUrl = http.newCall(startRequest).awaitResponse().use { response ->
            val raw = response.body?.string().orEmpty()
            handleGeminiErrorIfNeeded(response.code, raw)
            response.headers.names()
                .firstOrNull { it.equals("x-goog-upload-url", ignoreCase = true) }
                ?.let { response.header(it) }
                ?: throw DictationError.ServerError("Gemini did not return an upload URL.")
        }

        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .header("Content-Length", wav.size.toString())
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(wav.toRequestBody("audio/wav".toMediaType()))
            .build()

        http.newCall(uploadRequest).awaitResponse().use { response ->
            val raw = response.body?.string().orEmpty()
            handleGeminiErrorIfNeeded(response.code, raw)

            val file = runCatching {
                json.parseToJsonElement(raw).jsonObject["file"]?.jsonObject
            }.getOrNull()
            val uri = file?.get("uri")?.jsonPrimitive?.contentOrNull
            if (uri.isNullOrBlank()) {
                throw DictationError.ServerError(raw.ifBlank { "Gemini file upload failed." })
            }
            val mimeType = file["mimeType"]?.jsonPrimitive?.contentOrNull
                ?: file["mime_type"]?.jsonPrimitive?.contentOrNull
                ?: "audio/wav"
            return UploadedFile(uri = uri, mimeType = mimeType)
        }
    }

    private fun handleGeminiErrorIfNeeded(statusCode: Int, raw: String) {
        if (statusCode in 200..299) return
        if (statusCode in setOf(400, 401, 403) &&
            (raw.contains("API_KEY_INVALID") || raw.contains("PERMISSION_DENIED"))
        ) {
            throw DictationError.InvalidApiKey
        }
        throw DictationError.ServerError(raw.ifBlank { "Gemini transcription failed." })
    }

    private fun buildPrompt(
        outputMode: TranscriptionOutputMode,
        customPrompt: String,
        targetLanguage: LanguageOption,
        targetAppName: String?,
    ): String {
        val appContext = targetAppName?.let { " for insertion into $it" }.orEmpty()
        val base = """
            You are transcribing dictation audio$appContext.
            Return only the final text. Do not include labels, Markdown, timestamps, explanations, or surrounding quotes.
            Do not answer questions in the audio; transcribe or transform the dictated words only.
        """.trimIndent()

        return when (outputMode) {
            TranscriptionOutputMode.AS_IS ->
                "$base\nTranscribe the speech as-is. Preserve wording, filler words, casing, and punctuation as closely as possible. Do not correct grammar and do not translate."

            TranscriptionOutputMode.CORRECTED ->
                "$base\n$DEFAULT_CUSTOM_PROMPT"

            TranscriptionOutputMode.CUSTOM_PROMPT ->
                "$base\n${customPrompt.ifBlank { DEFAULT_CUSTOM_PROMPT }}"

            TranscriptionOutputMode.TRANSLATED ->
                "$base\nTranscribe the speech, then output only the translation in ${targetLanguage.plainName} (${targetLanguage.code})."

            TranscriptionOutputMode.ORIGINAL_AND_TRANSLATION ->
                "$base\nTranscribe the speech and translate it to ${targetLanguage.plainName} (${targetLanguage.code}). Output each utterance as: original - translation. Use one line per utterance and use a plain hyphen separator."
        }
    }

    private companion object {
        const val MODEL = "gemini-3.1-flash-lite"
        const val INLINE_AUDIO_LIMIT_BYTES = 14 * 1024 * 1024
        const val MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val UPLOAD_URL = "https://generativelanguage.googleapis.com/upload/v1beta/files"
        const val GENERATE_CONTENT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    }
}
