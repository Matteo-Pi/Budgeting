# Budgeting

A personal Android app for tracking spending and forecasting cash flow as a UK student:
how much is **safe to spend per week** until the next student loan instalment, with
term/holiday-aware predictions, uncertainty ranges, what-if scenarios and sinking funds.
All data stays on the phone.

- **Plan and design:** [`docs/PLAN.md`](docs/PLAN.md)
- **Status:** under construction (see the work packages in the plan).

## Installing on your phone

1. Open the repository's **Actions** tab on GitHub and pick the latest green **Build** run.
2. Download the `budgeting-debug-apk` artifact (a zip) and unzip it on your phone.
3. Open `app-debug.apk`. Allow your browser/file manager to "install unknown apps" when asked.
4. Later builds install over the top and keep your data (every build uses the same signing key).

## Development

```bash
./gradlew -p core test          # pure-Kotlin logic and forecast engine (works anywhere)
./gradlew :app:assembleDebug    # Android app (needs the Android SDK; CI does this on every push)
```
