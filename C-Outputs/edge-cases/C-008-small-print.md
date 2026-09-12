# C-008-small-print
- **Input:** ~6–9 pt repeating MSA packed with PAN/Aadhaar/account/IFSC
- **Condition probed:** dense spans / batch pressure
- **Modes run:** full, workhorse
## Full cascade
`spans=51 ocr_ms=611 workhorse_ms=30920 summary_ms=2679 referee_ms=36511 total_ms=70110 hide=39 keep=12 unsure=0 referee_queue=29 backend=CPU degraded=false doctype=Financial_transaction_details_or_contrac`
Almost the entire body is a black rectangle. Unusable.
## Workhorse only
`spans=51 workhorse_ms=34950 total_ms=34950 hide=13 keep=35 unsure=3 referee_queue=0`
Many PII-bearing lines still show; yellow unsure overlay on 3 spans.
## Verdict
Referee **made this worse** (13 → 39 hides, +35 s). Narrow the queue on tiny repeating legal text / skip referee when span density is this high.
