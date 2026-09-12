# Blackout

Redact before you share. Capture a document or screenshot on-device, black out anything you
don't want seen, and only then let it leave the phone.

> **Status: v0 — capture only.** Camera and gallery input work end to end. Text detection and
> redaction are the next milestone. See [progress.md](progress.md).

## Stack

| | |
|---|---|
| Language | Kotlin 2.4.20 |
| UI | Jetpack Compose (BOM 2026.09.00, Material 3) |
| Camera | CameraX 1.6.2 |
| Build | AGP 9.4.0 · Gradle 9.7.1 |
| SDK | `compileSdk` 37 · `targetSdk` 36 · `minSdk` 26 |
| ABI | `arm64-v8a` only |

Target handset: **iQOO 15** (OriginOS 6 / Android 16).

## Build

```bash
./gradlew :app:installDebug
```

Needs a `local.properties` with `sdk.dir` pointing at your Android SDK (git-ignored; Android
Studio writes it on first open).

**Setting this up on your own phone: see [PREREQUISITES.md](PREREQUISITES.md)** — hardware
requirements, what to download, how to side-load the models, and troubleshooting.
Toolchain inventory from the original machine is in [DOWNLOADS.md](DOWNLOADS.md).

Everything resolves from the local Gradle cache — `./gradlew --offline :app:assembleDebug`
builds with no network.

## Layout

```
app/src/main/java/com/blackout/app/
  MainActivity.kt          host activity, screen state, gallery picker
  camera/                  CameraX plumbing, capture → Bitmap, gallery decode
  ui/                      Home / Camera / PhotoPreview screens, permission state
  ui/theme/                colours + typography
```

## Privacy

Nothing leaves the device. There is no network permission, no analytics, and no upload path —
capture and (soon) redaction are entirely on-device.
