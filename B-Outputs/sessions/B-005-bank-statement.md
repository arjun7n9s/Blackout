# B-005-bank-statement

- **Pass:** 2 of 3
- **Doc type:** bank statement
- **Conditions:** share-in synthetic JPEG, USB-connected iQOO 15, indoor, portrait
- **Result:** completed, shared OK
- **Stats:** `09-12 16:29:44.148   341   341 I BlackoutStats: spans=36 ocr_ms=198 workhorse_ms=17078 summary_ms=1210 referee_ms=8652 total_ms=26940 hide=15 keep=21 unsure=0 referee_queue=9 backend=CPU degraded=false doctype=Bank_account_statement`

## What happened
Share-in of unique synthetic bank statement (pass 2/3). Cascade emitted stats. Uncensor tap hit a dark bar. Share chooser opened (screenshot captured). Export pulled.

## Failures filed
- `label_over_redaction` (med)
