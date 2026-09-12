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
warm-up generation before it is committed. The HUD prints whatever actually sampled
(`on-device · local models · GPU` today; `· NPU` only after NPU warm-up succeeded). Mixed cascades
show as `NPU+CPU`.

On the iQOO 15 (2026-09-12) it lands on **GPU**. NPU is skipped, not failed-over after a crash.
That is a measured outcome, not a HUD lie.

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
| Qwen3-0.6B Qualcomm pack | **none published** | — |
| `gemma-4-E2B-it.litertlm` | generic CPU/GPU | referee (what we ship) |
| `gemma-4-E2B-it_qualcomm_sm8750.litertlm` | SM8750 | **not this phone** |
| `gemma-4-E2B-it_qualcomm_qcs8275.litertlm` | QCS8275 | **not this phone** |
| `Gemma3-1B-IT_q4_ekv1280_sm8850.litertlm` | SM8850 | different model, gated Gemma 3 |

There is **no** Qwen3-0.6B Qualcomm pack and **no** Gemma-4-E2B SM8850 pack. Official LiteRT-LM
NPU docs only list Gemma3-1B for SM8750/SM8650/SM8550 on Qualcomm, plus the SM8850 Gemma3-1B
file above. We do not swap the referee to Gemma3-1B in this build (gated weights, different
prompts, quality unknown). When an `…_qualcomm_sm8850.litertlm` for *our* filenames lands in
`files/models/`, `ModelCatalog.locateNpu` will pick it up automatically.

#### Runtime libraries

`litertlm-android:0.17.0` ships **only** `liblitertlm_jni.so`. `Backend.NPU(nativeLibraryDir)`
needs `libLiteRtDispatch_Qualcomm.so` in that directory. Without it, initialize SIGABRTs
(`No usable Dispatch runtime found`) — uncatchable from Kotlin — so we **never construct
Backend.NPU** unless that file is present.

The phone *does* have Hexagon v81 QNN in vendor:

```
/vendor/lib64/hw/libQnnHtp.so
/vendor/lib64/hw/libQnnHtpV81Stub.so
/vendor/lib64/hw/libQnnHtpV81Skel.so
/vendor/lib64/hw/libQnnSystem.so
/vendor/lib64/libcdsprpc.so
```

No `libQnnHtpPrepare.so` (needed for on-device JIT) and no Google dispatch `.so`. Manifest
declares the vendor libs with `required=false` so a future dispatch drop-in can dlopen them.

To enable NPU later (do **not** bake 2.5 GB into the APK):

1. Build `libLiteRtDispatch_Qualcomm.so` from the LiteRT revision that matches this AAR
   (`bazel build --config=android_arm64 @litert//litert/vendors/qualcomm/dispatch:dispatch_api_so`
   — Linux/macOS + NDK r28b; ABI must match the AAR or dispatch init fails).
2. Copy it to `app/src/main/jniLibs/arm64-v8a/` and rebuild.
   `android.packaging.jniLibs.useLegacyPackaging = true` is already set so the `.so` is
   extracted to `nativeLibraryDir`.
3. Push SoC-matched weights next to the generic ones:

```bash
adb push qwen3_0.6b_q4_block32_ekv1280_qualcomm_sm8850.litertlm \
  /sdcard/Android/data/com.blackout.app/files/models/
adb push gemma-4-E2B-it_qualcomm_sm8850.litertlm \
  /sdcard/Android/data/com.blackout.app/files/models/
```

4. Confirm HUD / `adb logcat -s BlackoutLlm` shows `loaded on NPU` after warm-up, not after
   `initialize()` alone.

Until those two artifacts exist, `BlackoutLlm` logs
`NPU skipped for …: no libLiteRtDispatch_Qualcomm.so in …` and the cascade stays on GPU/CPU.

Official refs (do not use blog SoC slugs):
- LiteRT-LM Android NPU: https://ai.google.dev/edge/litert-lm/android
- Hugging Face `litert-community` Qwen3-0.6B-int4 (generic only):
  https://huggingface.co/litert-community/Qwen3-0.6B
- Gemma-4-E2B Qualcomm packs (`sm8750`, `qcs8275` — **not** `sm8850`):
  https://huggingface.co/litert-community/Gemma-4-E2B

Device paths:

```
/sdcard/Android/data/com.blackout.app/files/models/     # side-loaded weights
<apk>/lib/arm64/liblitertlm_jni.so                      # only JNI the AAR ships
<apk>/lib/arm64/libLiteRtDispatch_Qualcomm.so           # missing; required for Backend.NPU
```

Kit-transfer of the 2.5 GB referee: USB `adb push` from the laptop, or copy into the same
`files/models/` folder via Office Kit. Do **not** put `.litertlm` files in the APK.

#### GPU (this phone)

Declaring `<uses-native-library android:name="libOpenCL.so" android:required="false"/>`
is what made GPU warm-up succeed on OriginOS. Before that, init succeeded and generate failed
with `Can not find OpenCL library`. GPU is **not** faster than the previous CPU run on this
fixture (~18 s Qwen / ~18.6 s Gemma referee either way).

Measured 2026-09-12 on I2501, `testdoc-bank.png`, 47 spans:

```
NPU skipped for Qwen3-0.6B-int4: no libLiteRtDispatch_Qualcomm.so in …/lib/arm64
Qwen3-0.6B-int4 loaded on GPU in 5708ms (qwen3_0.6b_q4_block32_ekv1280.litertlm)
Gemma-4-E2B-it loaded on GPU in 7293ms (gemma-4-E2B-it.litertlm)
spans=47 ocr_ms=174 workhorse_ms=18010 summary_ms=735 referee_ms=18640 total_ms=37385
hide=14 keep=33 backend=GPU degraded=false
HUD: on-device · local models · GPU
```

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
