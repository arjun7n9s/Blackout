# B — soak & regression summary
_Last updated: 2026-09-12 17:14 IST · Sessions: 30 · Device: iQOO 15 (`10BFC41SB8001UZ`)_

## Headline
The on-device cascade held up across 10 document types × 3 unique images (30 sessions). HUD stayed `on-device · local models · CPU`; `degraded=false` on every completed run; no crashes; every export JPEG had burned bars and empty EXIF. What breaks most is **wrong hide/keep on dense pages**: left-column *labels* get blacked out (known) while some high-value *values* (payee names, IFSC, salary figures, receipt AUTH codes) stay visible. Unsure→keep is doing what A designed; the miss is that those spans were not contested enough to reach the referee, or the referee kept them. Dense two-column docs are the slowest (bank statements median ~28 s total). HackTracker pid `13878` stayed up the whole time.

## Coverage
| Doc type | Passes | Completed | Failures |
|---|---|---|---|
| retail receipt | 3/3 | 3 | 1 |
| bank statement | 3/3 | 3 | 4 |
| hospital / medical bill | 3/3 | 3 | 0 |
| ID card (Aadhaar / PAN / driving licence) | 3/3 | 3 | 1 |
| salary slip / payslip | 3/3 | 3 | 4 |
| dense government form | 3/3 | 3 | 2 |
| utility bill | 3/3 | 3 | 0 |
| screenshot shared in | 3/3 | 3 | 0 |
| handwritten note or form | 3/3 | 3 | 0 |
| business card / letterhead | 3/3 | 3 | 0 |

Each pass used a **different JPEG** (OCR span counts and `workhorse_ms` differ within a type). Share-in via Photo Picker; uncensor tap + Share chooser exercised every session.

## Top issues, ranked
1. **label_over_redaction** — 8 occurrences — left-column field labels (`Account Holder`, `PAN`, `Employee`, …) burned with the values on two-column layouts. Known; counts for A’s next prompt pass. Worst: `B-004-bank-statement` (hide=13 on a statement).
2. **under_redaction** — 4 named examples — sensitive *values* still in the pixels after READY. Receipt AUTH + customer name (`B-001-retail-receipt`); payee `ROHIT` + amounts (`B-004-bank-statement`); PAN-card address (`B-011-id-card`); payslip IFSC + Gross (`B-013-salary-slip`). This is the more serious product miss: labels die, numbers leak.
3. **Remote PC overlay** — 2 sessions initially failed (`B-011`, `B-029`) when vivo EasyShare covered Blackout. Not an app bug. Both were re-run successfully on the same unique JPEGs. Force-stop `com.vivo.remotecontrol` is safe; do not touch HackTracker.

## Timing bands (median total_ms by doc type)
| Doc type | median total_ms | range |
|---|---|---|
| retail receipt | 16361 | 14466–16626 |
| bank statement | 28436 | 26940–29023 |
| hospital / medical bill | 19357 | 16608–21599 |
| ID card (Aadhaar / PAN / driving licence) | 8902 | 8577–9496 |
| salary slip / payslip | 21360 | 21032–22310 |
| dense government form | 23353 | 22297–25702 |
| utility bill | 15881 | 15809–17369 |
| screenshot shared in | 11017 | 9606–11342 |
| handwritten note or form | 7719 | 7341–8750 |
| business card / letterhead | 10962 | 9748–11119 |

Qwen workhorse dominates; Gemma referee adds ~4–13 s when `referee_queue>0`. Bank statements peaked ~29 s — within the brief’s 30–60 s CPU expectation, not a hang.

## Stability
Crashes: 0 · Thermal throttling observed: no (`total_ms` did not climb across the three passes of a type) · Longest continuous run: ~35 min (B-001…B-030 back-to-back; B-011 and B-029 filled after overlay interruptions) · HackTracker pid present at end: True (`13878`)

`adb logcat -d -s BlackoutStats` was empty on several sessions even after READY; the on-screen debug chip still showed a full cascade (`workhorse_ms>0`). Recorded those from the debug panel. Worth A knowing the stats logger can miss a line on this build.

## P0s
none — no `exif_leak`, no `original_leak`, no unexpected `degraded=true`.

## Notes for A
- Unsure→keep plus label-over-redaction on two-column forms is the main quality story: the bars look aggressive on the left and still leak IFSC / payee / AUTH on the right.
- Recommendation only (no source change from B): consider treating IFSC, AUTH/approval codes, and transaction payee names as hide-biased even when the workhorse says keep.
- Loaner: dismiss `com.vivo.remotecontrol` / Remote PC if the redact surface is replaced by a desktop mirror. HackTracker must stay running.
