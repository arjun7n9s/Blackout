import subprocess
import time

ADB = r"W:\Users\Pavantej\Android\Sdk\platform-tools\adb.exe"
S = "10BFAT1UF7000XP"


def adb(*args, timeout=60):
    r = subprocess.run(
        [ADB, "-s", S, *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )
    print(">>", " ".join(args[:6]), "rc=", r.returncode)
    out = (r.stdout or "") + (r.stderr or "")
    print(out[-1200:])
    return r


adb("shell", "cp", "/sdcard/Pictures/BlackoutC/C-004-glare-receipt.png", "/sdcard/Pictures/BlackoutC/c004.png")
adb("shell", "ls", "-l", "/sdcard/Pictures/BlackoutC")
adb(
    "shell",
    "am",
    "broadcast",
    "-a",
    "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
    "-d",
    "file:///sdcard/Pictures/BlackoutC/c004.png",
)
time.sleep(0.5)
adb(
    "shell",
    'content query --uri content://media/external/images/media --projection _id:_display_name --where "_display_name=\'c004.png\'"',
)
print("--- recent ---")
adb(
    "shell",
    "content query --uri content://media/external/images/media --projection _id:_display_name:date_modified --sort \"date_modified DESC\"",
)
