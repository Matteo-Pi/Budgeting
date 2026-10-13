#!/usr/bin/env bash
# Diagnostics for the device-tests job: the emulator has been dying part-way through the suite
# ("Can't find service: settings", QEMU threads hanging). Started in the background before the
# tests; writes to device-diag/, which "Collect results" copies into the report.
#  - logcat.txt: which test is running (TestRunner), crashes, low-memory kills, errors.
#  - resources.txt: every 60 s, host memory/disk and the emulator's memory and /data, /sdcard use.
mkdir -p device-diag
adb logcat -v time 'TestRunner:I' 'ActivityManager:W' 'lowmemorykiller:I' 'lmkd:I' 'AndroidRuntime:E' '*:E' \
  > device-diag/logcat.txt 2>&1 &
while true; do
  {
    echo "=== $(date -u +%H:%M:%S)"
    free -m | head -2
    df -h / | tail -1
    adb shell 'grep -E "MemTotal|MemAvailable|SwapFree" /proc/meminfo; df -h /data /sdcard 2>/dev/null | tail -2' 2>&1
  } >> device-diag/resources.txt
  sleep 60
done
