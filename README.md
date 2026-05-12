# SrizonVoice for Android

System-wide voice dictation for Android, ported from the macOS SrizonVoice app.
Audio goes directly to Gemini, and the final text lands at the cursor in whatever app you're in.
BYOK (bring-your-own-key).

See [`ANDROID-SPEC.md`](./ANDROID-SPEC.md) for the design spec.

## Status

v2.0.0 — Gemini-first transcription, correction, custom prompts, translation output modes, and seconds-based handsfree auto-stop.

## What's in the box

| Surface | File |
|---|---|
| Onboarding wizard (welcome → Gemini key → mic → overlay → accessibility → done) | `app/src/main/java/com/srizonvoice/android/onboarding/` |
| Floating bubble + foreground mic service | `app/src/main/java/com/srizonvoice/android/trigger/bubble/` |
| Quick Settings tile | `app/src/main/java/com/srizonvoice/android/trigger/tile/` |
| Accessibility-based text insertion (with clipboard fallback) | `app/src/main/java/com/srizonvoice/android/insertion/` |
| Audio capture + WAV encode + RMS meter | `app/src/main/java/com/srizonvoice/android/audio/` |
| Gemini audio transcription client | `app/src/main/java/com/srizonvoice/android/api/` |
| Settings screen (Gemini key, dictation output, translation output, prompt, recording mode) | `app/src/main/java/com/srizonvoice/android/settings/` |
| 30-bar waveform (coral→purple→blue) | `app/src/main/java/com/srizonvoice/android/ui/WaveformBars.kt` |

## Build prerequisites

- **Android Studio** Ladybug (2024.2.1) or later — bundles JDK 17 + Gradle 8.10.
- Android SDK with platform 35 + build-tools 35.0.0 (Android Studio's SDK Manager will
  prompt to install when you open the project).

To build from the command line, run:

```bash
./gradlew assembleDebug
```

## Run it

1. Open the project in Android Studio.
2. Sync Gradle (the IDE handles wrapper + SDK download).
3. Plug in or boot a device/emulator running Android 12+ (API 31+).
4. Run `app`.
5. The first launch routes into the multi-step onboarding wizard. Walk through:
   - Paste a Gemini API key (get one at https://aistudio.google.com/apikey).
   - Grant Microphone, "Display over other apps", and Accessibility.
6. After "Start dictating", the floating bubble lets you tap to start dictation,
   tap Done for dictation output, tap Translate for translation output when enabled,
   or drag it up to the top to cancel a recording.
7. The Quick Settings tile is named "Dictate" — long-press the QS panel and add it.

## Reference fidelity

These constants and strings are copied verbatim from the macOS app to keep behavior
in sync — see `RmsLevelMeter.kt`, `WavEncoder.kt`, `GeminiClient.kt`,
`Models.kt`, and `Errors.kt` for the line-level citations.

| What | macOS source |
|---|---|
| `clamp(rms * 6, 0.02, 1.0)` visualization | `How-it-works.md:230` |
| Silence threshold `0.008` | `Services.swift:250` |
| 44-byte WAV header layout | `Services.swift:213-239` |
| Gemini inline audio + file upload flow | `Services.swift:343-479` |
| Gemini key validation | `Services.swift:386-400` |
| Default custom prompt | `Models.swift:253` |
| Gemini model + endpoint | `Services.swift:341,416` |
| macOS-aligned translation language list | `Models.swift:6-214` |
| User-visible error strings | `Models.swift:472-480` |

## Future work

- IME with mic key — biggest text-insertion fidelity win, but a big surface.
- Accessibility shortcut / volume-key trigger — niche.
- Recording history, per-app overrides, Wear OS.
- Distribution channel — sideload APK first, Play Store after Accessibility-policy review.
