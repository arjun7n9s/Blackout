# C-012-cutoff-page
- **Input:** left/top 62% × 55% crop of Horizon statement, nearest-neighbour upscaled
- **Condition probed:** truncated values / half-visible numbers
- **Modes run:** full
## Full cascade
`spans=51 ocr_ms=276 workhorse_ms=30500 summary_ms=1396 referee_ms=19546 total_ms=51442 hide=18 keep=33 unsure=0 referee_queue=17 backend=CPU degraded=false doctype=Bank_statement`
Account Holder **label kept** (good) but **Priya Ramachandran** fully visible. Truncated email `priya.r@example.c` visible. `UPI/PRIYA RAMACHANDRAN/8821` visible. Right-column IDs half-cut and inconsistently barred.
## Verdict
Cutoff does not trigger "incomplete identifier → hide". Names leak.
## Filed
- Account Holder value kept
