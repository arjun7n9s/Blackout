# C-005-motion-blur
- **Input:** Horizon statement Gaussian-blurred σ=3.2
- **Condition probed:** partial spans / garbage text
- **Modes run:** full
## Full cascade
`spans=1 ocr_ms=118 workhorse_ms=2712 summary_ms=664 referee_ms=4114 total_ms=7490 hide=0 keep=1 unsure=0 referee_queue=1 backend=CPU degraded=false doctype=Bank_name`
OCR collapsed to a single span (bank header). Share button is `Share` not `Share redacted`. Entire customer block (PAN, account, IFSC, phone, email, address) left in the clear. Pass-landed haptic did **not** fire (`hide=0`).
## Verdict
Worst privacy failure in the set. Referee cannot save a page the recognizer never saw. Motion blur is a capture-quality P0 for the product, not a cascade bug.
