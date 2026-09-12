# iQOO 15 prereq — laptop environment

Android Kotlin/Compose development environment, set up **laptop-only**.
No phone pairing, no `adb devices`, no app feature code, no release/signed APKs.

- **Machine:** Windows 11 Home 10.0.26200, `C:` had ~108 GB free before this run
- **Date set up:** 2026-09-12
- **Status:** ✅ Ready. A Compose + CameraX + ML Kit project builds end-to-end offline-warm.

---

## 1. Base installs

| Item | Version | Path / notes |
|---|---|---|
| Git | `2.53.0.windows.2` | already present, unchanged |
| **Android Studio** | **Quail 4 — `2026.1.4`**<br>build `AI-261.26222.65.2614.16204760` | `C:\Program Files\Android\Android Studio1` ⚠️ see [§7](#7-known-issues--things-that-failed) |
| JDK (system) | Temurin **OpenJDK 17.0.19+10** | `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot` — on `PATH` |
| JDK (Studio-bundled JBR) | **OpenJDK 25.0.3** | `C:\Program Files\Android\Android Studio1\jbr` |

**JDK 17 confirmed** — but note the brief's "Studio-bundled is fine" no longer holds literally:
**Studio now bundles JBR 25**, not 17 (`product-info.json` declares `minRequiredJavaVersion: 21`).
The JDK 17 requirement is satisfied by the separately-installed **Temurin 17.0.19**.
The warm-up project builds successfully on *both*, so either works — but if you specifically need
17, use the Temurin one, not Studio's.

Android Studio was upgraded from the previously-installed `2025.3`.
Installer was downloaded from Google's official CDN and **SHA-256 verified**:

```
android-studio-quail4-windows.exe   1.5 GB
https://edgedl.me.gvt1.com/android/studio/install/2026.1.4.7/android-studio-quail4-windows.exe
sha256 = 3be4f63119ca6bbb449948bd71ceffc3cf0805b505b07630f4ac8b43cc8ee45c   ✅ matched
```
Installer kept at `C:\Users\arjun\AndroidVendors\tools\android-studio-quail4-windows.exe`.

> `winget upgrade Google.AndroidStudio` does **not** work — winget reports
> *"The package cannot be upgraded using WinGet"*. Google manages Studio updates itself.

---

## 2. Android SDK

**SDK root:** `C:\Users\arjun\AppData\Local\Android\Sdk`

All 18 packages below are registered (`sdkmanager --list_installed`) and every binary was
smoke-tested, not just checked for existence.

| Package | Version | Notes |
|---|---|---|
| `platforms/android-35` | rev 2 | API 35 (Android 15), ext 13 — **required by brief** |
| `platforms/android-36` | rev 2 | API 36 (Android 16), ext 17 — **required by brief** |
| `platforms/android-37.0` | rev 2 | added — see [§4](#4-a-note-on-compilesdk-37) |
| `platforms/android-37.2` | rev 1 | added — latest minor |
| `platforms/android-34` | rev 3 | pre-existing, left alone |
| `build-tools/37.0.0` | 37.0.0 | **latest**; aapt2 2.20, d8 9.2.4-dev, apksigner 0.9 |
| `build-tools/36.1.0` | 36.1.0 | kept for compatibility |
| `build-tools/36.0.0` | 36.0.0 | pulled in as a dependency |
| `build-tools/34.0.0` | 34.0.0 | pre-existing |
| `platform-tools` | **37.0.1** | `adb` + `fastboot` |
| `cmdline-tools/latest` | **23.0.0** | upgraded from an unregistered 12.0 — see [§7](#7-known-issues--things-that-failed) |
| `ndk/30.0.16248370` | **r30 — LTS** | ✅ the latest LTS; clang 21.0.0 |
| `ndk/29.0.14206865` | r29 | latest stable (non-LTS); clang 21.0.0 |
| `ndk/27.3.13750724` | r27d | previous LTS; clang 18.0.4 — best OpenCV/AGP compatibility |
| `cmake/4.1.2` | 4.1.2 | latest; bundled ninja 1.12.1 |
| `cmake/3.22.1` | 3.22.1 | AGP default, most compatible; ninja 1.10.2 |
| `extras/google/usb_driver` | rev 13 | **Google USB Driver (Windows)** |
| `emulator` | 37.1.11 | arrived as a dependency, not requested |
| `sources/android-37.0` | rev 2 | arrived as a dependency |

### `adb version` — verified

```
Android Debug Bridge version 1.0.41
Version 37.0.1-15733141
Installed as C:\Users\arjun\AppData\Local\Android\Sdk\platform-tools\adb.exe
```

### NDK / CMake verified working

Both NDKs passed a real cross-compile smoke test
(`clang --target=aarch64-linux-android24 -x c - -fsyntax-only` → exit 0).

> **Which NDK to use:** `ndk/30.0.16248370` is the current **LTS** per
> <https://developer.android.com/ndk/downloads>. Pin it explicitly with
> `android { ndkVersion = "30.0.16248370" }`.
> Keep `27.3.13750724` around — OpenCV prebuilts and older AGP are best-tested against r27.

**A second, concrete reason to pin r30** — sysroot API coverage (`meta/platforms.json`):

| NDK | max API in sysroot |
|---|---|
| r27d | 35 |
| r29 | 35 |
| **r30** | **37** ✅ |

r27 and r29 have no API 36/37 stubs at all, so a native build targeting those levels silently
falls back to 35. Only r30 covers the full range. (Not hit yet — the warm-up project declares no
`externalNativeBuild`, so nothing native is compiled — but it will matter for OpenCV work.)

---

## 3. Warm Gradle cache

**Project:** `C:\Users\arjun\Desktop\iqoo-prereq\GradleWarmup`
Empty-Activity-style Compose app in Kotlin. Exists purely to pull dependencies down — it is
not the real app and contains no feature code.

| | |
|---|---|
| Gradle | **9.7.1** (wrapper committed; distribution cached) |
| AGP | **9.4.0** |
| Kotlin | **2.4.20** (via AGP 9 built-in Kotlin) |
| compileSdk | **37** (see [§4](#4-a-note-on-compilesdk-37)) |
| targetSdk | **36** |
| minSdk | **24** |

### Dependencies warmed

| Artifact | Resolved version |
|---|---|
| `androidx.compose:compose-bom` | **2026.09.00** (→ compose 1.12.1, material3 1.4.0) |
| `androidx.camera:camera-core` | 1.6.2 |
| `androidx.camera:camera-camera2` | 1.6.2 |
| `androidx.camera:camera-lifecycle` | 1.6.2 |
| `androidx.camera:camera-view` | 1.6.2 |
| `com.google.mlkit:text-recognition` | **16.0.1** (+ `text-recognition-bundled-common` 17.0.0, the 17 MB model bundle) |
| `androidx.core:core-ktx` | 1.19.0 |
| `androidx.activity:activity-compose` | 1.13.0 |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.11.0 |

### Proof it actually worked

A real debug APK was produced — this is stronger evidence than a sync alone:

```
app/build/outputs/apk/debug/app-debug.apk     57 MB
compileSdkVersion='37'  minSdkVersion='24'  targetSdkVersion='36'
```

The APK contains the ML Kit and CameraX **native** libraries, confirming the whole chain
resolved and packaged, not just the POMs:
`libmlkit_google_ocr_pipeline.so`, `libimage_processing_util_jni.so`,
`libsurface_util_jni.so`, `libandroidx.graphics.path.so`.

### Cache footprint

| Location | Size |
|---|---|
| `~/.gradle/caches` | 898 MB (241 binary artifacts: 96 `.aar`, 145 `.jar`) |
| `~/.gradle/caches/9.7.1` | 594 MB — 2,357 **unpacked** artifact transforms (AARs pre-exploded) |
| `~/.gradle/wrapper/dists/gradle-9.7.1-bin` | 164 MB, extracted |
| Build toolchain cached | AGP 9.4.0 jar (11.8 MB), `kotlin-compiler-embeddable` 2.4.20 (57 MB) |

Every requested coordinate was confirmed to be a real `.aar`/`.jar` on disk, spot-checked with
`unzip -l`. No partial/`.part` downloads. No device was involved at any point.

### ✅ Offline build — cache warmth *proven*, not inferred

`app/build` was deleted and the build re-run with the network disabled at the Gradle level:

```
$ rm -rf app/build && ./gradlew --offline :app:assembleDebug
BUILD SUCCESSFUL in 11s
36 actionable tasks: 15 executed, 21 from cache
```

Nothing was fetched. This is the real test of the whole exercise, and it passes.

---

## 4. A note on compileSdk 37

The brief asked for compile/target SDK 35/36. **targetSdk is 36 as requested.**
`compileSdk` had to move to 37 because the current androidx/Compose artifacts hard-require it:

> `Dependency 'androidx.core:core-ktx:1.19.0' requires libraries and applications that
> depend on it to compile against version 37 or later of the Android APIs.`

Same error for `androidx.compose.ui:ui-android:1.12.1` and
`androidx.lifecycle:lifecycle-runtime-compose-android:2.11.0` — 14 dependencies in total.

`compileSdk` and `targetSdk` are independent: compiling against 37 does **not** opt the app into
API 37 runtime behaviour. Platforms 35 and 36 are still installed as the brief required.
The alternative was pinning every androidx library backwards, which would have warmed the cache
with artifacts you won't actually use.

---

## 5. OpenCV (offline)

Downloaded from GitHub releases (the `opencv.org/releases` download target). Both zips passed
`unzip -t`, and extraction is complete — zip entry counts match on-disk file counts exactly.

### Primary — OpenCV 5.0.0

```
Zip:    C:\Users\arjun\AndroidVendors\opencv-android\opencv-5.0.0-android-sdk-16kb-page-fix.zip
        317,781,903 bytes (exact match to published size)
Module: C:\Users\arjun\AndroidVendors\opencv-android\opencv-5.0.0-android-sdk-16kb-page-fix\OpenCV-android-sdk\sdk
```

### Fallback — OpenCV 4.14.0

```
Zip:    C:\Users\arjun\AndroidVendors\opencv-android\opencv-4.14.0-android-sdk.zip
        319,074,395 bytes
Module: C:\Users\arjun\AndroidVendors\opencv-android\opencv-4.14.0-android-sdk\OpenCV-android-sdk\sdk
```

Both modules are Gradle-importable (`com.android.library`, namespace `org.opencv`,
compileSdk 34, minSdk 21). All four ABIs present in both: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
Java sources, javadoc, static `.a` libs, CMake config and full header tree included in both.

### 16 KB page alignment — verified with `llvm-readelf`

| SDK | arm64-v8a | x86_64 | armeabi-v7a / x86 |
|---|---|---|---|
| 5.0.0 | **0x4000 (16 KB)** ✅ | 0x4000 ✅ | 0x1000 (4 KB) |
| 4.14.0 | **0x4000 (16 KB)** ✅ | 0x4000 ✅ | 0x1000 (4 KB) |

4 KB on the 32-bit ABIs is correct — Android 15/16's 16 KB requirement applies only to 64-bit.
**Note:** 4.14.0 is *already* 16 KB-aligned, so the `-16kb-page-fix` 5.0.0 build isn't uniquely
required to get 16 KB support. Matters for the iQOO 15 (Android 16, 16 KB pages).

### ⚠️ Gotchas before you write OpenCV code

1. **Neither SDK ships `libc++_shared.so`**, but both `libopencv_java*.so` have a `DT_NEEDED`
   on it. Without supplying it, `System.loadLibrary(...)` fails at runtime with
   *"library libc++_shared.so not found"*. Fix: `externalNativeBuild` with `ANDROID_STL=c++_shared`,
   or copy `libc++_shared.so` from the NDK sysroot into `jniLibs`. Use an NDK ≥ r27 copy so it's
   also 16 KB-aligned.
2. **Library name differs by major version:** `System.loadLibrary("opencv_java5")` vs `"opencv_java4"`.
3. **OpenCV 5.0.0 ships no cascade data** — zero `haarcascade*.xml` files. 4.14.0 has 18 plus
   `lbpcascades`.
4. **OpenCV 5 renamed/removed modules:** `calib3d`→`calib`, `features2d`→`features`;
   `ml` and `gapi` are **gone**. `org.opencv.calib3d.*` / `features2d.*` / `ml.*` imports will not
   compile against 5.0.0. **If you want the path of least resistance, use 4.14.0.**
5. Restricting to `abiFilters += "arm64-v8a"` saves ~120 MB per SDK (x86/x86_64 libs are 53–68 MB each).

---

## 6. Optional extras

### scrcpy — ✅ cached *and* extracted, verified runnable

```
Zip:    C:\Users\arjun\AndroidVendors\tools\scrcpy-win64-v4.1.zip
        v4.1 · 11,305,298 bytes
        sha256 5b12172b3264b2889f4583ee64752ce832e29bc8b1089dca81093459697165db   ✅ matched
Binary: C:\Users\arjun\AndroidVendors\tools\scrcpy-win64-v4.1\scrcpy.exe
```

`scrcpy.exe --version` → `scrcpy 4.1` (SDL 3.4.12, libavcodec 62.28.102). Fully portable, no
installer, no VC++ redist. **Bundles its own `adb.exe`** — verified `Version 37.0.0-14910828`.

(Not pointed at any device — phone work is out of scope.)

### On-device LLM — `C:\Users\arjun\AndroidVendors\models\`

**Use LiteRT-LM, not MediaPipe.** The MediaPipe LLM Inference API is officially
*maintenance-only*; Google deleted its Android LLM sample (that path now 404s) and its last
release was 2026-04-27. LiteRT-LM shipped v0.17.0 on 2026-09-09.

```kotlin
implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.0")  // Google Maven, not Central
```

| Model | Size | License | Gated |
|---|---|---|---|
| `qwen3_0.6b_q4_block32_ekv1280.litertlm` | 347,251,840 B ✅ | Apache-2.0 | No |
| `gemma-4-E2B-it.litertlm` | 2,588,147,712 B | Apache-2.0 | No |

Both downloaded from the `litert-community` Hugging Face org and byte-size verified.
**Gemma 4 is Apache-2.0 and ungated** — a real change from Gemma 3, which sits behind the custom
Gemma Terms of Use and a HF token. Avoid `Gemma3-1B-IT` (gated=auto) and
`google/gemma-3n-E2B-it-litert-lm` (gated=**manual**, a human at Google must approve).

Sample app cloned to `C:\Users\arjun\AndroidVendors\samples\gallery`
(<https://github.com/google-ai-edge/gallery>, Apache-2.0) — Google's production-grade
Kotlin/Compose reference with a model download manager. Its
`Android/src/gradle/libs.versions.toml` is the authoritative source for compatible versions.

> Worth knowing: on a supported device, **ML Kit GenAI** (`com.google.mlkit:genai-prompt`)
> runs Gemma via Android AICore with **zero model download** — Google calls it the recommended
> production path. Tradeoff: fixed model, no fine-tunes. The Gallery app carries both.
>
> `.litertlm` is the LiteRT-LM container; `.task` is the older MediaPipe one. LiteRT-LM cannot
> read `.task`. Don't bundle 2.5 GB weights in the APK — Play caps base APKs at 200 MB.

---

## 7. Known issues & things that failed

### ⚠️ Android Studio installed to `Android Studio1`, and a dead folder remains

The installer tried to uninstall the old 2025.3 first, but a Gradle daemon I had running was
holding the old `jbr\bin\java.exe` open, so the uninstall failed partway (**exit code 199**) and
the new install landed in a suffixed directory.

| Path | State |
|---|---|
| `C:\Program Files\Android\Android Studio1` | ✅ **The working Quail 4 install** — 3.3 GB, 3,247 files |
| `C:\Program Files\Android\Android Studio` | ❌ Dead husk — 19 leftover JBR DLLs, 63 MB, cannot launch |

No user data was lost — Studio settings live in `%APPDATA%` / `%LOCALAPPDATA%`, not in either folder.
The Start Menu shortcut and the Add/Remove Programs uninstaller both already point at
`Android Studio1`, so **everything works as-is** and nothing is broken.

The leftover folder is pure dead weight. Deleting it needs an **admin** shell — run this in an
elevated terminal if you want it gone:

```bash
rm -rf "/c/Program Files/Android/Android Studio"
```

I deliberately did **not** rename `Android Studio1` → `Android Studio`: that would desync the
Windows uninstall registry entry and the Start Menu shortcut, trading a cosmetic path for a
broken uninstaller.

### `cmdline-tools` was unregistered and stale

It was a hand-extracted rev **12.0** with no `package.xml`, so Studio's SDK Manager didn't
manage it. Now a properly registered **23.0.0**. Two consequences:

1. It cannot update itself in place on Windows (file locks) — it installs to `latest-2`.
   I promoted that to `latest` and removed the old copy. Its `package.xml` already declared
   `path="cmdline-tools;latest"`, so the promotion was correct.
2. **The CLI changed.** Package specs now use `/` instead of `;`, and there's a new `android.exe`:

   ```bash
   # old (fails on 23.0.0 with "Package ndk not found")
   sdkmanager --install "ndk;30.0.16248370"
   # new
   sdkmanager --install "ndk/30.0.16248370"
   ```

### Corrected mid-run

- I initially installed NDK **r27** and **r29** on the assumption that r29 was the LTS line.
  Google's downloads page states the current LTS is **r30 (30.0.16248370)**, so r30 was installed.
  All three are present; **pin r30**.
- The first warm build **failed** against compileSdk 36 (see [§4](#4-a-note-on-compilesdk-37)).
  Fixed by installing API 37 and moving `compileSdk` to 37.
- The first Gradle config **failed** because AGP 9 rejects the `org.jetbrains.kotlin.android`
  plugin — AGP 9 has built-in Kotlin. The `kotlin { }` block also moved *inside* `android { }`.

### Nothing else failed

Every download was integrity-checked (exact byte size, plus SHA-256 where published). No
corrupt archives, no partial downloads, no unresolved dependencies.

---

## 8. Disk usage added

Free space on `C:` went from ~108 GB to **90 GB** — roughly **18 GB** added.

| Item | Size |
|---|---|
| Android SDK total (3 NDKs dominate) | 9.3 GB |
| Android Studio (new install) | 3.3 GB |
| LLM model packs | 2.9 GB |
| OpenCV zips + extracted | 2.5 GB |
| Studio installer (kept) | 1.4 GB |
| Gradle caches + wrapper | 1.1 GB |
| Gradle 9.7.1 standalone + zip | 290 MB |
| AI Edge Gallery sample (shallow clone) | 25 MB |

Reclaimable if you need space: the Studio installer (1.4 GB), the two OpenCV zips
(620 MB — already extracted), `~/.gradle/wrapper/dists/gradle-8.9-bin` (145 MB, unused),
and `C:\Users\arjun\AndroidVendors\tools\gradle-9.7.1*` (290 MB — the wrapper handles Gradle now).

---

## 9. Recommended tweaks (not done — your call)

**`adb` is not on `PATH`, and `ANDROID_HOME` is unset.** Builds work only because
`local.properties` hardcodes `sdk.dir`. Any script or CI step expecting `adb` on `PATH` will
break, and you'll want it for the eventual scrcpy/device workflow. I left your persistent
environment alone — run this yourself if you want it wired up:

```bash
setx ANDROID_HOME "C:\Users\arjun\AppData\Local\Android\Sdk"
setx PATH "%PATH%;C:\Users\arjun\AppData\Local\Android\Sdk\platform-tools"
```

**`compileSdk = 37` resolves to `android-37.0` (ExtensionLevel 22), not the installed
`android-37.2` (ExtensionLevel 24).** The next androidx/Compose bump that requires ext 24 will
re-trigger exactly the `checkDebugAarMetadata` failure that already broke this build once. If that
happens, the one-line fix is:

```kotlin
android {
    compileSdk = 37
    compileSdkMinor = 2   // -> android-37.2, ExtensionLevel 24
}
```

**Already applied:** `org.gradle.jvmargs` was raised `2048m` → **`4096m`**. 2 GB was tight for this
dependency set (ML Kit + play-services + 4 ABIs, 6 dex files) and would likely have OOM'd on a
Studio sync or any future R8/minify run.

**Cosmetic upstream bug:** `platforms/android-37.2/source.properties` ships unsubstituted
template placeholders (`Platform.Version=${PLATFORM_VERSION}`). Google's packaging issue, harmless
unless a tool reads that field literally.

---

## 10. Quick start

```bash
cd /c/Users/arjun/Desktop/iqoo-prereq/GradleWarmup
./gradlew :app:assembleDebug
```

Builds on the system JDK 17 with no `JAVA_HOME` set. To open in Studio, launch
`C:\Program Files\Android\Android Studio1\bin\studio64.exe` and open that folder —
`local.properties` already points `sdk.dir` at the SDK.

### Explicitly out of scope (not done, by instruction)

USB debugging / `adb devices` / installing on the phone · blackout & OCR feature code ·
Office Kit, HackTracker, Reskilll · signed or release APKs.
