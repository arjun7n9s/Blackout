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
