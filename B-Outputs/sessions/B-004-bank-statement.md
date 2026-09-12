# B-004-bank-statement

- **Pass:** 1 of 3
- **Doc type:** bank statement
- **Conditions:** share-in synthetic JPEG, USB-connected iQOO 15, indoor, portrait
- **Result:** completed, shared OK
- **Stats:** `09-12 16:28:34.070 31805 31805 I BlackoutStats: spans=38 ocr_ms=206 workhorse_ms=17270 summary_ms=1364 referee_ms=9802 total_ms=28436 hide=13 keep=25 unsure=0 referee_queue=11 backend=CPU degraded=false doctype=Bank_account_statement`

## What happened
Share-in of unique synthetic bank statement (pass 1/3). Cascade emitted stats. Uncensor tap hit a dark bar. Share chooser opened (screenshot captured). Export pulled.

## Failures filed
- `label_over_redaction` (med)
- `under_redaction` (high)
