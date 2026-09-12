# C-Outputs

Everything produced by **Teammate C's** edge-case and A/B comparison runs. C's agent writes here
and nowhere else; the brief is [`C-instructions.md`](../C-instructions.md).

Empty until C starts.

## Expected artifacts

| Path | What it is |
|---|---|
| `SUMMARY.md` | Rolling summary — worst inputs, and the **referee-worth-it verdict with numbers**. Read first. |
| `edge-cases/<id>.md` | One per hostile sample: input, condition probed, per-mode results, verdict |
| `compare.csv` | One row per A/B **run** (a pair shares a `case_id`) |
| `label-bugs.jsonl` | Structured label/value mistakes — **the highest-value output for A** |
| `artifacts/` | Screenshots and exports, named `<case_id>-*.png` |

Case id: `C-<NNN>-<slug>` — e.g. `C-004-glare-receipt`.

## `compare.csv`

```csv
case_id,mode,hide_count,label_over_redact,notes
C-004-two-col-bank,full,30,false,"referee restored 4 labels; all values hidden"
C-004-two-col-bank,workhorse,27,true,"6 labels blacked; IFSC value left visible"
```

`mode` ∈ `full` | `workhorse` | `degraded` · `label_over_redact` ∈ `true` | `false` ·
quote `notes` if it contains a comma.

There is **no in-app toggle** — modes are created by moving weights on device (see
`C-instructions.md` §4.2). All three are verified to work. Reference from A's phone, same
statement:

| Mode | hide | keep | referee_queue | total_ms |
|---|---|---|---|---|
| full | 30 | 17 | 21 | 35 483 |
| workhorse | 27 | 20 | 0 | 16 882 |

## `label-bugs.jsonl`

```json
{"case_id":"C-004-two-col-bank","mode":"workhorse","label_text":"Account Holder","value_text":"Priya Ramachandran","label_hidden":true,"value_hidden":true,"expected":"label keep, value hide","doc_type":"bank statement","referee_ran":false,"evidence":"artifacts/C-004-workhorse.png"}
```

Required: `case_id`, `mode`, `label_text`, `label_hidden`, `value_hidden`, `expected`,
`doc_type`. Optional: `value_text`, `referee_ran`, `evidence`, `model_reply`.

`model_reply` — pasted from `adb logcat -d -s BlackoutLlm` — is especially useful: it shows what
the model actually answered for that batch.

## Target

≥ 12 hostile samples · ≥ 6 A/B pairs (≥ 3 dense two-column forms) · every label over-redaction
filed.

## For A

`label-bugs.jsonl` is the corpus for fixing label-vs-value over-redaction — real labels, real
values, real model replies, and whether the referee caught it. `SUMMARY.md` says whether the
~19 s referee pass is worth keeping, and on which document types.
