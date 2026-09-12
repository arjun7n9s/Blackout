# C-013-handwriting
- **Input:** comic/script-font "patient intake" card (stand-in for handwriting)
- **Condition probed:** OCR limits on non-print
- **Modes run:** full
## Full cascade
`spans=8 ocr_ms=158 workhorse_ms=5006 summary_ms=1370 referee_ms=4569 total_ms=10945 hide=3 keep=5 unsure=0 referee_queue=2 backend=CPU degraded=false doctype=Patient_intake_form`
DOB / phone / Aadhaar lines hidden. **Name: Meera Joshi** and **Address: 8 Lake View, Indore** kept. Real ballpoint would OCR worse.
## Verdict
Script font is an upper bound on handwriting. Patient name leak is the finding.
## Filed
- Name / Meera Joshi
