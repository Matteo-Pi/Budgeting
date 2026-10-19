# Budgeting

A personal Android app for tracking spending and forecasting cash flow as a UK student:
how much is **safe to spend per week** until the next student loan instalment, with
term/holiday-aware predictions, uncertainty ranges, what-if scenarios and sinking funds.
All data stays on the phone.

- **Plan and design:** [`docs/PLAN.md`](docs/PLAN.md)
- **Status:** first complete version — feature-complete against the plan, not yet tested on a real phone.

## Installing on your phone

1. On your phone, open the repository on GitHub (signed in) → **Releases** → **Latest build**.
   A build is published there only after CI has installed it on an Android emulator as an update over
   the previous build.
2. Download `budgeting.apk`. The release notes give its exact size: if the download is smaller, it
   was cut off, so download it again.
3. Open it from Chrome's **Downloads** (or Files by Google), so **Android's own installer** handles it.
   Allow your browser to "install unknown apps" when asked. Don't use a separate APK installer app:
   some re-sign the app with their own key, and then no later build can update it.
4. Later builds install over the top and keep your data. Every build is signed with the same key,
   SHA-256 `56:C2:7A:40:…:B7:84`. The bottom of the app's **Settings** says whether the copy on
   the phone carries that key.

**If Android says the app is signed with a different certificate** (or "conflicts with an existing
package"), the copy on the phone isn't signed with the project key, and nothing on GitHub can update
it. Export a backup (**Settings → Backup & restore → Export backup**), uninstall the app, install
`budgeting.apk` as in step 3, then restore the backup (**Choose backup file**).

The APK is also attached to each **Build** run in the Actions tab as the `budgeting-debug-apk` artifact.

## Development

```bash
./gradlew -p core test          # pure-Kotlin logic and forecast engine (works anywhere)
./gradlew :app:assembleDebug    # Android app (needs the Android SDK; CI does this on every push)
```
