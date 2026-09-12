# C-006-skew-statement
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
