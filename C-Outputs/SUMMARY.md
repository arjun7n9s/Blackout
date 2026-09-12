# C — edge cases & A/B summary
_Last updated: 2026-09-12 15:41 IST · Cases: 17 hostile + 7 A/B pairs · Device: iQOO 15 (`10BFAT1UF7000XP`) · APK `com.blackout.app` 0.1.0 debug_

## Headline
The cascade stays on-device (`HUD: on-device · local models · CPU`) but the **installed APK still has `INTERNET` granted** via ML Kit's `transport-backend-cct` merge — that is a P0 against the "no INTERNET permission" claim. Hostile capture (blur, skew, crumple, 90° rotate) breaks **OCR/geometry**, not Gemma: `C-005` redacted nothing (`spans=1 hide=0`). On readable two-column forms the referee **does** restore bank/employer headers and catch some emails/PANs, but it consistently fails the actual bug (field **label** blacked, **name/DOB/IFSC/address** left up). On 6 pt contracts it makes the page worse. Dense 141-span ledgers blow the budget (~97 s workhorse before Gemma even starts).

## Does the referee earn its cost?

A/B on the **same PNG** (share-in, force-stop between modes). Medians over the six completed pairs with both timings (C-001, C-002, C-003, C-017, C-006, C-008):

| Metric | Full | Workhorse |
|---|---|---|
| median hide_count | 25.5 | 20.5 |
| cases with label over-redaction (two-col set) | 4/4 (C-001/002/003/017) | 4/4 (headers + pay labels) |
| sensitive values left visible | 6/6 pairs (names/DOB/address leak in both) | 6/6 |
| median total_ms | 53284 | 30393 |
| median referee_ms (where it ran) | 21890 | 0 |

Per-pair hide delta (full − workhorse): C-001 **+1**, C-002 **+6**, C-003 **0** (but swapped which bars), C-017 **−6**, C-006 **0**, C-008 **+26**.

1. **Remove wrong bars?** Yes on headers: `HORIZON BANK`, `NARMADA CO-OP BANK`, `CKYC / KYC UPDATION FORM`, `NORTHWIND TECHNOLOGIES PVT LTD`, and payslip HRA/LTA/PF labels came back in full. **No** on the classic left-column `Account Holder` / `Date of Birth` labels — still blacked on C-001/002/009/011.
2. **Add missed redactions?** Yes: emails/phones/PAN/ESI that workhorse left up were hidden in full on C-001/002/003/017. **No** on person names (`Priya Ramachandran`, `Arjun Mehta`, `Rohit Iyer`, `Rahul Sharma`, `Meera Joshi`) and most DOBs/addresses.
3. **Make things worse?** **Yes on C-008** (hide 13 → 39, +35 s, page becomes a black slab). Skew C-006: +5.6 s for identical three misplaced squares. C-001 full **introduced** an Account Holder label bar the workhorse run did not have (run-to-run Qwen variance + referee not restoring it).
4. **Is ~19 s extra worth it?** On two-column bank/KYC/payslip: **keep, but the queue is too wide** — 15–33 referee spans, 14–28 s, and the pairing bug still ships. On skew/blur/crumple/rotate: **not worth it** (OCR/geometry). On small-print: harmful. On the 141-span ledger: **C-015 full took 266908 ms (referee 174627 ms, queue 130) to go hide 131 → 120** and the identifier stripe is still visible — skip referee. A's 19 s / 35 s reference was a 47-span statement; our 85-span statements are ~66–67 s full vs ~48 s workhorse (~18 s Gemma, matches the brief).

**Verdict:** keep but narrow the queue (headers + strong-hint FNs only; cap span count; don't escalate every `hide` without a hint on dense pages).

## Worst inputs, ranked
1. **Motion blur** — OCR `spans=1`, hide=0, full PII in the clear — `C-005-motion-blur`
2. **Strong skew** — axis-aligned bars miss rotated text — `C-006-skew-statement`
3. **Crumpled/warped** — same geometry miss, 80 spans, 76 s — `C-010-crumpled`
4. **Extremely dense ledger** — 141 spans, 267 s full (referee 175 s, queue 130), hide 120 vs workhorse 131; PAN/IFSC stripe still visible — `C-015-dense-ledger`
5. **Rotated 90°** — 87 spans of sideways soup, 91 s, no deskew — `C-016-rotated-90`
6. **Hindi+English** — Devanagari → tofu; Latin name+DOB visible — `C-007-hindi-english`
7. **Two-column pairing** — inverted label/value, every bank/KYC/payslip — `C-001`/`C-002`/`C-003`/`C-017`
8. **Cutoff page** — truncated email + full name visible — `C-012-cutoff-page`
9. **Handwriting stand-in** — patient name kept — `C-013-handwriting`
10. **Screen photo** — OCR survived; +14 hides vs clean, 92 s — `C-011-screen-photo`
11. **Small print** — referee over-redacts the whole MSA — `C-008-small-print`
12. **Dark room** — OCR held at 85 spans; pairing still broken — `C-009-dark-room`
13. **Glare** — no OCR dropout on this fixture — `C-004-glare-receipt`
14. **Blank** — empty path OK — `C-014-blank`

## Label over-redaction
**29** structured instances in `label-bugs.jsonl` across **9** documents. Most common labels: **Account Holder** (C-001/002/009/011/012/007), **Date of Birth**, bank/employer **headers** (workhorse-only; referee often restores), **PAN** (label hidden / value visible on KYC workhorse), payslip **HRA/LTA/PF**. The pairing inversion (label hide, value keep) is the repeatable bug A should prompt-fix.

## Limitations confirmed
- Devanagari: **0/8** Hindi strings read; tofu boxes on `C-007`. Latin name `Rahul Sharma` and DOB `15-08-1987` remained visible. Latin PAN/IFSC/account were hidden.
- Haptics: **present**. `dumpsys vibrator_manager` shows `Prebaked=HEAVY_CLICK` from `com.blackout.app` aligned with every `hide>0` `BlackoutStats` line (e.g. C-004 15:35:40, C-017 15:08:15). **C-005 hide=0 produced no HEAVY_CLICK.** Span toggles use `Prebaked=TICK` (history 13:41–13:42 and 15:02). Voice: **N/A** in build `0.1.0` (no TTS/speech code).
- GPU: every run logs `Gemma-4-E2B-it unusable on GPU: Can not find OpenCL library` then loads CPU in ~1.3 s. Expected on this loaner.
- HackTracker AccessibilityService stayed up (pid 14104) for the whole session.

## P0s
1. **`android.permission.INTERNET` is on the installed APK** (`granted=true`), plus `ACCESS_NETWORK_STATE`. App manifest comments that INTERNET is absent; merger report blames `[com.google.android.datatransport:transport-backend-cct:2.3.3]` pulled by ML Kit. No evidence the **redact path** called the network (cascade logs are local LLM only), but the "impossible to make a cloud call" claim is false until the permission is `tools:node="remove"`d.
2. **Motion-blur / low-OCR pages share unredacted PII** (`C-005`). Consider blocking Share or warning when `spans` is tiny vs image size.

## Recommendations for A (not acted on)
- Prompt: "field labels stay visible; hide the value on the same row" with the jsonl rows as few-shot.
- Narrow `refereeQueue`: drop `hide && no hint` on batches that already look mode-collapsed *after* a size cap; skip referee when `spans>100` or mean font height < N.
- Deskew / OSD before OCR.
- Manifest: strip INTERNET/ACCESS_NETWORK_STATE from ML Kit CCT.
- Don't treat `UNSURE` as extra hide (already policy) — C-008's extra 26 hides are referee **HIDE**s, not unsure.

## A/B protocol notes
Weights moved with `mv` + `am force-stop com.blackout.app`. Restored after degraded:

```
347251840 qwen3_0.6b_q4_block32_ekv1280.litertlm
2588147712 gemma-4-E2B-it.litertlm
```

HackTracker was never force-stopped.
