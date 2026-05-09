# SrizonVoice for Android — Feature Spec / Handoff

A push-to-talk dictation app for Android, modeled on the macOS app in this repo (`SrizonVoice`). Hold a button, speak, release → audio goes to Groq Whisper, transcript is inserted at the cursor in whatever app the user is in. Optional Gemini post-processing cleanup. BYOK (user supplies their own API key).

This document is the brief for a separate Android repo. The receiving agent should treat the macOS app as the reference for *behavior* but not for *architecture* — Android idioms are different and several macOS mechanisms (menu bar, global Fn-key hotkey, Cmd+V synthesis) have no direct equivalent.

---

## 1. Concept (one-liner)

Hold-to-talk system-wide voice dictation for Android: the user triggers recording from any app, speaks, releases, and the transcript appears at their text cursor. No on-device model — audio is sent to Groq's Whisper API.

---

## 2. Core User Flow

```
User triggers PTT (floating bubble / IME mic key / accessibility button)
  → Audio capture starts (16 kHz mono PCM)
  → Floating recording UI shows live waveform

User releases trigger
  → Audio capture stops
  → PCM → WAV in memory
  → POST to Groq /openai/v1/audio/transcriptions (multipart)
  → (optional) Gemini post-processing pass
  → Text inserted at cursor in the foreground app
  → Floating UI dismissed

User cancels (tap-and-drag away / back gesture / cancel button)
  → Recording dropped, no API call
```

Two interaction modes (both already exist on macOS, port both):
- **Push-to-talk**: hold trigger, release to transcribe.
- **Handsfree / toggle**: tap once to start, tap again (or auto-stop on silence) to transcribe.

---

## 3. Must-have Features (P0)

- BYOK Groq API key, validated by `GET /openai/v1/models` on save.
- Whisper model choice: `whisper-large-v3-turbo` (default, "Prefer Speed") and `whisper-large-v3` ("Prefer Accuracy").
- Push-to-talk recording with live audio level visualization.
- Multi-language support (~111 languages, ISO 639-1 codes). Recently-used languages list for quick switching (mirrors macOS v2.2.0).
- Cancel-without-transcribing gesture.
- Text insertion at the cursor in foreground apps (see §6 for strategy).
- Settings screen: API key, language, model, trigger configuration, post-processing toggle.
- Polished first-run onboarding flow that walks the user through every required permission (mic, overlay, accessibility, notifications, foreground-service mic), explains *why* each is needed, deep-links to the relevant system settings page, and live-verifies the grant state before letting the user advance. The user should never reach the main app in a half-configured state, and re-running onboarding from Settings should be possible.
- API key stored in `EncryptedSharedPreferences` or Android Keystore. Never plaintext.
- Empty / silence-only recording: silently no-op (no error, no API call).
- Error surfaces: invalid key, network failure, insertion failure — shown in-app and as transient notification.

---

## 4. Nice-to-have Features (P1)

- Gemini post-processing (mirrors macOS v2.1.0): after Whisper returns text, optionally pipe through Gemini with a "clean up filler words, fix punctuation, but don't change meaning" prompt. User can toggle on/off in Settings.
- Handsfree / toggle mode with VAD-based auto-stop (mirrors macOS v2.3.0).
- Recording history (last N transcripts, copy-to-clipboard).
- Per-app overrides: "always use clipboard fallback in this app" list.
- Quick Settings tile (drop-down panel) to start/stop dictation.
- Wear OS companion (out of scope for v1; mention so it's not designed out).

---

## 5. Android-Specific: How to Trigger Recording

**This is the biggest divergence from macOS.** Android has no system-wide keyboard hotkey API equivalent to Carbon's `RegisterEventHotKey`. The receiving agent must pick one or more trigger surfaces. Recommended priority:

### 5a. Floating bubble (primary, recommended for v1)

A draggable circular overlay button, similar to Facebook Messenger chat heads.

- Implemented via `WindowManager` with `TYPE_APPLICATION_OVERLAY` (API 26+).
- Requires `SYSTEM_ALERT_WINDOW` permission (user must grant via Settings → Apps → Special access → Display over other apps).
- Hosted by a foreground service so it survives app being swiped away.
- Push-to-talk: `ACTION_DOWN` starts recording, `ACTION_UP` stops. Drag the bubble to a "cancel" target to abort.

### 5b. Custom IME (Input Method Editor, recommended for power users)

A keyboard with a single big mic key. When the user is in any text field, they switch to this keyboard, hold the mic, speak, release. Transcript is inserted via `InputConnection.commitText()`.

- This is the **most reliable** text-insertion path on Android — no Accessibility Service needed, no clipboard hacks.
- Cost: user has to enable it in Settings → System → Languages & input → On-screen keyboard, and select it via the keyboard switcher when they want to dictate.
- Recommended as a **secondary** trigger alongside the bubble, not the only one.

### 5c. Accessibility Service shortcut (alternative)

Android lets users assign actions to the volume button, accessibility button (gesture nav), or a triple-tap. The app registers an `AccessibilityService` and offers itself as the action target.

- Pro: no overlay permission, no IME setup.
- Con: setup is buried in Accessibility settings; some OEM skins have inconsistent behavior; volume-key bindings can conflict with media apps.

### 5d. Quick Settings tile (always include)

A simple "tap to start dictation" tile in the pull-down Quick Settings panel. Cheap to implement; useful even alongside the bubble.

**Decision the user/receiving agent must make:** ship all of (a)+(b)+(d) for v1, or just (a)+(d). I'd recommend **(a) + (d)** for v1 and add (b) once the rest is solid — IMEs are a big enough surface to deserve their own focused pass.

---

## 6. Android-Specific: How to Insert Text at the Cursor

Two strategies, mirroring the macOS app's two paths but with different tech.

### 6a. Via Custom IME (best path when applicable)

If the user is dictating *while the SrizonVoice IME is active*, just call `InputConnection.commitText(transcript, 1)`. Done. No permissions beyond the IME being enabled.

This only works if the trigger comes through the IME (5b). Worth supporting because it's the only fully-reliable way.

### 6b. Via Accessibility Service (general path)

When the trigger is the floating bubble, the app does not "own" the focused text field. To insert text:

1. Run an `AccessibilityService`.
2. On transcript ready, find the focused node: `rootInActiveWindow.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)`.
3. Try `performAction(ACTION_SET_TEXT, bundle{ EXTRA_VALUE = transcript })` — replaces the field's entire contents (bad if there's existing text).
4. Better: get current text, compute cursor position, build new text with transcript inserted, set it, then move cursor via `ACTION_SET_SELECTION`.
5. Fallback: clipboard + `ACTION_PASTE`. Same shape as macOS Strategy 2:
   - Snapshot clipboard.
   - Write transcript to clipboard.
   - `performAction(ACTION_PASTE)` on focused node.
   - Restore clipboard ~500ms later.

Note: many apps (especially WebViews, games, Compose text fields on older versions) don't expose proper accessibility nodes. The clipboard-paste fallback is the workhorse path; expect to use it most of the time.

### 6c. No focused field?

If no editable node is focused (e.g. user triggered dictation from the home screen), copy transcript to clipboard and show a notification: "Transcript copied to clipboard."

---

## 7. Recording UI ("Island" equivalent)

The macOS app shows a small floating pill at the top of the screen with a live waveform (recording) or spinner (transcribing). Android equivalent:

- While recording: the floating bubble itself expands into a horizontal pill showing a live waveform (30 bars, RMS-driven, same algorithm as macOS — see `How-it-works.md` §"The Recording Island UI").
- While transcribing: pill collapses to a small spinner.
- On error: pill briefly shows error text, then dismisses.
- For IME path: replace keyboard surface with the same waveform UI while the user is holding the mic key.
- For Quick Settings tile path: use a foreground-service notification with progress instead of an overlay.

Color palette: keep the coral → purple → blue gradient from macOS (`How-it-works.md` references it). Bar update rate: target 60 Hz (use `Choreographer`); fall back to 30 Hz on lower-end devices.

---

## 8. Audio Capture

- `AudioRecord` with: `MediaRecorder.AudioSource.VOICE_RECOGNITION` (preferred — applies AEC/NS), 16 kHz, 16-bit PCM, mono. This matches what Whisper expects, so no resampling is needed (unlike macOS where the mic's native rate has to be converted).
- Buffer size: `AudioRecord.getMinBufferSize(...) * 2`.
- Compute RMS per chunk for the waveform UI: `rms = sqrt(sum(sample²) / N)`, then `clamp(rms * 6, 0.02, 1.0)` (same normalization as macOS).
- Wrap raw PCM in a WAV container before upload (44-byte header, little-endian, same layout as macOS).
- On Android 14+ (API 34): declare `FOREGROUND_SERVICE_MICROPHONE` and start the foreground service with that type before opening the mic.

---

## 9. API Integration

Identical to macOS. Two endpoints:

### 9a. Groq Whisper

```
POST https://api.groq.com/openai/v1/audio/transcriptions
Authorization: Bearer {apiKey}
Content-Type: multipart/form-data; boundary={uuid}

fields:
  file              recording.wav (audio/wav, binary)
  model             whisper-large-v3-turbo | whisper-large-v3
  language          en | es | de | ...   (ISO 639-1)
  response_format   json
```

Response: `{"text": "..."}`. Extract `text`.

### 9b. Gemini post-processing (optional)

When enabled, pipe the Whisper transcript through Gemini with the same prompt the macOS app uses (port verbatim from `Sources/SrizonVoice/Services.swift` — search for the post-processing prompt). The prompt is tuned to leave short phrases / search queries un-capitalized and un-punctuated, which is important and easy to regress.

### 9c. Validation

On Save in Settings, validate the Groq key with `GET https://api.groq.com/openai/v1/models`. 401/403 = invalid; anything else = accept. (Don't require a specific 200 — Groq sometimes returns 200 with a different shape; treat non-401/403 as OK.)

---

## 10. Permissions

| Permission | Android Manifest | When requested |
|---|---|---|
| Microphone | `RECORD_AUDIO` | First dictation attempt |
| Foreground service (mic) | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` (API 34+) | App install / first run |
| Overlay (bubble) | `SYSTEM_ALERT_WINDOW` | When user enables bubble trigger |
| Accessibility Service | (user-granted via Settings) | When user enables system-wide text insertion |
| Notifications | `POST_NOTIFICATIONS` (API 33+) | First run |
| Internet | `INTERNET` | install-time, normal permission |

The Settings screen should show a row per permission with live status (granted / not granted) and a button to open the relevant system settings page. Mirror the macOS v2.0.0 pattern: hide "Save & Close" until the minimum required permissions are granted.

---

## 11. Settings & Persistence

Use `DataStore` (Preferences) for general settings, `EncryptedSharedPreferences` for the API key. Keys (mirroring macOS):

| Key | Type | Default |
|---|---|---|
| `groq.apiKey` | String (encrypted) | `""` |
| `dictation.language` | String (ISO code) | `"en"` |
| `dictation.recentLanguages` | List<String> | `[]` |
| `groq.transcriptionModel` | String | `"whisper-large-v3-turbo"` |
| `postprocessing.enabled` | Boolean | `false` |
| `postprocessing.model` | String | (mirror macOS default) |
| `trigger.mode` | Enum (`BUBBLE`, `IME`, `TILE`, `ACCESSIBILITY`) | `BUBBLE` |
| `trigger.handsfree` | Boolean | `false` |

Settings UI: Jetpack Compose, Material 3, sidebar/tab structure mirroring macOS v2.0.0 (General, Transcription, Post-Processing).

---

## 12. Threading

| Component | Thread |
|---|---|
| UI state | Main / `Dispatchers.Main.immediate` |
| Audio capture loop | Dedicated background thread (single-threaded `Executor`) |
| API calls | `Dispatchers.IO` (OkHttp + Coroutines, or Ktor) |
| Waveform updates | `Choreographer` callback → main thread |

Use coroutines + `Flow` for state (mirrors the macOS `@MainActor` + Combine pattern).

---

## 13. Out of Scope (v1)

- On-device transcription (no Whisper.cpp / no offline mode).
- Live streaming transcription (the macOS v1.0.0 used Gladia streaming; v2 dropped it for batch Groq, and Android should start there too).
- Multi-account / team features.
- Cloud sync of settings.
- Wear OS / Android Auto integrations.
- iOS port (separate effort).

---

## 14. Open Questions for the Receiving Agent

These should be confirmed with the user before / during implementation:

1. **Min SDK?** Suggest API 26 (Android 8) for `TYPE_APPLICATION_OVERLAY` support. API 29+ is friendlier for foreground service rules.
2. **Language / framework?** Suggest Kotlin + Jetpack Compose + Coroutines + OkHttp.
3. **Trigger surfaces for v1?** See §5 — recommend bubble + Quick Settings tile, defer IME and Accessibility shortcut.
4. **Distribution?** Play Store (requires policy review for Accessibility Service usage — be ready to justify) vs. sideload-only APK on GitHub Releases (matches the macOS DMG model). Sideload is faster to ship; Play Store is needed for reach.
5. **Gemini post-processing in v1 or v2?** macOS got it in v2.1.0; can be cut from Android v1.

---

## 15. Reference Material in This Repo

The receiving agent should read these files in this repo for behavioral reference:

- `README.md` — user-facing description, install flow.
- `How-it-works.md` — comprehensive technical reference. Especially: §Recording (RMS algorithm), §Transcription (Groq request shape), §Recording Island UI (waveform rendering), §Settings & Persistence (key list).
- `CHANGELOG.md` — feature history; useful for understanding what's "table-stakes" vs. "added later."
- `Sources/SrizonVoice/Services.swift` — Groq client, Gemini post-processing prompt, audio capture coordinator.
- `Sources/SrizonVoice/Models.swift` — settings shape, language list.

The Swift code is not portable, but the *logic* (RMS normalization constants, Whisper request fields, Gemini prompt, validation rules, error message strings) should be copied faithfully so the two apps feel like the same product.
