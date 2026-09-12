# Blackout — architecture

How a photo becomes a redacted image, entirely on the phone.

---

## Pipeline

```
Camera / gallery / shared-in image
        │
        ▼
  Bitmap (in memory, never written to disk)
        │
        ▼
  ocr/          ML Kit text recognition        → List<TextSpan>{id, text, SpanRect, confidence}
        │       ReadingOrder.sort()              row-band, then left-to-right; renumber ids
        ▼
  intelligence/ CandidateHints                 → regex signals (WEAK / STRONG)
        │
        ▼
  ocr/          FieldLayout                    → LABEL / VALUE / STANDALONE
        │       two-column geometry + caption lexicon; strong-hint veto
        │
        ├─ Qwen3-0.6B  (workhorse)  every span, batches of 10, constrained JSON
        │       │
        │       └─ refereeQueue: unsure ∪ (keep + STRONG hint) ∪ (hide + no hint)
        │                        ∪ missing ∪ mode-collapsed batches
        │                        — labels excluded (layout has already settled them)
        │
        └─ Gemma-4-E2B (referee)    only the contested *values* + a doc-type summary
        │
        ▼
  MergePolicy.merge()                          → Map<spanId, SpanDecision>
        │   user tap > layout KEEP > referee > workhorse > (hints, degraded only) > KEEP
        ▼
  redact/       Compose overlay (interactive)  ← what you see
                RedactionEngine.render()       ← what you share: bars burned into a COPY
        ▼
  share/        ACTION_SEND, redacted JPEG only
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

`MergePolicy`, `DecisionParser`, `CandidateHints` and `FitTransform` are **pure** — no Android
types — so the decision logic is unit-tested on the JVM with no device and no Robolectric.
`SpanRect` exists instead of `android.graphics.Rect` specifically to keep that boundary.

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

#### Referee queue cap

Gemma is still GPU. `MergePolicy.REFEREE_QUEUE_CAP = 8`: leak-risk (missing decision, KEEP+STRONG,
UNSURE) always goes; uncorroborated HIDE fills remaining slots. Labels never enter the queue
(layout KEEP precision unchanged).

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
3. **Referee** (Gemma).
4. **Workhorse** (Qwen).
5. **Hints** — *only* when degraded.
6. **Default → KEEP.**

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
- **Referee cost.** Gemma on CPU is ~1.7 s/span; a heavily contested page can take minutes. A
  queue cap, or dropping to `Qwen3-1.7B` as referee, is the obvious next lever.
- **Line-granularity spans.** Hiding a line hides its label too when both sit in one OCR line.
  Word-level rects within a hidden line would be tighter. Not started — a different milestone
  than pairing two-column boxes.
- Bitmap lives in the ViewModel, so it won't survive process death. Portrait lock makes config
  changes moot for now.
