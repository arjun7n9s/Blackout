"""Share a fixture into Blackout and capture BlackoutStats + screenshots."""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

ADB = r"W:\Users\Pavantej\Android\Sdk\platform-tools\adb.exe"
SERIAL = "10BFAT1UF7000XP"
PKG = "com.blackout.app"
FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"
ARTIFACTS = Path(__file__).resolve().parents[1] / "artifacts"
REMOTE_DIR = "/sdcard/Pictures/BlackoutC"
STATS_RE = re.compile(r"BlackoutStats:\s+(spans=\S+.*)")
M = "/sdcard/Android/data/com.blackout.app/files/models"


def adb(*args: str, check: bool = True, timeout: int = 180) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [ADB, "-s", SERIAL, *args],
        check=check,
        timeout=timeout,
        text=True,
        capture_output=True,
        encoding="utf-8",
        errors="replace",
    )


def adb_bin(*args: str, timeout: int = 120) -> bytes:
    return subprocess.check_output([ADB, "-s", SERIAL, *args], timeout=timeout)


def media_id(name: str) -> str | None:
    q = adb(
        "shell",
        f'content query --uri content://media/external/images/media --projection _id:_display_name --where "_display_name=\'{name}\'"',
        check=False,
    )
    m = re.search(r"_id=(\d+)", q.stdout + q.stderr)
    return m.group(1) if m else None


def ensure_scanned(local: Path) -> str:
    name = local.name
    remote = f"{REMOTE_DIR}/{name}"
    adb("shell", "mkdir", "-p", REMOTE_DIR)
    adb("push", str(local), remote, timeout=180)
    adb(
        "shell",
        "am",
        "broadcast",
        "-a",
        "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
        "-d",
        f"file://{remote}",
        check=False,
    )
    for _ in range(15):
        mid = media_id(name)
        if mid:
            return mid
        time.sleep(0.4)
    raise RuntimeError(f"no media id for {name}\n{adb('shell', 'ls', '-l', REMOTE_DIR).stdout}")


def send_image(mid: str) -> None:
    uri = f"content://media/external/images/media/{mid}"
    adb("shell", "am", "force-stop", PKG)
    time.sleep(0.5)
    adb("logcat", "-c")
    r = adb(
        "shell",
        "am",
        "start",
        "-n",
        f"{PKG}/.MainActivity",
        "-a",
        "android.intent.action.VIEW",
        "-d",
        uri,
        "-t",
        "image/png",
        "--grant-read-uri-permission",
        check=False,
    )
    if r.returncode != 0:
        raise RuntimeError(f"start failed\n{r.stdout}{r.stderr}")


def wait_stats(timeout_s: int, expect_empty: bool = False) -> str | None:
    deadline = time.time() + timeout_s
    last = ""
    started = time.time()
    while time.time() < deadline:
        dump = adb("logcat", "-d", "-s", "BlackoutStats:I", "BlackoutLlm:I", "BlackoutAnalyzer:W", check=False)
        last = dump.stdout
        m = STATS_RE.search(last)
        if m:
            return m.group(1).strip()
        if expect_empty and (time.time() - started) > 12:
            return None
        time.sleep(2)
    return None


def screencap(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    png = adb_bin("exec-out", "screencap", "-p")
    if png.startswith(b"\r\n"):
        png = png.lstrip(b"\r\n")
    path.write_bytes(png)


def tap_debug() -> None:
    adb("shell", "uiautomator", "dump", "/sdcard/uidump.xml", check=False)
    xml = adb("shell", "cat", "/sdcard/uidump.xml", check=False).stdout
    m = re.search(r'text="debug"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="debug"', xml)
    if m:
        x = (int(m.group(1)) + int(m.group(3))) // 2
        y = (int(m.group(2)) + int(m.group(4))) // 2
        adb("shell", "input", "tap", str(x), str(y), check=False)
        return
    adb("shell", "input", "tap", "1280", "220", check=False)


def dump_logs(stem: str) -> str:
    stats = adb("logcat", "-d", "-s", "BlackoutStats:I", check=False).stdout
    llm = adb("logcat", "-d", "-s", "BlackoutLlm:I", "BlackoutLlm:W", check=False).stdout
    ana = adb("logcat", "-d", "-s", "BlackoutAnalyzer:I", "BlackoutAnalyzer:W", "BlackoutAnalyzer:E", check=False).stdout
    log_dir = ARTIFACTS / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    (log_dir / f"{stem}-stats.txt").write_text(stats, encoding="utf-8")
    (log_dir / f"{stem}-llm.txt").write_text(llm, encoding="utf-8")
    (log_dir / f"{stem}-analyzer.txt").write_text(ana, encoding="utf-8")
    return stats + "\n" + llm + "\n" + ana


def vibrator_snapshot(stem: str) -> str:
    r = adb("shell", "dumpsys", "vibrator_manager", check=False)
    out = r.stdout
    if not out.strip():
        out = adb("shell", "dumpsys", "vibrator", check=False).stdout
    (ARTIFACTS / "logs" / f"{stem}-vibrator.txt").write_text(out[-8000:], encoding="utf-8")
    return out


def parse_stats(line: str) -> dict:
    out = {}
    for part in line.split():
        if "=" in part:
            k, v = part.split("=", 1)
            out[k] = v
    return out


def set_mode(mode: str) -> None:
    adb("shell", "am", "force-stop", PKG)
    time.sleep(0.3)
    if mode == "full":
        adb("shell", f"mv {M}/_g.bak {M}/gemma-4-E2B-it.litertlm", check=False)
        adb("shell", f"mv {M}/_q.bak {M}/qwen3_0.6b_q4_block32_ekv1280.litertlm", check=False)
    elif mode == "workhorse":
        adb("shell", f"mv {M}/_g.bak {M}/gemma-4-E2B-it.litertlm", check=False)
        adb("shell", f"mv {M}/_q.bak {M}/qwen3_0.6b_q4_block32_ekv1280.litertlm", check=False)
        adb("shell", f"mv {M}/gemma-4-E2B-it.litertlm {M}/_g.bak")
    elif mode == "degraded":
        adb("shell", f"mv {M}/gemma-4-E2B-it.litertlm {M}/_g.bak", check=False)
        adb("shell", f"mv {M}/qwen3_0.6b_q4_block32_ekv1280.litertlm {M}/_q.bak", check=False)
    else:
        raise ValueError(mode)
    adb("shell", "am", "force-stop", PKG)
    listing = adb("shell", f"ls -l {M}").stdout
    print(f"MODE {mode}\n{listing}", flush=True)


def restore_models() -> None:
    adb("shell", f"mv {M}/_g.bak {M}/gemma-4-E2B-it.litertlm", check=False)
    adb("shell", f"mv {M}/_q.bak {M}/qwen3_0.6b_q4_block32_ekv1280.litertlm", check=False)
    print(adb("shell", f"ls -l {M}").stdout, flush=True)


def run_one(case_id: str, mode: str, timeout: int, expect_empty: bool) -> dict:
    local = FIXTURES / f"{case_id}.png"
    if not local.exists():
        raise FileNotFoundError(local)
    stem = f"{case_id}-{mode}"
    mid = ensure_scanned(local)
    send_image(mid)
    stats_line = wait_stats(timeout, expect_empty=expect_empty)
    time.sleep(1.0)
    screencap(ARTIFACTS / f"{stem}.png")
    tap_debug()
    time.sleep(0.5)
    screencap(ARTIFACTS / f"{stem}-debug.png")
    logs = dump_logs(stem)
    vibrator_snapshot(stem)
    m = STATS_RE.search(logs)
    line = m.group(1).strip() if m else (stats_line or "")
    parsed = parse_stats(line) if line else {}
    rec = {
        "case_id": case_id,
        "mode": mode,
        "stats": line,
        "parsed": parsed,
        "media_id": mid,
        "empty_path": expect_empty and not line,
    }
    print(json.dumps(rec, ensure_ascii=False), flush=True)
    return rec


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("case_id")
    p.add_argument("--mode", default="full")
    p.add_argument("--timeout", type=int, default=180)
    p.add_argument("--empty", action="store_true")
    p.add_argument("--set-mode", choices=["full", "workhorse", "degraded"])
    p.add_argument("--restore", action="store_true")
    args = p.parse_args()
    if args.restore:
        restore_models()
        return 0
    if args.set_mode:
        set_mode(args.set_mode)
        return 0
    run_one(args.case_id, args.mode, args.timeout, args.empty)
    return 0


if __name__ == "__main__":
    sys.exit(main())
