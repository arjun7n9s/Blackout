# C-010-crumpled
- **Input:** Meridian statement with fold lines + quad warp
- **Condition probed:** warped baselines / row banding
- **Modes run:** full
## Full cascade
`spans=80 ocr_ms=345 workhorse_ms=44812 summary_ms=1679 referee_ms=29649 total_ms=76140 hide=28 keep=52 unsure=0 referee_queue=28 backend=CPU degraded=false doctype=Scanned_text_document`
Axis-aligned bars on a warped page: big black slabs covering empty regions, PAN/IFSC fragments still readable at odd angles.
## Verdict
Same geometry class as skew. Referee cannot rotate boxes. Capture-pipeline / deskew would buy more than Gemma.
