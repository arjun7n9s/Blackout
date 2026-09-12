# C-014-blank
- **Input:** near-textless 1240×1754 page
- **Condition probed:** `spans=0` path / empty-state UI
- **Modes run:** full
## Full cascade
No `BlackoutStats` line (`RedactViewModel.start` returns early when OCR is empty). Screenshot: blank canvas, HUD `on-device · local models` (no CPU suffix), Share not `Share redacted`. No crash. Pass-landed haptic did not fire.
## Verdict
Empty path is fine. Status string "No text found" is not sticky on the final frame.
