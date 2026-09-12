# Blackout — architecture

How a photo becomes a redacted image, entirely on the phone.

---

## Pipeline

`HybridRedactPipeline` runs five stages, and **each one is owned by a different piece of
silicon**. Which silicon actually ran a stage is measured, reported per image, and printed in the
HUD — see [Hybrid silicon ownership](#hybrid-silicon-ownership).

```
Camera / gallery / shared-in image
        │
        ▼
  Bitmap (in memory, never written to disk)
        │
        ▼
A ocr/          ML Kit text recognition        → List<TextSpan>{id, text, SpanRect, confidence}
        │       ReadingOrder.sort()              row-band, then left-to-right; renumber ids
        │       CandidateHints                   regex signals (WEAK / STRONG)
        │       FieldLayout                      LABEL / VALUE / STANDALONE, strong-hint veto
        ▼
B CpuDeterministicStage                  CPU   → settles what no model can beat:
        │   · STRONG identifier (PAN/Aadhaar/IFSC/UPI/email/phone/card/acct) → HIDE
        │   · VALUE paired with a sensitive caption                          → HIDE
        │   · field label / letterhead                                       → KEEP
        │   These spans are removed from the model queue entirely.
        ▼
C NpuClassifyStage                       NPU   → SKIPPED (no QNN dispatch / no NPU model)
        │                                        gate: dispatch + vendor HTP + QAIRT + no crash marker
        ▼
D GpuLlmCascadeStage                     GPU   → leftovers only
        │   ├─ Qwen3-0.6B  (workhorse)  batches of 10, constrained JSON
        │   │      └─ refereeQueue: unsure ∪ (keep + STRONG hint) ∪ (hide + no hint)
        │   │                       ∪ missing ∪ mode-collapsed — capped at 8, labels excluded
        │   └─ Gemma-4-E2B (referee)    contested values + a doc-type summary
        │          RefereeBudget can skip Gemma entirely (dense page / small print)
        ▼
E MergePolicy.merge()                    CPU   → Map<spanId, SpanDecision>
        │   user tap > layout KEEP > deterministic > referee > workhorse > (hints, degraded) > KEEP
        ▼
  redact/       Compose overlay (interactive)  ← what you see
                RedactionEngine.render()       ← what you share: bars burned into a COPY
        ▼
  share/        ShareGuard, then ACTION_SEND, redacted JPEG only
```

**No network.** The app declares no `INTERNET` permission (and `tools:node="remove"`s the
copy ML Kit datatransport / media3 would merge), so a cloud call on this path isn't merely
absent by policy — the process cannot make one.

---

## Layering

| Package | Responsibility | Android deps |
|---|---|---|
| `ocr/` | ML Kit adapter, `TextSpan`, `SpanRect`, reading order | ML Kit only |
| `intelligence/` | hints, prompts, parsing, cascade, merge policy | LiteRT-LM only |
| `redact/` | burn bars into a bitmap copy, hit-testing | Canvas |
| `share/` | export + share sheet | FileProvider |
| `ui/` | Compose screens, permission state, ViewModel | Compose |

`MergePolicy`, `DecisionParser`, `CandidateHints`, `CpuDeterministicStage`, `RefereeBudget`,
`BackendReport`, `ShareGuard` and `FitTransform` are **pure** — no Android
types — so the decision logic is unit-tested on the JVM with no device and no Robolectric.
`SpanRect` exists instead of `android.graphics.Rect` specifically to keep that boundary.

---

## Hybrid silicon ownership

The split is not decoration. It comes from Phone C's 17 hostile captures and 7 A/B pairs
(`C-Outputs/SUMMARY.md`, `C-Outputs/compare.csv`, `C-Outputs/label-bugs.jsonl`), which showed the
two things holding this app back were both *wrong-tool* problems:

- **The repeatable quality bug is structural, not semantic.** 29 label/value inversions across 9
  documents: `Account Holder` blacked out, `Priya Ramachandran` left readable; same for DOB, IFSC,
  addresses, payslip HRA/LTA/PF captions (`C-001`, `C-002`, `C-003`, `C-017`). The referee restored
  some letterheads after ~19 s and never once fixed the pairing.
- **The latency is volume, not model size.** Median 53 s full cascade vs 30 s workhorse-only over
  six pairs. Asking two models about every span is the cost.

So each stage goes to the silicon that is actually good at it:

| Stage | Silicon | Owns | Why |
|---|---|---|---|
| `OcrStage` (ML Kit) | CPU/NPU (ML Kit's choice) | text + boxes | already on-device |
| `CpuDeterministicStage` | **CPU** | identifier regexes, label/value pairing, letterhead KEEP | a regex beats a 0.6B on `BDFPN2201L`, and geometry beats it on `Account Holder` |
| `NpuClassifyStage` | **NPU** | small span classifier | **SKIPPED today** — see the NPU section. Never claimed unless it ran |
| `GpuLlmCascadeStage` | **GPU** | Qwen3-0.6B → Gemma-4-E2B over *leftovers* | judgement calls: narration, prose, unpaired values |
| `MergePolicy` + `RedactionEngine` + `ShareGuard` | CPU | one verdict per span, pixels, export | product rules, unchanged |

The models never rewrite OCR text — they return verdicts keyed by span id, and `UNSURE` still
means "stay visible".

### Measured effect (iQOO 15 / I2501, GPU, `tools/testdoc-bank.png`, 47 spans)

| | before (single cascade) | after (hybrid) |
|---|---|---|
| spans sent to Qwen | 47 | **19** |
| referee queue | 8 | **5** |
| `workhorse_ms` | 18338 | **7580** |
| `referee_ms` | 10109 | **7945** |
| `total_ms` | 28803 | **15871** |
| over-redaction (must-keep hidden) | 1/24 = 4.2 % | **0/24 = 0 %** |
| HIDE precision / recall | 0.929 / 0.650 | **1.000 / 0.700** |

```
cpu·det settled 28/47 spans (hide 11, keep 17) in 1ms; 19 left for the models
hybrid: CPU·det 28 | NPU·cls skip | GPU·qwen 19 · gemma 5 | total 15.9s
sources {DETERMINISTIC: 13, LAYOUT: 15, WORKHORSE: 12, REFEREE: 4}
```

Re-running C's own hostile fixtures on this build:

| case | C measured | this build |
|---|---|---|
| `C-005-motion-blur` | 7490 ms, `spans=1 hide=0`, ordinary Share button | 0 ms of inference (no engine loaded), **Share warns** |
| `C-008-small-print` | 70110 ms, hide 13 → **39** (black slab) | **9266 ms**, hide 31 from 27 exact regex hits, referee skipped |
| `C-015-dense-ledger` | 266908 ms, hide 120, PAN/IFSC stripe still visible | **2086 ms**, hide 141/141, referee skipped |

### The HUD line

`BackendReport.hudLine()` prints one receipt per image:

```
CPU·det 28 | NPU·cls skip | GPU·qwen 19 · gemma 5 | total 15.9s
```

Groups are built from `StageReport`s, and an LLM stage's silicon comes from
`LlmRuntime.backendLabel` — which is only set after a one-token warm-up generation *returned*. So
on a handset without OpenCL the same page reads `CPU·det 28 · qwen 19 · gemma 5`, and
`NPU·cls ok` is unreachable unless an NPU inference happened. `BackendReportTest` pins that.

---

## Models

| Role | File | Size | Purpose |
|---|---|---|---|
| Workhorse | `qwen3_0.6b_q4_block32_ekv1280.litertlm` | 347 MB | triage every span |
| Referee | `gemma-4-E2B-it.litertlm` | 2.59 GB | settle contested spans, name the doc type |

Runtime: `com.google.ai.edge.litertlm:litertlm-android:0.17.0`.

### Getting the weights onto the phone

Weights are **not** in the APK — Gemma alone is 2.5 GB. They're side-loaded into app-specific
external storage, which needs no runtime permission and is removed on uninstall:

```bash
adb shell mkdir -p /sdcard/Android/data/com.blackout.app/files/models
adb push qwen3_0.6b_q4_block32_ekv1280.litertlm /sdcard/Android/data/com.blackout.app/files/models/
adb push gemma-4-E2B-it.litertlm               /sdcard/Android/data/com.blackout.app/files/models/
```

> On Windows/Git Bash, prefix with `MSYS_NO_PATHCONV=1` and give the **local** path in Windows
> form (`C:/...`) — otherwise `/sdcard/...` is rewritten to `C:/Program Files/Git/sdcard/...`.

`ModelCatalog` looks in `getExternalFilesDir("models")` then `filesDir/models`. Missing weights
are not an error: the app runs the regex-only path and labels itself **degraded · patterns only**
in the HUD.

### Backend: NPU → GPU → CPU, proven by warm-up

`LiteRtLlmRuntime` tries **NPU, then GPU, then CPU**. Each candidate must complete a one-token
warm-up generation before it is committed. **Warm-up success is not enough for NPU:** LiteRT-LM
can log `Unsupported dispatch runtime version`, fall through to XNNPACK, and still return OK.
We only *construct* `Backend.NPU` when dispatch is on disk **and** either an SoC AOT pack or a
complete JIT set (`libLiteRtCompilerPlugin_Qualcomm.so` + `libQnnIr.so` + `libQnnSaver.so` +
`libQnnHtpPrepare.so`) is present. A dispatch-only drop-in is refused so the HUD cannot say
NPU for CPU.

On the iQOO 15 (2026-09-12, second pass) the HUD is **`on-device · local models · GPU`**.

#### The NPU gate (`NpuGate`, `NpuClassifyStage`)

`NpuClassifyStage` is the NPU's slot in the hybrid pipeline — a small span classifier between the
CPU rules and the GPU cascade. It reports `SKIPPED` on this device and says why. Four things must
all be true before anything is attempted:

1. no crash marker from a previous launch,
2. `libLiteRtDispatch_Qualcomm.so` in the APK's lib dir,
3. vendor `libQnnHtp.so` on the device,
4. a readable `libQnnHtpV*Stub.so`, plus the complete QAIRT set for JIT.

**The Hexagon generation is read, not hard-coded.** The brief said V79; this handset ships
`libQnnHtpV81Stub.so` / `libQnnHtpV81Skel.so`, and LiteRT's `supported_soc.csv` maps
`Qualcomm,SM8850,v81,87`. `NpuSupport.hexagonGeneration()` scans `/vendor/lib64`,
`/vendor/lib64/hw` and `/odm/lib64` and returns whatever it finds, so the gate is right on the
device in front of us instead of on a number in a document. (The matching *skel* lives on the DSP
side under `/vendor/lib/rfsa/adsp`, which an app process cannot list — the stub is the readable
half of the pair.)

**Crash marker.** A mismatched dispatch runtime does not throw: it calls `abort()` inside
`Engine.initialize()`, killing the process with nothing catchable. So `NpuSupport.beginNpuAttempt`
writes a marker file — containing a fingerprint of the dispatch `.so` — immediately before
`Backend.NPU` is constructed, and deletes it as soon as that attempt has returned *or* thrown. If
the marker is still there next launch, NPU is not attempted again. Because the marker records
*which* library crashed, dropping in a different one (the whole point of the AI Hub follow-up)
re-arms the attempt automatically instead of needing app data cleared.

#### RESOLVED 2026-09-12: real Hexagon tokens, via GenieX

Everything below this heading's original ask-list was reachable without the Qualcomm
software-centre login that returns 403. Measured on the loaner:

```
NPU PROBE ok compute=npu tokens=11 elapsed_ms=116 tok_per_s=94.83
profile=ProfilingData(ttftMs=16.98, promptTokens=36, generatedTokens=12,
                      prefillSpeed=2120.14, decodingSpeed=130.22, stopReason=eos)
text: "Gravity is the force that pulls objects toward each other."
```

**Prefill 2120 tok/s, decode 130 tok/s, TTFT 17 ms** on Qwen3-0.6B w4a16 - the same model the
GPU path runs as the workhorse, where it takes ~7.6 s for 19 spans.

CDSP proof, from the app's own process (`/proc/<pid>`):

| Evidence | Value |
|---|---|
| open fd | **`/dev/fastrpc-cdsp`** - the compute-DSP node |
| mapped | `libQnnHtpV81Stub.so` (Hexagon **v81**) |
| mapped | `libQnnHtp.so`, `libQnnSystem.so`, `/vendor/lib64/libcdsprpc.so` |
| mapped | `libgeniex_plugin_qairt.so` |
| plugin | `getPluginVersion("qairt")` -> `v2.45.0.260326`, matching the bundle's `tool_versions.qairt` |

**What was actually missing, and where it came from**

| Previously recorded blocker | Resolution |
|---|---|
| No `libLiteRtDispatch_Qualcomm.so` | Present all along in `AndroidVendors/litert-npu/{v2.1.5,v2.1.6}/…/qualcomm_runtime_v81/`; only v2.2.0's Gradle stubs had been checked |
| QAIRT trio unreachable (403) | **`com.qualcomm.qti:geniex-android` is on Maven Central.** Its AAR ships `libQnnHtp.so`, `libQnnHtpV81{,Skel,Stub}.so`, `libQnnSystem.so`, `libQnnHtpPrepare.so`, `libQnnIr.so`, `libQnnSaver.so` |
| "No SM8850 pack published" | **Wrong.** `qualcomm/Qwen3-0.6B` publishes `qwen3_0_6b-geniex_qairt-w4a16-qualcomm_snapdragon_8_elite_gen5.zip` (643 MB) on public S3, ungated. Snapdragon 8 Elite Gen 5 *is* SM8850 |

Bundle config confirms the target: `soc_model: 87, dsp_arch: v81, perf_profile: burst`.

**Three traps worth recording**

1. `nCtx` **must be 0** for the QAIRT plugin. Context size, sampler and backend all come from the
   bundle's own `genie_config.json`; passing our own gives `Parameter not supported by this plugin`.
2. `model_path` is **`genie_config.json`**, not the bundle directory - a directory gives
   `File not found or inaccessible`.
3. Side-loading must push **files into an app-created directory**. Anything created by
   `adb shell mkdir` or implicitly by `adb push <dir>` is owned by `shell`, and the app cannot
   traverse it: the bundle reads as present but empty.

The 643 MB bundle is side-loaded to `files/npu/`, never bundled in the APK or committed.
`minSdk` moved 26 -> 27, the floor GenieX imposes.

Still true: **QAIRT grammar/JSON generation fails on device.** When the workhorse moves here it
must ask for a bounded plain-text hide-list (`1 4 7`), not JSON - which is also cheaper.

---

#### What this phone actually is

```
adb shell getprop ro.soc.model    # SM8850   Snapdragon 8 Elite Gen 5
```

Not SM8750 (that's the previous Elite). Qualcomm AOT `.litertlm` packs are per-SoC context
binaries; loading `*_qualcomm_sm8750.litertlm` on SM8850 is the wrong blob.

#### Weights that exist today (Hugging Face `litert-community`, 2026-09-12)

| File | SoC | Usable as |
|---|---|---|
| `qwen3_0.6b_q4_block32_ekv1280.litertlm` | generic CPU/GPU | workhorse (what we ship) |
| Qwen3-0.6B Qualcomm pack | **none published** (`litert-community/Qwen3-0.6B` and `Qwen3-0.6B-int4` listed) | — |
| `gemma-4-E2B-it.litertlm` | generic CPU/GPU | referee (what we ship) |
| `gemma-4-E2B-it_qualcomm_sm8750.litertlm` | SM8750 | **not this phone** |
| `gemma-4-E2B-it_qualcomm_qcs8275.litertlm` | QCS8275 | **not this phone** |
| `Gemma3-1B-IT_q4_ekv1280_sm8850.litertlm` (662 MB) | SM8850 | **gated** (`401 GatedRepo`) — not swapped in |

#### Runtime libraries (checked 2026-09-12)

`litertlm-android:0.17.0` ships **only** `liblitertlm_jni.so`. Its LiteRT pin is
`LITERT_REF=9fe5be45564c868408e6514c8aabb83e211a0911` (LiteRT-LM `v0.17.0` WORKSPACE,
updated 2026-08-27).

| Artifact | Result |
|---|---|
| `litert_npu_runtime_libraries_jit.zip` **v2.2.0** (sha256 `d6d16010…`) | Gradle stubs only. **No** `libLiteRtDispatch_Qualcomm.so`. |
| Same zip **v2.1.6** | Contains dispatch + compiler plugin for v81. |
| Dropping the v2.1.6 `.so` into this 0.17.0 app | Dispatch **loads**, then `litert_dispatch.cc:187 Unsupported dispatch runtime version`. Plugin `dlopen` fails (`libQnnIr.so not found`). `initialize()` + 1-token warm-up **succeed**. HUD said `NPU`. Workhorse 30971 ms (slower than GPU). **This was a silent XNNPACK fallback — we unshipped those `.so` files so the HUD cannot lie.** |
| QAIRT 2.47.0.260601 community zip | `403 Forbidden` from Qualcomm software center (no login). Blocks `libQnnHtpPrepare.so` / `libQnnIr.so` / `libQnnSaver.so`. |
| Device vendor | Hexagon v81 `libQnnHtp.so` + V81 stub/skel + `libQnnSystem.so` + `libcdsprpc.so`. **No** `libQnnHtpPrepare.so`. |
| Open request | [LiteRT #6889](https://github.com/google-ai-edge/LiteRT/issues/6889) (open, last ping 2026-09-03): publish ABI-matched dispatch next to each `litertlm-android` AAR. |

Official enable path (Linux/macOS + NDK; **Windows is “coming soon”** on the Qualcomm LiteRT page):

```bash
# match the AAR, not HEAD
git clone https://github.com/google-ai-edge/LiteRT-LM
git -C LiteRT-LM checkout v0.17.0
bazel build --config=android_arm64 \
  @litert//litert/vendors/qualcomm/dispatch:dispatch_api_so
```

Copy the resulting `libLiteRtDispatch_Qualcomm.so` (and, for JIT, the compiler plugin + QAIRT
`libQnnIr.so` `libQnnSaver.so` `libQnnHtpPrepare.so` from SDK 2.47) into
`app/src/main/jniLibs/arm64-v8a/` (gitignored; Qualcomm license). Rebuild. Then either:

```bash
adb push qwen3_0.6b_q4_block32_ekv1280_qualcomm_sm8850.litertlm \
  /sdcard/Android/data/com.blackout.app/files/models/
```

or rely on JIT of the generic file **only if** `logcat` shows
`1 compiler plugins were applied successfully` and
`Replacing N out of N node(s) with delegate (DispatchDelegate)` — never trust `loaded on NPU`
alone.

**Ask list (organizers / Google / Qualcomm)**

1. Ship `libLiteRtDispatch_Qualcomm.so` ABI-matched to `litertlm-android:0.17.0` (LiteRT #6889).
2. Restore the `.so` files in `litert_npu_runtime_libraries_jit.zip` for 2.2.0+ (present in 2.1.6, gone in 2.2.0).
3. Publish `*_qualcomm_sm8850.litertlm` for Qwen3-0.6B and Gemma-4-E2B.
4. If swapping workhorse is acceptable: HuggingFace access to gated `Gemma3-1B-IT_q4_ekv1280_sm8850.litertlm`.
5. QAIRT 2.47 community zip (or redistributable `libQnnHtpPrepare.so` + `libQnnIr.so` + `libQnnSaver.so`).

Until then, `BlackoutLlm` logs `NPU skipped for …: no libLiteRtDispatch_Qualcomm.so` and the
cascade stays on GPU/CPU.

Official refs:
- https://ai.google.dev/edge/litert-lm/android
- https://developers.google.com/edge/litert/next/npu
- https://developers.google.com/edge/litert/next/qualcomm (SM8850 listed; Windows SDK “coming soon”)
- https://huggingface.co/litert-community/Qwen3-0.6B-int4
- https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm

Device paths:

```
/sdcard/Android/data/com.blackout.app/files/models/     # side-loaded weights
<apk>/lib/arm64/liblitertlm_jni.so                      # only JNI the AAR ships
app/src/main/jniLibs/arm64-v8a/*.so                     # local fetch; gitignored
```

Kit-transfer of the 2.5 GB referee: USB `adb push` from the laptop, or copy into the same
`files/models/` folder via Office Kit. Do **not** put `.litertlm` files in the APK.

#### Referee budget: when Gemma runs at all

Two independent brakes, both from Phone C's A/B data:

**Queue cap.** `MergePolicy.REFEREE_QUEUE_CAP = 8`: leak-risk (missing decision, KEEP+STRONG,
UNSURE) always goes; uncorroborated HIDE fills remaining slots. Labels never enter the queue
(layout KEEP precision unchanged).

**Page-shape veto** (`RefereeBudget`). The referee is skipped entirely when:

| Rule | Threshold | Evidence |
|---|---|---|
| dense page | `spans > 100` | `C-015`: 141 spans, `referee_ms=174627`, and hide went 131 → **120** — it *removed* redactions and still left a PAN/IFSC stripe readable |
| small print | `spans ≥ 40` and median line height `< 1.0 %` of page height | `C-008`: 6–9 pt MSA, hide 13 → **39**, +35 s, page became an unreadable slab |

Both are quality decisions as much as latency ones: on those two pages the *workhorse-only* output
was strictly better than the refereed output. The threshold is measured, logged
(`median_h=` in `BlackoutStats`) and surfaced in the debug panel as `no-gemma`, so it can be
re-tuned against evidence rather than guessed at. Reference points on device:
`C-008` median 13 px = 0.007 (skip), `C-015` median 9 px (skipped on span count first),
`testdoc-bank` median 22 px = 0.0125 (**runs**), C's 10–11 pt statements ~0.013 (runs).

#### GPU (this phone)

Declaring `<uses-native-library android:name="libOpenCL.so" android:required="false"/>`
is what made GPU warm-up succeed on OriginOS. Before that, init succeeded and generate failed
with `Can not find OpenCL library`. GPU is **not** faster than the previous CPU run on this
fixture (~18 s Qwen / ~18.6 s Gemma referee either way).

Measured 2026-09-12 on I2501, `testdoc-bank.png`, 47 spans (GPU, referee cap 8):

```
NPU skipped for Qwen3-0.6B-int4: no libLiteRtDispatch_Qualcomm.so in …/lib/arm64
Qwen3-0.6B-int4 loaded on GPU in 3294ms
Gemma-4-E2B-it loaded on GPU in 2894ms
spans=47 ocr_ms=180 workhorse_ms=18338 summary_ms=356 referee_ms=10109 total_ms=28803
hide=16 keep=31 backend=GPU degraded=false referee_queue=8
HUD: on-device · local models · GPU
over-redaction 1/24 = 4.2% (layout KEEP intact)
```

Referee before the cap on the same fixture/GPU was 12879–18640 ms at `referee_queue=14`. Cap cut wall time to 10109 ms. Leak-risk items still go; the leftover must-keep hide is still `19/08`.

#### Phones B/C — Tejesh copy list (same iQOO 15 / SM8850)

APK: debug `com.blackout.app` from this tree (`:app:installDebug`). Then copy **only** the
generic CPU/GPU weights — same as phone A — into
`/sdcard/Android/data/com.blackout.app/files/models/`:

```
qwen3_0.6b_q4_block32_ekv1280.litertlm     347,251,840 B
gemma-4-E2B-it.litertlm                   2,588,147,712 B
```

Do **not** copy `*_qualcomm_sm8750.litertlm`. There is no dispatch `.so` and no SM8850 pack
to copy. HUD must read `GPU` (or `CPU` if OpenCL is missing on that unit), **never** `NPU`.

---

## Prompting

Everything here is shaped by Qwen3's **1280-token** KV cache (`ekv1280`), and by what a 0.6B
actually does rather than what it should do.

- **Per-batch local ids 1..N.** `"id":7` is one token; `"id":1247` is three — and a 0.6B copies
  single digits back faithfully while corrupting four-digit ones. The caller keeps the mapping.
- **No `reason` from the workhorse.** ~13 tokens/span, ~10% of the window, roughly doubles decode,
  and at this size it's post-hoc confabulation. The referee produces reasons; that's where the
  UI's "why" comes from.
- **Thinking off, three ways.** Qwen3 is a hybrid-thinking model; an unbudgeted `<think>` block
  eats the entire cache and emits zero decisions. `ThinkingConfig(enableThinking = false)` +
  `extraContext["enable_thinking"] = false` + a literal `/no_think`, and the parser strips any
  `<think>` that still leaks.
- **A fresh `Conversation` per batch.** The engine is expensive, a conversation is cheap, and
  reusing one accumulates KV until batch 3 silently truncates.
- **Greedy via `topK = 1`**, temperature left at 1.0 (with topK=1 the argmax is already forced).

### Constrained decoding, and the `minItems` bug it papered over

`ResponseFormat.json(schema)` guarantees well-formed JSON. It does **not** guarantee useful JSON:
the decoder was perfectly happy to emit `{"decisions":[]}` — schema-valid, and observed on a real
batch containing an account number, a PAN and an Aadhaar. Every span fell through to `keep`: a
total, silent redaction failure.

The schema is therefore built per-batch with `"minItems": n, "maxItems": n`, forcing exactly one
decision per span.

`DecisionParser` still scans for `{...}` objects independently rather than parsing the document
whole, so a truncated array contributes every complete object before the cut. Duplicate ids
resolve **most-protective-wins** (KEEP < UNSURE < HIDE): a repeated id means the model was
confused, and a wrong hide is one tap from being undone whereas a leak is not.

---

## The merge policy

Priority, highest first:

1. **User tap** — always wins.
2. **Layout** — a span identified as a field label stays visible. Beats both models.
3. **Deterministic** (`CpuDeterministicStage`) — a high-confidence identifier pattern, or the value
   paired with a sensitive caption. Above both models because C measured them getting exactly these
   spans wrong, in both directions, on every two-column form. In practice there is nothing to
   conflict with: these spans were never sent to a model.
4. **Referee** (Gemma).
5. **Workhorse** (Qwen).
6. **Hints** — *only* when degraded.
7. **Default → KEEP.**

Two properties this encodes:

**UNSURE is not HIDE.** An unresolved span stays visible. A false positive silently destroys
information the user wanted; a false negative is on screen and one tap from fixed.

**Hints are never the sole decision.** They are deliberately withheld from the workhorse prompt,
which keeps the regex signal and the model signal independent — that's what makes their
*disagreement* informative. A hint can escalate a span to a model; it can never redact one by
itself (outside the degraded path, which the UI labels).

### What gets escalated to the referee

| Trigger | Failure it catches |
|---|---|
| `unsure` | the workhorse abstained |
| `keep` + STRONG hint | false negative — regex disagrees |
| `hide` + no hint | false positive on a *value* or unpaired span — labels never reach this rule |
| no decision returned | empty/short batch; escalating beats defaulting to keep |
| mode-collapsed batch | uniform verdict across ≥4 spans is a decoding artefact, not a judgement |

The last two were added after watching real failures. Greedy decoding over a constrained grammar
makes a 0.6B latch onto its first action and repeat it — a batch holding a PAN, an Aadhaar and
their labels came back as ten consecutive `hide`. A uniform batch is treated as a *confidence
signal*, not a verdict.

The `hide + no hint` rule is what lets the referee **remove** redactions on unpaired text: on the
test statement it restored "MERIDIAN BANK" (*"Bank name is generic label"*) while keeping every
value hidden. Field *labels* no longer depend on that rule — `FieldLayout` keeps them visible
before the referee runs, because Gemma was the thing confirming most of the wrong hides (it
sees the value as neighbour context).

Labels still go to the **workhorse**. Dropping them would destroy the label/value adjacency
`ReadingOrder` exists to create, and leave all-value batches that trip `isModeCollapsed`.
MergePolicy then overrides any `hide` on a LABEL. A strong regex hint vetoes LABEL status, so a
name printed in the left column stays redactable.

Stacked ID cards (caption above value, same left edge) are left entirely alone — a lone
left-column span is never a label, which is what keeps `PRIYA RAMACHANDRAN` on a PAN card
hideable.

---

## Redaction and export

Two different renderings, on purpose:

- **On screen** — a Compose `Canvas` overlay. Re-rendering a 1536×2048 bitmap on every tap would
  stutter; the overlay makes toggling instant.
- **On export** — `RedactionEngine.render()` allocates a **copy** and burns filled rects into
  pixels. Nothing blurs or pixelates: only an opaque rect can't be inverted back out.

### The share guard

`ShareGuard` sits in front of the share sheet. Phone C's `C-005-motion-blur`: a camera-shaken bank
statement gave ML Kit **one** span, so `hide=0`, and the app offered an ordinary `Share` button
over a page whose PAN, account number, IFSC, phone, email and address were all legible. Every
component behaved correctly and the user still got a confident-looking result with nothing hidden.

The rule compares span count to image *area*: fewer than **4 recognised regions per megapixel**
with nothing redacted means either "this isn't a document" or "we couldn't read the document", and
we can't tell which — so it warns and requires a confirmation instead of blocking, because sharing
an ordinary photo through Blackout is legitimate. `C-005` scores 0.5 spans/MP (warns);
`testdoc-bank` ~21 and C's readable statements 30–60 (silent).

`ShareRedacted.share()` takes the *composed* bitmap. It has no access to the original and no
parameter through which one could be passed, so there is no code path that writes the unredacted
image to disk. Re-encoding to JPEG also means **no EXIF survives** — no timestamp, no device, no
GPS. Verified by pulling the exported file off the device: 119 031 bytes byte-for-byte, valid
JPEG, bars present in the decoded pixels, `getexif()` empty.

`FitTransform` maps bitmap↔view coordinates for `ContentScale.Fit` and is pure and tested —
getting it wrong puts bars *next to* the text they should cover.

---

## Failure behaviour

| Situation | Result |
|---|---|
| Camera permission denied | rationale card + retry; permanent denial routes to Settings |
| No model files | regex-only path, HUD shows **degraded · patterns only** |
| Backend won't sample | next backend; all failed → degraded |
| A workhorse batch throws | that batch is skipped, its spans escalate |
| Referee fails | workhorse verdicts stand; not fatal |
| OCR finds no text | "No text found", image still shareable |

Nothing fails silently into "share the original".

---

## Known gaps

- **Residual label misses.** Two-column forms with a detected label column keep captions visible
  structurally. Captions outside that pattern (stacked PAN/Aadhaar cards, one-off headings,
  lexicon misses) still go to the models. Conservative; one tap fixes each.
- **Hostile geometry — no deskew.** Bars are axis-aligned, so a skewed, crumpled or 90°-rotated page
  gets rectangles next to its text: `C-006` (36° skew, 3 misplaced bars), `C-010` (crumpled, 80
  spans), `C-016` (rotated 90°, 87 spans of sideways soup). This is an OCR/geometry problem and more
  model does not touch it — C measured the referee spending 5.6 s on `C-006` for an identical
  result. Deskew/OSD before OCR is the fix and is **not** in this milestone.
- **Devanagari.** `C-007`: **0 of 8** Hindi strings read (tofu boxes); the Latin name and DOB on the
  same page were caught by pairing. Needs a Devanagari recognizer, not a bigger LLM.
- **Amounts and narration still leak.** On `testdoc-bank` the remaining 6/20 misses are all model
  judgement calls the CPU stage deliberately does not touch: `Rs 1,24,500.00`, `-4,200.00`,
  `UPI to RAHUL MEHTA`, `NEFT to LANDLORD S IYER`. Widening the regexes to catch these is how you
  get C-008's black slab, so they stay with the models.
- **Line-granularity spans.** Hiding a line hides its label too when both sit in one OCR line.
  Word-level rects within a hidden line would be tighter. Not started — a different milestone
  than pairing two-column boxes.
- Bitmap lives in the ViewModel, so it won't survive process death. Portrait lock makes config
  changes moot for now.

---

## Tilted captures: the second silent-share hole

`ShareGuard` originally returned early whenever `hideCount > 0`. That left a gap it was built to
close: a tilted photograph still produces *some* bars, so the page looks processed and the
sparse-text rule never fires.

`TextSpan.angleDeg` carries ML Kit's `Text.Line.getAngle()`, and `SkewMetrics.medianAbsAngle()`
folds it onto 0–90° (modulo 180, because text rotated a full 180° still runs along horizontal
lines — it is *tilt* that breaks axis-aligned boxes, not being upside down).

Measured on `tools/testdoc-bank.png` rotated by a known amount (iQOO 15, 2026-09-12):

| capture | median angle | spans the CPU stage settled | median span height | hide |
|---|---|---|---|---|
| upright | ~0° | **28 / 47** | 22 px | 15 |
| 12° | 11.9° | 19 / 47 | 65 px | 23 |
| 30° | 30.0° | **8 / 47** | 123 px | 12 |
| 90° | 90.0° | **6 / 49** | 198 px | 19 |

ML Kit reports the angle accurately (11.6–12.0° on the 12° fixture, 29.7–30.2° on the 30°). Two
things degrade together: the axis-aligned box around a slanted line inflates ~9× so bars become
loose blocks, and OCR quality drops so the deterministic identifier regexes stop matching —
the CPU stage settles 28 spans upright and 6 at 90°. Sensitive values simply stop being found
while the page still looks redacted.

`SkewMetrics.WARN_DEGREES = 8` sits above handheld jitter (~0° measured) and below the 12°
fixture, which already costs a third of the deterministic detections. The guard is checked
*before* the `hideCount` short-circuit, and `ShareGuard.Reason` lets the dialog pick an honest
headline — "This page looks tilted" rather than the C-005 "Almost no text was read".

This is a warning, not a fix. Deskew/OSD before OCR (C-006 / C-010 / C-016) remains the real
answer; this stops the page leaving silently in the meantime.
