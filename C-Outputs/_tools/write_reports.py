"""Emit Teammate C folder-contract files from the completed runs."""
from __future__ import annotations

from datetime import datetime, timezone, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EDGE = ROOT / "edge-cases"
EDGE.mkdir(exist_ok=True)
IST = timezone(timedelta(hours=5, minutes=30))
NOW = datetime.now(IST).strftime("%Y-%m-%d %H:%M IST")

CSV = """case_id,mode,hide_count,label_over_redact,notes
C-001-two-col-horizon,full,28,true,"referee restored HORIZON BANK header; Account Holder label still blacked; Priya Ramachandran + DOB 14-08-1988 + IFSC HZBN0001429 left visible"
C-001-two-col-horizon,workhorse,27,true,"HORIZON BANK header blacked; Account Holder label kept; name/DOB/IFSC/email priya.r@example.com visible; referee_queue=0 total_ms=48093 vs full 66607"
C-001-two-col-horizon,degraded,9,false,"HUD red degraded · patterns only; regex hid account number/Aadhaar/some PANs; name DOB IFSC email address all visible; total_ms=0"
C-002-two-col-narmada,full,31,true,"Account Holder label blacked; Arjun Mehta + DOB + address visible; email/phone hidden (referee catch vs workhorse); bank header kept"
C-002-two-col-narmada,workhorse,25,true,"NARMADA CO-OP BANK header blacked; phone +91 79400 11220 and email left visible; hide 25 vs full 31"
C-003-two-col-kyc,full,23,true,"form title restored; PAN/email hidden; DOB/Passport/Voter/DL labels blacked; Permanent Address 41 MG Road Thrissur left visible; total_ms=35826"
C-003-two-col-kyc,workhorse,23,true,"CKYC title blacked; PAN BDFPN2201L and email kavya.n@example.com visible; same hide_count as full but swapped FPs for FNs; total_ms=24577"
C-017-two-col-payslip,full,12,true,"company name restored; PAN/email/ESI hidden; Employee Name Rohit Iyer still visible; DOB label blacked; total_ms=39960"
C-017-two-col-payslip,workhorse,18,true,"NORTHWIND header blacked; PAN AHXPI3390B + email + ESI visible; HRA/LTA/PF labels blacked; hide 18 vs full 12 so referee net-removed bars"
C-006-skew-statement,full,3,false,"spans=8; 3 axis-aligned bars miss rotated glyphs; almost all PII readable; referee_ms=5565 for no pairing fix; total_ms=10621"
C-006-skew-statement,workhorse,3,false,"identical hide_count; bars still axis-aligned on 36deg page; total_ms=4784"
C-008-small-print,full,39,false,"referee_queue=29; almost entire MSA body blacked; hide 39 vs workhorse 13 — referee made page unreadable; total_ms=70110"
C-008-small-print,workhorse,13,false,"unsure=3; many PII lines still show through; less nuclear than full; total_ms=34950"
C-015-dense-ledger,workhorse,131,false,"spans=141 workhorse_ms=97087; 131/141 hidden (mode-collapse pattern) but several PAN/ACC/IFSC lines still visible in the middle band"
C-004-glare-receipt,full,3,false,"glare did not drop OCR; phone+card+TOTAL hidden; GSTIN and pharmacy name kept; total_ms=15214"
C-004-glare-receipt,degraded,1,false,"regex hid only the phone line; name Ananya Sharma and card ****8812 visible"
"""

LABEL_BUGS = [
    {"case_id":"C-001-two-col-horizon","mode":"full","label_text":"Account Holder","value_text":"Priya Ramachandran","label_hidden":True,"value_hidden":False,"expected":"label keep, value hide","doc_type":"bank statement","referee_ran":True,"evidence":"artifacts/C-001-two-col-horizon-full.png"},
    {"case_id":"C-001-two-col-horizon","mode":"full","label_text":"Date of Birth","value_text":"14-08-1988","label_hidden":True,"value_hidden":False,"expected":"label keep, value hide","doc_type":"bank statement","referee_ran":True,"evidence":"artifacts/C-001-two-col-horizon-full.png"},
    {"case_id":"C-001-two-col-horizon","mode":"full","label_text":"IFSC","value_text":"HZBN0001429","label_hidden":True,"value_hidden":False,"expected":"label keep, value hide","doc_type":"bank statement","referee_ran":True,"evidence":"artifacts/C-001-two-col-horizon-full.png"},
    {"case_id":"C-001-two-col-horizon","mode":"full","label_text":"Nominee","value_text":"K. Ramachandran","label_hidden":False,"value_hidden":False,"expected":"label keep, value hide","doc_type":"bank statement","referee_ran":True,"evidence":"artifacts/C-001-two-col-horizon-full.png"},
    {"case_id":"C-001-two-col-horizon","mode":"full","label_text":"Communication Address","value_text":"Bandra West, Mumbai 400050","label_hidden":False,"value_hidden":False,"expected":"label keep, value hide","doc_type":"bank statement","referee_ran":True,"evidence":"artifacts/C-001-two-col-horizon-full.png"},
    {"case_id":"C-001-two-col-horizon","mode":"workhorse","label_text":"HORIZON BANK","value_text":"(header)","label_hidden":True,"value_hidden":True,"expected":"label keep, value keep","doc_type":"bank statement","referee_ran":False,"evidence":"artifacts/C-001-two-col-horizon-workhorse.png","model_reply":"workhorse hid bank header; full cascade restored it"},
    {"case_id":"C-001-two-col-horizon","mode":"workhorse","label_text":"Account Holder","value_text":"Priya Ramachandran","label_hidden":False,"value_hidden":False,"expected":"label keep, value hide","doc_type":"bank statement","referee_ran":False,"evidence":"artifacts/C-001-two-col-horizon-workhorse.png"},
    {"case_id":"C-001-two-col-horizon","mode":"workhorse","label_text":"Email","value_text":"priya.r@example.com","label_hidden":False,"value_hidden":False,"expected":"label keep, value hide","doc_type":"bank statement","referee_ran":False,"evidence":"artifacts/C-001-two-col-horizon-workhorse.png"},
    {"case_id":"C-002-two-col-narmada","mode":"full","label_text":"Account Holder","value_text":"Arjun Mehta","label_hidden":True,"value_hidden":False,"expected":"label keep, value hide","doc_type":"bank statement","referee_ran":True,"evidence":"artifacts/C-002-two-col-narmada-full.png"},
    {"case_id":"C-002-two-col-narmada","mode":"full","label_text":"Date of Birth","value_text":"02-11-1991","label_hidden":True,"value_hidden":False,"expected":"label keep, value hide","doc_type":"bank statement","referee_ran":True,"evidence":"artifacts/C-002-two-col-narmada-full.png"},
    {"case_id":"C-002-two-col-narmada","mode":"workhorse","label_text":"NARMADA CO-OP BANK","value_text":"(header)","label_hidden":True,"value_hidden":True,"expected":"label keep, value keep","doc_type":"bank statement","referee_ran":False,"evidence":"artifacts/C-002-two-col-narmada-workhorse.png"},
    {"case_id":"C-002-two-col-narmada","mode":"workhorse","label_text":"Registered Mobile","value_text":"+91 79400 11220","label_hidden":False,"value_hidden":False,"expected":"label keep, value hide","doc_type":"bank statement","referee_ran":False,"evidence":"artifacts/C-002-two-col-narmada-workhorse.png"},
    {"case_id":"C-002-two-col-narmada","mode":"workhorse","label_text":"Email","value_text":"arjun.mehta@example.net","label_hidden":False,"value_hidden":False,"expected":"label keep, value hide","doc_type":"bank statement","referee_ran":False,"evidence":"artifacts/C-002-two-col-narmada-workhorse.png"},
    {"case_id":"C-003-two-col-kyc","mode":"full","label_text":"Date of Birth","value_text":"09-01-1994","label_hidden":True,"value_hidden":True,"expected":"label keep, value hide","doc_type":"KYC form","referee_ran":True,"evidence":"artifacts/C-003-two-col-kyc-full.png"},
    {"case_id":"C-003-two-col-kyc","mode":"full","label_text":"Permanent Address","value_text":"41, MG Road, Thrissur, Kerala 680001","label_hidden":False,"value_hidden":False,"expected":"label keep, value hide","doc_type":"KYC form","referee_ran":True,"evidence":"artifacts/C-003-two-col-kyc-full.png"},
    {"case_id":"C-003-two-col-kyc","mode":"workhorse","label_text":"CKYC / KYC UPDATION FORM","value_text":"(title)","label_hidden":True,"value_hidden":True,"expected":"label keep, value keep","doc_type":"KYC form","referee_ran":False,"evidence":"artifacts/C-003-two-col-kyc-workhorse.png"},
    {"case_id":"C-003-two-col-kyc","mode":"workhorse","label_text":"PAN","value_text":"BDFPN2201L","label_hidden":True,"value_hidden":False,"expected":"label keep, value hide","doc_type":"KYC form","referee_ran":False,"evidence":"artifacts/C-003-two-col-kyc-workhorse.png"},
    {"case_id":"C-003-two-col-kyc","mode":"workhorse","label_text":"Email Address","value_text":"kavya.n@example.com","label_hidden":False,"value_hidden":False,"expected":"label keep, value hide","doc_type":"KYC form","referee_ran":False,"evidence":"artifacts/C-003-two-col-kyc-workhorse.png"},
    {"case_id":"C-017-two-col-payslip","mode":"full","label_text":"Employee Name","value_text":"Rohit Iyer","label_hidden":False,"value_hidden":False,"expected":"label keep, value hide","doc_type":"payslip","referee_ran":True,"evidence":"artifacts/C-017-two-col-payslip-full.png"},
    {"case_id":"C-017-two-col-payslip","mode":"full","label_text":"Date of Birth","value_text":"21-06-1990","label_hidden":True,"value_hidden":True,"expected":"label keep, value hide","doc_type":"payslip","referee_ran":True,"evidence":"artifacts/C-017-two-col-payslip-full.png"},
    {"case_id":"C-017-two-col-payslip","mode":"workhorse","label_text":"NORTHWIND TECHNOLOGIES PVT LTD","value_text":"(header)","label_hidden":True,"value_hidden":True,"expected":"label keep, value keep","doc_type":"payslip","referee_ran":False,"evidence":"artifacts/C-017-two-col-payslip-workhorse.png"},
    {"case_id":"C-017-two-col-payslip","mode":"workhorse","label_text":"PAN","value_text":"AHXPI3390B","label_hidden":False,"value_hidden":False,"expected":"label keep, value hide","doc_type":"payslip","referee_ran":False,"evidence":"artifacts/C-017-two-col-payslip-workhorse.png"},
    {"case_id":"C-017-two-col-payslip","mode":"workhorse","label_text":"HRA","value_text":"48,000","label_hidden":True,"value_hidden":False,"expected":"label keep, value keep","doc_type":"payslip","referee_ran":False,"evidence":"artifacts/C-017-two-col-payslip-workhorse.png"},
    {"case_id":"C-009-dark-room","mode":"full","label_text":"Account Holder","value_text":"Arjun Mehta","label_hidden":True,"value_hidden":False,"expected":"label keep, value hide","doc_type":"bank statement","referee_ran":True,"evidence":"artifacts/C-009-dark-room-full.png"},
    {"case_id":"C-011-screen-photo","mode":"full","label_text":"Account Holder","value_text":"Priya Ramachandran","label_hidden":True,"value_hidden":False,"expected":"label keep, value hide","doc_type":"bank statement","referee_ran":True,"evidence":"artifacts/C-011-screen-photo-full.png"},
    {"case_id":"C-012-cutoff-page","mode":"full","label_text":"Account Holder","value_text":"Priya Ramachandran","label_hidden":False,"value_hidden":False,"expected":"label keep, value hide","doc_type":"bank statement","referee_ran":True,"evidence":"artifacts/C-012-cutoff-page-full.png"},
    {"case_id":"C-007-hindi-english","mode":"full","label_text":"Account Holder","value_text":"Rahul Sharma","label_hidden":True,"value_hidden":False,"expected":"label keep, value hide","doc_type":"bilingual bank statement","referee_ran":True,"evidence":"artifacts/C-007-hindi-english-full.png"},
    {"case_id":"C-007-hindi-english","mode":"full","label_text":"Date of Birth","value_text":"15-08-1987","label_hidden":True,"value_hidden":False,"expected":"label keep, value hide","doc_type":"bilingual bank statement","referee_ran":True,"evidence":"artifacts/C-007-hindi-english-full.png"},
    {"case_id":"C-013-handwriting","mode":"full","label_text":"Name","value_text":"Meera Joshi","label_hidden":False,"value_hidden":False,"expected":"label keep, value hide","doc_type":"handwritten intake","referee_ran":True,"evidence":"artifacts/C-013-handwriting-full.png"},
]

CASES = {
"C-001-two-col-horizon": """# C-001-two-col-horizon
- **Input:** synthetic two-column Horizon Bank statement (A4 @ 150dpi PNG, even indoor contrast)
- **Condition probed:** label/value pairing on dense two-column layout
- **Modes run:** full, workhorse, degraded
## Full cascade
`spans=85 ocr_ms=228 workhorse_ms=36967 summary_ms=1350 referee_ms=28290 total_ms=66607 hide=28 keep=57 unsure=0 referee_queue=33 backend=CPU degraded=false doctype=Bank_statement`
HUD: `on-device · local models · CPU`. Bank header kept. Account Holder / DOB labels blacked while **Priya Ramachandran**, **14-08-1988**, **HZBN0001429**, nominee and address stayed visible. Email hidden.
## Workhorse only
`spans=85 ocr_ms=404 workhorse_ms=48093 summary_ms=0 referee_ms=0 total_ms=48093 hide=27 keep=58 unsure=0 referee_queue=0 backend=CPU degraded=false doctype=-`
HORIZON BANK header blacked. Name/DOB/IFSC/**priya.r@example.com** visible.
## Degraded
`spans=85 hide=9 keep=76 backend=none degraded=true` — HUD red `degraded · patterns only`. Regex-only; name/DOB/IFSC/email/address visible.
## Verdict
Referee earned part of its ~18 s: restored the bank header and hid the email. It did **not** fix the inverted Account Holder pairing or the leftover IFSC/DOB/name. Still a two-column miss.
## Filed
- label-bugs.jsonl entries for Account Holder, DOB, IFSC, Nominee, Address, header, Email
""",
"C-002-two-col-narmada": """# C-002-two-col-narmada
- **Input:** second two-column bank statement (Narmada Co-op / Arjun Mehta)
- **Condition probed:** label/value pairing (dense two-column)
- **Modes run:** full, workhorse
## Full cascade
`spans=85 ocr_ms=253 workhorse_ms=38575 summary_ms=1211 referee_ms=27711 total_ms=67497 hide=31 keep=54 unsure=0 referee_queue=32 backend=CPU degraded=false doctype=Savings_Account_Statement`
Account Holder label blacked; **Arjun Mehta** and DOB visible. Phone/email hidden. UPI/PRIYA RAMACHANDRAN in particulars still visible.
## Workhorse only
`spans=85 ocr_ms=371 workhorse_ms=48209 total_ms=48209 hide=25 keep=60 referee_queue=0`
Bank header blacked. **+91 79400 11220** and **arjun.mehta@example.net** left visible.
## Verdict
Referee added 6 hides (phone, email, extra txn lines) and restored the bank name. Net win on FNs; label-over-redact on Account Holder remains.
## Filed
- Account Holder / DOB (full); header / mobile / email (workhorse)
""",
"C-003-two-col-kyc": """# C-003-two-col-kyc
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
""",
"C-017-two-col-payslip": """# C-017-two-col-payslip
- **Input:** two-column payslip (Northwind / Rohit Iyer)
- **Condition probed:** label/value pairing on HR form
- **Modes run:** full, workhorse
## Full cascade
`spans=46 ocr_ms=253 workhorse_ms=23173 summary_ms=1312 referee_ms=15475 total_ms=39960 hide=12 keep=34 unsure=0 referee_queue=17 backend=CPU degraded=false doctype=Payslip`
Company name kept. PAN/email/ESI hidden. **Rohit Iyer still visible.** DOB label blacked. Pay-component labels kept.
## Workhorse only
`spans=46 workhorse_ms=25836 total_ms=25836 hide=18 keep=28 referee_queue=0`
Header blacked. PAN **AHXPI3390B**, email, ESI visible. HRA/LTA/PF **labels** blacked.
## Verdict
Referee removed 6 wrong bars (header + pay labels) and added PAN/email/ESI hides. Did not catch the employee name. Worth it on payslips.
## Filed
- Employee Name, DOB (full); header, PAN, HRA (workhorse)
""",
"C-004-glare-receipt": """# C-004-glare-receipt
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
""",
"C-005-motion-blur": """# C-005-motion-blur
- **Input:** Horizon statement Gaussian-blurred σ=3.2
- **Condition probed:** partial spans / garbage text
- **Modes run:** full
## Full cascade
`spans=1 ocr_ms=118 workhorse_ms=2712 summary_ms=664 referee_ms=4114 total_ms=7490 hide=0 keep=1 unsure=0 referee_queue=1 backend=CPU degraded=false doctype=Bank_name`
OCR collapsed to a single span (bank header). Share button is `Share` not `Share redacted`. Entire customer block (PAN, account, IFSC, phone, email, address) left in the clear. Pass-landed haptic did **not** fire (`hide=0`).
## Verdict
Worst privacy failure in the set. Referee cannot save a page the recognizer never saw. Motion blur is a capture-quality P0 for the product, not a cascade bug.
""",
"C-006-skew-statement": """# C-006-skew-statement
- **Input:** Horizon statement rotated 36° on a dark canvas
- **Condition probed:** box geometry / bars offset from text
- **Modes run:** full, workhorse
## Full cascade
`spans=8 ocr_ms=121 workhorse_ms=4241 summary_ms=815 referee_ms=5565 total_ms=10621 hide=3 keep=5 unsure=0 referee_queue=4 backend=CPU degraded=false doctype=Bank_account_details`
Three large axis-aligned black squares sit on empty background / wrong columns. Most glyphs remain readable.
## Workhorse only
`hide=3 total_ms=4784` — visually the same three squares.
## Verdict
Referee spent 5.6 s and did not move a single bar onto the skewed text. Geometry is an engine problem, not a model problem. Not worth the extra seconds here.
""",
"C-007-hindi-english": """# C-007-hindi-english
- **Input:** Bharat Bank page mixing Devanagari labels/values with Latin PAN/IFSC/account/DOB
- **Condition probed:** Latin-only ML Kit recognizer
- **Modes run:** full
## Full cascade
`spans=15 ocr_ms=159 workhorse_ms=8777 summary_ms=1357 referee_ms=10522 total_ms=20656 hide=9 keep=6 unsure=0 referee_queue=9 backend=CPU degraded=false doctype=Bank_account_statement`
Devanagari rendered as tofu `□□□` in the HUD screenshot — recognizer emitted empty/replacement boxes, not real Hindi. Latin **Rahul Sharma** and **15-08-1987** left visible. PAN / account / IFSC hidden. Address line mostly boxed/garbled.
## Quantification
Roughly 6–8 Devanagari strings on the fixture; **zero** were read as Hindi. Sensitive Latin that sat beside those labels (name, DOB) was treated as ordinary text and often kept. This is the stated Latin-model limitation, but it is exactly the hole that would justify a Devanagari script.
## Verdict
Not a cascade bug. Product gap: mixed-script Indian docs leak names/dates.
""",
"C-008-small-print": """# C-008-small-print
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
""",
"C-009-dark-room": """# C-009-dark-room
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
""",
"C-010-crumpled": """# C-010-crumpled
- **Input:** Meridian statement with fold lines + quad warp
- **Condition probed:** warped baselines / row banding
- **Modes run:** full
## Full cascade
`spans=80 ocr_ms=345 workhorse_ms=44812 summary_ms=1679 referee_ms=29649 total_ms=76140 hide=28 keep=52 unsure=0 referee_queue=28 backend=CPU degraded=false doctype=Scanned_text_document`
Axis-aligned bars on a warped page: big black slabs covering empty regions, PAN/IFSC fragments still readable at odd angles.
## Verdict
Same geometry class as skew. Referee cannot rotate boxes. Capture-pipeline / deskew would buy more than Gemma.
""",
"C-011-screen-photo": """# C-011-screen-photo
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
""",
"C-012-cutoff-page": """# C-012-cutoff-page
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
""",
"C-013-handwriting": """# C-013-handwriting
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
""",
"C-014-blank": """# C-014-blank
- **Input:** near-textless 1240×1754 page
- **Condition probed:** `spans=0` path / empty-state UI
- **Modes run:** full
## Full cascade
No `BlackoutStats` line (`RedactViewModel.start` returns early when OCR is empty). Screenshot: blank canvas, HUD `on-device · local models` (no CPU suffix), Share not `Share redacted`. No crash. Pass-landed haptic did not fire.
## Verdict
Empty path is fine. Status string "No text found" is not sticky on the final frame.
""",
"C-015-dense-ledger": """# C-015-dense-ledger
- **Input:** 140-line two-column ledger of PAN/account/IFSC/mobile/email/Aadhaar-like tokens
- **Condition probed:** many batches / timing blowout
- **Modes run:** workhorse (full first attempt timed out at 300 s while a system "Connecting device" overlay was up; rerun logged separately if present)
## Workhorse only
`spans=141 ocr_ms=815 workhorse_ms=97087 total_ms=97087 hide=131 keep=10 unsure=0 referee_queue=0 backend=CPU degraded=false doctype=-`
~97 s of Qwen. 131/141 hidden — uniform hide stripes with a mid-page band of **still-visible** `PAN AARPR… ACC … IFSC …` lines. Looks like mode-collapse plus leftover FNs.
## Full cascade
First pass: no stats within 300 s (overlay + likely 141-span referee queue). A's 47-span page was 35 s; this is 3× the spans.
## Verdict
Referee should be skipped or hard-capped on 100+ span pages. Workhorse already spends ~97 s and still leaks a stripe of identifiers.
""",
"C-016-rotated-90": """# C-016-rotated-90
- **Input:** Horizon statement rotated 90° then letterboxed back to portrait
- **Condition probed:** orientation handling
- **Modes run:** full
## Full cascade
`spans=87 ocr_ms=374 workhorse_ms=49090 summary_ms=1604 referee_ms=40634 total_ms=91328 hide=21 keep=66 unsure=0 referee_queue=39 backend=CPU degraded=false doctype=Bank_transaction_statement`
OCR still found 87 spans (reading sideways glyphs as Latin soup). First screenshot was contaminated by a BACK key used to dismiss a system overlay — treat stats as the source of truth; a clean reshoot is `C-016-rotated-90-full.png` after rerun.
## Verdict
No EXIF/orientation deskew. High span count + ~91 s for a page a human would rotate in one gesture. Recommend a pre-OCR orientation pass.
""",
}

SUMMARY = f"""# C — edge cases & A/B summary
_Last updated: {NOW} · Cases: 17 hostile + 7 A/B pairs · Device: iQOO 15 (`10BFAT1UF7000XP`) · APK `com.blackout.app` 0.1.0 debug_

## Headline
The cascade stays on-device (`HUD: on-device · local models · CPU`) but the **installed APK still has `INTERNET` granted** via ML Kit's `transport-backend-cct` merge — that is a P0 against the "no INTERNET permission" claim. Hostile capture (blur, skew, crumple, 90° rotate) breaks **OCR/geometry**, not Gemma: `C-005` redacted nothing (`spans=1 hide=0`). On readable two-column forms the referee **does** restore bank/employer headers and catch some emails/PANs, but it consistently fails the actual bug (field **label** blacked, **name/DOB/IFSC/address** left up). On 6 pt contracts it makes the page worse. Dense 141-span ledgers blow the budget (~97 s workhorse before Gemma even starts).

## Does the referee earn its cost?

A/B on the **same PNG** (share-in, force-stop between modes). Medians over the six completed pairs with both timings (C-001, C-002, C-003, C-017, C-006, C-008):

| Metric | Full | Workhorse |
|---|---|---|
| median hide_count | 25.5 | 20.5 |
| cases with label over-redaction (two-col set) | 4/4 (C-001/002/003/017) | 4/4 (headers + pay labels) |
| sensitive values left visible | 6/6 pairs (names/DOB/address leak in both) | 6/6 |
| median total_ms | 53284 | 30393 |
| median referee_ms (where it ran) | 21890 | 0 |

Per-pair hide delta (full − workhorse): C-001 **+1**, C-002 **+6**, C-003 **0** (but swapped which bars), C-017 **−6**, C-006 **0**, C-008 **+26**.

1. **Remove wrong bars?** Yes on headers: `HORIZON BANK`, `NARMADA CO-OP BANK`, `CKYC / KYC UPDATION FORM`, `NORTHWIND TECHNOLOGIES PVT LTD`, and payslip HRA/LTA/PF labels came back in full. **No** on the classic left-column `Account Holder` / `Date of Birth` labels — still blacked on C-001/002/009/011.
2. **Add missed redactions?** Yes: emails/phones/PAN/ESI that workhorse left up were hidden in full on C-001/002/003/017. **No** on person names (`Priya Ramachandran`, `Arjun Mehta`, `Rohit Iyer`, `Rahul Sharma`, `Meera Joshi`) and most DOBs/addresses.
3. **Make things worse?** **Yes on C-008** (hide 13 → 39, +35 s, page becomes a black slab). Skew C-006: +5.6 s for identical three misplaced squares. C-001 full **introduced** an Account Holder label bar the workhorse run did not have (run-to-run Qwen variance + referee not restoring it).
4. **Is ~19 s extra worth it?** On two-column bank/KYC/payslip: **keep, but the queue is too wide** — 15–33 referee spans, 14–28 s, and the pairing bug still ships. On skew/blur/crumple/rotate: **not worth it** (OCR/geometry). On small-print and 100+ span ledgers: **skip referee**. A's 19 s / 35 s reference was a 47-span statement; our 85-span statements are ~66–67 s full vs ~48 s workhorse (~18 s Gemma, matches the brief).

**Verdict:** keep but narrow the queue (headers + strong-hint FNs only; cap span count; don't escalate every `hide` without a hint on dense pages).

## Worst inputs, ranked
1. **Motion blur** — OCR `spans=1`, hide=0, full PII in the clear — `C-005-motion-blur`
2. **Strong skew** — axis-aligned bars miss rotated text — `C-006-skew-statement`
3. **Crumpled/warped** — same geometry miss, 80 spans, 76 s — `C-010-crumpled`
4. **Extremely dense ledger** — 141 spans, 97 s workhorse, 131 hides + leftover PAN/IFSC stripe; full timed out at 300 s — `C-015-dense-ledger`
5. **Rotated 90°** — 87 spans of sideways soup, 91 s, no deskew — `C-016-rotated-90`
6. **Hindi+English** — Devanagari → tofu; Latin name+DOB visible — `C-007-hindi-english`
7. **Two-column pairing** — inverted label/value, every bank/KYC/payslip — `C-001`/`C-002`/`C-003`/`C-017`
8. **Cutoff page** — truncated email + full name visible — `C-012-cutoff-page`
9. **Handwriting stand-in** — patient name kept — `C-013-handwriting`
10. **Screen photo** — OCR survived; +14 hides vs clean, 92 s — `C-011-screen-photo`
11. **Small print** — referee over-redacts the whole MSA — `C-008-small-print`
12. **Dark room** — OCR held at 85 spans; pairing still broken — `C-009-dark-room`
13. **Glare** — no OCR dropout on this fixture — `C-004-glare-receipt`
14. **Blank** — empty path OK — `C-014-blank`

## Label over-redaction
**29** structured instances in `label-bugs.jsonl` across **9** documents. Most common labels: **Account Holder** (C-001/002/009/011/012/007), **Date of Birth**, bank/employer **headers** (workhorse-only; referee often restores), **PAN** (label hidden / value visible on KYC workhorse), payslip **HRA/LTA/PF**. The pairing inversion (label hide, value keep) is the repeatable bug A should prompt-fix.

## Limitations confirmed
- Devanagari: **0/8** Hindi strings read; tofu boxes on `C-007`. Latin name `Rahul Sharma` and DOB `15-08-1987` remained visible. Latin PAN/IFSC/account were hidden.
- Haptics: **present**. `dumpsys vibrator_manager` shows `Prebaked=HEAVY_CLICK` from `com.blackout.app` aligned with every `hide>0` `BlackoutStats` line (e.g. C-004 15:35:40, C-017 15:08:15). **C-005 hide=0 produced no HEAVY_CLICK.** Span toggles use `Prebaked=TICK` (history 13:41–13:42 and 15:02). Voice: **N/A** in build `0.1.0` (no TTS/speech code).
- GPU: every run logs `Gemma-4-E2B-it unusable on GPU: Can not find OpenCL library` then loads CPU in ~1.3 s. Expected on this loaner.
- HackTracker AccessibilityService stayed up (pid 14104) for the whole session.

## P0s
1. **`android.permission.INTERNET` is on the installed APK** (`granted=true`), plus `ACCESS_NETWORK_STATE`. App manifest comments that INTERNET is absent; merger report blames `[com.google.android.datatransport:transport-backend-cct:2.3.3]` pulled by ML Kit. No evidence the **redact path** called the network (cascade logs are local LLM only), but the "impossible to make a cloud call" claim is false until the permission is `tools:node="remove"`d.
2. **Motion-blur / low-OCR pages share unredacted PII** (`C-005`). Consider blocking Share or warning when `spans` is tiny vs image size.

## Recommendations for A (not acted on)
- Prompt: "field labels stay visible; hide the value on the same row" with the jsonl rows as few-shot.
- Narrow `refereeQueue`: drop `hide && no hint` on batches that already look mode-collapsed *after* a size cap; skip referee when `spans>100` or mean font height < N.
- Deskew / OSD before OCR.
- Manifest: strip INTERNET/ACCESS_NETWORK_STATE from ML Kit CCT.
- Don't treat `UNSURE` as extra hide (already policy) — C-008's extra 26 hides are referee **HIDE**s, not unsure.

## A/B protocol notes
Weights moved with `mv` + `am force-stop com.blackout.app`. Restored after degraded:

```
347251840 qwen3_0.6b_q4_block32_ekv1280.litertlm
2588147712 gemma-4-E2B-it.litertlm
```

HackTracker was never force-stopped.
"""


def main() -> None:
    import json

    (ROOT / "compare.csv").write_text(CSV, encoding="utf-8")
    with (ROOT / "label-bugs.jsonl").open("w", encoding="utf-8") as f:
        for obj in LABEL_BUGS:
            f.write(json.dumps(obj, ensure_ascii=False) + "\n")
    (ROOT / "SUMMARY.md").write_text(SUMMARY, encoding="utf-8")
    for cid, body in CASES.items():
        (EDGE / f"{cid}.md").write_text(body, encoding="utf-8")
    print("wrote", len(CASES), "edge-cases,", len(LABEL_BUGS), "label-bugs")


if __name__ == "__main__":
    main()
