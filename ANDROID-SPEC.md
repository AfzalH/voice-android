# SrizonVoice for Android — Feature Spec / Handoff

SrizonVoice for Android is a system-wide dictation app modeled on the macOS 3.0 app. The user triggers recording from a floating bubble or Quick Settings tile, speaks, stops recording, and Gemini returns the final text for insertion at the current cursor. The app is BYOK: audio is sent directly to Gemini with the user's API key.

---

## 1. Core Flow

```
User taps the floating bubble or Quick Settings tile
  → Audio capture starts (16 kHz mono PCM)
  → Floating recording UI shows live waveform

User taps Done / taps tile again / hits auto-stop
  → Audio capture stops
  → PCM is wrapped into WAV in memory
  → WAV is sent to Gemini with the selected dictation-output prompt
  → Gemini returns final dictation text
  → Text is inserted at the cursor via Accessibility, with clipboard fallback

User taps Translate on the bubble
  → Audio capture stops
  → WAV is sent to Gemini with the selected translation-output prompt
  → Gemini returns translated text
  → Text is inserted at the cursor

User cancels
  → Recording is dropped, no API call
```

---

## 2. Output Modes

Android uses two output setting groups. **Dictation output** controls Done, push-to-talk release, auto-stop, and the Quick Settings tile. **Translation output** controls only the optional Translate button on the floating bubble.

Dictation output mirrors macOS `TranscriptionOutputMode`:

| Mode | Behavior |
|---|---|
| As is | Transcribes without intentional correction or translation |
| Correct things | Removes filler sounds, hesitations, repetitions, false starts, mumbling artifacts, and returns clean sentences |
| Custom prompt | Uses the saved custom instruction from Settings |

Translation output is intentionally limited to translation modes:

| Mode | Behavior |
|---|---|
| Translate to target language | Outputs only the target-language translation |
| Original + target translation | Outputs `original - translation`, one utterance per line |

Gemini detects the spoken source language from audio. There is no spoken-language selector or transcription model selector.

---

## 3. Trigger Surfaces

The primary trigger is a draggable floating bubble hosted by a foreground service using `TYPE_APPLICATION_OVERLAY`. Handsfree mode is the default: tap once to start recording, tap Done for dictation output, or tap Translate for translation output when the button is enabled. Push-to-talk remains available: press and hold to record, release to transcribe, or drag to the cancel zone to discard.

The Quick Settings tile toggles the same shared recording coordinator. It is useful when the bubble is hidden or not yet positioned.

---

## 4. Text Insertion

The Accessibility service subscribes to transcript events and inserts text into the focused field. It first tries editable-node insertion, then falls back to clipboard paste. If no editable target is available, the insertion layer should copy the transcript to clipboard and surface an error/notification.

---

## 5. Audio Capture

Audio is captured with `AudioRecord` using `MediaRecorder.AudioSource.VOICE_RECOGNITION`, 16 kHz, 16-bit PCM, mono. RMS is computed per chunk for the live waveform. Empty or silence-only recordings are dropped without an API call.

Before upload, raw PCM is wrapped with a 44-byte WAV header matching the macOS encoder.

---

## 6. Gemini API Integration

Normal recordings are sent inline to:

```
POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent
x-goog-api-key: {apiKey}
Content-Type: application/json
```

The request includes:

| Field | Value |
|---|---|
| `contents.parts[].inline_data.mime_type` | `audio/wav` |
| `contents.parts[].inline_data.data` | Base64 WAV bytes |
| `contents.parts[].text` | Prompt for the selected output mode |
| `generation_config.temperature` | `0` |

For WAV payloads larger than 14 MiB, upload through Gemini's resumable Files API first, then reference the returned `file_uri` with `file_data` in `generateContent`.

Validation uses:

```
GET https://generativelanguage.googleapis.com/v1beta/models
x-goog-api-key: {apiKey}
```

A 2xx response means the key is accepted.

---

## 7. Settings

General settings use DataStore. The Gemini API key uses EncryptedSharedPreferences.

| Key | Type | Default |
|---|---|---|
| `gemini.apiKey` | String, encrypted | `""` |
| `dictation.outputMode` | Enum | `corrected` |
| `dictation.customPrompt` | String | Correct things prompt |
| `bubble.showTranslateButton` | Boolean | `false` |
| `translation.outputMode` | Enum | `translated` |
| `translation.language` | ISO code | `en` |
| `app.recordingMode` | Enum | `handsfree` |
| `app.handsfreeMaxSeconds` | Int | `60` |
| `bubble.opacity` | Float | `0.7` |
| `bubble.showOnlyWhenKeyboard` | Boolean | `true` |

Legacy `app.handsfreeMaxMinutes`, `llm.translationEnabled`, `dictation.translationLanguage`, `llm.targetLanguage`, and `llm.translationIncludeSource` are read for migration if the new keys are absent.

---

## 8. Permissions

| Permission | Purpose |
|---|---|
| Microphone | Capture user speech |
| Foreground service microphone | Keep capture legal while the app is backgrounded |
| Display over other apps | Show the floating bubble |
| Accessibility service | Insert text into the foreground app |
| Internet | Call Gemini directly |

---

## 9. Out of Scope

- On-device transcription.
- Live streaming transcription.
- IME-based insertion.
- Recording history.
- Per-app overrides.
- Wear OS / Android Auto integrations.
