#!/usr/bin/env bash
# Installs the APK of the newest earlier build of this branch on the emulator, then this build's APK over
# it with `adb install -r`, as a phone updating would. Android refuses the update if the signing key changed
# (or the version went down), so this fails exactly when a phone couldn't update. Needs GH_TOKEN with
# actions: read. Writes its verdict to update-check.txt.
set -u
pkg=io.github.matteopi.budgeting
new=app/build/outputs/apk/debug/app-debug.apk
verdict() { echo "$1" | tee update-check.txt; }

if [ ! -f "$new" ]; then verdict "FAIL: no APK at $new"; exit 1; fi
runs=$(gh run list -R "$GITHUB_REPOSITORY" -w Build -b "$GITHUB_REF_NAME" -L 15 --json databaseId,headSha \
  --jq ".[] | select(.headSha != \"$GITHUB_SHA\") | .databaseId") || runs=""
previous=""
for run in $runs; do
  rm -rf previous-apk
  if gh run download "$run" -R "$GITHUB_REPOSITORY" -n budgeting-debug-apk -D previous-apk >/dev/null 2>&1 &&
    [ -f previous-apk/app-debug.apk ]; then
    previous=$run
    break
  fi
done
if [ -z "$previous" ]; then verdict "SKIPPED: no earlier build of this branch has an APK to update from"; exit 0; fi

adb uninstall "$pkg" >/dev/null 2>&1 || true
first=$(adb install previous-apk/app-debug.apk 2>&1)
if ! echo "$first" | grep -q "^Success"; then verdict "FAIL: could not install run $previous's APK: $first"; exit 1; fi
update=$(adb install -r "$new" 2>&1)
adb uninstall "$pkg" >/dev/null 2>&1 || true
if echo "$update" | grep -q "^Success"; then
  verdict "OK: this build installs as an update over run $previous's APK"
else
  echo "::error::This build does not install as an update over run $previous's APK: $update"
  verdict "FAIL: this build does not install as an update over run $previous's APK: $update"
  exit 1
fi
