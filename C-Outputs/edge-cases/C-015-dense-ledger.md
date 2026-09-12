# C-015-dense-ledger
- **Input:** 140-line two-column ledger of PAN/account/IFSC/mobile/email/Aadhaar-like tokens
- **Condition probed:** many batches / timing blowout
- **Modes run:** full, workhorse
## Full cascade
`spans=141 ocr_ms=548 workhorse_ms=89835 summary_ms=2446 referee_ms=174627 total_ms=266908 hide=120 keep=21 unsure=0 referee_queue=130 backend=CPU degraded=false doctype=Bank_account_details_list`
**267 s / 4.5 min.** Gemma re-judged 130 of 141 spans. Hide 120 vs workhorse 131 (11 bars restored). The same mid-page PAN/ACC/IFSC stripe stays readable. First attempt at 300 s timed out because a vivo "Connecting device" overlay was up; this rerun is the valid measurement.
## Workhorse only
`spans=141 ocr_ms=815 workhorse_ms=97087 total_ms=97087 hide=131 keep=10 unsure=0 referee_queue=0 backend=CPU degraded=false doctype=-`
~97 s of Qwen. 131/141 hidden — uniform hide stripes with a mid-page band of **still-visible** `PAN AARPR… ACC … IFSC …` lines. Looks like mode-collapse plus leftover FNs.
## Verdict
Referee spent **175 s to remove 11 wrong bars and still left identifiers visible**. Skip or hard-cap referee when `spans>100`. This is the clearest "not worth ~19 s" — here it is ~175 s.
