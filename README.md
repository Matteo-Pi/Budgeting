# Budgeting

A personal Android app for tracking spending and forecasting cash flow as a UK student:
how much is **safe to spend per week** until the next student loan instalment, with
term/holiday-aware predictions, uncertainty ranges, what-if scenarios and sinking funds.
All data stays on the phone.

- **Plan and design:** [`docs/PLAN.md`](docs/PLAN.md)
- **Status:** under construction (see the work packages in the plan).

## Installing on your phone

1. On your phone, open the repository on GitHub (signed in) → **Releases** → **Latest build**
   (it is replaced after every successful build).
2. Download `budgeting.apk` and open it. Allow your browser to "install unknown apps" when asked.
3. Later builds install over the top and keep your data (every build uses the same signing key).

The APK is also attached to each **Build** run in the Actions tab as the `budgeting-debug-apk` artifact.

## Development

```bash
./gradlew -p core test          # pure-Kotlin logic and forecast engine (works anywhere)
./gradlew :app:assembleDebug    # Android app (needs the Android SDK; CI does this on every push)
```
