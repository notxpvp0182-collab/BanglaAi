# বাংলা এআই সহকারী (Personal Bengali Android AI Agent)

A lightweight, honest, privacy-first Bengali-speaking Android AI assistant built from source in Kotlin for Android 10+ (API 29+) and engineered for 2 GB RAM devices.

---

## 1. Architecture & Pinned Build Versions
- **Minimum SDK**: Android 10 (API 29) | **Target / Compile SDK**: Android 14 (API 34)
- **Kotlin**: `1.9.24` | **KSP**: `1.9.24-1.0.20`
- **Android Gradle Plugin (AGP)**: `8.5.2` | **Gradle**: `8.7` (JDK 17)
- **UI**: Pure Dark Native XML Views (`#0D0D0D`) + `ViewBinding` + `RecyclerView` (no heavy Compose runtime overhead).
- **Storage**: `Room` SQLite (`bengali_agent_brain.db`) with schema migration support + `DataStore` Preferences + Hardware-backed `AndroidKeyStore` (`AES/GCM/NoPadding` 256-bit) for API keys.

---

## 2. API Setup Instructions (Gemini, Groq, OpenRouter)
1. **Google Gemini API**:
   - Create an API key at [Google AI Studio](https://aistudio.google.com/). Keys start with `AIza...`.
2. **Groq Cloud API**:
   - Create an API key at [Groq Console](https://console.groq.com/keys). Keys start with `gsk_...`.
   - Also powers the optional **Groq Whisper (`whisper-large-v3`) Cloud STT fallback** when a phone lacks built-in Bengali speech recognition.
3. **OpenRouter API**:
   - Create an API key at [OpenRouter](https://openrouter.ai/keys). Keys start with `sk-or-...`.
4. **Connecting Inside the App**:
   - Open **API ও সেটিংস** -> Enter one, two, or all three keys -> Tap **মডেল খুঁজুন** (fetches live available models from the provider API, or clearly labels the curated fallback list) -> Tap **Check & Connect** (executes a real minimal generation request and displays latency or specific HTTP error).

---

## 3. Permissions & Privacy Documentation
Every permission is optional; ordinary text chat works with zero runtime permissions granted:
- `RECORD_AUDIO`: Requested only when you tap the microphone button or enroll your voice. Muting (`🔇`), pausing, or tapping **থামুন (Stop)** immediately stops `SpeechRecognizer` and `AudioRecord`.
- `POST_NOTIFICATIONS` & `FOREGROUND_SERVICE`: Used only when you enable an active session so you have persistent **Mute**, **Pause**, **Stop**, and **Disable** controls.
- `BIND_ACCESSIBILITY_SERVICE`: Must be manually enabled in Android **Settings -> Accessibility**. Used to inspect visible UI labels and perform requested taps/scrolls/typing. Automatically blocks interaction with password/OTP fields, biometric prompts, and system permission dialogs.
- `MediaProjection` (Screen Sharing): Only starts after you tap **স্ক্রিন ভিউ** and approve Android's official system consent dialog. Captures downscaled frames (`<=720px` width) at most once every 3 seconds when needed.
- **Speaker Preference (`SpeakerVerifier`)**: Extracts a 16-dimensional acoustic feature vector locally on-device and immediately zeroes out the raw PCM buffer. It is a convenience signal to pause automation if a different voice is suspected—never used as biometric authentication.

---

## 4. Termux & Git Commands to Push and Build on GitHub Actions
Run these commands in Termux (or any terminal) to push the repository and trigger the automated APK build:
```bash
pkg update && pkg install git -y
cd ~/bengali-ai-agent
git init
git branch -M main
git add .
git commit -m "Complete 10-Milestone Personal Bengali Android AI Agent"
git remote add origin https://github.com/<YOUR_GITHUB_USERNAME>/<YOUR_REPO_NAME>.git
git push -u origin main

Downloading the Built APK Artifact from GitHub Actions

1.  Open your GitHub repository in a browser and tap the Actions tab.
2.  Select the latest Android CI & APK Build workflow run.
3.  Scroll down to Artifacts and download bengali-ai-agent-debug-apk (or
    bengali-ai-agent-release-apk if you configured RELEASE_KEYSTORE_BASE64,
    RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS, and RELEASE_KEY_PASSWORD in
    GitHub Repository Secrets).
4.  Unzip the downloaded artifact and install app-debug.apk.

5. Known Limitations & Honest Troubleshooting

  - OS Background & Battery Limits: OEM battery savers (Xiaomi MIUI/HyperOS,
    Samsung OneUI, Realme/Oppo ColorOS) may kill background services under heavy
    memory pressure. If the assistant stops, disable battery optimization for
    the app via অনুমতি ড্যাশবোর্ড -> সিস্টেম অ্যাপ সেটিংস খুলুন.
  - Protected Apps & DRM: Banking apps, DRM video players, password fields, and
    FLAG_SECURE windows block accessibility inspection and screenshots by
    Android design. The agent will honestly report when a screen cannot be read
    or tapped.
  - Video Editing Scope: The built-in Video Studio performs real lossless stream
    trimming, audio muting, .m4a audio extraction, and rotation via
    MediaExtractor/MediaMuxer with a 64 KB buffer. For heavy visual effects or
    multi-layer compositing, use the ভিডিও প্লেয়ারে চালান button to open a
    dedicated external editor.


---

## 3. ACCEPTANCE CRITERIA & TEST VERIFICATION MATRIX (A–P)

| ID | Acceptance Criterion | Implementation & Verification Status |
| :--- | :--- | :--- |
| **A** | Fresh install opens and setup can finish with optional permissions skipped | **IMPLEMENTED & TESTED**: `SetupWizardActivity.btnFinishSetup` completes setup with zero permissions granted; verified in `MainActivityInstrumentedTest`. |
| **B** | Invalid API keys show useful error; valid keys pass real provider test | **IMPLEMENTED & TESTED**: `KeyValidator` + `LlmProviderClient.testConnection()`; verified against 401/200 responses in `Milestone1And2UnitTest`. |
| **C** | Model discovery lists live provider models or clearly labels curated fallback | **IMPLEMENTED & TESTED**: `LlmProviderClient.fetchAvailableModels()`; verified in `Milestone1And2UnitTest`. |
| **D** | Bengali speech recognition and TTS work, or clear fallback is shown | **IMPLEMENTED**: `VoiceManager.kt` (`bn-BD` STT/TTS) + `CloudSttClient.kt` (Groq Whisper fallback) + `VoiceSettingsActivity.kt`. |
| **E** | Mute immediately stops mic capture; Stop immediately cancels tasks & screen capture | **IMPLEMENTED & TESTED**: `voiceManager.stopListeningImmediately()`, `ScreenCaptureManager.stopProjection()`, `activeTaskJob?.cancel()`. |
| **F** | Screen capture always follows official consent and visibly indicates active state | **IMPLEMENTED**: `MediaProjectionManager.createScreenCaptureIntent()` in `MainActivity.kt` + `🟢 স্ক্রিন চালু` badge + foreground notification + overlay dot. |
| **G** | Accessibility automation can open app, tap, long-press, type, scroll, and press Back | **IMPLEMENTED & TESTED**: `AgentAccessibilityService.kt` + `ActionExecutor.kt` + `AgentPlanner.kt`; verified in `Milestone5To7UnitTest`. |
| **H** | Agent verifies results and does not falsely claim success when action fails | **IMPLEMENTED**: `ActionExecutor.executeSingleStep()` re-observes `AgentAccessibilityService.observeCurrentScreen()` after actions and halts on failure. |
| **I** | Memory remains after restart/reboot/disable; recall returns stored info or admits absence | **IMPLEMENTED & TESTED**: `AgentDatabase.kt` (Room SQLite) + `MemoryRepository.buildRelevantMemoryContext()`; verified in `Milestone3And4UnitTest`. |
| **J** | Memory can be corrected and deleted by the user | **IMPLEMENTED & TESTED**: `MemorySettingsActivity.kt` + `MemoryRepository.addOrUpdateFact()` / `deleteFact()` / `clearAllMemory()`. |
| **K** | Video selection uses system picker; edit produces real output file and preserves original | **IMPLEMENTED**: `MediaVideoStudioActivity.kt` + `VideoEditorProcessor.processVideoNonDestructive()` using `MediaExtractor`/`MediaMuxer`. |
| **L** | Process/service failures show useful status and recover without duplicate actions | **IMPLEMENTED**: `START_NOT_STICKY` in `AgentForegroundService` prevents zombie restarts and duplicate action execution. |
| **M** | Low-memory behavior uses bounded queues and avoids loading whole media files into RAM | **IMPLEMENTED**: 64 KB direct `ByteBuffer` in `VideoEditorProcessor`, max 100 UI messages in `TranscriptAdapter`, 720px max screenshot width, and `AgentApp.onTrimMemory()`. |
| **N** | GitHub Actions builds and uploads the APK | **IMPLEMENTED**: `.github/workflows/android-ci.yml` runs `gradle testDebugUnitTest` and `gradle assembleDebug` and uploads `bengali-ai-agent-debug-apk`. |
| **O** | No API keys or signing secrets appear in repository history or logs | **IMPLEMENTED & TESTED**: `SecureKeyStore` (AndroidKeyStore AES-GCM) + `KeyValidator.redactSecrets()` + GitHub Secrets for optional release signing. |
| **P** | Every control in the UI performs its advertised action; no fake/demo controls | **IMPLEMENTED**: All 9 activities/services and all buttons are wired to real implementations. |
