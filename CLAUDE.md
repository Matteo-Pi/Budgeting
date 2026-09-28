# Notes for Claude

- Read `docs/PLAN.md` before changing anything; it is the source of truth for scope,
  architecture, algorithms and file ownership.
- `core/` is a standalone pure-Kotlin build: `./gradlew -p core test`. Maven Central may
  return HTTP 429 in bursts; re-run (optionally with
  `-Dorg.gradle.internal.repository.max.retries=12 -Dorg.gradle.internal.repository.initial.backoff=3000`).
- `app/` needs Google Maven (`dl.google.com`), which may be blocked in cloud sessions; then
  only CI (`.github/workflows/build.yml`) can compile it. Check runs with the GitHub tools.
- Never regenerate `app/debug.keystore`; never use destructive Room migrations.
