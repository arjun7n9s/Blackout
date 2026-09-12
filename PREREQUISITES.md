# Prerequisites & setup

Everything needed to build Blackout and run it on **your own** Android phone.

Developed and verified on an **iQOO 15** (Android 16 / SDK 36, arm64-v8a), but nothing is
specific to that handset — see [Running on a different phone](#7-running-on-a-different-phone).

> **Short version:** install Android Studio, plug in your phone with USB debugging on, run
> `./gradlew :app:installDebug`. That gives you a working app in **degraded mode** (regex
> patterns only). The two on-device LLMs are a separate ~2.9 GB download you push over ADB.

---

## 1. Phone requirements

| | Requirement | Why |
|---|---|---|
| Android version | **8.0 (API 26) or newer** | `minSdk = 26` — adaptive launcher icons |
| CPU ABI | **`arm64-v8a`** | the build sets `abiFilters += "arm64-v8a"` — see [§7](#7-running-on-a-different-phone) |
| RAM | **6 GB** min · **8 GB+** recommended | measured peak **3,454 MB PSS** with both models loaded |
| Free storage | **~3.2 GB** | 2.9 GB models + ~90 MB app |
| Camera | any rear camera | optional — gallery and share-in work without one |
| USB debugging | enabled | to install and to push the models |

**Without the models the app still runs** — it falls back to a regex-only path and labels itself
`degraded · patterns only` in the HUD. That needs no extra storage and no meaningful RAM. Verified
by removing the weights and relaunching.

### Enabling USB debugging

1. **Settings → About phone** → tap **Build number** 7 times
2. **Settings → System → Developer options** → enable **USB debugging**
3. Plug in, and tap **Allow** on the *Allow USB debugging?* prompt on the phone

> **vivo / iQOO / OriginOS, Xiaomi, Oppo:** also enable **USB debugging (Security settings)** in
> Developer options, then reboot. Without it, installs from ADB are silently blocked.

Confirm the phone is visible:

```bash
adb devices -l
```

You should see your device with status `device` (not `unauthorized` or `offline`).

---

## 2. Computer requirements

| | Requirement |
|---|---|
| OS | Windows 10/11, macOS, or Linux |
| RAM | 8 GB min, 16 GB comfortable |
| Free disk | **~25 GB** (Android Studio 3.3 GB + SDK ~9 GB + Gradle caches ~1 GB + models 2.9 GB + headroom) |
| JDK | **17 or newer** — Android Studio's bundled JBR is fine |
| Network | first build downloads ~1 GB of Gradle/Maven artifacts |

### What the build itself pins

You don't install these by hand — the Gradle wrapper and version catalog handle them — but for
reference:

| | Version |
|---|---|
| Gradle | 9.7.1 (via `./gradlew`) |
| Android Gradle Plugin | 9.4.0 |
| Kotlin | 2.4.20 (AGP 9 built-in Kotlin) |
| Compose BOM | 2026.09.00 |
| CameraX | 1.6.2 |
| ML Kit text-recognition | 16.0.1 |
| LiteRT-LM | 0.17.0 |
| `compileSdk` / `targetSdk` / `minSdk` | 37 / 36 / 26 |

> **AGP 9 note:** `compileSdk = 37` means you need **SDK Platform 37** installed. AGP 9 also has
> built-in Kotlin support — do **not** add the `org.jetbrains.kotlin.android` plugin, it is
> rejected.

---

## 3. What to download

### a) Android Studio — required

<https://developer.android.com/studio> · ~1.5 GB

Any recent stable release works. Developed on **Quail 4 / 2026.1.4**.

> `winget upgrade Google.AndroidStudio` does **not** work — Google manages Studio's own updates.
> Download the installer directly.

### b) SDK packages — required

Install via **Studio → Settings → Languages & Frameworks → Android SDK**, or from the CLI:

```bash
sdkmanager --install "platforms;android-37" "build-tools;37.0.0" "platform-tools"
```

| Package | Why |
|---|---|
| SDK Platform **37** | `compileSdk = 37` |
| Build-Tools **37.0.0** | aapt2 / d8 / apksigner |
| Platform-Tools | `adb` |
| Google USB Driver | Windows only, for device detection |

NDK and CMake are **not** needed — Blackout ships no native code of its own.

> cmdline-tools 23.0.0+ changed the CLI: package specs now use `/` instead of `;`
> (`ndk/30.0.16248370`, not `ndk;30.0.16248370`). The `;` form above works on older versions;
> use `/` if you get "Package not found".

### c) On-device models — optional but recommended

Both are **Apache-2.0 and ungated** — a plain `curl` works, no Hugging Face account or token.

| Model | Role | Bytes | SHA-256 |
|---|---|---|---|
| `qwen3_0.6b_q4_block32_ekv1280.litertlm` | workhorse | `347,251,840` | `312ce4e6bc0816edf844b9b7e66542648a89440b54822c3581e48a19e9ff5715` |
| `gemma-4-E2B-it.litertlm` | referee | `2,588,147,712` | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |

```bash
curl -L -O https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_q4_block32_ekv1280.litertlm
curl -L -O https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm
```

Verify before pushing 2.9 GB to a phone:

```bash
sha256sum qwen3_0.6b_q4_block32_ekv1280.litertlm gemma-4-E2B-it.litertlm
```

> **Don't substitute a gated model.** `litert-community/Gemma3-1B-IT` and
> `google/gemma-3n-E2B-it-litert-lm` require a Hugging Face login (the latter needs *manual*
> human approval). An unauthenticated `curl` on those returns a 401 error page, not weights.
>
> `.litertlm` is the LiteRT-LM container. The older MediaPipe `.task` format will **not** load.

---

## 4. Setup, in order

**Order matters** — the app must be installed before you can push models, because the target
directory is created by Android at install time.

### Step 1 — clone

```bash
git clone https://github.com/arjun7n9s/Blackout.git
cd Blackout
```

### Step 2 — point the build at your SDK

Open the folder in Android Studio once and it writes `local.properties` for you. Or create it
by hand (it is git-ignored):

```properties
# Windows — note the escaped colon and double backslashes
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
# macOS / Linux
# sdk.dir=/Users/<you>/Library/Android/sdk
```

### Step 3 — build and install

```bash
./gradlew :app:installDebug
```

First run downloads Gradle 9.7.1 and ~1 GB of dependencies. Later builds are offline-capable.

At this point the app **works** — in degraded mode.

### Step 4 — push the models

```bash
adb shell mkdir -p /sdcard/Android/data/com.blackout.app/files/models
adb push qwen3_0.6b_q4_block32_ekv1280.litertlm /sdcard/Android/data/com.blackout.app/files/models/
adb push gemma-4-E2B-it.litertlm               /sdcard/Android/data/com.blackout.app/files/models/
adb shell ls -l /sdcard/Android/data/com.blackout.app/files/models
```

Expect ~90 s total at USB 2 speeds (~33 MB/s measured).

> **Windows / Git Bash:** MSYS rewrites Unix-looking paths, turning `/sdcard/...` into
> `C:/Program Files/Git/sdcard/...`. Prefix with `MSYS_NO_PATHCONV=1` — and then give the
> **local** path in Windows form, because conversion is off in both directions:
>
> ```bash
> MSYS_NO_PATHCONV=1 adb push C:/models/gemma-4-E2B-it.litertlm \
>   /sdcard/Android/data/com.blackout.app/files/models/
> ```

> If you changed `applicationId`, the path changes with it — it is always
> `/sdcard/Android/data/<applicationId>/files/models`.

### Step 5 — run

Launch **Blackout**, grant the camera permission, and shoot a document. Or skip the camera and
share any screenshot into Blackout from your gallery's share sheet.

---

## 5. Checking it actually works

Open the **debug** chip, top-right. You should see:

```
spans       47   ocr 169ms
verdict     hide 30 · keep 17 · unsure 0
doctype     Bank statement
workhorse   Qwen3-0.6B-int4 · CPU · 16559ms · 5 batch · 47 spans
doc-summary Gemma-4-E2B-it · CPU · 1088ms · 1 batch · 0 spans
referee     Gemma-4-E2B-it · CPU · 18773ms · 2 batch · 21 spans
inference   36420ms total
models      Qwen3-0.6B-int4, Gemma-4-E2B-it
```

That is a real capture from the iQOO 15 on a one-page bank statement.

| HUD reads | Meaning |
|---|---|
| `on-device · local models · CPU` | both models loaded, running on CPU |
| `on-device · local models · GPU` | your phone exposes OpenCL — faster |
| `degraded · patterns only` | **no models found** — check Step 4 and the `models` debug line |

Expect roughly **30–60 s** of inference for a dense page on CPU, and a **~3.5 GB** memory peak.
That is real on-device work, not a spinner.

A sparse page (a screenshot, a receipt) is much faster — cost scales with the number of text
spans and with how many of them the workhorse and the regex pass disagree about.

---

## 6. Privacy properties you can verify yourself

- **No `INTERNET` permission.** The manifest both omits it and `tools:node="remove"`s the
  copy ML Kit datatransport / LiteRT media3 would merge. Check with
  `adb shell dumpsys package com.blackout.app` — `INTERNET` must be absent.
- The only permissions requested are `CAMERA` and `VIBRATE`.
- The exported image has redaction bars **burned into the pixels** and is re-encoded to JPEG, so
  no EXIF survives. Pull it and inspect:

```bash
adb exec-out run-as com.blackout.app cat cache/shared/blackout-redacted.jpg > out.jpg
```

---

## 7. Running on a different phone

### Non-arm64 devices and emulators

The build ships **arm64-v8a only**, which keeps the debug APK small and installs fast. Most
emulators are `x86_64` and the install will fail. Fix in `app/build.gradle.kts`:

```kotlin
ndk {
    abiFilters += "arm64-v8a"
    abiFilters += "x86_64"   // add for emulators
}
```

LiteRT-LM ships JNI for `arm64-v8a` and `x86_64` only — **there is no 32-bit (`armeabi-v7a`)
support**, so a 32-bit-only phone cannot run the models at all (the app still runs degraded).

### Phones with less RAM

Gemma-4-E2B is the heavy one. If it fails to load, the app logs a warning and keeps the
workhorse's verdicts — you still get redaction, just without the referee's arbitration. To skip
it deliberately, simply don't push `gemma-4-E2B-it.litertlm`; Qwen alone is ~350 MB.

### GPU vs CPU

`LiteRtLlmRuntime` tries **NPU → GPU → CPU**, and proves each backend with a tiny warm-up
generation before committing to it.

On the iQOO 15 (SoC **SM8850**, not SM8750) NPU is skipped: the AAR has no
`libLiteRtDispatch_Qualcomm.so`, and there are no SM8850 packs for Qwen3-0.6B or Gemma-4-E2B.
GPU warm-up **succeeds** once `libOpenCL.so` is declared as a `uses-native-library`. The HUD
prints the backend that actually sampled (`GPU` on phone A). See [ARCH.md](ARCH.md) for the
exact NPU blocker, vendor QNN paths, and what to copy onto phones B/C.

---

## 8. Troubleshooting

| Symptom | Cause & fix |
|---|---|
| `adb devices` shows `unauthorized` | Accept the RSA prompt on the phone. If it never appears: `adb kill-server && adb start-server`, replug. |
| Device not listed at all (Windows) | Install the **Google USB Driver** via SDK Manager. |
| Install fails on a vivo/iQOO/Xiaomi phone | Enable **USB debugging (Security settings)** in Developer options, then reboot. |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | Your device/emulator isn't arm64. See [§7](#7-running-on-a-different-phone). |
| `adb push` writes to `C:/Program Files/Git/sdcard/...` | Git Bash path mangling — use `MSYS_NO_PATHCONV=1` and a Windows-form local path. |
| `remote secure_mkdirs() failed` | The app isn't installed yet, so the target dir doesn't exist. Do Step 3 before Step 4. |
| HUD stuck on `degraded · patterns only` | Weights missing or misnamed. Filenames must match exactly; check the `models` line in the debug panel. |
| `Failed to resolve: com.google.ai.edge.litertlm` | `google()` must be in `dependencyResolutionManagement` repositories — it already is in `settings.gradle.kts`. Check your network/proxy. |
| Build error: *plugin 'org.jetbrains.kotlin.android' is no longer required* | AGP 9 has built-in Kotlin. Remove that plugin. |
| `Dependency ... requires compileSdk 37` | Install **SDK Platform 37**. |
| App installs but crashes on launch | Confirm JDK 17+ is the Gradle JVM: **Settings → Build Tools → Gradle → Gradle JDK**. |
| Inference takes minutes | Expected on CPU for a dense page. Push only Qwen to skip the referee. |

---

## 9. Related docs

- [ARCH.md](ARCH.md) — how the cascade, merge policy and export actually work
- [progress.md](progress.md) — build log, decisions, and every bug found along the way
- [DOWNLOADS.md](DOWNLOADS.md) — the full toolchain inventory from the original machine setup
