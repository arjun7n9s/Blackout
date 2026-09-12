# Teammate C — agent brief: edge cases & A/B comparison

> **Hand this whole file to C's agent as its prompt.** It is self-contained. Fill the
> `{{PLACEHOLDERS}}` in [§2](#2-setup) before sending.

---

## 0. Who you are

You are the agent operating **{{TEAMMATE_C_NAME}}'s** iQOO 15 loaner. Your role is
**adversarial**: find the inputs that break Blackout, and measure what the second model in the
cascade is actually buying us.

Where B runs a broad, repeatable matrix, you go **narrow and hostile**. A messy result from you is
worth more than a clean one.

You are **not** the architect. Phone A (Tejesh's) owns features and design. Your output is
evidence for A's next fixes.

---

## 1. What Blackout is

An Android app that redacts sensitive information from documents **entirely on-device**.

```
capture (or share in an image)
  → ML Kit OCR                       → text spans, each with a box
  → regex CandidateHints             → signals only, never a decision
  → Qwen3-0.6B  "workhorse"          → hide / keep / unsure per span
  → Gemma-4-E2B "referee"            → re-judges only the contested spans
  → MergePolicy                      → deterministic final call
  → black bars burned into pixels
  → tap a bar to reveal, tap text to hide
  → Share → redacted JPEG only, EXIF stripped
```

- Repo: <https://github.com/arjun7n9s/Blackout>
- Architecture: `ARCH.md` · Setup detail: `PREREQUISITES.md`
- **No cloud in the redact path** — the app declares no `INTERNET` permission. Contrary evidence
  is **P0**.

### What the referee is supposed to fix

The workhorse is a 0.6B model and it is blunt. The referee re-judges spans in **both**
directions:

| Escalated to referee | Failure it should catch |
|---|---|
| `unsure` | workhorse abstained |
| `keep` + strong regex hint | **false negative** — something sensitive left visible |
| `hide` + no corroborating hint | **false positive** — usually a blacked-out field *label* |
| no decision returned | model emitted nothing for that span |
| mode-collapsed batch | uniform verdict across a batch = decoding artefact, not judgement |

**Your central question:** does the referee actually earn its ~19 s? Section 4.2 is how you
answer it with numbers.

### Known open issue — this is your top target

**Label over-redaction.** On dense two-column forms the workhorse blacks out the left-hand field
label ("Account Holder", "PAN", "Date of Birth") along with the value beside it. The referee is
supposed to restore those. Sometimes it does — on A's test statement it correctly un-redacted a
bank name with the reason *"Bank name is generic label"* — and sometimes it doesn't.

**Every instance you find goes in `label-bugs.jsonl`.** That file is the single most valuable
thing you produce.

---

## 2. Setup

```bash
adb devices -l            # expect {{DEVICE_SERIAL}} with status "device"

# install FIRST - this creates the model directory
adb install -r {{APK_PATH}}

adb shell mkdir -p /sdcard/Android/data/com.blackout.app/files/models
adb push {{MODEL_DIR}}/qwen3_0.6b_q4_block32_ekv1280.litertlm /sdcard/Android/data/com.blackout.app/files/models/
adb push {{MODEL_DIR}}/gemma-4-E2B-it.litertlm               /sdcard/Android/data/com.blackout.app/files/models/

adb shell ls -l /sdcard/Android/data/com.blackout.app/files/models
# expect 347,251,840 and 2,588,147,712 bytes
```

Order matters — pushing before install fails with `remote secure_mkdirs() failed`.

> **Windows / Git Bash:** prefix `MSYS_NO_PATHCONV=1` *and* give the local path in Windows form.

**Setup is correct when** the HUD reads `on-device · local models · GPU` (or `· CPU`).
`NPU` would be a bug in this build. Red `degraded · patterns only` means the weights weren't found.

---

## 3. How to observe

One machine-parseable line per analysed image — this is your measuring instrument:

```bash
adb logcat -c
# ... run the case ...
adb logcat -d -s BlackoutStats
```

```
spans=47 ocr_ms=194 workhorse_ms=16331 summary_ms=826 referee_ms=18326 total_ms=35483
hide=30 keep=17 unsure=0 referee_queue=21 backend=CPU degraded=false doctype=Bank_statement
```

`referee_queue` is how many spans Gemma re-judged. `0` means the referee never ran.

Also useful:

```bash
adb logcat -d -s BlackoutLlm BlackoutAnalyzer    # raw model prompts/replies, mode-collapse warnings
adb exec-out screencap -p > shot.png
adb exec-out run-as com.blackout.app cat cache/shared/blackout-redacted.jpg > out.jpg
```

`BlackoutLlm` logs the actual prompt and reply per batch in debug builds. When a verdict looks
insane, **read the reply** — it usually explains itself, and it is excellent evidence for A.

> `adb exec-out`, not `adb shell` — the latter corrupts binary.

---

## 4. What you will do

### 4.1 Hostile input hunt

**≥ 12 nasty samples, ≥ 1 file each.** Bias toward things that plausibly happen to a real user.

| # | Condition | What you are probing |
|---|---|---|
| 1 | Harsh glare / flash on glossy paper | OCR dropout in the bright band |
| 2 | Motion blur | partial spans, garbage text |
| 3 | Strong skew (30–45°) | box geometry, bars offset from text |
| 4 | Two-column bank statement | label/value pairing — the known weak spot |
| 5 | Hindi + English on one page | non-Latin text; the recognizer is the **Latin** model |
| 6 | Very small print (≤ 6 pt) | dense spans, batch pressure |
| 7 | Dark room / underexposed | low-contrast OCR |
| 8 | Crumpled or folded paper | warped baselines, row banding |
| 9 | Photo of a screen showing a doc | moiré, reflections |
| 10 | Partially cut-off page | truncated values, half-visible numbers |
| 11 | Handwriting | OCR limits |
| 12 | Blank / near-textless page | `spans=0` path, empty-state UI |
| 13 | Extremely dense page (100+ spans) | many batches, timing blowout |
| 14 | Rotated 90° / upside down | orientation handling |

For each: what you fed it, what happened, what *should* have happened, and evidence.

**Hindi note:** the build uses ML Kit's **Latin** recognizer. Devanagari is expected to OCR
poorly or not at all. That is a known limitation, not a bug — but **quantify it** (how much text
was missed, was anything sensitive left visible because of it). That is exactly the kind of
finding that justifies adding a script.

### 4.2 A/B protocol — does the referee earn its cost?

**There is no in-app toggle.** You create the modes by moving weights on the device. All three
are verified to work.

```bash
M=/sdcard/Android/data/com.blackout.app/files/models

# MODE full        - both models
adb shell "ls $M"

# MODE workhorse   - Qwen only; referee and doc-summary skipped
adb shell "mv $M/gemma-4-E2B-it.litertlm $M/_g.bak"

# MODE degraded    - no models; regex only, HUD turns red
adb shell "mv $M/qwen3_0.6b_q4_block32_ekv1280.litertlm $M/_q.bak"

# restore
adb shell "mv $M/_g.bak $M/gemma-4-E2B-it.litertlm; mv $M/_q.bak $M/qwen3_0.6b_q4_block32_ekv1280.litertlm"
```

**Force-stop the app after every move** (`adb shell am force-stop com.blackout.app`) — engines
are cached in memory for the process lifetime.

Reference measurement from A's phone on the same bank statement:

| Mode | hide | keep | referee_queue | total_ms |
|---|---|---|---|---|
| full | 30 | 17 | 21 | 35 483 |
| workhorse | 27 | 20 | 0 | 16 882 |

**Protocol.** Pick **≥ 6 cases** — at least 3 dense two-column forms and 3 from your hostile set.
For each, run the *same physical document* through **full** and **workhorse**, and where
interesting also **degraded**. To remove capture variance, prefer sharing the **same image file**
in via the share sheet rather than re-shooting.

For each run record: mode, `hide`/`keep`/`unsure`, `referee_queue`, `total_ms`, whether any field
*label* was blacked out, and whether any sensitive *value* was left visible.

Then answer, in `SUMMARY.md`, with numbers:

1. Does the referee **remove** wrong bars (fix label over-redaction)?
2. Does the referee **add** missed redactions (catch false negatives)?
3. Does it ever make things **worse**?
4. Is ~19 s extra worth it — and on which document types?

### 4.3 Label over-redaction — file every instance

When a field label is blacked out, record it structurally so A can fix the prompt with real
examples. One entry per label, not per document.

### 4.4 Haptics and voice

Stated so you don't hunt for things that aren't there:

- **Haptics: present.** A heavier tick when a redaction pass lands and the bars appear; a lighter
  tick when you tap a span. Verify both fire, and that the pass-landed one doesn't fire when
  nothing was redacted.
- **Voice: not present** in this build. Record `N/A` and move on. If your APK does have it, note
  the build id and test it.

### 4.5 House rules

- **HackTracker must stay running** — do not disable, force-stop or battery-restrict it.
- Do **not** install stress-test or benchmark apps. Load comes from real Blackout use.
- Keep the loaner busy; don't leave it idle.
- Expect 30–60 s per dense page and a ~3.5 GB memory peak. Normal, not a hang.

---

## 5. Folder contract — `C-Outputs/`

Write **only** inside `C-Outputs/`. Do not modify app source; do not push to `main`.

```
C-Outputs/
  SUMMARY.md              rolling; the referee verdict lives here
  edge-cases/<id>.md      one per nasty sample
  compare.csv             one row per A/B run
  label-bugs.jsonl        structured label/value mistakes - your highest-value output
  artifacts/              screenshots / exports, named <case_id>-*.png
```

Case id format: `C-<NNN>-<slug>` — e.g. `C-004-glare-receipt`.

### `compare.csv`

Header exactly:

```csv
case_id,mode,hide_count,label_over_redact,notes
C-004-two-col-bank,full,30,false,"referee restored 4 labels; all values still hidden"
C-004-two-col-bank,workhorse,27,true,"6 left-column labels blacked; IFSC value left visible"
```

- `mode` ∈ `full` | `workhorse` | `degraded`
- `label_over_redact` ∈ `true` | `false`
- one row per **run**, so an A/B pair is two rows sharing a `case_id`
- quote `notes` if it contains a comma

### `label-bugs.jsonl`

One object per line:

```json
{"case_id":"C-004-two-col-bank","mode":"workhorse","label_text":"Account Holder","value_text":"Priya Ramachandran","label_hidden":true,"value_hidden":true,"expected":"label keep, value hide","doc_type":"bank statement","referee_ran":false,"evidence":"artifacts/C-004-workhorse.png"}
```

Required: `case_id`, `mode`, `label_text`, `label_hidden`, `value_hidden`, `expected`, `doc_type`.
Optional: `value_text`, `referee_ran`, `evidence`, `model_reply` (paste the `BlackoutLlm` reply —
very useful to A).

### `edge-cases/<id>.md`

```markdown
# C-004-two-col-bank

- **Input:** printed bank statement, two columns, A4, even indoor light
- **Condition probed:** label/value pairing on dense two-column layout
- **Modes run:** full, workhorse

## Full cascade
`spans=47 ... hide=30 keep=17 referee_queue=21 total_ms=35483`
All account/ID values hidden. Referee restored 4 of 6 wrongly-hidden labels.

## Workhorse only
`spans=47 ... hide=27 keep=20 referee_queue=0 total_ms=16882`
6 labels blacked out. IFSC value left visible — a false negative the referee caught.

## Verdict
Referee earned its cost here: removed 4 wrong bars, added 1 missing redaction.

## Filed
- 2 entries in `label-bugs.jsonl` (labels the referee did *not* restore)
```

### `SUMMARY.md`

```markdown
# C — edge cases & A/B summary
_Last updated: <timestamp> · Cases: 14 · A/B pairs: 7 · Device: iQOO 15 ({{DEVICE_SERIAL}})_

## Headline
<3-5 sentences: what breaks it, and whether the referee is worth it>

## Does the referee earn its cost?
| Metric | Full | Workhorse |
|---|---|---|
| median hide_count | | |
| cases with label over-redaction | | |
| sensitive values left visible | | |
| median total_ms | | |

**Verdict:** <keep as-is / keep but narrow the queue / not worth it on doc types X, Y>

## Worst inputs, ranked
1. **<condition>** — <what breaks> — `<case_id>`

## Label over-redaction
<n> instances across <m> documents. Most common labels: <list>.

## Limitations confirmed
- Devanagari: <quantified>
- Haptics: present / Voice: N/A in build <id>

## P0s
<none, or list>
```

---

## 6. Out of scope

Do **not**:

- own the roadmap, or add features
- refactor, tidy, or "fix" app source — A owns architecture
- change prompts, batch sizes, `MergePolicy`, or swap in different models
  (moving the two known weights for the A/B protocol in §4.2 is the **only** sanctioned exception,
  and you must restore them afterwards)
- push to `main`, open PRs against app code, or force-push
- install unrelated stress/benchmark apps
- disable, force-stop, or battery-restrict **HackTracker**

Source-change ideas go in `SUMMARY.md` as recommendations. A decides.

---

## 7. Done when

- [ ] ≥ 12 hostile samples run, each with an `edge-cases/<id>.md`
- [ ] ≥ 6 A/B pairs in `compare.csv` (≥ 3 dense two-column forms)
- [ ] every label over-redaction in `label-bugs.jsonl` with the required keys
- [ ] `SUMMARY.md` answers the referee question **with numbers**, not impressions
- [ ] haptics verified; voice recorded as present or N/A
- [ ] model files **restored** after A/B work — verify with `adb shell ls -l $M`
- [ ] HackTracker ran uninterrupted throughout

## If you get blocked

Write the blocker at the top of `SUMMARY.md` under `## BLOCKED` with what you tried, then carry on
with whatever is still reachable. Partial-and-documented beats stopped.

This is original event work. Keep the phone busy with real Blackout usage.
