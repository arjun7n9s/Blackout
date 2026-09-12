# C-016-rotated-90
- **Input:** Horizon statement rotated 90° then letterboxed back to portrait
- **Condition probed:** orientation handling
- **Modes run:** full
## Full cascade
`spans=87 ocr_ms=392 workhorse_ms=49450 summary_ms=1549 referee_ms=49144 total_ms=100143 hide=21 keep=66 unsure=0 referee_queue=39 backend=CPU degraded=false doctype=Bank_transaction_statement`
Clean reshoot: `artifacts/C-016-rotated-90-full.png`. Text is on its side; bars are axis-aligned in screen space, so they paint fat rectangles across empty canvas and miss sideways names. **priya.r@example.com** remains readable. Transaction amounts stay visible. ~100 s.
## Verdict
No EXIF/OSD deskew. A 90° pre-pass would beat 49 s of Gemma. Referee does not rotate boxes.
