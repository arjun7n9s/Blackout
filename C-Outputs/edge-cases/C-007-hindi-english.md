# C-007-hindi-english
- **Input:** Bharat Bank page mixing Devanagari labels/values with Latin PAN/IFSC/account/DOB
- **Condition probed:** Latin-only ML Kit recognizer
- **Modes run:** full
## Full cascade
`spans=15 ocr_ms=159 workhorse_ms=8777 summary_ms=1357 referee_ms=10522 total_ms=20656 hide=9 keep=6 unsure=0 referee_queue=9 backend=CPU degraded=false doctype=Bank_account_statement`
Devanagari rendered as tofu `□□□` in the HUD screenshot — recognizer emitted empty/replacement boxes, not real Hindi. Latin **Rahul Sharma** and **15-08-1987** left visible. PAN / account / IFSC hidden. Address line mostly boxed/garbled.
## Quantification
Roughly 6–8 Devanagari strings on the fixture; **zero** were read as Hindi. Sensitive Latin that sat beside those labels (name, DOB) was treated as ordinary text and often kept. This is the stated Latin-model limitation, but it is exactly the hole that would justify a Devanagari script.
## Verdict
Not a cascade bug. Product gap: mixed-script Indian docs leak names/dates.
