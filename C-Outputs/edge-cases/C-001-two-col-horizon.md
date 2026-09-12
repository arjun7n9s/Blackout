# C-001-two-col-horizon
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
