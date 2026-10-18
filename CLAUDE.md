# Notes for Claude

- Read `docs/PLAN.md` before changing anything; it is the source of truth for scope,
  architecture, algorithms and file ownership.
- `core/` is a standalone pure-Kotlin build: `./gradlew -p core test`. Maven Central may
  return HTTP 429 in bursts; re-run (optionally with
  `-Dorg.gradle.internal.repository.max.retries=12 -Dorg.gradle.internal.repository.initial.backoff=3000`).
- `app/` needs Google Maven (`dl.google.com`), which may be blocked in cloud sessions; then
  only CI (`.github/workflows/build.yml`) can compile it. Check runs with the GitHub tools.
- Never regenerate `app/debug.keystore`; never use destructive Room migrations. Every APK is signed
  with that keystore (certificate SHA-256 `56:C2:7A:40:…:B7:84`) and CI fails the build if not;
  `.github/workflows/apk-certs.yml` prints the certificate of each recent build's APK (it runs when
  that file changes). Each CI build's versionCode is its run number (Settings shows "build N").
- Device tests (`app/src/androidTest`) run on two emulators in CI's `device-tests` job (two shards;
  one emulator running everything ran its runner out of memory), merged by `device-report`. The report
  (SUMMARY.txt, screenshots/, results/, log) is pushed to `refs/ci/device-report`; read it with
  `git fetch origin refs/ci/device-report && git worktree add /tmp/report FETCH_HEAD` (or
  `git show FETCH_HEAD:SUMMARY.txt`). CI force-pushes these refs, so when fetching into a named
  ref use a forced refspec (`+refs/ci/device-report:refs/remotes/ci/device-report`); a plain one
  silently keeps the old report. The report commit's message names the commit it tested. A
  push cancels the run in progress, which still publishes a partial (often empty) report, and a
  full run takes about 30 minutes, so batch changes before pushing. Each shard's diagnostics
  (logcat tail, memory/disk/swap and the biggest processes per minute) are in the report's `diag/`. The
  emulator process grows to 6+ GB on the 8 GB runner (host-side graphics), so the job adds swap and gives the
  test run's Gradle a 1 GB heap; if a shard dies near the end, look there first.
- UI test conventions (tapping, date pickers, lazy lists, the keyboard): see the "Testing" notes
  at the end of `docs/PLAN.md` §8.
