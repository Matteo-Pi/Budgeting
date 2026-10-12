# Budgeting app — build plan

This is the single source of truth for what we are building and how. Every coding
agent reads this whole file before starting. If code and this file disagree, the
file wins unless the orchestrator has updated it.

Contents
1. Product summary and decisions
2. Constraints: how this project is built and verified
3. Architecture overview
4. Repository layout and file ownership
5. `core` module in detail (domain + forecast engine)
6. `app` module in detail (Android)
7. Coding conventions
8. Work packages (who builds what, in which order)
9. Open items and future work

---

## 1. Product summary and decisions

A personal, single-user Android app for a UK university student to track spending and
**forecast cash flow**, with the headline number **"safe to spend per week"**.
All data stays on the phone. No server, no login, no bank connection.

### Decisions log (agreed with the user — do not change without asking)

| Topic | Decision |
|---|---|
| Platform | Native Android app (Kotlin, Jetpack Compose, Glance widget, Room). No website. |
| Region / currency | UK, GBP only. |
| Bank | Santander UK (no personal API). Spending is logged by hand; history can be imported from statement files. |
| Primary input | Home-screen widget → quick-add pop-up. Also in-app add. |
| Accounts | User adds any number of accounts of any type. Initially: two current accounts + one savings account. Transfers between accounts are not spending. |
| Zero point | A setting: show **£0 = empty account** or **£0 = overdraft limit used up**. Default: empty account. |
| Future money | Recurring rules (subscriptions, rent, wages), one-off scheduled items on specific dates, and predictions of spending. |
| Labels & categories | Both inflows and outflows have a free-text label and a category. |
| Rent | Just a recurring rule or scheduled item (no special feature). |
| Regimes | Two: **term** and **holiday** (term dates entered by the user). |
| Categories | ~10 spending + 6 income + system "Untracked" (see `DefaultCategories`). |
| Forecast | Tiers 1, 2 and 3 all built from the start. Tiers 2/3 fall back to simpler estimates when data is thin, and **ask the user for a prediction** when it is too thin. |
| Predictions | User can enter their own weekly estimates and adjust the automatic ones. |
| Reconciliation | "Update balance" records the gap as an **Untracked** transaction; the forecast learns the user's untracked leakage like any other category. |
| Features in v1 | What-if scenarios, term-aware calendar/timeline, sinking funds, lumpy-income handling (budget periods + student loan helper), balance reconciliation, anomaly flagging, forecast accuracy tracking, trend graphs, category breakdowns, multiple accounts. |
| Not in v1 | Subscription detector, shared expenses, multi-currency, notification capture, Open Banking, laptop/web version. |
| Privacy | Option to hide widget amounts; optional fingerprint/PIN lock; option to hide app content in recent apps. |
| Backups | Android auto-backup + manual export/import (JSON) + CSV export. |
| Widget | Shows safe-to-spend/week and days until next money in; "+" opens quick add; optional one-tap preset buttons. |
| Laptop | Maybe later. Keep all logic in the pure-Kotlin `core` module so it could be reused; do nothing else for it now. |

### Glossary
- **Pool**: the accounts with `includeInSafeToSpend = true`. Forecasts are for the pool's summed balance.
- **Committed flow**: a known future amount (recurring rule occurrence, scheduled item, sinking-fund spend, scenario item).
- **Learned flow**: an amount estimated from history — categories with `ForecastMode.LEARNED` (e.g. groceries, variable wages, Untracked).
- **Discretionary spending**: learned OUT categories except the system Untracked category. Safe-to-spend is a budget for this.
- **Major income**: an income that starts a new budget period (e.g. student loan instalment). Flag on rules/items.
- **Raw vs display balance**: raw = the bank's number. Display = raw + overdraft limit when zero point is OVERDRAFT_LIMIT.

---

## 2. Constraints: how this project is built and verified

- The cloud environment this is developed in **cannot download the Android SDK or AndroidX**
  (`dl.google.com` is blocked by network policy). So:
  - **`core` is a separate, pure-Kotlin Gradle build** that compiles and tests locally:
    `./gradlew -p core test`. Put every piece of logic that doesn't need Android here.
  - **`app` can only be compiled by GitHub Actions** (`.github/workflows/build.yml`), which
    runs on every push: core tests → `:app:assembleDebug :app:testDebugUnitTest` → uploads
    the APK as the `budgeting-debug-apk` artifact.
- Maven Central rate-limits bursts (HTTP 429). If Gradle fails with 429 / "Could not resolve",
  just re-run; add `-Dorg.gradle.internal.repository.max.retries=12
  -Dorg.gradle.internal.repository.initial.backoff=3000`. Successful downloads are cached.
- **Signing**: `app/debug.keystore` is committed (repo is private) so every build has the same
  signature and installs as an update over the previous one **without losing data**. Never
  regenerate or remove it.
- **Database migrations**: the user's data lives only on their phone. **Never** use
  `fallbackToDestructiveMigration`. Every schema change bumps the Room version and adds an
  `@AutoMigration` or a manual `Migration`. Room schemas are exported to `app/schemas/` and committed.
- **Installing on the phone**: download the APK artifact from the latest green Actions run,
  unzip, open the `.apk` on the phone, allow "install unknown apps" once.

---

## 3. Architecture overview

```
┌─────────────────────────── app (Android) ───────────────────────────┐
│  UI (Compose screens + ViewModels)     Widget (Glance)   QuickAdd    │
│            │                                │               │        │
│            ▼                                ▼               ▼        │
│  Repositories  ◄──── AppContainer (manual DI) ────►  ForecastService │
│      │                                                     │         │
│  Room DB + DataStore settings            WorkManager (DailyWorker)   │
└──────┼─────────────────────────────────────────────────────┼─────────┘
       │ maps entities ⇄ core models                          │ calls
┌──────▼─────────────────────── core (pure Kotlin) ───────────▼─────────┐
│ model/  forecast/ (engine: tiers 1–3, safe-to-spend, periods, funds) │
│ analysis/ (anomalies, accuracy, trends)  importer/  backup/  entry/  │
│ reconcile/                                                            │
└───────────────────────────────────────────────────────────────────────┘
```

Data flow:
1. Every write goes through a repository → Room → Room `Flow`s re-emit.
2. `ForecastService` combines all data flows + settings + today's date into a `ForecastInput`,
   runs `ForecastEngine.run` on `Dispatchers.Default`, and exposes `StateFlow<ForecastResult?>`.
3. Screens observe repositories and/or the forecast via ViewModels.
4. After any write, repositories ask `WidgetUpdater` to refresh the widget (debounced).
5. `DailyWorker` (and app start) posts due recurring/scheduled items, saves accuracy snapshots,
   refreshes the widget and sends the optional weekly "check your balances" reminder.

---

## 4. Repository layout and file ownership

```
Budgeting/
├── docs/PLAN.md                  ← this file
├── README.md, CLAUDE.md
├── settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml
├── .github/workflows/build.yml
├── core/                         ← standalone Gradle build (pure Kotlin, JVM 17 bytecode)
│   └── src/{main,test}/kotlin/io/github/matteopi/budgeting/core/
│       ├── model/                ← DONE (orchestrator). Shared contract. Do not change without asking.
│       ├── forecast/             ← WP-C1 (ForecastModels.kt is contract: add fields only)
│       ├── analysis/             ← WP-C2 (anomalies, accuracy, trends)
│       ├── importer/             ← WP-C2 (CSV, statement mapping, merchant keys, dedupe)
│       ├── backup/               ← WP-C2 (backup DTOs + JSON codec)
│       ├── entry/                ← WP-C2 (keypad amount entry, category ranking)
│       └── reconcile/            ← WP-C2
└── app/
    ├── build.gradle.kts, debug.keystore, schemas/
    └── src/main/java/io/github/matteopi/budgeting/
        ├── BudgetApp.kt, AppContainer.kt, MainActivity.kt        ← WP-A1
        ├── data/db/ (entities, DAOs, database)                    ← WP-A1
        ├── data/repo/ (repositories, mappers)                      ← WP-A1
        ├── data/settings/SettingsStore.kt                          ← WP-A1
        ├── domain/ (ForecastService, PostingService, Clock)        ← WP-A1
        ├── ui/theme/, ui/navigation/, ui/components/ (shared)      ← WP-A1 (charts: WP-U4)
        ├── ui/quickadd/, widget/, ui/settings/PresetsScreen.kt     ← WP-U1
        ├── ui/home/, ui/activity/, ui/accounts/                    ← WP-U2
        ├── ui/plan/, ui/forecast/PredictionsScreen.kt              ← WP-U3
        ├── ui/forecast/ (rest), ui/components/charts/, ui/insights/ ← WP-U4
        ├── ui/settings/ (rest), ui/lock/, work/, backup/, ui/importer/ ← WP-U5
        └── ui/onboarding/                                          ← WP-U6
```

**Ownership rule**: a work package only creates/edits files it owns. WP-A1 creates a
placeholder composable for every screen (e.g. `ui/home/HomeScreen.kt` containing
`@Composable fun HomeScreen(...)`) and wires it into navigation, so later packages only
replace the bodies of files they own. If you genuinely need to change a shared file
(AppContainer, navigation, a repository, the manifest, the version catalog), make the
smallest possible change and **list it in your final report**.

---

## 5. `core` module in detail

Package root: `io.github.matteopi.budgeting.core`. No Android imports. Only JDK APIs that
exist on Android API 26: `java.time.*` is fine; **do not** use `java.util.random.*`,
`List.getFirst()`/`getLast()` (JDK 21), `String.repeat` is fine (Kotlin's). Use
`kotlin.random.Random(seed)` for randomness. Everything must be deterministic for a given input.

### 5.1 Models (`model/`, done)
See `Money.kt`, `Enums.kt`, `Models.kt`, `Settings.kt`, `DefaultCategories.kt`.
Key conventions:
- `Money` = pence in a `Long`. **Negative = money leaving an account.**
- `Transaction.amount` is signed. Transfers are two rows (kind TRANSFER, same `transferGroupId`,
  `categoryId = null`): the source row negative, the destination row positive.
- `RecurringRule.amount` / `ScheduledItem.amount` are signed, except transfers
  (`toAccountId != null`) where it is the positive amount moved.
- `Prediction.weeklyAmount` is a positive magnitude; the sign comes from the category's flow
  (OUT → negative, IN → positive).

### 5.2 Calendar and regimes (`forecast/Regimes.kt`)
```kotlin
object Regimes {
    fun weekStart(date: LocalDate): LocalDate          // Monday on or before date
    fun termContaining(date: LocalDate, terms: List<TermPeriod>): TermPeriod?
    fun regimeOf(date: LocalDate, terms: List<TermPeriod>): Regime   // TERM if inside any term (inclusive) else HOLIDAY
    fun regimeOfWeek(weekStart: LocalDate, terms: List<TermPeriod>): Regime // majority (>=4 of 7 days)
}
```

### 5.3 Recurrence (`forecast/Recurrence.kt`)
```kotlin
object Recurrence {
    /** Occurrence dates of the rule within [from, to] inclusive, ascending. */
    fun occurrences(rule: RecurringRule, from: LocalDate, to: LocalDate): List<LocalDate>
    fun occurrences(frequency: Frequency, interval: Int, start: LocalDate, end: LocalDate?,
                    from: LocalDate, to: LocalDate): List<LocalDate>
}
```
Occurrence k = `start.plusDays/plusWeeks/plusMonths/plusYears(k * interval)` computed **from
start each time** (never chained), so a rule on the 31st lands on the 31st in long months and
the last day in short months. Stop after `end` (inclusive) or `to`. `interval < 1` → treat as 1.

### 5.4 Balances (`forecast/Balances.kt`)
```kotlin
object Balances {
    fun accountBalance(accountId: Long, transactions: List<Transaction>, asOf: LocalDate): Money
    fun balancesByAccount(transactions: List<Transaction>, asOf: LocalDate): Map<Long, Money>
    fun pool(accounts: List<Account>): List<Account>          // includeInSafeToSpend && !archived
    fun poolBalance(accounts: List<Account>, transactions: List<Transaction>, asOf: LocalDate): Money
    fun poolOverdraft(accounts: List<Account>): Money          // sum of overdraftLimit over pool
    fun floor(accounts: List<Account>, zeroPoint: ZeroPoint): Money  // 0 or -poolOverdraft
    fun toDisplay(raw: Money, overdraft: Money, zeroPoint: ZeroPoint): Money // raw + overdraft if OVERDRAFT_LIMIT
}
```
A balance "as of" a date sums all transactions (every kind) with `date <= asOf`.

### 5.5 Committed flows (`forecast/CommittedFlows.kt`)
Build the list of `CommittedFlow` for days `today+1 .. forecastEnd`
(`forecastEnd = today + settings.forecastHorizonDays`):
1. **Recurring rules** (active): occurrences in `(postedThrough ?: startDate-1, forecastEnd]`.
   Any occurrence dated `<= today` is not yet posted/confirmed → move it to `today+1`
   (source `RECURRING` for AUTO, `OVERDUE_CONFIRM` for CONFIRM).
2. **Scheduled items**: `!posted && !skipped && date <= forecastEnd`; dated `<= today` → `today+1`
   (source `SCHEDULED` or `OVERDUE_CONFIRM`).
3. **Future-dated transactions** (defensive; the UI shouldn't allow them): treat as flows on their date.
4. **Sinking funds** (ACTIVE) with `today < targetDate <= forecastEnd`: flow of `-target` on
   `targetDate`, source `SINKING_FUND`, label = fund name.
5. **Scenario** items (if `input.scenario != null`): ONE_OFF (date > today) and RECURRING
   expansions, source `SCENARIO`. (CATEGORY_ADJUST is applied to estimates, §5.8.)

Pool effect of an amount on account A (optionally to account B):
- not a transfer: the amount if A is in the pool, else skip;
- transfer A→B: both in pool → skip; A in pool only → `-amount`; B in pool only → `+amount`; neither → skip.

`isMajorIncome` is kept only when the pool effect is positive. Label = rule/item label, or the
category name if blank. Output sorted by date, then label.

### 5.6 History preparation (`forecast/History.kt`)
- **Learned transactions**: `kind in {NORMAL, RECONCILIATION}`, account in pool, `categoryId`
  of a category with `forecastMode == LEARNED`, `recurringRuleId == null`, `scheduledItemId == null`.
- **History start** = `settings.historyStartDate` ?: earliest date among learned transactions ?:
  none. **Complete weeks**: Monday-start weeks that begin on/after the history start (a partial
  first week is excluded) and end (Sunday) before today. Look back at most 52 weeks.
- **Reconciliation spreading** (statistics only, never the ledger): a RECONCILIATION transaction
  is spread evenly across `[periodStart, date]` of its `Reconciliation` record (match by
  `transactionId`; if none, the 7 days ending on its date). Spread in whole pence, remainder on
  the last day.
- Produce, for each learned category: daily amounts and weekly totals `W[c][week]` (signed),
  with each week tagged with `Regimes.regimeOfWeek`.

### 5.7 Tier 1 — baseline (`forecast/Tier1.kt`)
For each learned category c: `μ1[c]` = median of `W[c]` over the most recent ≤ 8 complete weeks
(any regime). If there are 0 weeks: the average of the user's PRIOR/REPLACE predictions for c
across regimes (signed), else 0. Daily amount = `μ1[c] / 7` every day.
`DayForecast.tier1Balance` = pool balance now + cumulative committed flows + cumulative tier-1
learned flows. Also fill `ForecastResult.tier1WeeklyByCategory`.

### 5.8 Tier 2 — term/holiday levels + day-of-week (`forecast/Tier2.kt`)
Constants (one `object ForecastConstants`): `SHRINK_K = 4`, `TRUSTED_WEEKS = 6`,
`THIN_WEEKS = 2`, `FALLBACK_MIN_WEEKS = 4`, `RECENT_WEEKS = 12`, `DOW_PRIOR_WEEKS = 4`.

For each learned category c and regime r:
- `n_r` = number of complete weeks with regime r in the history window.
- `m_r` = median of `W[c]` over the most recent ≤ `RECENT_WEEKS` weeks of regime r.
- `n_all`, `m_all` = the same over all regimes (most recent ≤ 12 weeks).
- Fallback `F` = `m_all` if `n_all >= FALLBACK_MIN_WEEKS` else null.
- Shrink weight `w = n_r / (n_r + SHRINK_K)`.
- **Automatic estimate** `A`: if `n_r >= 1`: `w*m_r + (1-w)*(F ?: m_r)`; else `F ?: 0`.
- **Final estimate** by the user's prediction `P` for (c, r), if any
  (`P.signed` = `±weeklyAmount` by category flow):
  - REPLACE → `P.signed` (source PREDICTION_REPLACE)
  - ADJUST_PERCENT → `A × (1 + adjustPercent/100)` (source PREDICTION_ADJUSTED)
  - PRIOR → if `n_r >= 1`: `w*m_r + (1-w)*P.signed` (source DATA if `n_r >= 6` else BLENDED);
    else `P.signed` (source PREDICTION_PRIOR)
  - none → `A`; source DATA (`n_r >= 6`), BLENDED (`1..5`), FALLBACK_OVERALL (`n_r == 0 && F != null`), else NONE.
- **Sufficiency**: TRUSTED `n_r >= 6`; THIN `2..5`; TOO_THIN `<= 1`.
- **Needs prediction** (→ `ForecastResult.needsPrediction`): TOO_THIN **and** no prediction
  **and** category flow OUT, not system, not archived **and** regime r occurs somewhere in
  `[today, forecastEnd]`. `suggestedWeekly` = `|A|` unless the source would be NONE.
- **Scenario CATEGORY_ADJUST** multiplies the final estimate by `1 + percent/100` for the
  matching category (and regime, or both if null).
- **Day-of-week shares** (7 values, Mon..Sun, sum 1), from the last ≤ 26 complete weeks using
  absolute daily amounts:
  - global empirical share `e_G` over all learned OUT categories; `g = (N·e_G + β·uniform)/(N+β)`
    with N = weeks of data, β = `DOW_PRIOR_WEEKS`; if no data `g = uniform`.
  - category share `s_c = (N_c·e_c + β·g)/(N_c+β)` where N_c = weeks in which c had any amount;
    if c has no data `s_c = g`.
- Expected learned amount on date d = Σ_c `estimate(c, regime(d)) × s_c[dayOfWeek(d)]`.
- `DayForecast.expectedBalance` = pool now + cumulative committed + cumulative expected learned.

### 5.9 Tier 3 — Monte Carlo uncertainty (`forecast/Tier3.kt`)
Run only if `input.runMonteCarlo`. `S = settings.simulations` runs, `Random(input.seed)`.
- Simulate week by week over the forecast days (first week partial: only its remaining days).
- **Bootstrap whole weeks** to keep categories correlated: for forecast week with regime r,
  pick one historical week index from pool_r = complete weeks of regime r if `|pool_r| >= 4`,
  else all complete weeks if `>= 4`, else use the parametric fallback for every category.
- For each learned category c with final weekly estimate `E` (signed):
  - flow OUT or IN: multiplicative — `mean_c` = mean of `W[c]` over the pool used;
    if `|mean_c| >= 100` pence: `sim = E × W[c][picked] / mean_c`; else parametric.
  - flow BOTH (Untracked): additive — `sim = E + (W[c][picked] − mean_c)`.
  - Clamp: OUT results ≤ 0, IN results ≥ 0.
  - Parametric fallback: lognormal around `|E|` with CV 0.5 (CV 0.35 when the estimate came
    from a REPLACE prediction): `σ = sqrt(ln(1+CV²))`, `μ = ln|E| − σ²/2`,
    `|sim| = exp(μ + σZ)` (Z standard normal via Box–Muller), sign of E. `E == 0` → 0.
  - Spread the weekly amount over the week's days with `s_c`; drop days outside the forecast.
- Add committed flows (deterministic) per day; track the pool balance path per simulation.
- Per day: P10/P50/P90 across simulations (sort a column; index `floor(q·(S−1))`).
- `RiskSummary`: over days `today+1 .. safeToSpend.horizonEnd`:
  `probBelowFloor` = share of simulations whose minimum balance `< floor`;
  `probBeyondOverdraft` = share whose minimum `< -poolOverdraft`;
  `firstLikelyBreach` = first day with `P10 < floor`; end-balance percentiles at `horizonEnd`.
- Performance target: 1000 simulations × 183 days × 15 categories in < 300 ms on a desktop JVM.
  Avoid allocating per simulated day; use `LongArray`s.

### 5.10 Sinking funds (`forecast/SinkingFunds.kt`)
For an ACTIVE fund with `start < targetDate`: `reserved(d)` = 0 before `start`;
`target × min(1, (d − start + 1) / (targetDate − start))` for `start <= d < targetDate`
(fully reserved the day before); 0 from `targetDate` (the money is then spent — the committed
flow in §5.5). If `start >= targetDate`: fully reserved from `start` until `targetDate`.
`weeklyContribution = target × 7 / (targetDate − start)` days. `DayForecast.reserved` = Σ funds.

### 5.11 Safe-to-spend (`forecast/SafeToSpend.kt`)
A **sustainable weekly budget over the whole forecast**. (The first version stopped at the day
before the next major income; with an income a few days away that gave absurd answers — £0 or
£14,000+/week — so it was replaced.)
- `H = forecastEnd`. For each day d in `today+1 .. H`, with `k(d)` = days from today:
  `h(d) = poolNow + C(d) + L(d) − R(d) − floor − buffer`, where `C` = cumulative committed flows,
  `L` = cumulative expected learned flows excluding discretionary categories (learned IN at 75%,
  Untracked at 100%), `R` = sinking-fund reservations; bound `W(d) = 7 × h(d) / k(d)`.
- `weekly = max(0, min_d W(d))` (whole pence, rounded down); `perDay = weekly / 7`;
  `shortfall = max(0, −min_d h(d))`; `bindingDate` = the d giving the minimum.
- `bindingReason`: the largest committed outflow in the 3 days up to `bindingDate` →
  "Rent on 1 Oct"; else a major income the day after → "Until Student loan on 11 Jan"; else
  `bindingDate == H` → "Over the next N weeks"; else null. Month names via `Locale.ENGLISH`.
- `nextMajorIncome` / `daysUntilNextMajorIncome`: first major income after today (overdue CONFIRM
  items count as tomorrow) — for display only.
- `predictedWeeklySpending` = Σ |final estimate| over discretionary categories for `regime(today)`.

### 5.11a Per-account forecasts (`forecast/AccountSplit.kt`)
`ForecastResult.accountForecasts` repeats the forecast for every non-archived account.
- Committed flows go to their own account; transfers A→B are −amount on A and +amount on B;
  sinking-fund spends and scenario items without an account go to the **default account**
  (settings default if a non-archived pool account, else the smallest-id pool account, else the
  smallest-id non-archived account).
- Learned flows go to pool accounts only, split by each category's historical share per account
  (no history → default account), with largest-remainder rounding so that Σ pool accounts'
  expected balances equals the pool's exactly, every day.
- Tier 3 tracks each pool account inside the same simulations (per-account P10/P50/P90);
  non-pool accounts are deterministic (p10 = p50 = p90 = expected when Monte Carlo ran).

### 5.12 Budget periods — lumpy income (`forecast/BudgetPeriods.kt`)
Split `today+1 .. forecastEnd` at each major-income date (same-day incomes merged).
Period 0 = `today+1 .. firstMajor−1` (current); then `[M_i, M_{i+1}−1]`; last ends at forecastEnd.
For each period: `committedOut`/`committedIn` = sums of negative / positive committed flows in
the period, **excluding** the major incomes and sinking-fund flows. Fund cost allocated to a
period = `reserved(end) − reserved(start−1)` + targets whose date falls in the period.
`availableForSpending`:
- current period: `poolNow − reserved(today) − floor − buffer + committedIn + committedOut
  + L(period) − fundAllocation`;
- later periods: `startingIncome + committedIn + committedOut + L(period) − fundAllocation`
  (assumes the previous period ended at floor + buffer).
`weeks = days/7.0`; `perWeek = available / weeks` (may be negative — show as a deficit).

### 5.13 Engine entry point (`forecast/ForecastEngine.kt`)
```kotlin
object ForecastEngine { fun run(input: ForecastInput): ForecastResult }
```
Pure, no I/O, deterministic for equal input. `days` starts at **today** (balance = pool now,
no flows) and then one entry per day to `forecastEnd`. Everything in `ForecastResult` must be
populated (risk = null when Monte Carlo skipped; p10/p50/p90 null likewise).

### 5.14 Analysis (`analysis/`)
Implemented independently of `forecast/` internals (use only the public contract types and
`model/`). Small duplicated aggregation code is acceptable.
```kotlin
object Anomalies { fun detect(input: ForecastInput, result: ForecastResult): List<Anomaly> }
data class Anomaly(val kind: AnomalyKind, val categoryId: Long?, val weekStart: LocalDate,
                   val actual: Money, val expected: Money, val message: String)
enum class AnomalyKind { HIGH_CATEGORY, HIGH_TOTAL, LOW_LOGGING }
```
- Uses the current week-to-date and the last complete week, pool accounts, discretionary categories.
- Expected for a (partial) week = |weekly estimate for that week's regime| × Σ day shares of the
  days covered (from `result.categoryEstimates`).
- HIGH_CATEGORY: actual > 1.5 × expected **and** actual − expected ≥ £15, only if the
  estimate isn't NONE. HIGH_TOTAL: same on total discretionary with ≥ £25.
- LOW_LOGGING (last complete week only): logged discretionary < 40% of expected, expected ≥ £30,
  and no reconciliation in the last 7 days → "Quiet week — did you forget to log something?
  Update your balances to check."
- Messages are plain English, e.g. "Eating out this week: £62 vs usual £35".

```kotlin
object Accuracy {
    /** Snapshots to store today: pool balance at +7/+14/+28 days (tier 1 and full model) and
     *  next week's per-category prediction (tier 1 and full). */
    fun snapshotsFor(result: ForecastResult): Pair<List<BalanceSnapshot>, List<CategoryWeekSnapshot>>
    fun evaluate(balanceSnapshots: List<BalanceSnapshot>, categorySnapshots: List<CategoryWeekSnapshot>,
                 accounts: List<Account>, transactions: List<Transaction>, today: LocalDate): AccuracyReport
}
```
`AccuracyReport`: per horizon (7/14/28): number evaluated, mean absolute error for tier 1 and for
the full model, and coverage (share of actuals inside P10–P90); per category: mean absolute
week-ahead error tier 1 vs full; an overall `improvementPercent` of full vs tier 1 (null if
< 3 evaluated snapshots). Only snapshots with `targetDate < today` (or week ended) are evaluated.
Actual pool balance uses the *current* pool membership.

```kotlin
object Trends {
    fun weeklyByCategory(accounts, categories, transactions, from: LocalDate, to: LocalDate): List<WeekTotals>
    fun termVsHoliday(accounts, categories, transactions, terms, historyStart: LocalDate?): List<CategoryRegimeAverage>
    fun balanceHistory(accounts, transactions, from: LocalDate, to: LocalDate): List<Pair<LocalDate, Money>>
    fun monthlyInOut(accounts, categories, transactions, months: Int, today: LocalDate): List<MonthInOut>
}
```
Trends exclude TRANSFER and OPENING_BALANCE; pool accounts only; RECONCILIATION counted under Untracked.

### 5.15 Reconciliation (`reconcile/Reconcile.kt`)
```kotlin
object Reconcile {
    fun plan(accountId: Long, expected: Money, actual: Money, today: LocalDate,
             previous: Reconciliation?, accountStartDate: LocalDate?, untrackedCategoryId: Long,
             nowMillis: Long): ReconcilePlan
}
data class ReconcilePlan(val difference: Money, val transaction: Transaction?, val reconciliation: Reconciliation)
```
`difference = actual − expected`. If non-zero: a RECONCILIATION transaction on today, category
Untracked, label "Balance update", amount = difference. `periodStart` = previous.date + 1, else
accountStartDate + 1, else today − 6; never after today.

### 5.16 Statement import (`importer/`)
- `CsvParser.parse(text): CsvTable` — strips a UTF-8 BOM; auto-detects the delimiter among
  `,` `;` `\t` (most consistent column count over the first 20 non-empty lines); RFC-4180 quoting
  (`""` escapes, quoted newlines); CRLF/LF; trims cells.
- `StatementMapping(hasHeader, dateColumn, datePattern, amountMode: SIGNED|DEBIT_CREDIT,
  amountColumn, debitColumn, creditColumn, descriptionColumn, balanceColumn?)`.
- `StatementImport.guessMapping(table)` from header names (Date / Transaction date; Description /
  Details / Narrative / Reference; Amount / Value; Money in / Paid in / Credit; Money out /
  Paid out / Debit; Balance). Date pattern guessed from the first rows among
  `dd/MM/yyyy`, `dd/MM/yy`, `yyyy-MM-dd`, `dd-MM-yyyy`, `d MMM yyyy`, `dd MMM yyyy`, `d/M/yyyy`.
- `StatementImport.parseRows(table, mapping): ParseOutcome(rows: List<ParsedRow>, errors: List<RowError>)`;
  amounts accept "£", commas, "-", "(12.50)" negatives, and "CR"/"DR" suffixes.
  DEBIT_CREDIT: amount = credit − |debit|.
- `MerchantKey.normalise(description)`: uppercase; drop prefixes such as "CARD PAYMENT TO",
  "CONTACTLESS PAYMENT TO", "DIRECT DEBIT PAYMENT TO", "STANDING ORDER TO", "BILL PAYMENT VIA
  FASTER PAYMENT TO", "FASTER PAYMENTS RECEIPT FROM", "PURCHASE"; drop ",? ON dd-mm-yyyy"-style
  dates, "REF…" tails, card masks, 4+ digit numbers, amounts, "GBP"; collapse spaces; max 40 chars.
- `CategorySuggester.suggest(merchantKey, rules, categories): Long?` — exact `MerchantRule` match
  first, then a built-in keyword table keyed by **default category names** (Groceries: TESCO,
  SAINSBURY, ASDA, ALDI, LIDL, MORRISONS, CO-OP, WAITROSE, M&S; Eating out: UBER EATS, DELIVEROO,
  JUST EAT, MCDONALD, GREGGS, PRET, STARBUCKS, COSTA, NANDO, KFC, SUBWAY; Transport: TFL,
  TRAINLINE, NATIONAL RAIL, STAGECOACH, ARRIVA, FIRST BUS, UBER (not UBER EATS), BOLT;
  Shopping: AMAZON, ASOS, PRIMARK, H&M, ZARA, ARGOS; Subscriptions: SPOTIFY, NETFLIX, APPLE.COM,
  DISNEY, YOUTUBE, AMAZON PRIME; Health: BOOTS, SUPERDRUG, PHARMACY; Student loan: SLC,
  STUDENT LOANS; Wages: SALARY, WAGES, PAYROLL). Unknown names are skipped.
- `ImportDedupe.importKeys(accountId, rows)`: SHA-256 hex (`java.security.MessageDigest`) of
  `accountId|date|pence|merchantKey|occurrence`, where occurrence counts identical
  (date, amount, key) rows earlier in the same file.
- `ImportDedupe.classify(accountId, rows, keys, existing): List<RowStatus>` —
  ALREADY_IMPORTED if the key exists; POSSIBLE_DUPLICATE if an existing NORMAL non-imported
  transaction on the same account has the same amount within ±2 days (each existing
  transaction matches at most one row); else NEW.
- `OpeningBalanceAdjuster.adjust(opening: Transaction, imported: List<ParsedRow>): Transaction?` —
  imported rows dated **before** the opening-balance date would double count (the opening
  balance already includes them). Return the opening transaction with
  `amount = old − Σ(those rows)` and `date = earliest imported date − 1`, or null if none.
- Santander's own text export format: **pending a sample file from the user** (see §9).

### 5.17 Backup (`backup/`)
- `@Serializable data class BackupV1(format = "budgeting-backup", version = 1, exportedAt: String,
  accounts, categories, transactions, recurringRules, scheduledItems, termPeriods, predictions,
  sinkingFunds, scenarios (with items), presets, merchantRules, reconciliations, importBatches,
  balanceSnapshots, categoryWeekSnapshots, settings: SettingsDto, appSettings: Map<String,String>)`.
- **Version 2** (current) adds `receipts`, `receiptItems`, `products`, `productAliases` and
  `TransactionDto.receiptId`; the class keeps the name `BackupV1` and holds versions 1 and 2. All new
  fields default to empty/null, so a version-1 file still decodes (no receipts); a version above the
  current one is refused. `decode` also checks the new references (items → receipt/product/category,
  aliases → product, products → category, transaction → receipt).
- DTOs use primitives only: ISO date strings, pence as Long, enum names as String. Ids preserved.
- `BackupMapper.toBackup(data: BackupData, exportedAt: String, appSettings): BackupV1` and
  `fromBackup(b): BackupData` where `BackupData` holds the core model lists + `BudgetSettings`.
- `BackupCodec.encode(b): String` (pretty JSON) and `decode(text): BackupDecodeResult`
  (success with data, or a list of human-readable errors: wrong format, newer version, broken
  references such as a transaction pointing at a missing account).

### 5.18 Quick entry helpers (`entry/`)
- `AmountEntry(text)` immutable; `press(key: KeypadKey): AmountEntry`; keys `Digit(0..9)`, `Dot`,
  `Backspace`, `Clear`. Rules: ≤ 2 decimals, ≤ 6 integer digits, no leading zeros, one dot,
  "." first → "0.". `money: Money?` (null if empty or zero); `display: String` ("£0", "£12.5").
- `CategoryRanking.rank(categories, transactions, flow, today, windowDays = 60): List<Category>` —
  non-archived, non-system categories usable for the flow; ordered by count of NORMAL
  transactions in the window (desc), then `sortOrder`.

### 5.19 Tests (required)
Every file in `core` gets unit tests (`kotlin.test` on JUnit 4). Minimum scenarios:
recurrence month-end handling and intervals; transfer pool effects; history window & partial
weeks; reconciliation spreading; tier 1 medians; tier 2 shrinkage, fallback, every prediction
mode, sufficiency and needs-prediction rules; DoW shares sum to 1; tier 3 determinism (same
seed → same result), percentile ordering (P10 ≤ P50 ≤ P90), zero-variance history gives a
narrow band, sign clamping; safe-to-spend binding date (rent before the loan), shortfall, the
"< 7 days" horizon rule, buffer and zero-point modes, fund reservations; budget periods;
anomalies; accuracy; CSV quoting/delimiters/date formats; merchant keys on realistic Santander
strings; dedupe occurrences; opening balance adjustment; backup round-trip and error cases;
keypad rules; category ranking.

---

## 6. `app` module in detail

Package root: `io.github.matteopi.budgeting`. minSdk 26, targetSdk 36, compileSdk 37.
Library versions are in `gradle/libs.versions.toml` (Compose 1.12.1, Material3 1.4.0,
Room 2.8.5, Glance 1.2.0, Navigation 2.10.2, Lifecycle 2.11.0, WorkManager 2.12.0,
DataStore 1.2.1, Biometric 1.1.0). AGP 9 has **built-in Kotlin**: never apply
`org.jetbrains.kotlin.android`, never use `kotlinOptions {}` or kapt (Room uses KSP).

### 6.1 Database (`data/db/`)
`BudgetDatabase` (Room, `version = 2`, `exportSchema = true`, `@AutoMigration(from = 1, to = 2)`;
the version-1 schema is committed in `app/schemas/`, and `data/MigrationTest` checks 1 → 2). **Entities use only primitive
column types** (Long, Int, String, Boolean, nullable variants): dates as `Long` epoch-day,
money as `Long` pence, enums as `String` names, instants as `Long` epoch millis. No
TypeConverters needed. Table and column names are snake_case.

| Table | Columns (besides `id INTEGER PK autoGenerate`) | Notes |
|---|---|---|
| accounts | name, type, overdraft_limit, include_in_pool, archived, sort_order | |
| categories | name, flow, forecast_mode, color_argb?, is_system, system_key?, is_major_income_default, archived, sort_order | unique index on system_key |
| transactions | account_id → accounts CASCADE, amount, date, created_at, category_id? → categories (NO ACTION), label, note, kind, transfer_group_id?, recurring_rule_id? → recurring_rules SET NULL, scheduled_item_id? → scheduled_items SET NULL, import_batch_id? → import_batches SET NULL, import_key? | indices: account_id, date, category_id, transfer_group_id, recurring_rule_id, scheduled_item_id, import_batch_id; unique import_key |
| recurring_rules | label, account_id → accounts CASCADE, category_id?, amount, frequency, interval, start_date, end_date?, posting_mode, posted_through?, to_account_id? → accounts CASCADE, is_major_income, active | |
| scheduled_items | label, account_id → CASCADE, category_id?, amount, date, posting_mode, to_account_id? → CASCADE, is_major_income, posted, skipped | index date |
| term_periods | name, start_date, end_date | |
| predictions | category_id → categories CASCADE, regime, mode, weekly_amount, adjust_percent | unique (category_id, regime) |
| sinking_funds | name, target, start_date, target_date, category_id?, status | |
| scenarios | name, created_at | |
| scenario_items | scenario_id → scenarios CASCADE, type, label, account_id?, category_id?, amount, date?, frequency?, interval, end_date?, percent, regime? | |
| presets | label, amount, flow, category_id → CASCADE, account_id? → accounts SET NULL, show_on_widget, sort_order | |
| merchant_rules | merchant_key, category_id → CASCADE, label? | unique merchant_key |
| reconciliations | account_id → CASCADE, date, expected, actual, transaction_id?, period_start | |
| import_batches | account_id → CASCADE, imported_at, file_name, row_count, first_date?, last_date? | |
| balance_snapshots | created_on, target_date, tier, p10?, p50, p90? | unique (created_on, target_date, tier) |
| category_week_snapshots | created_on, week_start, category_id → CASCADE, tier, predicted | unique (created_on, week_start, category_id, tier) |
| receipts *(v2)* | store, date, total, source, transaction_id? (informational, no FK), created_at | |
| receipt_items *(v2)* | receipt_id → receipts CASCADE, product_id? → products SET NULL, raw_name, quantity, weight_grams?, unit_price?, price, discount, category_id? → categories (NO ACTION) | indices on the three FK columns |
| products *(v2)* | name, category_id? → categories (NO ACTION) | |
| product_aliases *(v2)* | store, raw_name_key, product_id → products CASCADE | unique (store, raw_name_key) |

Version 2 also adds `transactions.receipt_id INTEGER` (nullable, indexed, **no foreign key**: adding
one to an existing table would need a table rebuild, so `ReceiptRepository` keeps it consistent).
The version-1 → 2 change only adds tables and a nullable column, so Room's `@AutoMigration` needs
no spec; never replace it with a destructive migration. Commit the generated `2.json` next to `1.json`.

Categories are never deleted, only archived (they're referenced by transactions).
One DAO per table group, e.g. `AccountDao`, `TransactionDao`, `CategoryDao`, `PlanDao`
(rules, scheduled items, terms, predictions, funds), `ScenarioDao`, `PresetDao`, `ImportDao`
(merchant rules, batches), `ReconciliationDao`, `SnapshotDao`. Reads the UI observes return
`Flow<List<…>>`; writes are `suspend`. Multi-row operations use `@Transaction` or
`database.withTransaction {}`. Seeding: on app start, if `categories` is empty insert
`DefaultCategories.all`.

Mapping entity ⇄ core model lives in `data/repo/Mappers.kt` (`fun AccountEntity.toModel()`,
`fun Account.toEntity()`, …).

### 6.2 Repositories (`data/repo/`)
Repositories expose core models, never entities. Every write calls
`widgetUpdater.requestUpdate()` afterwards.

- `AccountRepository`: `observeAccounts()`, `observeBalances()` (Map id → raw balance, via
  `Balances`), `createAccount(account, openingBalance, date)` (account + OPENING_BALANCE
  transaction in one DB transaction), `update`, `archive`, `delete` (confirm in UI; cascades),
  `reconcile(accountId, actualBalance)` (uses `Reconcile.plan`; inserts transaction +
  reconciliation atomically).
- `TransactionRepository`: `observeAll()`, `observeRecent(limit)`, `add(tx)`,
  `addTransfer(fromId, toId, amount, date, label)` (two legs, UUID group id), `update(tx)`
  (updating a transfer leg updates the other leg's amount/date), `delete(id)` (deletes both
  transfer legs), `logPreset(preset, defaultAccountId, today)`.
- `CategoryRepository`: `observeCategories()`, `ensureSeeded()`, `add`, `update`, `archive`,
  `untrackedCategoryId()`.
- `PlanRepository`: CRUD + observe for recurring rules, scheduled items, term periods,
  predictions (upsert by category+regime; delete = back to automatic), sinking funds.
- `ScenarioRepository`, `PresetRepository`, `ImportRepository` (merchant rules, batches,
  `importRows(...)` atomically incl. opening-balance adjustment, `undoBatch(id)`; the adjustment
  is one pair of batch-tagged OPENING_BALANCE entries, re-derived from the original opening
  balance and the imported rows that remain after every import and undo, so batches can be
  undone in any order),
  `SnapshotRepository`.
- `ReceiptRepository` (§10.3): `observeReceipts/Items/AllItems/Products/Aliases`,
  `saveReceipt(receipt, items, splitTransactions, replaceTransactionId)` (receipt + deleting the
  matched transaction + inserting the split transactions with `receiptId` + items, one DB
  transaction; the amounts are computed by the caller), `deleteReceipt(id, mergeBack)` (merge the
  splits back into one NORMAL transaction, or keep them and clear `receipt_id`; items cascade) and
  `learnProduct(store, rawNameKey, name, categoryId)` (upsert product by case-insensitive name and
  the alias; returns the product id).
- `BackupRepository`: `readAll(): BackupData` and `replaceAll(data)` (delete every table in
  FK-safe order then insert with original ids, one transaction; receipt items/aliases/receipts/
  products go before categories, and `transactions.receipt_id` is a plain id).

### 6.3 Settings (`data/settings/SettingsStore.kt`)
Preferences DataStore named `settings`. Exposes `budgetSettings: Flow<BudgetSettings>`,
`appSettings: Flow<AppSettings>` and suspend setters.
`AppSettings(hideWidgetAmounts, appLockEnabled, secureScreen, reconcileReminderEnabled,
reconcileReminderDay: DayOfWeek = SUNDAY, onboardingDone, lastSnapshotDate: LocalDate?,
lastExportDate: LocalDate?, theme: AppTheme = PAPER, darkMode: DarkMode = SYSTEM)`. Keys: `zero_point`,
`buffer_pence`, `default_account_id`, `history_start_epoch_day`, `simulations`, `horizon_days`,
`hide_widget_amounts`, `app_lock`, `secure_screen`, `reconcile_reminder`, `reconcile_reminder_dow`,
`onboarding_done`, `last_snapshot_epoch_day`, `last_export_epoch_day` (−1 = null for Long keys),
`app_theme`, `dark_mode` (enum names; a missing or unknown value falls back to the default).
`AppTheme` and `DarkMode` live in `data/settings/ThemeSettings.kt`; both are part of the
`AppSettingsCodec` backup map (`theme`, `darkMode`).

### 6.4 Domain services (`domain/`)
- `Clock`: `today(): LocalDate`, `nowMillis()`, `todayFlow: Flow<LocalDate>` (emits now and
  again just after each midnight).
- `ForecastService(repos, settings, clock, appScope)`:
  - `inputFlow: Flow<ForecastInput>` — nested `combine`s of all repository flows + settings +
    `todayFlow` (use an intermediate data class; `combine` is typed only up to 5 flows).
  - `forecast: StateFlow<ForecastResult?>` — `inputFlow.debounce(200).mapLatest { withContext(Dispatchers.Default) { ForecastEngine.run(it) } }.stateIn(appScope, SharingStarted.WhileSubscribed(5_000), null)`.
  - `suspend fun quick(): ForecastResult` — current input with `runMonteCarlo = false` (widget, worker).
  - `suspend fun full(): ForecastResult` and `suspend fun withScenario(s: Scenario): ForecastResult`.
- `PostingService`:
  - `postDue(today)`: AUTO recurring occurrences in `(postedThrough ?: start−1, today]` → insert
    transactions (NORMAL, or two TRANSFER legs) with `recurringRuleId`, then set `postedThrough = today`;
    AUTO scheduled items with `date <= today && !posted` → insert and mark posted. Idempotent.
  - `pendingConfirmations(today): List<PendingItem>` — CONFIRM rule occurrences after
    `postedThrough` up to today, and CONFIRM scheduled items due and not posted/skipped.
  - `confirm(item, amount, date)` → insert transaction; advance `postedThrough` to that occurrence
    / mark posted. `skip(item)` → advance / mark skipped. `snooze` = do nothing.
- `AppContainer` (created in `BudgetApp.onCreate`, reachable via
  `(context.applicationContext as BudgetApp).container`): database, repositories, settings,
  clock, forecastService, postingService, widgetUpdater, `appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
- ViewModels get dependencies through `viewModelFactory { initializer { … } }` reading the
  container from `APPLICATION_KEY`. No Hilt/Koin.

### 6.4a Changes after first use (2026-09-29)
- **One snapshot**: `ForecastService.state: StateFlow<ForecastState>` (`Loading` / `Ready(input,
  result, computedAtMillis)` / `Failed(input, message, lastGood)`), computed from one shared input
  flow. Every screen takes the input and the result from the same `Ready` snapshot, so balances
  and forecast numbers can never disagree (a user saw "Your money £0" beside correct account
  balances because they came from different subscriptions). Settings → About shows when the
  forecast last ran. `Clock.todayFlow` re-checks the date every 60 s (a single long `delay`
  stalls during deep sleep).
- **Account views**: `AppSettings.chartAccountView` (all accounts / one account / each account)
  drives the Forecast chart, Timeline and Insights balance history; `balanceView` (all accounts /
  one account) drives Home's "Your money" and sparkline. Safe-to-spend stays pool-wide.
- **Default account**: the first account created becomes the default (changeable in Settings);
  archiving/deleting it moves the default to the earliest remaining account. One resolver
  (`data/repo/DefaultAccount.kt`) is used everywhere.
- **Recurring rules** inserted with a start date on or before today get `postedThrough = today`:
  those occurrences are already in the balance the user entered from their bank app, so they
  are never posted retroactively; that includes one due today, the default start date (the
  editor says "Payments up to and including today are treated as already paid").
- **Student loan helper / onboarding**: instalment dates without a term start are left blank
  ("Pick a date") and required, instead of defaulting to today.

### 6.5 Navigation and screens
Single activity (`MainActivity : FragmentActivity`) + `QuickAddActivity`. Navigation Compose
with type-safe `@Serializable` routes. Bottom bar (5): **Home, Activity, Forecast, Plan, Insights**. Tapping a tab always opens that tab's own screen with only Home beneath it (sub-screens
left open on a tab are not restored); archiving or deleting an account closes its detail screen.
Settings from the Home top bar. Use Material 3; `@OptIn(ExperimentalMaterial3Api::class)`
where needed (TopAppBar, DatePicker, ModalBottomSheet).

**Home** — hero card: "Safe to spend" **£71 / week** (big), "£10 a day · until Student loan on
12 Jan"; red variant if `shortfall > 0` ("Even with no spending you'll be £40 short on 1 Oct").
Below: pool balance in display terms + days until next money in. Conditional cards (in this
order): pending confirmations ("Did your £3,100 Student loan arrive?" Confirm / Edit / Not yet /
Skip), "N spending estimates needed" → Predictions, risk (`probBelowFloor >= 0.10`: "18%
chance of going below £0 before 12 Jan"), up to 3 anomalies, reconcile nudge (no reconciliation
in 7 days), overdue sinking funds. Then accounts (name + display balance → Account detail),
a mini forecast chart (8 weeks → Forecast), recent 5 transactions (→ Activity). FAB "+"
opens the quick-add sheet.

**Activity** — all transactions, grouped by day with sticky headers ("Today", "Yesterday",
"Mon 28 Sep"). Row: category colour dot, label (or category name), account (small), amount
(income green). Transfers shown once ("Current → Savings"). Badges for Reconciliation /
Opening balance. Filter chips (account, category, in/out) and label search. Tap → edit sheet
(amount, in/out, category, account, date ≤ today, label, note, delete).

**Accounts** — list with display balances and a pool total; add/edit (name, type, current
balance for new accounts, overdraft limit, include in safe-to-spend, archive). Account detail:
display + raw balance, overdraft, **Update balance** (enter what the bank app shows → preview
"The app thought £X; difference £Y will be recorded as Untracked" → confirm), **Transfer**,
recent transactions, reconciliation history.

**Forecast** — horizon chips (4 wks / 12 wks / until next income / 26 wks); forecast chart
(P10–P90 band, expected line, optional dashed tier-1 line, floor line, buffer line, term
shading, today marker, tap for a tooltip); risk sentence; **Budget periods** cards (lumpy
income: dates, starting income, committed costs, available, per week); links to Predictions
(badge = needs-prediction count), What-if, Timeline, Accuracy.

**Predictions** — every LEARNED category with a Term row and a Holiday row: automatic estimate
(£/wk), source badge (Data / Blended / Fallback / Your estimate / None), weeks of data; editor:
mode (Automatic / My estimate (fades as data grows) / Always use my number / Adjust ±%) and
amount. Filter "Needs input". "I don't spend on this" sets a PRIOR of £0.

**What-if (Scenarios)** — list; editor with name and items (one-off, recurring, category
adjust %); comparison vs baseline: safe-to-spend, end balance P50, chance below £0; chart
overlay; "Apply to plan" converts items into real scheduled items / recurring rules /
ADJUST_PERCENT predictions.

**Timeline** (term-aware calendar) — one row group per week to the forecast end: header
"Week of 5 Oct · Term (Autumn)" or "· Holiday"; the committed flows that week; expected
learned spending; end-of-week balance P50 (P10–P90); money reserved in funds; weeks with
P10 below the floor highlighted.

**Accuracy** — until something can be evaluated: "Accuracy appears after a week of data".
Then: error at 7/14/28 days for the full model vs the simple baseline, coverage ("the real
balance was inside the shaded range 76% of the time; aim ≈ 80%"), per-category week-ahead
errors, and one verdict line.

**Plan** hub — sections with counts linking to: Money in (recurring + scheduled incomes),
Regular costs, One-off dates, Term dates, Sinking funds, Student loan helper, Spending
estimates (Predictions).
- Recurring edit: label, direction (out / in / transfer), amount, account (+ to-account),
  category, "every [n] [day/week/month/year]", start, optional end, posting mode (auto for
  direct debits / confirm for incomes — default by direction), major income toggle (incomes;
  default from category), active; preview of the next 3 dates.
- Scheduled edit: the same without frequency.
- Term dates: list + add/edit (name, start, end); warn on overlaps.
- Student loan helper: three instalments (dates default to the start of the next three
  terms; amounts, or "enter the year's total → split 33/33/34"), account → creates three
  CONFIRM, major-income scheduled items in the Student loan category.
- Sinking funds: list (reserved today / target, weekly amount, target date); edit (name,
  target, target date, start date default today, category); "Mark as done" (optionally open
  quick add pre-filled) and "Cancel".

**Insights** — period selector (12 weeks / 6 months / 12 months); weekly spending stacked by
category; this month vs average by category; term vs holiday average weekly spend per
category; balance history line; income vs spending per month. Tap a category → its weekly bars
with a median line.

**Settings** — Money display (zero point with explanation; buffer), default account,
Forecast (history start date, simulations 500/1000/2000, horizon 13/26/52 weeks), Categories,
Presets, Privacy (hide widget amounts, app lock, hide in recent apps), Reminders (weekly balance
check + day), Data (import statement, export backup, restore backup, export CSV, "last
exported N days ago"), About.

**Categories** — add (name, in/out, learned vs scheduled-only, colour), edit, archive. The
Untracked system category can only change colour.

**Presets** — add/edit (label, amount, in/out, category, account or default, show on widget),
reorder (up/down), delete.

**Statement import** wizard — (1) choose file + account; (2) column mapping, auto-guessed,
with a 5-row preview; (3) review rows (New / Already imported / Possible duplicate of a manual
entry — duplicates unticked by default); (4) categorise by merchant group, pre-filled from
merchant rules/keywords, "remember" ticked; (5) import → batch, transactions, merchant rules,
opening-balance adjustment; offer to move the history start date earlier; summary with
"Undo import".

**Onboarding** (first run, every step skippable) — welcome (data stays on the phone) →
accounts (suggest "Santander current", "Second current", "Savings") → zero point & buffer →
term dates → money in (student loan helper + other regular income) → regular costs → weekly
spending estimates per category for term and holiday → optional statement import → add the
widget (`AppWidgetManager.requestPinAppWidget`) → done (`onboardingDone = true`).

### 6.6 Quick add (`ui/quickadd/`)
`QuickAddScreen` composable, used both by `QuickAddActivity` (launched from the widget) and by
an in-app `ModalBottomSheet`.
- `QuickAddActivity`: translucent theme (`Theme.Budgeting.QuickAdd`: `windowIsTranslucent`,
  transparent background, `backgroundDimEnabled`), `excludeFromRecents="true"`,
  `taskAffinity=""`, `launchMode="singleTask"`. Compose draws a card at the bottom; tapping
  the dimmed area finishes. Does **not** require app unlock (shows no balances).
  Optional extras: `preset_id`, `flow`.
- Layout: Out/In toggle (default Out); large amount; category chips ranked by
  `CategoryRanking` (top 8 + "More"); account chip (default account, else first current
  account); preset chips (tap = fill fields); "Add label" (expands a text field); date chip
  ("Today", date picker, no future dates); custom keypad (1–9, ".", 0, ⌫) using `AmountEntry`;
  **Save** enabled when amount > 0 and a category is selected. Target: amount → category → Save.

### 6.7 Widget (`widget/`)
- `SafeToSpendWidget : GlanceAppWidget`, `SizeMode.Responsive` with small (≈110×48dp),
  wide (≈250×48dp) and tall (≈250×110dp) layouts; coloured by the chosen theme via
  `GlanceTheme(colors = ColorProviders(light, dark))` (`AppTheme.DYNAMIC` keeps the default `GlanceTheme`
  colours), and refreshed whenever the theme or dark mode changes.
- State loaded in `provideGlance` via the container: `forecastService.quick()`,
  `appSettings`, presets with `showOnWidget`. Small: "Safe to spend" + "£71/wk" + "+".
  Wide: + "12 days to Student loan". Tall: + up to 3 preset buttons.
  Hidden amounts → "••••". Onboarding not done → "Set up Budgeting" (opens app).
- "+" → `actionStartActivity<QuickAddActivity>()`; tapping the amount opens `MainActivity`;
  preset → `actionRunCallback<LogPresetAction>(actionParametersOf(PresetIdKey to id))`, which
  logs via `TransactionRepository.logPreset`, refreshes the widget and shows a Toast
  "Logged Meal deal £3.85" (post to the main thread).
- `SafeToSpendWidgetReceiver : GlanceAppWidgetReceiver`; `res/xml/safe_to_spend_widget_info.xml`
  (minWidth 110dp, minHeight 40dp, targetCellWidth 3, targetCellHeight 1, resizeMode
  horizontal|vertical, `updatePeriodMillis="0"`, widgetCategory home_screen, description).
- `WidgetUpdater.requestUpdate()`: debounced (500 ms) `SafeToSpendWidget().updateAll(context)`
  on `appScope`.

### 6.8 Background work (`work/`)
- `DailyWorker : CoroutineWorker`: `postingService.postDue(today)`; if `lastSnapshotDate != today`:
  full forecast → `Accuracy.snapshotsFor` → store → set `lastSnapshotDate`; widget refresh;
  reminder notification if enabled, today is the chosen day and the last reconciliation of any
  pool account is > 6 days old.
- `WorkScheduler.schedule(context)`: unique periodic work "daily", 24 h, initial delay to about
  03:00, `ExistingPeriodicWorkPolicy.KEEP`. Called from `BudgetApp.onCreate`, which also runs
  `ensureSeeded()` and `postDue(today)` once on `appScope`.
- Notification channel "reminders" created in `BudgetApp`. Ask for `POST_NOTIFICATIONS` (API 33+)
  when the user turns the reminder on. Tapping the notification opens Accounts.

### 6.9 Privacy (`ui/lock/`)
- App lock: when enabled and (cold start, or backgrounded > 30 s — tracked with
  `ProcessLifecycleOwner`), MainActivity shows a lock screen and a `BiometricPrompt` with
  `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`. Only allow enabling if
  `BiometricManager.canAuthenticate(...) == BIOMETRIC_SUCCESS`.
- Hide in recent apps: `FLAG_SECURE` on both activities when enabled.
- Hide widget amounts: §6.7.

### 6.10 Backups (`backup/`)
- Manifest: `android:allowBackup="true"`, `android:dataExtractionRules="@xml/data_extraction_rules"`,
  `android:fullBackupContent="@xml/backup_rules"` including the database and DataStore files.
- Export: `ActivityResultContracts.CreateDocument("application/json")` →
  `BackupRepository.readAll()` → `BackupMapper`/`BackupCodec` → write; record `lastExportDate`.
- Restore: `OpenDocument` → decode → show counts and date range → "Replace all data?" →
  `BackupRepository.replaceAll` → restore settings → refresh widget.
- CSV export of transactions: date, account, category, label, note, amount, kind.

### 6.11 Charts (`ui/components/charts/`)
Custom Compose `Canvas` charts (no chart library): `ForecastChart`, `WeeklyStackedBarChart`,
`BarChart`, `LineChart`. Category colours from `Category.colorArgb`. Before writing chart
code, invoke the `dataviz` skill if your tools allow it and follow its guidance (colours,
accessibility, light/dark). Charts must work in light and dark themes and at phone width.

### 6.12 Theme (`ui/theme/`)
Material 3, minimalist: near-neutral surfaces, one restrained accent, containers only a shade off
the background, thin light outlines, corner radii 8/12/16dp. The user picks an `AppTheme` (Paper
(default), Mono, Slate, Sand, Sage, Ink, Dynamic) and a `DarkMode` (System/Light/Dark) in Settings >
Appearance; `BudgetingTheme(theme, darkMode)` is fed from `AppSettings` by `MainActivity` and
`QuickAddActivity`, so changes apply live. Palettes are plain ARGB longs in `ui/theme/ThemePalettes.kt`
(`PaletteSpec`), each a complete light and dark `ColorScheme` including every surface-container role;
`ThemePalettesTest` enforces WCAG AA contrast (text ≥ 4.5:1 on every surface and card tone, accents
≥ 3:1). Dynamic uses the wallpaper scheme on API 31+ and Paper below. Money text: income and negative
colours come from `LocalMoneyColors` (per theme; spending uses the normal text colour). The theme
also sets the system bar icon appearance and drives `ChartColors` (derived from the active
`ColorScheme`; floor/buffer keep red/amber status colours) and the widget (§6.7).

---

## 7. Coding conventions

- Kotlin official style, 4-space indent, explicit imports (no wildcards).
- Match the existing code: KDoc on public types/functions where the "why" isn't obvious;
  no commented-out code; no TODOs without a matching item in §9.
- Money is always `Money`; never `Double` for amounts (ratios/probabilities may be `Double`).
- Dates are `LocalDate`; "today" always comes from `Clock` (app) or `ForecastInput.today` (core).
- UI text is British English ("colour", "categorise", "£"). Money formatting via `Money.format`.
- Compose: state hoisting; ViewModels expose `StateFlow<UiState>` built with `combine(...).stateIn(...)`;
  screens collect with `collectAsStateWithLifecycle()`. No business logic in composables.
- Every screen handles empty state (e.g. "No transactions yet — tap + to add one").
- Accessibility: content descriptions on icon buttons; touch targets ≥ 48dp.
- Never block the main thread; Room and the engine run off the main thread.

### Verifying your work
- **core**: `./gradlew -p core test` must pass before you finish (re-run on HTTP 429).
- **app**: cannot be compiled in this environment. Be conservative: use APIs you are sure exist
  in the listed versions, add `@OptIn` for experimental Material 3 APIs, double-check every
  import, and re-read each file once for type errors. The orchestrator pushes and runs CI, and
  will send you back the compiler errors if there are any.
- Commit your work in your worktree with a clear message ending with the trailer lines the
  orchestrator gives you. **Do not push.**
- Finish with a short report: files created/changed, any shared files touched (and why),
  anything you could not do, and anything the next package needs to know.

---

## 8. Work packages

Waves run in order; packages inside a wave run in parallel (separate git worktrees).
After each wave the orchestrator merges, pushes, gets CI green, and updates this section.

### Wave 1 — foundations
| WP | Scope | Depends on | Done when |
|---|---|---|---|
| **C1 Forecast engine** | `core/forecast/`: Regimes, Recurrence, Balances, CommittedFlows, History, Tier1, Tier2, Tier3, SinkingFunds, SafeToSpend, BudgetPeriods, ForecastEngine, ForecastConstants (§5.2–5.13) + tests | model (done) | `./gradlew -p core test` green; every §5.19 forecast scenario covered |
| **C2 Core support** | `core/analysis/`, `core/importer/`, `core/backup/`, `core/entry/`, `core/reconcile/` (§5.14–5.18) + tests | model (done) | tests green |
| **A1 App foundation** | Room DB (§6.1), repositories (§6.2), SettingsStore (§6.3), Clock/ForecastService/PostingService/AppContainer (§6.4), BudgetApp wiring, theme, navigation with bottom bar and **placeholder composables for every screen in §6.5** in the owning packages, full AndroidManifest (QuickAddActivity, widget receiver, backup rules, notification permission), stub `QuickAddActivity`, stub widget + receiver + `WidgetUpdater`, stub `DailyWorker` + `WorkScheduler`, shared UI components (MoneyText, SectionCard, EmptyState, ConfirmDialog, DateField, AccountPicker, CategoryPicker, AmountKeypad) | model (done); calls `Recurrence.occurrences`, `Balances`, `Reconcile.plan`, `ForecastEngine.run` by their §5 signatures | CI builds the APK; app launches to the bottom-nav shell |

### Wave 1 outcome (merged, CI green) — read before starting Wave 2
- **Status**: C1 (101 tests), C2 (116 tests) and A1 merged; the APK builds in CI.
- **Core API as built** — call only these public entry points:
  `ForecastEngine.run`, `Regimes`, `Recurrence`, `Balances` (forecast/);
  `Anomalies.detect`, `Accuracy.snapshotsFor/evaluate` (`AccuracyReport(horizons, categories, improvementPercent)`),
  `Trends.weeklyByCategory/termVsHoliday/balanceHistory/monthlyInOut` (analysis/);
  `Reconcile.plan` (reconcile/); `CsvParser`, `StatementImport.guessMapping/parseRows`, `MerchantKey`,
  `CategorySuggester`, `ImportDedupe`, `OpeningBalanceAdjuster` (importer/);
  `BackupMapper`, `BackupCodec.encode/decode` → `BackupDecodeResult.Success/Failure(errors)`, `core.backup.BackupData` (backup/);
  `AmountEntry(text).press(KeypadKey)` with `KeypadKey.Digit/Dot/Backspace/Clear`, `.money`, `.display`;
  `CategoryRanking.rank` (entry/). Engine internals (`HistoryBuilder`, `SafeToSpendCalculator`, `Tier1/2/3`…) are
  public only for tests — don't call them from the app. `FlowSource.TRANSACTION` was added (future-dated transactions).
- **Screens**: every route already has a placeholder composable in its owning file (see `ui/navigation/BudgetNavHost.kt`
  for the exact signatures). Keep the existing parameters; you may add parameters **with default values**. If you need a
  new navigation callback, add it and wire it in `BudgetNavHost.kt` with the smallest possible edit.
- **Add transaction**: Home/Activity call `onAddTransaction`. U1 wires this in `BudgetNavHost.kt` to start
  `QuickAddActivity` (the same dialog-style pop-up the widget uses) — no separate in-app bottom sheet.
- **Accounts**: there is no standalone accounts-list route; the accounts list lives on Home (U2), with
  AccountDetail/AccountEdit routes.
- **Mini forecast chart on Home**: U2 draws its own small sparkline in `ui/home/`; the full charts are U4's.
- **Known gaps assigned in Wave 2**: `ImportRepository.importRows` is not implemented yet (U5). There are two
  `BackupData` classes (`data.repo.BackupData` in the app, `core.backup.BackupData` in core) — U5 switches
  `BackupRepository` to the core one and deletes the app copy. `MainActivity` changes for the app lock and
  FLAG_SECURE belong to U5; FLAG_SECURE for `QuickAddActivity` belongs to U1.
- **Quirks**: `Scenario` has no createdAt, so `Scenario.toEntity(createdAt)` takes it explicitly. Transfers posted
  from recurring rules don't carry `recurringRuleId` (harmless for the forecast).
- **Shared files** (`BudgetNavHost.kt`, `AppContainer.kt`, `AndroidManifest.xml`, `strings.xml`, repositories): minimal
  additive edits only, listed in your report. The orchestrator resolves merge conflicts.

### Wave 2 — features (after Wave 1 is merged and green)
| WP | Scope |
|---|---|
| **U1 Capture** | QuickAddScreen + QuickAddActivity + in-app sheet (§6.6), the Glance widget + LogPresetAction + WidgetUpdater (§6.7), Presets screen |
| **U2 Money** | Home (§6.5 incl. pending confirmations, cards), Activity + edit sheet, Accounts list/detail/edit, transfer and Update balance flows |
| **U3 Plan** | Plan hub, recurring/scheduled lists + editors, term dates, student loan helper, sinking funds, Predictions screen |
| **U4 Forecast & insights** | Charts (§6.11), Forecast screen + budget periods, Timeline, What-if scenarios, Accuracy screen, Insights screen |
| **U5 Settings & data** | Settings, Categories, privacy/app lock (§6.9), reminders + DailyWorker + WorkScheduler (§6.8), backup export/restore + CSV export (§6.10), statement import wizard |

### Wave 3 — finish
| WP | Scope |
|---|---|
| **U6 Onboarding** | First-run flow (§6.5) reusing the editors from U2/U3 |
| **Integration** | Orchestrator: CI fixes, end-to-end review against this plan, APK to the user |

---

### Status (2026-09-28)
All work packages (C1, C2, A1, U1–U6) are merged and CI builds the APK. Orchestrator review fixes
after merging: duplicate posting race in `PostingService`; tab back stack; "Sept" vs "Sep" month
abbreviations (use `Locale.ENGLISH` for short month names); "Undo import" not restoring the moved
opening balance (the move is now two batch-tagged OPENING_BALANCE entries); backup restore inserting
transactions before their import batches (FK failure); app lock rebuilding navigation (lock screen is
now an overlay); daily worker snapshots before any account exists and its widget refresh.

### Testing (2026-09-29)
- **Core** (`./gradlew -p core test`): 287 JVM tests, including stress/metamorphic suites and a
  20,000-budget soak (`core/src/test/.../stress/`); forecast invariants include "expected spending
  + expected income = expected learned flow".
- **Device tests** (`app/src/androidTest`, run by CI's `device-tests` job on an API 34 emulator,
  report and screenshots on `refs/ci/device-report`): data layer against the real Room database
  (`data/`), end-to-end UI with screenshots (`ui/`, helpers in `testing/UiTestSupport.kt`), and the
  widget (`widget/`: Glance layouts at the three sizes, one-tap preset logging).
- UI test conventions: tap through `UiTest.tap` (waits for the target to stop moving: screens
  resize for the keyboard), pick dates with `chooseInDatePicker` (Material 3 day cells carry the
  full date as text), reach cards far down a list with `swipeListUp`, and never address a field by
  index in a lazy list (only the rows on screen exist).
- Bugs found and fixed by these tests: out-of-order import undo corrupting the balance history;
  restore not bringing back the account views; what-if save navigating off the main thread (crash);
  a deleted/archived account's page staying open; tabs reopening a stale sub-screen; account-page
  transfers without a direction; Timeline "expected spending" being net of income; "Quiet week"
  firing straight after onboarding; the other-income form defaulting to Student loan; Save hidden
  by the keyboard (quick add, forms); a doubled bottom inset; overlapping chart month labels.

---

## 9. Open items and future work

- **Santander statement format**: need a real export (amounts/names can be changed) to add a
  dedicated parser and tune `MerchantKey`. The generic CSV importer works meanwhile.
- **Room schema files**: committed under `app/schemas/` (version 1 taken from CI, which publishes
  every build's schemas to `refs/ci/room-schemas`). Commit each new version's JSON the same way.
- **Device testing**: nothing has run on a real device or emulator yet (no emulator in this
  environment); first-install feedback from the user is the next verification step.
- **Faster development**: if `dl.google.com` is allowed in the cloud environment's network
  settings, the app can be compiled locally instead of only in CI.
- Later ideas (not v1): notification capture of card payments, subscription detector,
  shared expenses, laptop/web view reusing `core`.

---

## 10. Receipts and item prices (planned 2026-10-12)

Goal: record what was bought, item by item, at Tesco, Sainsbury's and Morrisons, from (a) a photo
of a paper receipt, corrected on a review screen, and (b) imported online receipts / loyalty-card
data (e.g. a Nectar or Clubcard data export, an online-order receipt email or PDF). Uses: split one
shop across categories, item price history, "what do I spend on X". The forecast is unchanged: it
still learns from category totals, which the split transactions keep correct.

### 10.1 Pipeline
Every source ends in the same `ReceiptDraft`, so the review screen and saving are shared:

```
photo / gallery image ──► ML Kit text recognition (on device) ─┐
shared PDF ──► render pages (PdfRenderer) ──► ML Kit ──────────┤
shared text / email / HTML ──► plain text ─────────────────────┼──► lines ──► store parser ──► ReceiptDraft
loyalty data export (JSON/CSV) ──► export parser ──────────────┘                                    │
                                                         review screen (fix, categorise) ◄──────────┘
                                                                     │
                                         save: Receipt + ReceiptItems + split transactions
```

- OCR: ML Kit Text Recognition with the **bundled** Latin model (works offline, nothing leaves
  the phone). Optional later: an opt-in "read with AI" setting using the user's own API key.
- Input: a camera/gallery button in quick add and on Activity, plus an Android share target
  (`ACTION_SEND` for image/*, application/pdf, text/*) so a receipt email or PDF can be shared
  into the app.

### 10.2 Core (pure Kotlin, `core/.../receipts/`, fully unit-tested)
- `ReceiptLine(text, top, left, right)` (OCR gives positions; text sources give one per line) →
  `LineGrouping.rows(lines)`: merge fragments on the same baseline into rows (price column right).
- `ReceiptDraft(store, date?, items: List<DraftItem>, discounts, total?, paymentTotal?, source)`,
  `DraftItem(rawName, quantity, unitPrice?, weightKg?, price, discount, confidence)`.
- Store parsers behind one interface `ReceiptParser { fun detect(rows): Boolean; fun parse(rows): ReceiptDraft }`:
  `TescoParser`, `SainsburysParser`, `MorrisonsParser`, `GenericParser` (fallback). Each handles:
  item lines "NAME  £1.25"; quantity lines ("2 @ £1.10"); weighed items ("0.452 kg @ £1.20/kg");
  discounts on following lines (Clubcard Price, Nectar Price, multibuy "3 for £5", reduced to
  clear, coupons) attached to the item above or spread over the multibuy's items; the total; the
  date/time. Pure functions over text, so they are tested against real receipt texts.
- `ReceiptChecks.check(draft)`: Σ items − Σ discounts vs total → OK / mismatch amount (the review
  screen highlights a mismatch so OCR mistakes are found).
- `ProductNames.normalise(rawName)` (case, spacing, store prefixes like "TS "/"SAINSBURYS ") and a
  learned **alias** table: the first time the user renames "TS SEMI SKMD MLK 2PT" to
  "Semi-skimmed milk 2 pints" and picks Groceries, later receipts fill both in automatically.
- `ReceiptMatching.findTransaction(draft, transactions)`: an existing NORMAL transaction with the
  receipt total (±1p) within ±2 days, else none (a new one is created).
- `ItemPrices`: per product, price history (unit price per date and store), latest vs previous,
  monthly spend; feeds an Insights card.

### 10.3 Data (`app`, Room version 2 with a non-destructive `@AutoMigration(1, 2)`; done)
- `receipts(id, store, date, total, source, transaction_id?, created_at)`
- `receipt_items(id, receipt_id, product_id?, raw_name, quantity, unit_price, price, discount, category_id?)`
- `products(id, name, category_id?)` and `product_aliases(id, store, raw_name_key, product_id)`.
- **Splitting**: when a receipt's items span several categories, the matched transaction is
  replaced by one NORMAL transaction per category (same date/account, label "Tesco · Groceries",
  sharing a `receipt_id` column added to `transactions`); amounts add up to the receipt total, so
  balances don't change. Deleting the receipt merges them back into one.
- Backup format version 2 adds the four tables; version 1 files still restore.
- Before this migration: commit the version-1 schema JSON (have CI publish `app/schemas` the way
  it publishes the device report) and add a `MigrationTestHelper` device test 1 → 2.

### 10.4 Screens
- **Review receipt**: store, date, total (with the check result), a list of items (name, qty,
  price, discount, category) — each editable, deletable, addable; "Apply to all like this" for
  categories; Save. Shows the photo above for reference.
- **Receipts** list (from Activity / an account): past receipts, open to view or edit.
- **Insights → Items**: most bought items, price changes, search an item for its history.

**Built (step 5): Receipts list and Insights → Items.**
- `ui/receipts/list/`: `ReceiptsScreen` (`Route.Receipts`, opened from Settings → Data → Receipts; newest first,
  store display name, date, total, item count; empty state explains Scan receipt) and `ReceiptDetailScreen`
  (`Route.ReceiptDetail(receiptId)`: header with account, items with product name else printed line,
  quantity/weight, price, discount and category, the split transactions, and Delete with the choice "Merge
  back into one transaction" (default) / "Keep the split transactions" → `deleteReceipt(id, mergeBack)`).
  `ReceiptMapping` is the pure part: store key → display name, item naming, quantity text and the join of
  items with their receipts into `PurchasedItem`s (items without a product are left out).
- A split transaction's Activity edit sheet has a "View receipt" button (next to Save/Delete) that opens its receipt.
- `ui/insights/`: `ItemsCard` is the last Insights card (hidden while there are no receipts) and follows the
  period chips: top 5 products by spend with quantity, top 5 unit-price changes (latest vs previous purchase
  in the period, ranked by size, ↑ red / ↓ green with %), a search field and an "All items" list.
  `ItemDetailScreen` (`Route.ItemDetail(productId)`) has its own period chips, the spend and quantity in the
  period, and every unit price (date, store, price, newest first, change since the previous). The pure part is
  `ItemInsights` (on `core/receipts/ItemPrices`); the data comes from `ReceiptRepository.observeSnapshot()`.
- Tests: `ReceiptMappingTest`, `ItemInsightsTest` (JVM), `ui/ReceiptsTest` (device; seeds through
  `seedReceipt` in `testing/UiTestSupport.kt`).

### 10.5 Imports — need real samples first
Formats are unknown until the user provides files (personal details can be removed):
- Tesco: Clubcard / account "download my data" export; Tesco online order receipt (email/PDF).
- Sainsbury's / Nectar: whatever the data download or app receipt gives; online order receipt.
- Morrisons: online order receipt (email/PDF); More Card data if it has items.
- Photos of one paper receipt from each store, including a multibuy, a Clubcard/Nectar price and a
  weighed item.

### 10.6 Order of work
1. Core: pipeline types, row grouping, generic parser, checks, product names/aliases, matching,
   price history — with tests (no samples needed).
2. App: Room 2 + migration (+ schema JSON in CI), repositories, backup v2.
3. App: photo/gallery/share input, ML Kit OCR, review screen, saving with category split.
4. Store parsers and data-export importers, tuned on the user's samples.
5. Receipts list and Insights → Items (done; see §10.4).

### 10.7 Built: capture → review → save (step 3 of §10.6)
Code is in `app/.../ui/receipts/` (the list/detail screens live in `ui/receipts/list/`).
- **OCR**: `ReceiptOcr` runs ML Kit's bundled Latin model (`com.google.mlkit:text-recognition:16.0.1`,
  offline) on an image `Uri` (`InputImage.fromFilePath`, which applies EXIF rotation) or a `Bitmap`;
  each ML Kit text line becomes a `ReceiptLine` with its box. PDFs are rendered page by page with
  `PdfRenderer` at 2× on white and stacked (each page's `top` offset by the pages before it); shared
  text goes straight to `ReceiptParsers.parseText`. `ReceiptReader` picks the path per
  `ReceiptInputKind` (IMAGE / PDF / TEXT) and throws `NoTextException` when nothing was read.
- **Inputs**: every input is first copied into `cache/receipts` (`ReceiptFiles`), so the review
  route is just `Route.ReceiptReview(path, kind)`. *Scan receipt* is a camera icon on Activity's top
  bar and a chip in quick add; both open `Route.ReceiptScan` (a screen with "Take a photo", the
  `TakePicture` contract into a `FileProvider` uri, no camera permission, and "Choose from gallery",
  `PickVisualMedia` images only), which is replaced by the review. Quick add (a separate activity)
  starts `MainActivity` with `ReceiptFiles.ACTION_SCAN_RECEIPT`. `MainActivity` is a share target for
  `ACTION_SEND` image/*, application/pdf and text/plain; the stream or text is copied to the cache,
  then the review route is opened once the NavHost exists (behind the app lock overlay as usual).
- **Review** (`ReceiptReviewScreen` / `ReceiptReviewViewModel`): thumbnail (tap for full size,
  pinch to zoom), store, date, total, account, the `ReceiptChecks` result ("Items add up to the total ✓"
  / "Items are £0.23 more than the total — check the highlighted lines"; low-confidence lines are
  highlighted while the items do not add up), the match ("Replaces Tesco -£23.45 on Mon 12 Oct" with a
  switch, default on, else "A new transaction will be added"), and the items with name, qty, price,
  discount, category, delete and add. Names and categories come from a learned alias
  (`Aliases.suggest`), else the store's most used category from earlier receipts, else Groceries; a
  guessed category is marked "Category guessed" until the user picks one. "Set category for all
  unset" applies to guesses only. Save is disabled while there are no items, an amount is invalid, a
  name is empty or the total is missing; a mismatch only warns. The raw fields are in a plain
  `MutableStateFlow` (`edit`) so typing is never delayed; everything derived is in `derived`
  (`ReceiptReviewRules`, pure).
- **Save**: `ReceiptSplit.byCategory` → one NORMAL transaction per category ("Tesco" for one part,
  "Tesco · Groceries" per part; store names Tesco / Sainsbury's / Morrisons / Shop) → `saveReceipt`,
  replacing the matched transaction when the switch is on. `learnProduct` runs for every item whose
  name was edited, whose category the user chose, or that came from an alias; a merely guessed
  category is not stored on the product. The cache file is deleted after saving.
- **Deviations / decisions**: when a match exists and the switch is on, the new transactions go in the
  *match's* account (balances stay exactly as they were); picking an account by hand restricts the
  match to that account. Transactions that already belong to a receipt are never matched again.
  A future receipt date is replaced by today with a warning. The error state offers *Try again* and
  *Enter it by hand*. Item fields carry test tags `receipt-item-<key>-<field>` (keys count from 1 in
  reading order; `ReceiptReviewTags`) so UI tests never address fields by position.
- **Tests** (device): `ReceiptOcrTest` (synthetic Tesco receipt as a bitmap, an image file and a PDF,
  ≥ 80% of prices read), `ReceiptReviewViewModelTest` (split, replace, balance, aliases learned and
  applied to a second receipt, edits and validation), `ui/ReceiptReviewTest` (share a text receipt →
  fix → categorise → save → Activity; replace switch; typing a receipt in; scan entry points). The
  camera and gallery pickers themselves are system UI and are not driven by tests.
