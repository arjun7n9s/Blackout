# C-009-dark-room
- **Input:** Narmada statement brightness 0.28 / contrast 0.85
- **Condition probed:** low-contrast OCR
- **Modes run:** full
## Full cascade
`spans=85 ocr_ms=337 workhorse_ms=47226 summary_ms=1574 referee_ms=30194 total_ms=78994 hide=33 keep=52 unsure=0 referee_queue=28 backend=CPU degraded=false doctype=Savings_Account_Statement`
OCR span count matched the well-lit sibling (85). Same pairing bug: **Arjun Mehta** + DOB + address visible; email visible on this copy. Synthetic underexposure did not break Latin OCR.
## Verdict
Dark-room is not the failure mode. Pairing still is.
## Filed
- Account Holder / Arjun Mehta
