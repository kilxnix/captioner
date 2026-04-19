# Captioner

Local, always-available voice captioning on Android. Records, transcribes on-device via Vosk, saves sessions, lets you share or delete them. No cloud. No account. No Play Store.

## What's in the box

- Kotlin + Compose + Material 3, dark theme, min SDK 26
- Foreground service (`microphone` type) running `AudioRecord` at 16 kHz mono PCM
- Vosk small-en model (~40 MB, downloaded on first launch, bundled offline thereafter)
- Room database storing `Session` and `Line` rows
- Three screens: Home (record + session list), Live (captions as you speak), Session detail (transcript, share, delete)
- GitHub Actions workflow that builds a debug APK on every push — no local Android Studio required

## Install in under an hour

### 1. Push to GitHub (2 min)

```bash
cd captioner
git init
git add .
git commit -m "initial commit"
gh repo create captioner --private --source=. --push
```

Or create the repo in the browser and push manually. Any GitHub repo works — public or private.

### 2. Let GitHub Actions build the APK (~5–10 min first time)

Pushing to `main` or `master` auto-triggers `.github/workflows/build.yml`. Watch it in the **Actions** tab. First build downloads the Android SDK and Gradle, so it's slow; subsequent builds with cache are ~2 min.

When the workflow finishes, scroll to the bottom of the run page — there's an **Artifacts** section with `captioner-debug-apk`. Download the zip; inside is `captioner-debug.apk`.

### 3. Install on your phone (2 min)

- Email/Drive/AirDroid the APK to your phone, or `adb install captioner-debug.apk` over USB
- Tap the APK, confirm "install from unknown source" if prompted
- Open Captioner

### 4. First launch (3 min)

- Grant **microphone** permission when asked
- Grant **notifications** permission (Android 13+) — required so the OS keeps the recording service alive
- Tap **Download model** — pulls ~40 MB from alphacephei.com. One-time.
- Optionally tap **Allow background use** to exempt Captioner from battery optimization (matters for long sessions)
- Hit the record button

That's it.

## Project layout

```
captioner/
├── .github/workflows/build.yml      # APK build pipeline
├── settings.gradle.kts              # Vosk maven repo declared here
├── build.gradle.kts                 # root, plugin versions
├── app/
│   ├── build.gradle.kts             # Compose BOM, Room, Vosk, arm64 only
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/sheltron/captioner/
│       │   ├── CaptionerApp.kt      # Application class, wires Repository
│       │   ├── MainActivity.kt
│       │   ├── audio/
│       │   │   ├── Transcriber.kt        # Vosk Recognizer wrapper
│       │   │   ├── ModelManager.kt       # model download + unzip + ready check
│       │   │   └── RecorderService.kt    # foreground service (the heavy lift)
│       │   ├── data/
│       │   │   ├── Repository.kt
│       │   │   └── db/                   # Session, Line, DAOs, AppDatabase
│       │   └── ui/
│       │       ├── nav/Nav.kt            # 3 routes
│       │       ├── screens/              # Home, Live, SessionDetail
│       │       ├── theme/                # Color, Type, Theme
│       │       └── vm/CaptionerViewModel.kt
│       └── res/                          # themes, backup rules, strings
```

## How it works

**Capture path:** tapping record starts `RecorderService` as a foreground service. It opens `AudioRecord` on `VOICE_RECOGNITION` source at 16 kHz mono, feeds PCM shorts into `Transcriber` (which wraps Vosk's `Recognizer`), and emits partials/finals via two `StateFlow`s.

**Persistence:** finals get written to Room immediately with their offset from session start. Partials are display-only — they flicker as Vosk refines the current phrase, then collapse into a final when silence hits.

**UI state:** `LiveScreen` collects the two flows. Finals render as solid text. The current partial renders dimmed beneath them. Auto-scrolls on new finals.

**Session lifecycle:** starting creates a row in `sessions` with `endedAt = null`. Stopping sets `endedAt` and writes any trailing partial as a final line. Sessions list reverse-chronological on Home.

## Extending

The repository contract (`Repository.kt`) is the clean seam for everything you'd add next:

- **Task extraction** — build `TaskExtractor.kt` that takes a `session_id`, loads lines via `linesForOnce(sessionId)`, sends the joined transcript to Claude API with a structured-output prompt, writes results to a new `tasks` Room table. Run it from `RecorderService.stopCapture()` on session end, or as a manual "Extract" button on session detail.
- **Overlay captions** — add a second service with `SYSTEM_ALERT_WINDOW` and subscribe to the same `RecorderService.live` flow.
- **Other ASR engines** — swap `Transcriber.kt` behind the same `Partial`/`Final` sealed result.
- **Export formats** — `SessionDetailScreen` already has a share intent; extend `Repository` with export helpers for SRT/VTT/JSON.

## Known gotchas

- **Vosk small-en is small, not perfect.** Expect ~10–15% WER on clean speech, much worse on noisy environments. Swap to the larger model by changing `ModelManager.MODEL_URL` and `MODEL_NAME`.
- **APK includes arm64-v8a only.** Add `"armeabi-v7a"` to `abiFilters` in `app/build.gradle.kts` if you need to support older devices. The APK grows ~40 MB per ABI because of the Vosk native lib.
- **Release builds are unsigned.** The workflow builds `assembleDebug`. For signed release APKs, add a keystore, store as a GitHub Secret, and extend the workflow.
- **Local Gradle wrapper jar not committed.** The CI workflow uses `gradle/actions/setup-gradle@v4`, which doesn't need it. If you want to build locally, run `gradle wrapper --gradle-version 8.9` once (requires Gradle on your PATH) or open the project in Android Studio and it'll generate the wrapper automatically.

## License

Do whatever you want with this. Personal project template.
