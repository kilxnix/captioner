# Design: Session audio recording + per-line playback

**Status:** approved; ships before v1.1 task extraction.
**Why it's needed:** the transcript is the only record of a session today. If Vosk mis-transcribes a critical line, there is no ground truth to compare against — and any downstream feature (task extraction, summaries) inherits that untrustworthiness. Recording and exposing the raw audio makes the transcript verifiable.

## Goals

- Capture every session's audio alongside the transcript, at negligible storage cost.
- Let the user play back any transcript line by tapping it, seeking to that line's timestamp.
- Keep the Vosk transcription pipeline untouched in behavior — audio capture is a fanout, not a replacement.

## Non-goals

No waveform rendering, no export/share, no trim, no noise reduction, no speaker diarization, no re-running Vosk against saved audio. Just "hear what was said."

## Storage

- **Location:** `context.filesDir/audio/{sessionId}.m4a` — app-private, backed up by default system rules.
- **Format:** AAC-LC in an MP4 container (`.m4a`), mono, 16 kHz sample rate, 32 kbps bitrate.
- **Rationale:** AAC + MediaMuxer is supported on API 21+ with zero external deps. 16 kHz matches the PCM stream Vosk already consumes, so the fanout requires no resampling. 32 kbps is plenty for speech-verification playback (≈14 MB/hour).
- **No schema change:** the path is deterministic from `sessionId`. No Room migration, no nullable column to manage. A session without a file simply gets no playback UI.

## Data flow

Current pipeline reads PCM shorts from `AudioRecord` and hands them to `Transcriber`. The fanout adds one consumer:

```
AudioRecord.read(shortBuf, n)
  ├── Transcriber.accept(shortBuf, n)        (unchanged)
  └── AudioEncoder.encodePcm(shortBuf, n)    (new)
```

`AudioEncoder` wraps `MediaCodec` (AAC-LC encoder) + `MediaMuxer`. Its lifecycle brackets the capture loop: `start()` is called just before the loop in `RecorderService.runCapture`, `close()` is called in the loop's `finally` block alongside the existing `record.release()`. If `AudioEncoder` throws during start (e.g., codec unavailable on exotic devices), capture proceeds without audio — the transcript still works, and the session just has no `.m4a` file.

## Components

### `audio/AudioEncoder.kt` (new)

```kotlin
class AudioEncoder(outputFile: File, sampleRate: Int = 16_000) : Closeable {
    fun start()
    fun encodePcm(shortBuf: ShortArray, count: Int)  // feeds PCM, drains encoded frames
    override fun close()                              // signal EOS, drain final frames, stop muxer
}
```

Implementation notes: `MediaCodec` in async-byte-buffer mode, `MediaMuxer` started after the encoder emits `INFO_OUTPUT_FORMAT_CHANGED` with the real format. PCM shorts are converted to little-endian bytes via `ByteBuffer.order(LITTLE_ENDIAN)` before `queueInputBuffer`. On `close()`, queue `BUFFER_FLAG_END_OF_STREAM`, drain until codec reports EOS, then stop the muxer. Any exception during draining is swallowed with a log — we prefer a partially-recorded file over a crashed session.

### `audio/RecorderService.kt` (modified)

Changes confined to `runCapture`:
- Create and `start()` the encoder after `AudioRecord` initializes successfully (so a codec failure doesn't leak an AudioRecord).
- Call `encodePcm` immediately after `transcriber.accept` inside the read loop.
- Call `encoder.close()` in the existing `finally` block, before `record.release()`.

### `data/Repository.kt` (modified)

`deleteSession(id)` also deletes `audioFile(id)` if present. A one-liner — the DAO delete stays as is.

### `audio/AudioPaths.kt` (new)

Single source of truth for the audio path convention:

```kotlin
object AudioPaths {
    fun sessionAudio(context: Context, sessionId: Long): File =
        File(context.filesDir, "audio/$sessionId.m4a").also { it.parentFile?.mkdirs() }
}
```

Used by the encoder, the repository cleanup, and the playback layer. Prevents the "path drift" bug where one caller writes `audio/1.m4a` and another reads `audio_files/1.m4a`.

### `ui/playback/PlaybackController.kt` (new)

Thin wrapper around `MediaPlayer`:

```kotlin
class PlaybackController : Closeable {
    val state: StateFlow<PlaybackState>  // Idle | Prepared(durationMs) | Playing(positionMs) | Paused(positionMs) | Error(msg)
    fun prepare(file: File)
    fun playFrom(offsetMs: Long)
    fun pause()
    fun resume()
    fun seekTo(offsetMs: Long)
    override fun close()
}
```

Internally runs a 100 ms ticker while playing to publish position updates. Error path: if `prepare` throws (corrupt file, codec missing), state goes to `Error` and the SessionDetail UI falls back to no-playback mode.

### `ui/vm/CaptionerViewModel.kt` (modified)

Adds:
- `sessionAudioExists(sessionId: Long): Boolean`
- Lazy per-session `PlaybackController` — created when SessionDetail composes, released on dispose.

### `ui/screens/SessionDetailScreen.kt` (modified)

- On entry, check for the audio file. If absent, render as today. If present, create a `PlaybackController`, call `prepare(file)`.
- Line rows become `clickable` → `controller.playFrom(line.offsetMs)`.
- The line whose `[offsetMs, nextOffsetMs)` window contains the current playback position gets a subtle accent color and optional bold. Derived from `controller.state` + the sorted line list.
- A `MiniPlayer` Composable pinned at the bottom (above the nav bar) renders: play/pause button, `currentMs / totalMs` text, and a horizontal seek slider. Wired to `controller.state` and `controller.seekTo`.

## Error handling

- **Encoder fails to start:** log and continue without audio. Session still gets a transcript.
- **Encoder throws mid-capture:** log, stop feeding PCM to it, continue transcription. The partial `.m4a` is deleted in the finally block to avoid a truncated file that later confuses playback.
- **Playback prepare fails:** UI stays in no-playback mode; lines are not tappable for seek. No modal error dialog — it's a verification nice-to-have, not a critical path.
- **Audio file missing when expected:** treated identically to "no audio for this session." No crash, no error state.

## Testing

- **Manual:** record a 30-second session, stop, open SessionDetail, tap a line, confirm audio seeks and plays from that point. Record another, delete it, confirm the `.m4a` file is gone from `filesDir/audio/`. Install over a prior version, open an old session (pre-audio), confirm SessionDetail still renders with no playback UI and no crash.
- **No unit tests** on the encoder itself — MediaCodec is hard to fake and the behavior is observable end-to-end.

## Rollout

One commit, single PR to `main`. The build produces an APK as today. The user installs over the existing app; old sessions gracefully degrade to no-audio.

## Open questions

None for v1.
