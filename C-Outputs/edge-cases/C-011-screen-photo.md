# C-011-screen-photo
- **Input:** Horizon statement with 3px mesh + reflection polygon (moiré stand-in)
- **Condition probed:** photo-of-a-screen
- **Modes run:** full
## Full cascade
`spans=85 ocr_ms=383 workhorse_ms=48564 summary_ms=1386 referee_ms=42413 total_ms=92363 hide=42 keep=43 unsure=0 referee_queue=39 backend=CPU degraded=false doctype=Bank_statement`
OCR still 85 spans. Hide jumped 28 → 42 vs clean C-001. **Priya Ramachandran** and DOB still visible. Slowest two-column run (~92 s).
## Verdict
Moiré didn't kill OCR; it made the cascade more aggressive and slower. Pairing bug unchanged.
## Filed
- Account Holder / Priya
