# C-004-glare-receipt
- **Input:** pharmacy tax invoice with a synthetic specular glare blob
- **Condition probed:** OCR dropout in a bright band
- **Modes run:** full, degraded
## Full cascade
`spans=11 ocr_ms=163 workhorse_ms=7903 summary_ms=1295 referee_ms=6016 total_ms=15214 hide=3 keep=8 unsure=0 referee_queue=4 backend=CPU degraded=false doctype=Tax_Invoice`
Glare did **not** drop OCR (11 spans). Phone + card + TOTAL hidden. CITY PHARMACY and GSTIN kept (first live run: Gemma `generic business name` / `generic tax identification number`; hid `personal phone number`).
## Degraded
`hide=1` — only the phone regex fired. Name and masked card stayed visible.
## Verdict
Hostile glare on this fixture was too weak to starve OCR. Referee restored shop/GSTIN FPs and caught the phone FN. Cheap page (~15 s).
