# B-Outputs

Everything produced by **Teammate B's** soak & regression runs. B's agent writes here and
nowhere else; the brief is [`B-instructions.md`](../B-instructions.md).

Empty until B starts.

## Expected artifacts

| Path | What it is |
|---|---|
| `SUMMARY.md` | Rolling summary — counts, coverage table, top issues ranked, P0s. **Read this first.** |
| `sessions/<id>.md` | One per session: doc type, conditions, what happened, stats line, failures filed |
| `failures.jsonl` | One JSON object per line, append-only, one per failure |
| `timings.csv` | One row per session |
| `artifacts/` | Optional screenshots and exported JPEGs, named `<session_id>-*.png` |

Session id: `B-<NNN>-<doctype>` — e.g. `B-007-bank-statement`.

## `timings.csv`

```csv
session_id,ocr_ms,qwen_ms,gemma_ms,total_ms,hide_count,keep_count,unsure_count
B-007-bank-statement,194,16331,19152,35483,30,17,0
```

Sourced from the `BlackoutStats` logcat line, **not** from reading screenshots:

```bash
adb logcat -d -s BlackoutStats
```

`qwen_ms` = `workhorse_ms`; `gemma_ms` = `summary_ms + referee_ms`; `0` if a stage didn't run.

## `failures.jsonl`

```json
{"session_id":"B-007-bank-statement","kind":"label_over_redaction","severity":"med","doc_type":"bank statement","summary":"6 left-column labels blacked out with their values","repro":"capture full page, portrait, even indoor light","evidence":"artifacts/B-007-shot.png"}
```

Required: `session_id`, `kind`, `severity` (`p0`|`high`|`med`|`low`), `doc_type`, `summary`,
`repro`. Optional: `evidence`, `stats`, `notes`.

`kind` ∈ `crash` · `empty_ocr` · `all_keep` · `under_redaction` · `over_redaction` ·
`label_over_redaction` · `share_failure` · `exif_leak` · `original_leak` · `uncensor_failure` ·
`degraded_unexpected` · `perf` · `ui`

## Target

≥ 10 document types × ≥ 3 passes = **≥ 30 sessions**, every one with a session file and a
timings row.

## For A

Triage order: any `p0` (`exif_leak`, `original_leak`) → `all_keep` (sensitive page fully
missed) → `crash` → everything else by count. `SUMMARY.md` already ranks them.
