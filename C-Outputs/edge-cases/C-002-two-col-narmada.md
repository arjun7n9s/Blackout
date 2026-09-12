# C-002-two-col-narmada
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
