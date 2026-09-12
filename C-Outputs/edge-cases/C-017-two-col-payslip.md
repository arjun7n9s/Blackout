# C-017-two-col-payslip
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
