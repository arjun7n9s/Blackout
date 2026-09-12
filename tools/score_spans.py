"""Scores a Blackout run against a fixture's ground truth.

Usage:
    adb logcat -d -s BlackoutSpans > spans.txt
    python score_spans.py spans.txt testdoc-bank.groundtruth.json

Reads the per-span decision lines the debug build emits:
    id=4 action=HIDE src=REFEREE x=520 y=200 text=Priya Ramachandran

Reports HIDE precision/recall plus the number that actually matters for the
label/value bug: LABEL OVER-REDACTION RATE - the share of must-keep strings that
were blacked out anyway. Plain accuracy is useless here because the many easy
KEEPs drown the signal.
"""
import json
import re
import sys
from collections import Counter

LINE = re.compile(r"id=(\d+)\s+action=(\w+)\s+src=(\w+)\s+x=(-?\d+)\s+y=(-?\d+)\s+text=(.*)$")


def norm(s: str) -> str:
    return re.sub(r"\s+", " ", s).strip().lower()


def load_spans(path):
    out = []
    for raw in open(path, encoding="utf-8", errors="replace"):
        m = LINE.search(raw)
        if m:
            out.append({
                "id": int(m.group(1)),
                "action": m.group(2),
                "src": m.group(3),
                "x": int(m.group(4)),
                "y": int(m.group(5)),
                "text": m.group(6).rstrip(),
            })
    # A run may appear more than once in the buffer; keep the last complete pass.
    if out:
        starts = [i for i, s in enumerate(out) if s["id"] == 1]
        if starts:
            out = out[starts[-1]:]
    return out


def match(text, gt):
    """Ground-truth class for an OCR'd span, or None if it matches nothing."""
    n = norm(text)
    if not n:
        return None
    if n in gt:
        return gt[n]
    # OCR drops/merges characters, so fall back to containment either way.
    best, best_len = None, 0
    for key, cls in gt.items():
        if len(key) < 4:
            continue
        if (key in n or n in key) and len(key) > best_len:
            best, best_len = cls, len(key)
    return best


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    spans = load_spans(sys.argv[1])
    gt_raw = json.load(open(sys.argv[2], encoding="utf-8"))
    gt = {norm(k): v for k, v in gt_raw.items()}

    tp = fp = fn = tn = 0
    unmatched = 0
    over = []      # must-KEEP but hidden  -> the bug
    under = []     # must-HIDE but visible -> a leak
    by_src = Counter()

    for s in spans:
        cls = match(s["text"], gt)
        if cls is None:
            unmatched += 1
            continue
        if cls == "AMBIG":
            continue
        hidden = s["action"] == "HIDE"
        by_src[s["src"]] += 1
        if cls == "HIDE":
            if hidden:
                tp += 1
            else:
                fn += 1
                under.append(s)
        else:
            if hidden:
                fp += 1
                over.append(s)
            else:
                tn += 1

    scored = tp + fp + fn + tn
    prec = tp / (tp + fp) if tp + fp else 0.0
    rec = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
    keep_total = fp + tn
    over_rate = fp / keep_total if keep_total else 0.0

    print(f"spans logged      : {len(spans)}   scored: {scored}   unmatched: {unmatched}")
    print(f"HIDE precision    : {prec:.3f}   ({tp} correct of {tp + fp} hidden)")
    print(f"HIDE recall       : {rec:.3f}   ({tp} of {tp + fn} sensitive caught)")
    print(f"F1                : {f1:.3f}")
    print()
    print(f"OVER-REDACTION    : {fp}/{keep_total} must-keep strings hidden  = {over_rate:.1%}   <<< the bug")
    print(f"UNDER-REDACTION   : {fn}/{tp + fn} sensitive strings left visible = "
          f"{(fn / (tp + fn)) if tp + fn else 0:.1%}")
    print(f"decision sources  : {dict(by_src)}")

    if over:
        print("\nWrongly hidden (should be readable):")
        for s in sorted(over, key=lambda v: v["y"]):
            print(f"  [{s['src']:<9}] x={s['x']:<5} {s['text']}")
    if under:
        print("\nLeaked (should be hidden):")
        for s in sorted(under, key=lambda v: v["y"]):
            print(f"  [{s['src']:<9}] x={s['x']:<5} {s['text']}")


if __name__ == "__main__":
    main()
