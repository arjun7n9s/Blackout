"""Run the Teammate C hostile matrix + A/B pairs. Writes C-Outputs/_tools/runs.jsonl."""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import run_case as rc

OUT = Path(__file__).resolve().parents[1] / "_tools" / "runs.jsonl"

FULL_CASES = [
    ("C-001-two-col-horizon", 180, False),
    ("C-002-two-col-narmada", 180, False),
    ("C-003-two-col-kyc", 180, False),
    ("C-017-two-col-payslip", 180, False),
    ("C-004-glare-receipt", 120, False),
    ("C-005-motion-blur", 180, False),
    ("C-006-skew-statement", 180, False),
    ("C-007-hindi-english", 150, False),
    ("C-008-small-print", 240, False),
    ("C-009-dark-room", 180, False),
    ("C-010-crumpled", 180, False),
    ("C-011-screen-photo", 180, False),
    ("C-012-cutoff-page", 150, False),
    ("C-013-handwriting", 150, False),
    ("C-014-blank", 25, True),
    ("C-015-dense-ledger", 300, False),
    ("C-016-rotated-90", 180, False),
]

AB_CASES = [
    ("C-001-two-col-horizon", 180, False),
    ("C-002-two-col-narmada", 180, False),
    ("C-003-two-col-kyc", 180, False),
    ("C-017-two-col-payslip", 180, False),
    ("C-006-skew-statement", 180, False),
    ("C-008-small-print", 240, False),
    ("C-015-dense-ledger", 300, False),
]

DEGRADED_CASES = [
    ("C-001-two-col-horizon", 40, False),
    ("C-004-glare-receipt", 40, False),
]


def append(rec: dict) -> None:
    with OUT.open("a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")


def run_list(cases, mode: str) -> None:
    rc.set_mode(mode)
    for case_id, timeout, empty in cases:
        print(f"\n===== {case_id} mode={mode} =====", flush=True)
        try:
            rec = rc.run_one(case_id, mode, timeout, empty)
        except Exception as e:
            rec = {"case_id": case_id, "mode": mode, "error": repr(e)}
            print("FAILED", rec, flush=True)
        rec["ts"] = time.time()
        append(rec)


def main() -> int:
    OUT.write_text("", encoding="utf-8")
    run_list(FULL_CASES, "full")
    run_list(AB_CASES, "workhorse")
    run_list(DEGRADED_CASES, "degraded")
    rc.restore_models()
    print("DONE", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
