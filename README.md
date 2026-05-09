# SrizonVoice for Android

Hold-to-talk system-wide voice dictation, ported from the macOS [SrizonVoice](../t3code-7fe30329/) app.
Audio goes to Groq Whisper, transcript lands at the cursor in whatever app you're in.
BYOK (bring-your-own-key).

See [`ANDROID-SPEC.md`](./ANDROID-SPEC.md) for the design spec.

## Status

v0.1.0 — initial scaffold covering all P0 milestones (M0–M8) from the implementation plan.
Not built or device-tested yet (no system JDK on this dev machine).

## What's in the box

| Surface | File |
|---|---|
| Onboarding wizard (welcome → keys → mic → notifications → overlay → accessibility → done) | `app/src/main/java/com/srizonvoice/android/onboarding/` |
| Floating bubble + foreground mic service | `app/src/main/java/com/srizonvoice/android/trigger/bubble/` |
| Quick Settings tile | `app/src/main/java/com/srizonvoice/android/trigger/tile/` |
| Accessibility-based text insertion (with clipboard fallback) | `app/src/main/java/com/srizonvoice/android/insertion/` |
| Audio capture + WAV encode + RMS meter | `app/src/main/java/com/srizonvoice/android/audio/` |
| Groq Whisper client + Gemini cleanup client | `app/src/main/java/com/srizonvoice/android/api/` |
| Settings screen (API keys, language, model, prompt, mode) | `app/src/main/java/com/srizonvoice/android/settings/` |
| 30-bar waveform (coral→purple→blue) | `app/src/main/java/com/srizonvoice/android/ui/WaveformBars.kt` |

## Build prerequisites

- **Android Studio** Ladybug (2024.2.1) or later — bundles JDK 17 + Gradle 8.10.
- Android SDK with platform 35 + build-tools 35.0.0 (Android Studio's SDK Manager will
  prompt to install when you open the project).

There is no checked-in `gradle-wrapper.jar` because the dev machine that scaffolded the
project doesn't have a system JDK. On first import in Android Studio, the IDE will
generate it automatically. If you build from the command line outside the IDE, run:

```bash
gradle wrapper --gradle-version 8.10.2
./gradlew assembleDebug
```

## Run it

1. Open the project in Android Studio.
2. Sync Gradle (the IDE handles wrapper + SDK download).
3. Plug in or boot a device/emulator running Android 12+ (API 31+).
4. Run `app`.
5. The first launch routes into the multi-step onboarding wizard. Walk through:
   - Paste a Groq API key (get one at https://console.groq.com).
   - Optionally enable Gemini for cleanup (key from https://aistudio.google.com).
   - Grant Microphone, Notifications, "Display over other apps", and Accessibility.
6. After "Start dictating", the tracer-bullet screen lets you hold the mic to dictate
   in-app. From there you can also "Show floating bubble" to drop a draggable bubble
   into every app — drag it up to the top to cancel a recording.
7. The Quick Settings tile is named "Dictate" — long-press the QS panel and add it.

## Reference fidelity

These constants and strings are copied verbatim from the macOS app to keep behavior
in sync — see `RmsLevelMeter.kt`, `WavEncoder.kt`, `GroqClient.kt`, `GeminiClient.kt`,
`Models.kt`, and `Errors.kt` for the line-level citations.

| What | macOS source |
|---|---|
| `clamp(rms * 6, 0.02, 1.0)` visualization | `How-it-works.md:230` |
| Silence threshold `0.008` | `Services.swift:250` |
| 44-byte WAV header layout | `Services.swift:213-239` |
| Whisper multipart fields | `Services.swift:403-405` |
| Whisper key validation (non-401/403 = OK) | `Services.swift:366-378` |
| Default cleanup system prompt | `Models.swift:441` |
| Gemini model + endpoint | `Services.swift:492,501` |
| 107-language ISO-639-1 list | `Models.swift:6-214` |
| Recently-used languages (max 3, stack) | `AppModel.swift:85-96` |
| User-visible error strings | `Models.swift:472-480` |

## Decisions deferred to v2

- IME with mic key (spec §5b) — biggest text-insertion fidelity win, but a big surface.
- Accessibility shortcut / volume-key trigger (spec §5c) — niche.
- Recording history, per-app overrides, Wear OS (spec §4).
- Distribution channel — sideload APK first, Play Store after Accessibility-policy review.
