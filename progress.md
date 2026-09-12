# Blackout — progress log

Running log of what's been built, what was decided and why, and what broke along the way.
Newest entries at the bottom.

**Target device:** iQOO 15 (`I2501`) · OriginOS 6 / **Android 16, SDK 36** · `arm64-v8a`
(`abilist32` is empty — the handset is **64-bit only**).

---

## 2026-09-12 · Session 1 — laptop toolchain

Full detail in [DOWNLOADS.md](DOWNLOADS.md). Summary:

- Android Studio upgraded 2025.3 → **Quail 4 / 2026.1.4** (installer SHA-256 verified)
- SDK: platforms 35/36 (+37.0/37.2), Build-Tools 37.0.0, Platform-Tools 37.0.1,
  cmdline-tools 23.0.0, **NDK r30 LTS** (+ r29, r27d), CMake 3.22.1 + 4.1.2, Google USB Driver
- JDK 17 (Temurin 17.0.19) confirmed; Studio now bundles JBR 25
- Gradle caches warmed and **proven offline** — `./gradlew --offline assembleDebug` builds
  from a wiped `app/build` with zero network
- OpenCV 5.0.0 + 4.14.0 Android SDKs downloaded and extracted, 16 KB page alignment verified
- Optional: scrcpy v4.1, Gemma 4 E2B + Qwen3 0.6B `.litertlm` packs, AI Edge Gallery sample

---

## 2026-09-12 · Session 2 — v0 app on device

Goal: turn the throwaway `GradleWarmup` cache-warming project into a real v0 app that runs on
the iQOO 15. Scope was deliberately capture-only — **no OCR, no redaction yet**.

### Project restructure

| Before | After |
|---|---|
| `GradleWarmup/` | `Blackout/` |
| `rootProject.name = "GradleWarmup"` | `"Blackout"` |
| `com.example.iqooprereq` | `com.blackout.app` |
| `versionName "1.0"` | `0.1.0` (`versionCode 1`) |

Wiped the old `com/example` package and the stale `build/` dirs.

### Decisions worth recording

- **`minSdk` 24 → 26.** Adaptive launcher icons land at API 26. Staying on 24 would have meant
  shipping PNG mipmap fallbacks purely for two API levels that are irrelevant on a 2026 handset.
- **`abiFilters = ["arm64-v8a"]`.** The iQOO 15 reports an empty `abilist32`. Dropping the other
  three ABIs cuts the debug APK substantially, which makes every Run / Apply Changes cycle
  faster. One line to remove if an x86_64 emulator is ever needed.
- **`compileSdk 37` / `targetSdk 36`.** Carried over from session 1 — current androidx artifacts
  refuse to compile against anything below 37. `targetSdk` stays at 36 to match Android 16.
- **In-memory capture, not capture-to-file.** `ImageCapture.takePicture` with the in-memory
  callback → `ImageProxy.toBitmap()`. No `FileProvider`, no storage permission, and it matches
  where this is heading: decode → detect text → draw boxes → *then* let the user save.
- **Capture capped at ~1536×2048.** A full-res frame off this sensor decodes to a bitmap big
  enough to OOM. 3:4 at ~12 MB stays sharp enough for OCR. Set via `ResolutionSelector`.
- **No Navigation-Compose.** Three destinations, one of which carries a `Bitmap` — awkward to
  thread through a nav graph for zero benefit. A single `sealed interface Screen` state value
  does the job. Swap it out when there are real routes to deep-link.
- **Portrait-locked.** Camera app on a 6.8″ phone; also sidesteps bitmap-across-config-change
  for v0.
- **Always dark theme**, `isSystemInDarkTheme()` deliberately ignored, so the capture and
  redaction previews read consistently.
- **ML Kit `text-recognition` kept as a dependency but unused.** It's the next milestone and the
  artifact is already cached. Flagged here so it isn't mistaken for dead weight.

### What was built

```
app/src/main/java/com/blackout/app/
  MainActivity.kt              edge-to-edge host + Screen state + gallery picker + snackbars
  camera/CameraSupport.kt      provider await, ImageCapture builder, capture → upright Bitmap
  camera/Images.kt             downsampling gallery decode (software-backed, EXIF-aware)
  ui/CameraPermission.kt       CAMERA permission state incl. permanent-denial detection
  ui/HomeScreen.kt             title, tagline, CTAs, permission notice, Compose brand mark
  ui/CameraScreen.kt           PreviewView + shutter + lens switch + close
  ui/PhotoPreviewScreen.kt     captured still + Retake / Use photo
  ui/theme/                    Color.kt, Theme.kt
```

Launcher icon is a pure-vector adaptive icon — three "text lines" with the middle one redacted,
on a violet gradient. Includes a `<monochrome>` layer for Android 13+ themed icons. No PNGs.

### Things that broke, and the fixes

1. **`androidx.compose.material.icons` doesn't resolve.** material3 1.4.0 no longer pulls
   `material-icons-core` transitively. Rather than add a dependency Google is winding down,
   drew five local vector drawables (`ic_close`, `ic_check`, `ic_retake`, `ic_switch_camera`,
   `ic_camera`, `ic_image`).
2. **Gallery icon rendered as a solid white square.** One `<path>` with nested subpaths fills
   solid under the default `nonZero` fill rule. Split into three paths (stroked frame, sun,
   ridge). Same class of bug in `ic_camera` — fixed with `android:fillType="evenOdd"` so the
   lens punches through.
3. **Name shadowing in `BrandMark`** — a local `fun bar(...)` collided with a `val bar` colour.
   Renamed to `stripe` / `barColor`.
4. **A bulk find-replace mapped Retake to the switch-camera icon.** Both had been
   `Icons.Filled.Refresh`. Pointed Retake at `ic_retake` (arrow reversed).

### Two false alarms worth writing down

- **"Permission denial navigates to the camera anyway."** It didn't. `dumpsys package` showed
  `CAMERA: granted=true` — the scripted tap had hit an allow option, not *Don't allow*. The
  navigation was correct.
- **"Camera preview is black."** It isn't. `adb exec-out screencap` cannot capture the camera
  surface. `dumpsys media.camera` showed an **active client** for `com.blackout.app` on Camera
  ID 0, and capture returned a correctly letterboxed 3:4 bitmap. Don't trust `screencap` for
  camera preview — check `dumpsys media.camera` instead.

### Verified on the device

- Installs and launches — `ActivityTaskManager: Displayed com.blackout.app/.MainActivity +413ms`
- Home screen renders with correct edge-to-edge insets (status bar + gesture pill respected)
- `Take photo` → system permission dialog appears
- Permission granted → camera binds, `dumpsys media.camera` confirms a live client
- Shutter → `ProcessingRequest: onImageCaptured` → preview screen with Retake / Use photo
- `Use photo` shows the "redaction lands next build" snackbar, stays on preview (v0 by design)

### Known gaps (deliberate for v0)

- `Use photo` is a dead end — that's the spec for v0
- Captured `Bitmap` isn't explicitly recycled on retake; GC handles it at this size. Revisit if
  memory pressure shows up
- Bitmap lives in composition state, so it wouldn't survive a config change. Portrait lock makes
  this moot for now; a `ViewModel` is the fix when it stops being moot
- No tests yet

---

## 2026-09-12 · Session 3 — v1 on-device redaction cascade

OCR → Qwen3-0.6B → Gemma-4-E2B → blackout → tap-to-uncensor → share. All on-device.
Architecture in [ARCH.md](ARCH.md).

### Resolving the LiteRT-LM API

Two research agents died on a session limit, so the API was resolved directly: downloaded
`litertlm-android-0.17.0.aar` from Google Maven, extracted `classes.jar`, and read the real
signatures with `javap`. Worth the detour — it surfaced three things the sample never used:

- `ResponseFormat.json(schema)` — **constrained JSON decoding**
- `ThinkingConfig(enableThinking=false)` — Qwen3 reasons by default and would eat the whole cache
- `sendMessage(String, …)` — a plain String overload

The AAR ships `liblitertlm_jni.so` for arm64-v8a and x86_64 only. Everything compiled first try.

### Built

```
ocr/            TextSpan, SpanRect, MlKitOcrEngine, ReadingOrder
intelligence/   CandidateHints, Prompts, DecisionParser, MergePolicy,
                ModelCatalog, LlmRuntime, RedactionAnalyzer
redact/         RedactionEngine (burn + hit-test)
share/          ShareRedacted (FileProvider, redacted only)
ui/             RedactViewModel, RedactScreen, FitTransform, Haptics
```

32 unit tests, all passing, no device or Robolectric needed.

### Five real bugs, found by running it

1. **GPU initialises but cannot generate.** `Engine.initialize()` succeeded, then every batch
   failed with `Can not find OpenCL library on this device` — OriginOS doesn't expose libOpenCL.
   Backend fallback only triggered on init failure, so the whole run silently degraded.
   Fix: `load()` now spends one tiny **warm-up generation** proving a backend can sample.
2. **ML Kit returns block order, not reading order.** The killer. On a two-column statement the
   labels came out as one block and the values as another, so batch 1 was *all* field labels and
   batch 3 was *all* bare values — each stripped of the context that makes it judgeable. The model
   kept everything, correctly. Fix: `ReadingOrder` row-bands spans and sorts left-to-right, which
   restores `Account Holder | Priya Ramachandran` adjacency.
3. **Constrained decoding emitted `{"decisions":[]}`.** Schema-valid, and observed on a batch
   containing an account number, a PAN and an Aadhaar. Every span fell through to `keep` — a
   silent, total redaction failure. Fix: per-batch `minItems`/`maxItems` pinned to the span count.
4. **Mode collapse.** Greedy decoding over a constrained grammar made the 0.6B latch onto one
   action and repeat it — ten consecutive `hide`. Fix: few-shot label-vs-value examples in the
   system prompt (which fixed it), plus `isModeCollapsed` as a safety net that escalates any
   uniform batch to the referee.
5. **The referee could only ever add redactions.** The queue caught `unsure` and
   `keep`+strong-hint but never `hide`, so false-positive hides went unreviewed and every field
   label stayed blacked out. Fix: escalate `hide` with no corroborating hint. The referee then
   restored "MERIDIAN BANK" — *"Bank name is generic label"* — while keeping every value hidden.

### Verified on the iQOO 15

Tested with a generated bank statement (name, account no., IFSC, PAN, Aadhaar, DOB, mobile,
email, address, balances) pushed to the device.

- Both models load on **CPU** after the GPU probe fails — Qwen 1.1 s, Gemma 3.2 s
- 47 spans · OCR 201 ms · Qwen 5 batches 8.2 s · Gemma summary 1.1 s (correctly returned
  "Bank statement") · Gemma referee 25.7 s · **~35 s total inference**
- Referee reasons are specific and correct: *"specific account holder name"*, *"generic account
  label"*, *"Aadhaar is sensitive"*
- Tap-to-uncensor works — revealed "Account Holder" while its value stayed hidden
- Share opens the system chooser; **the exported file was pulled back and decoded on the laptop**:
  119 031 bytes byte-for-byte, valid JPEG, bars present in the actual pixels, EXIF stripped

### Decisions worth recording

- **HUD says `on-device · local models · CPU`, not NPU.** NPU would need Qualcomm QNN libs the
  AAR doesn't ship, plus the per-SoC `..._qualcomm_sm8750.litertlm` weights rather than the
  generic build we have. Showing the real backend beats claiming a path the build can't take.
- **Hints are withheld from the workhorse prompt.** Keeping the regex and model signals
  independent is what makes their disagreement informative — and it's what structurally
  guarantees a hint can never redact on its own.
- **Luhn sets confidence, never gates.** Every single-digit corruption of a valid Luhn number
  fails Luhn, so a card with one OCR error would always be missed if the checksum gated the hint.
- **Overlay on screen, burned pixels on export.** Re-rendering a 1536×2048 bitmap per tap would
  stutter; `ShareRedacted` has no parameter that could carry the original.
- **Added an inbound share/view intent** so a screenshot can be redacted from any app. Genuinely
  useful, and it made the pipeline testable without a physical document.

### Known gaps

- Over-redacts field labels on dense forms — conservative, one tap each to fix
- Gemma referee is ~1.7 s/span on CPU; a contested page can take minutes
- Line-granularity spans: hiding a line hides its label too

---

## 2026-09-12 · Session 4 — setup docs for other phones

Added [PREREQUISITES.md](PREREQUISITES.md) so the build is reproducible off this machine.

Facts in it were measured rather than assumed:

- **Peak memory 3,454 MB PSS** with both models loaded (sampled via `dumpsys meminfo` across a
  full cascade) — that's what drives the 6 GB minimum / 8 GB recommended RAM guidance
- **Degraded path verified** by renaming the weights on device and relaunching: HUD turns red with
  `degraded · patterns only`, no crash. So the app is usable without the 2.9 GB download
- **SHA-256 computed for both model files**, so a 2.9 GB push can be checked before it starts
- Real debug-panel capture used as the "is it working" reference: 47 spans · OCR 169 ms ·
  Qwen 16.6 s / 5 batches · Gemma summary 1.1 s · Gemma referee 18.8 s / 2 batches / 21 spans ·
  36.4 s total
- Re-verified `./gradlew --offline :app:assembleDebug` still builds clean after the LiteRT-LM,
  viewmodel-compose and junit additions

Note: the workhorse pass got slower (8.2 s → 16.6 s) when the few-shot examples were added to the
system prompt. Worth it — that change is what fixed mode collapse — but it is a real cost, and the
system instruction is now the largest fixed part of every batch.

Documented gotchas that cost time here, so they cost nobody else any:

- MSYS path mangling rewrites `/sdcard/...` to `C:/Program Files/Git/sdcard/...`, and
  `MSYS_NO_PATHCONV=1` then breaks the *local* path too — both directions need care
- The app must be installed **before** pushing models; the target dir is created at install time
- `abiFilters` is arm64-only, so x86_64 emulators fail with `INSTALL_FAILED_NO_MATCHING_ABIS`
- vivo/iQOO/Xiaomi need "USB debugging (Security settings)" plus a reboot
