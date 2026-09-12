# C-003-two-col-kyc
- **Input:** two-column CKYC / KYC update form
- **Condition probed:** boxed label/value fields
- **Modes run:** full, workhorse
## Full cascade
`spans=41 ocr_ms=218 workhorse_ms=19621 summary_ms=1686 referee_ms=14519 total_ms=35826 hide=23 keep=18 unsure=0 referee_queue=15 backend=CPU degraded=false doctype=KYC_Update_Form`
Title kept. PAN/email/Aadhaar values hidden. DOB and ID-document **labels** blacked. **Permanent Address 41, MG Road, Thrissur** fully visible.
## Workhorse only
`spans=41 workhorse_ms=24577 total_ms=24577 hide=23 keep=18 referee_queue=0`
Same hide_count. Title blacked. **BDFPN2201L** and **kavya.n@example.com** visible.
## Verdict
Best referee demo in the set: same 23 hides, but it swapped header FPs for PAN/email FNs. Permanent address still a miss. ~11 s extra.
## Filed
- DOB label, Permanent Address (full); title, PAN, Email (workhorse)
