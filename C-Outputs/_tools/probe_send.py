import subprocess
import time

ADB = r"W:\Users\Pavantej\Android\Sdk\platform-tools\adb.exe"
S = "10BFAT1UF7000XP"
PKG = "com.blackout.app"
URI = "content://media/external/images/media/100"


def adb(*args, timeout=60):
    r = subprocess.run(
        [ADB, "-s", S, *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )
    print(">>", " ".join(args)[:200], "rc=", r.returncode)
    print(((r.stdout or "") + (r.stderr or ""))[-2000:])
    return r


adb("shell", "am", "force-stop", PKG)
time.sleep(0.4)
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
    URI,
    "-t",
    "image/png",
    "--grant-read-uri-permission",
)
print("--- wait 12s ---")
time.sleep(12)
adb("shell", "dumpsys activity activities")
print("--- BLACKOUT LOGS ---")
adb("logcat", "-d", "-s", "BlackoutStats:I", "BlackoutLlm:I", "BlackoutAnalyzer:W", "AndroidRuntime:E")
print("--- ActivityTaskManager ---")
adb("logcat", "-d", "-s", "ActivityTaskManager:I", "ActivityManager:I")
