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
10. Receipts and item prices
11. Food: recipes, cooking and shopping (the Recipes section)

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
| Food (2026-10-17) | A recipe book, shopping list and cooking view live in this app as their own **Food** section (§11), costed from receipt prices. Offline first: the only network use is an optional, off-by-default barcode lookup that sends just the barcode number to Open Food Facts. |

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
  FASTER PAYMENT TO", "FASTER PAYMENTS RECEIPT FROM", "PURCHASE", and Square's leading "SQ *"; drop
  ",? ON dd-mm-yyyy"-style dates, "(VIA GOOGLE PAY)"-style wallet groups, "REF…" tails, card masks
  (a run of X/* on its own, not the X and * inside "NYX*PHOTOBOOTH"), 4+ digit numbers, amounts,
  "GBP"; collapse spaces; drop a trailing " -"; max 40 chars. Repeated until nothing changes.
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
  ALREADY_IMPORTED if the key exists; POSSIBLE_DUPLICATE if an existing NORMAL **or TRANSFER**
  transaction on the same account with no import key has the same amount within ±2 days (each
  existing transaction matches at most one row); else NEW. The TRANSFER case is how the leg an
  import left on this account (from the other account's statement), or a transfer entered by hand,
  is recognised when this account's own statement is imported later.
- `OpeningBalanceAdjuster.adjust(opening: Transaction, imported: List<ParsedRow>): Transaction?` —
  imported rows dated **before** the opening-balance date would double count (the opening
  balance already includes them). Return the opening transaction with
  `amount = old − Σ(those rows)` and `date = earliest imported date − 1`, or null if none.
- **Santander PDF statements** (downloaded from Santander online banking). The PDF's text layer is
  read exactly, with positions (no OCR); the app extracts it with `com.tom-roush:pdfbox-android`
  2.0.27.0 (Apache-2.0, from Maven Central; `PDFBoxResourceLoader.init` runs once before first use) in
  `ui/importer/PdfStatementReader`, and everything after that is pure `core` code in `importer/`:
  - `PdfTextChunk(text, x, y, endX, height, page)` (`PdfText.kt`): one piece of text in drawing order, at
    any granularity (glyph, word or cell); y grows down the page; height is the font size.
    `PdfStatementReader` returns one chunk per glyph (from a `PDFTextStripper` subclass with
    `sortByPosition = false` whose `processTextPosition` collects the glyphs instead of building lines;
    text drawn twice in the same place is dropped, as PDFBox does) or throws `PdfReadException`.
  - `PdfCells.merge(chunks)` joins consecutive chunks into cells, so glyph-, word- and cell-level input
    give identical cells. A new cell starts when the page changes, the baseline moves by more than
    0.3 × height, the x moves back left by more than 0.5 × height, or a gap exceeds 0.9 × height (a column).
    Within a cell one space is inserted where the gap exceeds 0.15 × height and neither side already has
    whitespace there. Cells are trimmed (their edges move in by the trimmed width) and blank ones dropped.
  - `SantanderStatement.looksLikeSantander(cells)`: a cell contains "santander" and a cell starts with
    "Your transactions". `parse(cells)` returns `Success(ParsedStatement)` or `Unreadable(reason)` (a
    sentence for the user: no transactions section / no rows / direction undecidable).
    `ParsedStatement(periodStart, periodEnd, accountHolder, openingBalance, closingBalance, totalIn,
    totalOut, lines, problems)`; `StatementLine(row, rawDescription, postedDate, likelyTransfer)` where
    `row` is a normal `ParsedRow` (signed amount, cleaned description, `balance` = balance after the row,
    `rowIndex` = index in `lines`) so the rest of the wizard is shared with CSV.
  - **Period and dates**: "Your transactions 10th Sep 2026 to 10th Oct 2026" (suffixes st/nd/rd/th). Rows
    carry no year: the period's year is used, and across a December to January period the start year for
    months at or after the start month, else the end year. A card row's `date` is its ", ON dd-mm-yyyy"
    card date (when the purchase happened; ignored if after the posted date), else the posted date.
  - **Table rules**: the table starts after the "Your transactions" heading *and* its header row (cells
    "Money in" and "Money out"); the right edges of those two cells and the "Date" header's x are
    remembered. A row starts at a date cell (`^\d{1,2}(st|nd|rd|th) [A-Z][a-z]{2}$`) whose x is within
    2 × height of the "Date" header's; it takes the following cells on the same page within
    2.5 × height of its baseline, up to the next date cell (wrapped descriptions put the date and amounts on
    the middle line). Cells that are entirely money (optional "£", commas, "-" or "−" prefix, trailing "-",
    " DR" or " OD" for an overdrawn balance) are amounts; the rest is the description (joined with single
    spaces, " ," → ","). A normal row has two amounts (amount, balance). "Opening Balance", "Balance
    brought forward…" and "Balance carried forward…" rows have one: they set or check the running balance
    and are not transactions; "Balance carried forward to next statement" sets the closing balance and ends
    the table. When cells move to a later page everything is ignored until that page's header row appears
    again. The "Average balance for the month" table on page 2 comes before the heading and is ignored.
  - **Direction and checks**: with a known previous balance, `delta = balance − previous`; if `|delta|`
    equals the amount, the sign is the delta's. Otherwise (or with no previous balance) the amount cell's
    right edge decides — nearer the "Money in" header's right edge is money in, nearer "Money out" is out
    (a tie makes the statement unreadable) — and, when the chain disagrees, the problem "Row 7 (7 Oct,
    UNION SHOP): the balance doesn't follow from the amount" is recorded (the printed balance then becomes
    the running balance again). The page-1 summary supplies the opening balance ("Balance brought forward
    from previous Statement"), "Total money in:", "Total money out:" (stored positive) and the closing balance
    ("Your balance at close of business …"), each the money cell to the right of its label on the same line;
    the account holder is the cell after "Account name" (or "Account name: X" on page 2). `problems` also
    holds: opening + Σ amounts ≠ closing, Σ money in ≠ total in, Σ money out ≠ total out.
  - **`cleanDescription(raw)`**: "<MERCHANT> (VIA GOOGLE PAY), ON 05-10-2026" (also APPLE/SAMSUNG PAY, and the
    classic "CARD PAYMENT TO <MERCHANT>,7.75 GBP, RATE 1.00/GBP, ON 08-10-2026") → "<MERCHANT>", with a
    trailing " -" removed; "BILL PAYMENT VIA FASTER PAYMENT TO <X> REFERENCE <r> , MANDATE NO 0002" →
    "<X>, ref <r>" (the reference keeps its case; standing orders and direct debits likewise);
    "FASTER PAYMENTS RECEIPT REF.<ref> FROM <payer>" → "From <payer>" (", ref <ref>" only when the reference
    is not all digits: those are a sort code and account number); "TRANSFER TO/FROM …" and "REGULAR TRANSFER
    PAYMENT TO ACCOUNT …" are kept; ", MANDATE NO n" is always removed.
  - **`likelyTransfer`**: descriptions starting "TRANSFER TO/FROM" or "REGULAR TRANSFER PAYMENT TO/FROM
    ACCOUNT", or whose counterparty (after "… PAYMENT TO " up to " REFERENCE"/" REF"/","; or after
    "FASTER PAYMENTS RECEIPT … FROM ") is the account holder: titles (MR, MRS, MS, MISS, MX, DR, PROF) are
    dropped, case is ignored, every counterparty token must equal a holder token or be one letter that
    starts one, and at least one token must be a full match ("A Tester", "ALEX TESTER" match "MISS ALEX
    TESTER"; "ALEX SMITH" does not).
  - Tests: `PdfCellsTest`, `SantanderStatementTest` (the invented `SantanderStatementFixture` at all three
    granularities, a multi-page table, December to January, tampered balances/amounts, every cleaning and
    transfer rule); on a device `data/SantanderPdfTest` draws an invented statement into a real PDF with
    `PdfDocument` and reads it back through PDFBox. **Privacy**: no real statement is ever used as a
    fixture; all test data is invented.
- **Import checks** (`ImportChecks.kt`, pure; "when I import a statement it checks anything inconsistent and asks
  me about it"). Before anything is saved the wizard asks about each inconsistency between the statement and the
  ledger, CSV and PDF alike. `ImportChecks.questions(input, answers)` takes an `ImportCheckInput` (the account, the
  parsed rows with their import keys, `RowStatus` and review ticks as parallel lists, every existing transaction
  of every account, the accounts, categories and merchant rules, the statement's coverage and closing balance and
  the PDF's `problems`) and returns, in this order, questions with a stable `id` (so an answer stays with its
  question when the list is worked out again). A "row index" is a position in `rows`. Rows whose status is
  `ALREADY_IMPORTED` (or whose key is already in the ledger) never produce a question.
  1. `PossibleDuplicate(rowIndex, existing)`: an entry on the same account with no import key, kind NORMAL or
     TRANSFER, the same amount, dated within ±3 days of the row. Answers: `SameUseBank` (the entry's date becomes
     the bank's — and its other leg's, for a transfer — and its import key the row's; the row is not imported),
     `SameKeepMine` (only the import key is set; the row is not imported), `Different` (the row is imported too).
  2. `AmountMismatch(rowIndex, existing)`: of what is left, a NORMAL entry with no import key within ±2 days, the
     same direction, a different amount, that looks like the same purchase: the `MerchantKey` of its label shares a
     word of 4+ letters (not a generic one such as STORES or SHOP) with the row's, or its category is the one
     `CategorySuggester` suggests for the row. Answers: `FixMine` (amount and date become the bank's and the
     import key is set; the row is not imported), `Different`.
  3. `NotOnStatement(existing, transferPartner, mayNotHaveReachedBank, moveTargets)`: an unmatched NORMAL entry or
     TRANSFER leg with no import key dated inside the coverage (both ends inclusive). Answers: `Keep` (with the
     hint "it may not have reached the bank yet" in the coverage's last three days), `Delete` (a transfer deletes
     both legs), `MoveTo(accountId)` for another non-archived account (not the account of its other leg).
  4. `StatementProblem(text, rowIndex?)`: each PDF parse problem; the row comes from the "Row N (" the reader
     writes. Answers: `SkipRow` (only when the row would be imported: it is left out) or `Ok`.
  5. `BeforeOpening(count, openingDate)`: rows to import dated before the account's own OPENING_BALANCE date, which
     will be adjusted so they aren't counted twice. Information only: pre-answered `Ok`.
  6. `BalanceGap(date, appBalance, statementBalance)`: the account's balance on the coverage end, from a preview
     that applies every other answer (rows to import, entries updated, deleted or moved, and the opening-balance
     move `rebuildOpeningBalanceMove` makes), against the statement's closing balance (a PDF's; a CSV's is the
     balance column of its last row, `closingBalanceOf`). Answers: `RecordDifference` or `Leave`. A question
     without an answer is previewed as `defaultAnswer` (SameKeepMine, FixMine, Keep), so the gap is always what
     the likeliest answers give and it follows every answer the user changes.

  Pairing is one-to-one in both directions and independent of the review ticks: edges sorted by date distance, then
  shared `MerchantKey` words (then amount distance, row order, entry date and id), taken greedily; the duplicates
  are paired first, the mismatches from what is left. A row in a pair is imported unless its answer says it is the
  same purchase, whatever was ticked (the review ticks it and cannot untick it); an unticked row still takes its
  partner so the partner is not reported as missing. `ImportChecks.plan(input, questions, answers)` turns answers
  into an `ImportPlan` (rows to import, `ImportChanges` — `ExistingUpdate`s, deletions, `ExistingMove`s and an
  `ImportReconcile(date, statementBalance)` — and counts for the summary); an unanswered question changes nothing.
  `appBalance(input, plan, asOf)` is the preview. Tests: `ImportChecksTest` (every question type and answer, the
  pairing rules, the coverage window, the preview balance including the opening-balance move, the invented
  statement with hand-entered entries around it, ALREADY_IMPORTED rows asking nothing).

### 5.17 Backup (`backup/`)
- `@Serializable data class BackupV1(format = "budgeting-backup", version = 1, exportedAt: String,
  accounts, categories, transactions, recurringRules, scheduledItems, termPeriods, predictions,
  sinkingFunds, scenarios (with items), presets, merchantRules, reconciliations, importBatches,
  balanceSnapshots, categoryWeekSnapshots, settings: SettingsDto, appSettings: Map<String,String>)`.
- **Version 3** (current) adds the eight food lists of §11.3: `ingredients`, `ingredientPortions`,
  `ingredientAliases`, `recipes`, `recipeLines` and `recipeSteps` (both with a `position`), `shoppingEntries` and
  `shoppingMarks`. `BackupData` holds them as models (`Ingredient` with its portions, `Recipe` with its lines and
  steps); `decode` also checks line/portion/alias/entry/mark references, `ingredient.productId`, and the unique
  keys (ids, barcode, alias name, portion name, one mark per ingredient). Versions 1 and 2 still restore, with no
  food data; cooking ticks and the Recipes settings are not in backups, and a restore forgets the ticks.
- **Version 2** adds `receipts`, `receiptItems`, `products`, `productAliases` and
  `TransactionDto.receiptId`; the class keeps the name `BackupV1` and holds versions 1 to 3. All new
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
  transfer legs), `logPreset(preset, defaultAccountId, today)`. The edit sheet's operations (§6.5,
  "Editing past transactions"), each one DB transaction: `convertToTransfer(edited, otherAccountId)`,
  `updateTransfer(legId, fromAccountId, toAccountId, amount, date, label)`, `convertToNormal(keepId,
  categoryId)`, `countRecategorisable` / `recategorise(merchantKey, categoryId, exceptId, rememberRule)` /
  `rememberMerchantRule(merchantKey, categoryId)`. The rows each edit inserts, updates and deletes are
  decided by the pure `data/repo/TransferEdits`.
- `CategoryRepository`: `observeCategories()`, `ensureSeeded()`, `add`, `update`, `archive`,
  `untrackedCategoryId()`.
- `PlanRepository`: CRUD + observe for recurring rules, scheduled items, term periods,
  predictions (upsert by category+regime; delete = back to automatic), sinking funds.
- `ScenarioRepository`, `PresetRepository`, `ImportRepository` (merchant rules, batches,
  `importRows(...)` atomically incl. opening-balance adjustment, `importWithChanges(...)` (the same plus the
  wizard's check answers, §6.5, in one DB transaction: rows inserted, then updates / deletions / moves of existing
  transactions, then the opening-balance move, then the reconciliation), `undoBatch(id)`; the adjustment
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
- **Account views** (`data/settings/AccountView.kt`): `AppSettings.chartAccountView` (**All
  accounts** / **Safe-to-spend accounts** / one account / each account) drives the Forecast chart,
  Timeline and Insights balance history; `balanceView` (the same, without "each") drives Home's
  "Your money" and sparkline. *All* is every non-archived account; *Pool* is the §5.4 pool (included
  in safe-to-spend, not archived). Safe-to-spend itself always uses the pool (no engine change).
  Encoding: `"all"`, `"pool"`, `"each"`, `"account:<id>"`; the legacy `"total"` (written before
  the two were told apart, and always labelled "All accounts" in the UI) is read as *All*. A
  missing/archived account, or "each" for a balance, resolves to *All*. The selectors offer
  "Safe-to-spend accounts" only when some live account is left out of the pool (otherwise it would
  equal "All accounts", and a stored *Pool* is shown as *All*; see `poolDiffersFromAll` /
  `collapsePool`).
  - Home: *All* sums the raw balances and the overdraft limits of all non-archived accounts
    (floor via the same zero-point rule); its sparkline is the pool's expected balance plus the
    expected balances of the non-pool account forecasts.
  - Charts: *Pool* is the classic pool chart (band, optional tier-1 line, buffer). *All* adds the
    non-pool accounts' expected balances (deterministic, so exact for every percentile) to the
    pool's P10/P50/P90 and converts with the pool overdraft plus theirs; no tier-1 line and no
    buffer (both are pool-only). With no account outside the pool, *All* is the pool data.
    Timeline *All* shifts its end-of-week balances the same way, lists every committed flow and
    hides the pool-only "Reserved for sinking funds" row. The Forecast risk sentence is prefixed
    "Across the accounts in safe-to-spend" whenever the chart is not the pool's own.
  - Insights balance history: *All* sums every non-archived account ("All accounts"); *Pool* is the
    previous total line ("Safe-to-spend accounts").
  - **Choose accounts**: the Home hero has a "Choose accounts" button (shown whenever an account
    exists, including under the "None of your accounts is included in safe-to-spend" message). It
    opens a dialog listing every non-archived account with a checkbox for
    `includeInSafeToSpend`; each toggle is saved immediately through `AccountRepository.update`
    (`HomeViewModel.setIncludedInSafeToSpend`), so the account edit screen's switch is no longer
    the only way to change it.
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
with type-safe `@Serializable` routes. The app has two sections, Budgeting and Recipes, switched from the
top left of every main screen (§11.8). Budgeting's bottom bar (5): **Home, Activity, Forecast, Plan, Insights**. Tapping a tab always opens that tab's own screen with only Home beneath it (sub-screens
left open on a tab are not restored); archiving or deleting an account closes its detail screen.
Settings from the Home top bar. Use Material 3; `@OptIn(ExperimentalMaterial3Api::class)`
where needed (TopAppBar, DatePicker, ModalBottomSheet).

**Home** — hero card: "Safe to spend" **£71 / week** (big), "£10 a day · until Student loan on
12 Jan"; red variant if `shortfall > 0` ("Even with no spending you'll be £40 short on 1 Oct").
Below: "Your money" in display terms (all accounts by default; a chip picks the safe-to-spend
accounts or one account), then days until next money in. Conditional cards (in this
order): pending confirmations ("Did your £3,100 Student loan arrive?" Confirm / Edit / Not yet /
Skip), "N spending estimates needed" → Predictions, risk (`probBelowFloor >= 0.10`: "18%
chance of going below £0 before 12 Jan"), up to 3 anomalies, reconcile nudge (no reconciliation
in 7 days), overdue sinking funds. Then accounts (name + display balance → Account detail),
a mini forecast chart (8 weeks → Forecast), recent 5 transactions (→ Activity). FAB "+"
opens the quick-add sheet.

**Activity** — all transactions, grouped by day with sticky headers ("Today", "Yesterday",
"Mon 28 Sep"). Row: category colour dot, label (or category name), account (small), amount
(income green). Transfers shown once ("Current → Savings"). Badges for Reconciliation /
Opening balance. Filter chips (account, category, in/out) and label search. Tap → the edit sheet
(amount, in/out, category, account, date ≤ today, label, note, delete; the whole sheet is described under
"Editing past transactions" below).

**Accounts** — list with display balances and a pool total; add/edit (name, type, current
balance for new accounts, overdraft limit, include in safe-to-spend, archive). Account detail:
display + raw balance (the card is tappable: it opens the history), overdraft, **Update balance**
(enter what the bank app shows → preview "The app thought £X; difference £Y will be recorded as
Untracked" → confirm), **Transfer**, recent transactions (a tap opens the edit sheet, with the account as
its perspective) and a **See full history** button under them, reconciliation history.

**Account history** (`Route.AccountHistory(accountId)`; `ui/accounts/AccountHistoryScreen`,
`AccountHistoryViewModel` and the pure, JVM-tested `AccountHistoryMapping`). One scrolling list, top to bottom:
- Range chips **1M / 3M / 6M / 1Y / All** (default 3M; the range ends today and starts 1/3/6 months or a year
  back, or at the account's first transaction for All) and a **Balance over time** line chart of the account's
  actual end-of-day balances in display terms (same zero point and the account's own overdraft as the account
  page). The chart is the existing `LineChart`, fed by `InsightsMapping.balanceHistory(AccountView.Single(id))`
  (so by `Trends.balanceHistory`): nothing new is drawn.
- A summary of the range: balance at the start → balance at the end, money in, money out. Money in/out count
  every kind of row except opening balances (they are where the account started, not money moving); an
  opening balance dated inside the range is part of the starting position, so start + in − out = end always.
- Filters: a search field (label, category name or note, ignoring case), a category drop-down ("Filter by
  category", offering the categories this account uses) and In / Out / All chips (money in = positive amount; a
  transfer leg counts by its sign). **Filters only hide rows**: running balances and month totals always come
  from all of the account's transactions, a month with no visible row is left out, and while a filter is on
  the screen says "Showing 2 of 8 transactions. Balances and month totals still count them all." with a
  Clear filters button.
- Every transaction, all time, in a `LazyColumn` (never addressed by index in tests): a **month header** for
  each month, newest first — "October 2026 · in £500.00 · out £485.21 · ends at £14.79" (in/out as above,
  ends at = the balance after the month's last row) — then **day headers** as in Activity ("Today", "Yesterday",
  "Mon 28 Sep"), then the rows, newest first. Month headers are deliberately not sticky: a pinned header covers
  the row a list scrolls to the top, and the tests tap rows.
- **Running balance**: all of the account's transactions, every kind, sorted by date, then
  `createdAtEpochMillis`, then id; a row's "Balance £59.83" (small text, under its amount) is the sum up to and
  including it, in display terms. A row also shows badges where relevant: **Imported** (has an import batch or
  key, except an opening balance moved by an import), **Receipt**, **Transfer to / from <account>** (or
  "Transfer · other account not in the app" for a lone leg), **Opening balance**, **Balance check** (a
  reconciliation).
- A tap opens the same edit sheet as Activity (both legs of a transfer when the other leg exists), with the
  account as its perspective. The category drop-down is labelled "Filter by category" because the sheet that
  opens over this screen has a "Category" field and tests find fields by label.

**Editing past transactions** (`ui/activity/TransactionEditSheet`, `TransferEditor`, `TransactionEdit`,
`TransactionEditor`, `TransactionEditHost`; shared by Activity, the account page and the history screen). The
sheet never touches the database: it builds a `TransactionEdit` (Single, ToTransfer, Transfer, ToNormal,
Delete), `TransactionEditor` turns it into repository calls, and each screen's view model owns a
`TransactionEditController` (runs the edit, holds the "change similar" question) that the
`TransactionEditHost` composable reads; a screen only keeps "which row is open". The form depends on the row
(`EditRules.editorKind`):
- **Ordinary transaction** (and a balance check, which says what it is): out/in, amount, category, account, date
  (any past date, never the future), label, note, Delete. A NORMAL transaction that is not part of a receipt
  also has a **Move between my accounts** switch: the category is replaced by **Other account** (each other
  non-archived account, or "Not in the app", the default). Saving calls `convertToTransfer`: the row becomes
  `TxKind.TRANSFER` (category cleared, new UUID `transferGroupId`, same id) and, for another account, the
  opposite leg is created there (negated amount, same date and label, the original's import batch so undoing an
  import takes both, no import key). A receipt part shows "Part of a receipt. Changing the amount makes it differ
  from the receipt." and cannot be moved.
- **Starting balance** (an `OPENING_BALANCE` row): "Edit starting balance" with a signed amount (minus = started
  overdrawn) and a date; no category. An opening-balance entry that a statement import made (it has an import
  batch) is only explained, because the next import or undo re-derives it.
- **Transfer** (both legs, or a lone leg whose other account isn't in the app): amount, date, label and two
  pickers, **From account** and **To account**, each an account or "Not in the app" (at least one side must be
  an account, and the two must differ). `updateTransfer` moves a leg to its new account, creates a missing leg,
  or deletes the leg of a side set to "Not in the app"; both legs carry the same amount, date and label.
  **Not a transfer** opens a dialog: pick a category (income or spending categories, by the sign of the kept leg);
  the side to keep is the account the sheet was opened from, and from Activity (no perspective) a "Keep it on"
  picker asks; it says which side will be deleted. `convertToNormal` keeps that leg as a NORMAL transaction and
  deletes the other.
- **Change similar**: after saving a NORMAL, non-receipt transaction whose category changed, if other NORMAL
  transactions share its merchant (`MerchantKey.normalise(label)`, non-blank) and would change (not already in the
  category, direction allowed by the category's flow, not receipt parts), a dialog asks `Change the other 7
  "TESCO STORES" transactions to Groceries too?` — **Change all** / **Just this one** — with a ticked-by-default
  "Use Groceries for TESCO STORES in future imports" checkbox. The checkbox is honoured by either button
  (`recategorise(..., rememberRule)` / `rememberMerchantRule` upsert the `MerchantRule` that the importer's
  `CategorySuggester` consults first).
- `TransactionRepository.update` still mirrors a transfer leg's amount and date onto the other leg; the sheet's
  transfer form no longer relies on it.

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
learned spending; end-of-week balance P50 (P10–P90) for the chosen account view; money reserved
in funds (pool views only); weeks with P10 below the floor highlighted.

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

**Statement import** wizard — (1) choose file + account (a Santander statement PDF or a CSV; the picker
offers both and the file is told apart by its type or its "%PDF" header); (2) column mapping,
auto-guessed, with a 5-row preview (CSV only); (3) review rows (New / Already imported; every row that is
not already imported starts ticked — possible duplicates are no longer silently unticked, the next step asks);
(4) **check** — only when there is something to ask (otherwise (3) goes straight to (5)); (5) categorise by
merchant group, pre-filled from merchant rules/keywords, "remember" ticked; (6) import → batch,
transactions, merchant rules, opening-balance adjustment; offer to move the history start date earlier;
summary with "Undo import". `ImportStep` is `PICK_FILE, MAPPING, REVIEW, CHECK, CATEGORISE, SUMMARY`.
- **Check step** (`ImportStep.CHECK`, `ImportCheckStep.kt`, rules in §5.16 `ImportChecks`). Shown when
  `ImportChecks.questions` returns anything, for CSV and PDF alike; nothing is saved until the import. Heading
  "N things to check" ("Nothing is saved until you press Import."), then one card per question, showing both
  sides where there are two — the bank's row (date, description, amount) and the app's transaction (date,
  label, category, amount) — with plain wording as the title: "This might be the same as something you entered"
  (`PossibleDuplicate`: "Same, use the bank's date" / "Same, keep mine as it is" / "Different, add the bank's
  too"), "Same purchase, different amount?" (`AmountMismatch`: "Yes, use the bank's amount and date" / "No, add
  the bank's too"), "In the app but not on this statement" (`NotOnStatement`: "Keep it" — with "It's from the
  last few days of this statement, so it may not have reached the bank yet." — / "Delete it" ("Delete both sides"
  for a move between accounts, with a warning naming the other account) / "Move to another account", which opens
  a menu of the accounts and then reads "Move to Cash"), "Something on the statement doesn't add up"
  (`StatementProblem`: "Leave this row out" when it names a row that would be imported / "OK, carry on"; this
  replaces the old "Check these against your statement" warning on the review), "Dated before this account's
  starting balance" (`BeforeOpening`, pre-answered "OK") and "Your balance won't match the statement"
  (`BalanceGap`: "On 10 Oct 2026 the app would show £2.45, but your statement says £14.79: a difference of
  £12.34." with "Record the difference so they match" / "Leave it"; it is worked out again after every answer
  and disappears when the answers make the balances agree). Answers are selectable chips; a shortcut at the top,
  "Same as mine for all possible duplicates" (`SameKeepMine`), appears when there are several duplicates.
  "Next" (test tag `import-check-next`) is enabled once every question has an answer; when no row is left to
  import it reads "Import" and saves straight away (no batch is made if no row is imported). Back from the
  categorise step returns here with the answers kept; going back to the review and forward again keeps the
  answers of the questions that are still asked. On the review, a row the check will ask about (a pair) shows
  "You'll be asked about this" and its checkbox is ticked and disabled.
- **Applying the answers**: on Import, `ImportRepository.importWithChanges` runs in one database transaction:
  the rows to import (as before, transfer groups included), then the answers' changes to transactions that were
  already in the app (`ExistingUpdate` sets date / amount / import key; deletions, a transfer's legs together;
  moves to another account), then the opening-balance move (rebuilt for the account and for any account an entry
  was moved to), then, for `RecordDifference`, `Reconcile.plan` for the statement's last day with the statement's
  balance as the actual one (the same reconciliation "Update balance" makes, dated that day instead of today;
  nothing is recorded if the balance already matches). The balance-update transaction carries the batch id, so
  `undoBatch` removes it and its `reconciliations` row (`ReconciliationDao.deleteForBatch`) along with the
  imported rows; the changes made to transactions that were already in the app stay.
- **PDF path** (§5.16): `onPdfLoaded` merges the glyphs into cells, checks `looksLikeSantander` and
  `parse`s; success goes straight to (3), skipping the mapping (Back from the review returns to the file
  picker). A PDF that is not a Santander statement, or cannot be read, shows a sentence on the picker step
  ("This PDF doesn't look like a Santander statement. Statement PDFs from Santander online banking, or CSV
  files from any bank, can be imported."). The review starts with a card — "Santander statement · 10 Sep –
  10 Oct 2026 · 19 transactions · closing balance £14.79" — and an "Import into" account picker (so a shared
  statement can be pointed at the right account; changing it classifies the rows again; the statement's
  `problems` are asked about in the check step). Rows flagged `likelyTransfer`
  say "Looks like a move between your accounts". A PDF shared into the app (`ACTION_SEND`, `application/pdf`)
  that is a Santander statement opens the wizard on `Route.StatementImport(pdfPath)` (the cached copy is read
  when the screen opens, then deleted) instead of the receipt review; other PDFs still go to receipts.
- **Move between my accounts** (every merchant group, CSV too; on by default when every row of the group is
  `likelyTransfer`): the category picker and the "remember" tick are hidden, no `MerchantRule` is saved, and
  a picker offers each other non-archived account or "Not in the app" (default). `SelectedImportRow.transfer`
  carries the choice to `ImportRepository.importRows` (via `ImportTransactions.forRow`): the row becomes a
  `TxKind.TRANSFER` leg (no category, a fresh UUID `transferGroupId` per row, the row's import key and
  batch); if another account was chosen its opposite leg is inserted there (amount negated, same date,
  label and group, the batch id so `undoBatch` removes it, no import key) unless it is dated before that
  account's own OPENING_BALANCE date (its opening balance already includes it). `rebuildOpeningBalanceMove`
  treats imported TRANSFER legs on the imported account like imported spending. A lone leg is tolerated
  (Activity shows it as a single row, and its edit sheet is the transfer form, where a second account can be
  added or "Not a transfer" applied; forecasts only learn from NORMAL and RECONCILIATION rows, Trends skip
  TRANSFER, balances count every kind).
- **Summary**: for a PDF (and a CSV whose balance column is mapped, using its last row) the app's balance for
  the imported account on the statement's last day (after the import) is compared with the closing balance —
  "Matches your statement: £14.79 on 10 Oct ✓", or "Your statement says £14.79 on 10 Oct; the app says £X (£Y
  different). Transactions you entered by hand that are also on the statement, or rows you unticked, would
  explain it." Below "Imported N transaction(s)" and "Undo import" (or "No new transactions were added."), a line
  lists what the checks changed — "What the checks changed: 2 matched to your own entries, 1 deleted, 1 moved to
  Cash, difference of £3.20 recorded" (also "N amounts corrected", "N rows left out") — and, when the checks
  changed, deleted or moved transactions that were already in the app: "Changes you chose for transactions
  already in the app aren't undone."

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
- **Core** (`./gradlew -p core test`): 795 JVM tests (2026-10-17: 573 before the Recipes section's core, which adds 200,
  and the Recipes data package adds 22: backup version 3 and bundle merging), including
  stress/metamorphic suites and a 20,000-budget soak (`core/src/test/.../stress/`); forecast invariants include
  "expected spending + expected income = expected learned flow".
- **Recipes core** (`core/src/test/.../recipes/`, 200 tests): the §11.7 checkpoint table on the decoded starter book
  (`StarterCheckpointsTest`), every starter ingredient line parsed and resolved against its ingredient with the two
  known exceptions (`IngredientLinesTest`), every starter line name matched among all 40 ingredients
  (`IngredientMatchingTest`), paste-format round trips of all four recipes and the §11.4 example (`RecipeTextTest`),
  plus `RecipeMathsTest` (the braise rounding, the pulses cap), `TrafficLightsTest` (every boundary), `PricesTest`,
  `ShoppingListTest`, `RecipeStepsTest`, `RecipeBundleTest`, `QuantitiesTest`, `RecipeModelsTest` and
  `RecipeRobustnessTest` (random pastes never throw).
- **Recipes data** (F2, 2026-10-17): core `BackupCodecTest` (food round trips, a version 2 file, every new reference and
  uniqueness check) and `BundleMergeTest` (which bundle ingredients match existing ones); app JVM `FoodMappersTest`; device
  `data/FoodRepositoryTest` (the starter book imported through `RecipeRepository.importBundle` into a database that already
  has ingredients, so real ids differ from the bundle's provisional ones, then read back through the repositories to give the
  eight §11.7 checkpoint rows; importing again with Use existing adds 4 recipes and no ingredients; Import as new; learned
  aliases; archived ingredients keep their lines; recipe delete cascades; save/replace; the shopping list's entries, marks, clear
  ticked and clear list), `data/MigrationTest` 2 → 3 (and 1 → 3, with the foreign keys' ON DELETE actions),
  `data/BackupRoundTripTest` (food in every table, restore into an empty database, an older file clears the food tables) and
  `data/SettingsStoreTest` (the new keys and the cooking ticks). The 2 → 3 test needs `3.json`, which the build generates.
- **Device tests** (`app/src/androidTest`, run by CI's `device-tests` job on two API 34 emulators
  in parallel shards, merged by `device-report`; report and screenshots on `refs/ci/device-report`): data layer against the real Room database
  (`data/`), end-to-end UI with screenshots (`ui/`, helpers in `testing/UiTestSupport.kt`), and the
  widget (`widget/`: Glance layouts at the three sizes, one-tap preset logging). The PDF statement importer is
  covered by `data/SantanderPdfTest` (an invented statement drawn into a real PDF with `PdfDocument`, read back
  through PDFBox and parsed) and `data/StatementImportViewModelTest` (the wizard's view model importing it
  with moves between accounts, the opening-balance cases, the balance check and undo) and
  `ui/StatementImportPdfTest` (the statement PDF shared into the app → review → categorise → import → balance check;
  a non-statement PDF still opens the receipt review); `testing/SantanderPdfFixture` holds the invented content.
  The import checks (§5.16, §6.5) are covered by `data/StatementImportChecksTest` (the wizard's view model on the
  invented statement and on a small CSV, with hand-entered transactions that produce every question type; each
  answer is given and the ledger asserted: no duplicates, amounts and dates fixed, deletions, moves, the balance
  update recorded and the balance then matching, undo, nothing imported when every row is matched) and
  `ui/StatementImportChecksTest` (the check step walked on screen with screenshots `import-check-*`). App JVM
  tests: `CheckTextTest`, `ImportSelectionTest`.
- **Account history and editing (2026-10-16)**: JVM `AccountHistoryMappingTest` (running balance incl. same-day
  order and every `TxKind`, month/day grouping and totals, badges, filters, ranges and the summary),
  `TransferEditsTest`, `TransactionEditTest`; device `data/TransactionEditingTest` (normal → transfer with and
  without the other account, re-pointing/removing/creating legs, "Not a transfer", `recategorise` with and
  without the rule), `ui/AccountHistoryTest` (opening it, months and running balances, filters, range summary,
  editing an amount/date/starting balance from the list) and `ui/EditPastTransactionsTest` (the account page's
  rows, moving a transaction to another account, transfer editing, "Not a transfer" from an account and from
  Activity, change similar, receipt parts). Shared seeds are in `testing/HistoryTestSupport.kt` (a ledger with
  fixed 2025 dates so months can be named); test tags are in `AccountHistoryTags`. Beware two screens with the
  same field label when a sheet opens over a screen (`field(label)` takes the first match): that is why the
  history's category filter is "Filter by category".
- **Sections (2026-10-17)**: device `ui/SectionSwitchTest` (screenshots `sections-*`): the switch from Home to Recipes and
  back with each section's own tabs and no tab of the other, the switch on every tab and not on a sub-screen, the last
  section reopening on launch, and Back on the Recipes tab leaving the app (from Android 12 a root launcher activity is
  moved to the back, not destroyed, so the test waits for it to stop being resumed). The Recipes tabs are placeholders
  until their packages land; the test looks only at the bars, the switch, the Recipes tab's "Recipe settings" action
  and a sub-screen's "Back", so it keeps passing when they are replaced. JVM `AppSectionTest` covers parsing.
- **Recipes screens (2026-10-17)**:
  - **Device tests** (seeds and helpers in `testing/FoodTestSupport.kt`, among them `tagged(tag, text)` for a tag
    whose text may be on a child node):
    - `ui/RecipesViewTest` (`recipes-*`): the starter book added from the empty state with the checkpoint numbers
      on its cards; the chilli's page with cue boxes, storage and 6 servings; optional extras off on the traybake;
      a rating and a note kept.
    - `ui/CookingAndCompareTest` (`cooking-*`, `compare-*`): ticks kept until *Done cooking*, the screen kept on
      only while cooking, and Compare sorted by cost and by protein.
    - `ui/AddRecipeTest` (`add-recipe-*`):
      - the chilli pasted, with the rice and lemon suggestions taken and the water not counted, at £2.54 and
        897 kcal;
      - an ingredient created on the review that matches by itself next time;
      - recipe text shared into the app opening the review, while receipt text still opens the receipt review.
    - `ui/IngredientsAndShoppingTest` (`ingredients-*`, `shopping-*`): a new onion price changing the stir-fry
      card, a portion added, an ingredient archived; the list summing two recipes, *I have it*, ticks, free text
      and *Clear ticked*.
    - `data/RecipeBundleImportTest`: the JSON import path without the picker.
    - `ui/ReceiptPricesInRecipesTest` (`receipt-prices-*`): a receipt for the onions' linked product changes the
      stir-fry card and shows as "paid £1.20 at Tesco" on the Ingredients tab.
  - **JVM tests** (`app/src/test/.../ui/food/`): `FoodMappingsTest` (cards, Compare order and best marks, scaled
    lines on the starter book), `ReviewRulesTest`, `FoodTextsTest` and `IngredientFormRulesTest`.
  - **Screens**: the review and the recipe page are plain scrolling columns rather than lazy lists, so every field
    stays composed (see the import check step's lesson).
- **Labels and barcodes (2026-10-17)**:
  - **Core tests**: `NutritionLabelTest` (per 100 g first or second, kJ and kcal on one or two rows, "<0.5 g",
    "trace", sodium to salt, no heading, not a label) and `OpenFoodFactsTest` (found, missing values, kJ and
    sodium conversions, brand not doubled, multipacks, not found and broken answers, barcodes, the request).
  - **JVM test**: `ScanRulesTest`, putting a reading or a draft into the editor's form.
  - **Device tests**: none for the photo flows (they need a camera or a photo picker). The scanning code is
    `ui/food/scan/` (`FoodScan`, `PackScanViewModel`, `PackPhotoButton` and the two review dialogs).
- UI test conventions: tap through `UiTest.tap` (waits for the target to stop moving: screens
  resize for the keyboard), pick dates with `chooseInDatePicker` (Material 3 day cells carry the
  full date as text), reach cards far down a list with `swipeListUp`, and never address a field by
  index in a lazy list (only the rows on screen exist). `type` hides the keyboard afterwards (it
  shrinks the screen, so lower fields stop being laid out). Scroll with `scrollIntoView`, never
  `performScrollTo` directly: that retries for ever when a node can't come fully into view (a chip
  cut off by the edge of a sideways row hung a whole shard). Every UI test fails after five minutes,
  with the main thread's stack in the failure and every thread's in logcat under `HangGuard`.
- Bugs found and fixed by these tests: out-of-order import undo corrupting the balance history;
  restore not bringing back the account views; what-if save navigating off the main thread (crash);
  a deleted/archived account's page staying open; tabs reopening a stale sub-screen; account-page
  transfers without a direction; Timeline "expected spending" being net of income; "Quiet week"
  firing straight after onboarding; the other-income form defaulting to Student loan; Save hidden
  by the keyboard (quick add, forms); a doubled bottom inset; overlapping chart month labels.

---

## 9. Open items and future work

- **Santander statement format**: supported through the PDF statements from Santander online banking (§5.16),
  built from the layout of a real statement and tested only on invented data. The first real imports are the
  verification: the check step's "Something on the statement doesn't add up" questions and the summary's balance check say
  when something was misread. A Santander CSV/text export is still unseen; the generic CSV importer works for it.
- **Opening balance and card dates**: statement rows are dated by their card date, while the bank's opening
  balance for an account created mid-statement counts rows by their posted date, so an account opened on a
  day between a purchase and its posting would be adjusted by that purchase. Opening the account on the day
  after the statement's last day (the usual way) is not affected.
- **Room schema files**: committed under `app/schemas/` (version 1 taken from CI, which publishes
  every build's schemas to `refs/ci/room-schemas`). Commit each new version's JSON the same way.
- **Device testing**: the suite runs on emulators in CI (see §8 Testing); nothing has run on a
  real phone yet, so first-install feedback from the user is the next verification step.
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
  then the review route is opened once the NavHost exists (behind the app lock overlay as usual). A shared PDF that is a Santander
  statement (read with `PdfStatementReader`, §5.16) opens the statement import instead of the receipt review.
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

---

## 11. Food: recipes, cooking and shopping (planned 2026-10-17; checked against the code and revised the same day)

Goal: the user's recipe book inside this app, as a second **section** next to the budget. Each recipe
shows **cost per portion**, **nutrition per portion** (UK label style: % of reference intakes and
traffic-light colours) and **veg portions** (5 a day). Recipes can be compared, cooked from on the phone,
scaled and turned into a shopping list. The user adds recipes without help: they paste or type one, the
app reads it, and anything it can't match goes to a review screen. Prices come from the user's own
receipts (§10) wherever a product has been bought, otherwise from a typed price. Nothing here changes
the forecast.

Until this is built, the recipes live on two Claude pages. "My Recipe Book" has the stir-fry, traybake
and braise (32 Tesco-priced ingredients, prices checked 15 Oct 2026); the chilli has its own page
(prices checked 17 Oct 2026). Both ship inside the app as the **starter recipe book** (§11.7), so
nothing has to be copied across by hand.

### 11.1 Decisions (agreed with the user 2026-10-17)
| Topic | Decision |
|---|---|
| Where | This app, as its own section with its own tables. A **switch at the top left** of every main screen moves between **Budgeting** and **Recipes**. Each section has its own bottom tabs (§11.8), almost like two separate apps. The only link to the budget is products (receipt prices). |
| Users | Just the user. No sharing, no web version. |
| Adding recipes | Typed or pasted by the user, or written by Claude in the paste format (§11.4). No AI inside the app in v1. |
| Prices | Per ingredient: the latest price **paid** for its linked product on a receipt (after Clubcard and multibuy discounts), else a typed pack price. Always shown with its date and source. |
| Nutrition | Per 100 g or 100 ml, from a photo of the label (ML Kit text recognition, already bundled), from a barcode lookup, or typed. Estimates are marked. Colours follow the FSA front-of-pack rules (§11.2). |
| Barcodes | ML Kit barcode scanning with the **bundled model** (works offline), from a photo (`TakePicture`, as for receipts; no camera permission). Looking a barcode up on Open Food Facts is an **opt-in setting, off by default**. It is the app's only network use, and it sends only the barcode number. |
| Open Food Facts contact | Their User-Agent asks for a contact. The app sends the project page (`https://github.com/matteo-pi/budgeting`), never an email address. |
| Budget link | None in v1. The shopping list's estimate never reaches the forecast, which already learns grocery spend. |
| Extras in v1 | Works offline, shopping list, scale servings, barcode scanning, cooking view (screen kept on, ingredients and steps ticked off). |
| Not in v1 | Meal planner calendar, importing from recipe websites, AI parsing, nutrition targets or tracking what was eaten. What the user has in the kitchen was added later (§11.11), set by hand. |

### 11.2 Units, quantities and maths (`core/.../recipes/`, pure Kotlin, fully unit-tested)

**Units and portions**
- **Base unit**: every ingredient is measured in grams (`G`) or millilitres (`ML`). A recipe line
  stores its quantity in the ingredient's base unit (`Double`), plus the text as written.
- **Measures**: g, kg (×1000), ml, l (×1000), tsp = 5 ml, tbsp = 15 ml, pinch = 0.3 g, oz = 28.35 g,
  lb = 453.6 g, cup = 240 ml.
  - **Spoons and cups on a `G` ingredient**: the ingredient's own "tsp" or "tbsp" portion is used if
    set (cornflour: 1 tbsp = 9 g). One spoon can stand in for the other at 1 tbsp = 3 tsp. Otherwise
    1 ml = 1 g, and the line is marked *estimated weight*.
  - **Grams on an `ML` ingredient**: 1 g = 1 ml, marked *estimated*.
  - **pinch**: the ingredient's "pinch" portion if set, else 0.3.
- **Portions**: named weights per ingredient, such as `each` (1 onion = 150 g), `clove`, `rasher`,
  `fillet`, `slice`, `tin`, `head`, `leaf`, `cube`, `bulb`, `pack`, or any name the user adds. Names
  match singular or plural. "2 onions" and "3 garlic cloves" resolve through them (§11.4).

**Cost**
- **Price per base unit** = pack price (pence) ÷ pack size, as a `Double`. A weighed receipt item
  gives the price per gram directly (below).
- A line costs `quantity × price per base unit`. It is kept unrounded (`Double` pence) and summed into
  the recipe's unrounded total.
- This is a deliberate, documented exception to "never `Double` for amounts": per-gram prices are
  fractions of a penny. Every displayed amount is a `Money`, rounded half-up to the penny.
- **Whole dish** = round(total).
- **Per portion** = round(total ÷ servings). Always divide the *unrounded* total. The braise totals
  744.996p: that is £7.45 for the dish but £3.72 a portion. Halving the rounded £7.45 would give £3.73.
- **From an empty cupboard** = Σ over distinct ingredients of ⌈(Σ quantity ÷ pack size) − 1e-9⌉ ×
  pack price, in integer pence.
- **Missing prices**: lines whose ingredient has no price are left out. The recipe then says
  "at least £x · 2 ingredients have no price".

**Nutrition**
- **Per portion** = Σ quantity ÷ 100 × value per 100 ÷ servings. This covers energy (kcal; kJ =
  kcal × 4.184), fat, saturates, carbohydrate, sugars, fibre, protein and salt.
- **Display**: energy to the whole number, grams to 1 dp.
- **Unknown values**: unknown nutrition on any counted ingredient makes the total "at least", and the
  screen says so.
- **% of reference intake**: against the UK adult RIs (8,400 kJ / 2,000 kcal, fat 70 g, saturates
  20 g, carbohydrate 260 g, sugars 90 g, protein 50 g, salt 6 g). Fibre is not an RI: it is shown
  against the 30 g a day guideline, worded that way.

**Traffic lights** (`TrafficLights`, FSA/DH front-of-pack guidance) for fat, saturates, sugars (total)
and salt:

| | Green (per 100 g) | Red (per 100 g) | Red (per portion) |
|---|---|---|---|
| Fat | ≤ 3.0 g | > 17.5 g | > 21 g |
| Saturates | ≤ 1.5 g | > 5.0 g | > 6.0 g |
| Sugars | ≤ 5.0 g | > 22.5 g | > 27 g |
| Salt | ≤ 0.3 g | > 1.5 g | > 1.8 g |

- Between green and red per 100 g is amber.
- The per-portion red rule (30% of the RI) is the one for portions over 100 g, which every main meal is.
- **Colour** = red if either rule says red, else the per-100 g colour.
- "Per 100 g" uses the raw weight of the counted lines (ml counted as g), because the cooked weight
  isn't known. Dry rice, pasta and pulses gain water as they cook, so the colour can read high; the
  label says it is a guide.
- Energy has no colour.

**Veg portions** (NHS 5 A Day: 80 g a portion)
- Each ingredient has a `vegKind`: `NONE`, `VEG` or `PULSE`.
- veg = Σ `VEG` grams ÷ servings ÷ 80 + min(1, Σ `PULSE` grams ÷ servings ÷ 80). Beans and lentils
  count as one portion at most, however much there is.
- Potatoes, garlic, chilli, ginger, herbs, spices and lemon juice are `NONE`. Pulses weighed dry count
  at their dry weight, so they are under-counted.
- Shown to 1 dp, and as dots (full and part).

**Optional lines, scaling and breakdown**
- **Optional lines** (`optional = true`) are counted unless *Count optional extras* is off. It is one
  setting, default on, with a switch on Compare and on the recipe screen.
- **Scaling**: cooking for N instead of `serves` multiplies every quantity by N ÷ serves.
  `IngredientLines.scaleText(text, factor)` rewrites the line as written:
  - The leading amount is scaled: counts and spoons to the nearest ½ ("1½ onions", "1½ tbsp"),
    weights and volumes to the nearest 5 ("480 g"), or 1 dp under 10.
  - In "N × 400 g tins" only N is scaled.
  - A bracketed weight such as "(about 320 g)" is scaled too.
  - After a count or a spoon the base amount is appended: "1½ onions, diced (225 g)".
  - A line with no leading amount gets "×1.5" appended.
  - Lines without a quantity stay as written.
- **Breakdown**: the share of cost, kcal, salt and protein by ingredient, largest first, as the top 5
  plus "everything else".

**Files**
- `RecipeModels.kt`
- `Quantities.kt`: amount and unit parsing, base-unit conversion
- `RecipeMaths.kt`: cost, nutrition, veg, scaling factor, breakdowns
- `TrafficLights.kt`
- `Prices.kt`: price resolution, below
- `ShoppingList.kt` (§11.6)
- `NutritionLabel.kt` (§11.5)
- `IngredientLines.kt` and `RecipeText.kt` (§11.4)
- `IngredientMatching.kt`
- `OpenFoodFacts.kt`: pure mapping of a parsed response
- `RecipeBundle.kt` (§11.7)

**Price resolution** (`Prices.resolve(ingredient, purchases, today)`)
- **From a receipt**, if the ingredient has a `productId` and there are purchases of that product. The
  latest one (by date, then input order) gives the paid price:
  - A weighed item (`weightGrams` set): spend ÷ weight gives pence per gram (1 g ≈ 1 ml for an `ML`
    ingredient).
  - Otherwise: spend ÷ quantity gives the price of one pack, then ÷ the ingredient's pack size.
  - A purchase with spend ≤ 0 (a free item) is skipped.
  - Source: `RECEIPT(date, store)`.
  - Needs one change to an existing type: `PurchasedItem` (core `receipts/ItemPrices.kt`) has no weight
    today. It gains `weightGrams: Int? = null`, defaulted so every existing caller still compiles.
    `ReceiptMapping.purchasedItems` fills it from `receipt_items.weight_grams`.
- **Else the typed pack price**. Source: `TYPED(date)`.
- **Else no price**: the recipe shows its cost as "at least …" (above) and still shows nutrition.
- A price older than 120 days is marked *old*.

### 11.3 Data (`app`, Room version 3 with `@AutoMigration(2, 3)`: new tables only)
Columns are primitives as elsewhere. Quantities and nutrition are `REAL` (Double), and money is pence.

| Table | Columns (besides `id`) | Notes |
|---|---|---|
| ingredients | name, unit (`G`/`ML`), pack_size, pack_label, price? (pence), price_date?, store?, product_id? → products SET NULL, product_name? (the shop's name for it, text), source_url?, barcode?, kcal?, fat?, saturates?, carbs?, sugars?, fibre?, protein?, salt? (per 100 base units; null = unknown), nutrition_source (`LABEL`/`BARCODE`/`TYPED`/`ESTIMATE`), veg_kind (`NONE`/`VEG`/`PULSE`), shelf (`FRUIT_VEG`/`MEAT`/`DAIRY`/`FROZEN`/`CUPBOARD`/`OILS`/`HERBS_SPICES`/`OTHER`), note, archived, created_at | index product_id; unique barcode |
| ingredient_portions | ingredient_id → CASCADE, name, amount (base units) | unique (ingredient_id, name) |
| ingredient_aliases | name_key, ingredient_id → CASCADE | unique name_key (learned, like product aliases) |
| recipes | title, short_title?, tagline?, style? ("Hob · one pot"), serves, prep_mins?, cook_mins?, time_note?, served_with?, tags (comma-separated), rating (0–5), notes, source (`MINE`/`CLAUDE`/`IMPORTED`), created_at, updated_at, archived | |
| recipe_lines | recipe_id → CASCADE, position, grp?, text, ingredient_id? → ingredients SET NULL, quantity?, optional | index recipe_id, ingredient_id |
| recipe_steps | recipe_id → CASCADE, position, kind (`STEP`/`TIP`/`EQUIPMENT`/`STORAGE`), title?, time? ("~10 min"), body | index recipe_id |
| shopping_entries | recipe_id? → recipes CASCADE, servings?, ingredient_id? → ingredients CASCADE, quantity?, free_text?, checked, added_at | a recipe entry or an extra item; `checked` is used by free-text items only |
| shopping_marks | ingredient_id → ingredients CASCADE, checked, have_it | unique ingredient_id: the ticks of the list's ingredient lines, which sum several entries |

**Step bodies**
- A body is paragraphs separated by blank lines.
- A paragraph starting "Ready when:" is a *cue*. One starting "If …:" (up to the first colon) is a
  *rescue*. Any other paragraph is plain text.
- `RecipeSteps.paragraphs(body)` in core splits and classifies them. The cooking view shows cues and
  rescues as coloured boxes.

**Rules**
- Ingredients are archived, never deleted, while a recipe line points at them.
- **Backup version 3** adds the eight tables to `core/.../backup/` (`BackupV1`, the DTOs, the mapper,
  and `decode` checking the new references). Versions 1 and 2 still restore, with no food data.
- **Schema file**: commit `3.json` from `refs/ci/room-schemas` after the first green build.
  `MigrationTest` gains 2 → 3, seeding version-2 rows and checking they survive.
- **Code**: entities in `data/db/entities/` (`IngredientEntity`, …), one `FoodDao`, and the
  repositories `IngredientRepository`, `RecipeRepository` and `ShoppingRepository` in `AppContainer`.

**Settings** (`SettingsStore`, new keys)
- `appSection` (`BUDGETING`/`RECIPES`): which section opens next time.
- `countOptionalExtras`: default true.
- `lookUpBarcodesOnline`: default false.
- **Cooking ticks**: one string-set key per recipe, cleared by *Done cooking*.
- None of these are in backups.

### 11.4 Reading recipes (`IngredientLines`, `RecipeText`, `IngredientMatching`)
**One line**: `[amount] [measure] name[, note] [(bracket)]`. `IngredientLines.parse(text)` gives the
amount, measure or portion word, name words, note, bracket weight, optional flag and multiplier.
`IngredientLines.resolve(parsed, ingredient)` gives the base quantity and an *estimated* flag.
- **Amounts**: `2`, `1.5`, `½` `¼` `¾` `⅓` `⅔`, `1 1/2`, `1½`, and `1-2` (the first value; the range
  stays in the text). "a"/"an" counts as 1, and "a pinch of"/"pinch of" is 1 pinch.
- **Multiplier**: `2 × 400 g tins` or `2 x 400g` is 2 × 400 g. If the ingredient has a portion named by the
  word after it (`tin`), that portion is used instead: beans are weighed drained, so "2 × 400 g tins kidney
  beans" is 2 × 240 g.
- **Measures**: as §11.2, singular or plural, with or without a space ("320g").
- **Counts**: a number with no measure.
  - If a word of the name (singular) is one of the matched ingredient's portion names, that portion
    is used: "3 garlic **cloves**" is 3 × clove and "2 **tins** kidney beans" is 2 × tin.
  - Otherwise the `each` portion is used: "2 onions" is 300 g.
  - With no such portion the line is unresolved, and review asks for the weight.
- **Bracketed weights**: a bracketed weight after a count ("2 chicken breast fillets (about 320 g)",
  "1 head of broccoli (about 350 g)") wins over the portion estimate. A leading weight
  ("150 g long-grain rice (75 g per person)") always wins over brackets.
- **Notes**: words after a comma, or in brackets, are a note ("sliced 1 cm thick").
- **Optional**: "optional" anywhere marks the line optional.
- **No amount**: a line with no amount (*salt and pepper to taste*) is kept as text with no quantity
  and isn't counted.
- **Tests**: every line of the four starter recipes is a test case. Parsing its text and resolving it
  against the starter ingredient must give the bundle's quantity (within 0.01 g), or must be listed in
  the test as a known exception. Known exceptions: "300 ml chicken stock (1 cube)" (the 300 ml is the
  water) and "Salt only after tasting (up to ¼ tsp)".

**Paste format** (what Claude writes, and what *Share as text* exports; `RecipeText.format` and
`RecipeText.parse` round-trip):

  ```
  # Beef, Bean & Lentil Chilli
  Serves: 4 · Prep: 20 min · Cook: 55 min
  Style: Hob · one pot · batch of 4 · freezes well
  Tags: beef, rice, batch
  About: Browned mince, bloomed spices and red lentils that melt into the sauce.
  Serve with: 100 g rice per portion (included in the numbers).
  Time: Allow about 1½ hours.

  ## Ingredients
  ### Main
  - 500 g lean beef mince (5% fat)
  - 1 tbsp olive oil
  ### Vegetables
  - 1 bird's eye chilli, finely chopped (optional)

  ## Method
  1. Brown the mince (~10 min): Oil in the pot, then the mince…

     Ready when: the bubbling turns to a sizzle and the pieces are brown and crumbly.
  2. Veg in (8–10 min): …

  ## Tips
  - …
  ## Equipment
  - …
  ## Storage
  - …
  ```

- **Header lines**: "Serves 4", "Serves: 4" and "Makes 4" all work. "Prep"/"Cook" accept "20 min",
  "1 hr 15 min" and "1½ hours".
- **Steps**: a step is `N. Title (time): body`. The title is the text before the first ": " when that
  is at most 60 characters; otherwise the step has no title. Indented or unnumbered lines after a step
  are paragraphs of that step.
- **Without headings**: lines starting with an amount are ingredients, numbered lines are steps, and
  the first line is the title.

**Matching** a line's name to an ingredient:
1. Normalise: lower case, strip punctuation, simple plurals, and drop words such as *fresh*,
   *large*, *finely* and *chopped*.
2. Then try the learned alias, then an exact name.
3. Then token overlap. These are suggestions only, never applied automatically below a set score.

Choosing an ingredient on the review screen learns the alias, so the next recipe matches by itself.

**Review screen** (like the receipt review)
- At the top: title, servings, times, tags and style, with live cost, nutrition and colours per
  portion.
- Every line shows its matched ingredient, or *Choose ingredient* / *Create ingredient* /
  *Not counted*. It also shows the amount in base units ("1 onion → 150 g", editable) and a warning
  for estimated weights.
- Steps, tips, equipment and storage are editable.
- Save is allowed with unmatched lines.

### 11.5 Ingredients: prices, labels and barcodes
**Ingredient editor** fields:
- name; g or ml
- pack size and label; typed price (with date and store)
- **Link to a product**: a picker over `products` that shows the latest paid price from receipts
- shop product name and link (text only, never opened automatically)
- portions; nutrition per 100 (with its source)
- veg kind; shelf; note
- where it is used

**Label photo** (`NutritionLabel.parse(rows)`)
- OCR rows (reusing `LineGrouping.rows`) → find the "per 100 g/ml" column from the header.
- Read Energy (kJ/kcal), Fat, of which saturates, Carbohydrate, of which sugars, Fibre, Protein and
  Salt. "<0.5 g" reads as 0.5 and is marked. Each value gets a confidence.
- Shown on a review card before saving.
- Tested on typed UK label texts in the common layouts: per 100 g first or second, with and without
  %RI and per-serving columns.

**Barcode**
- **Scanning**: photo → ML Kit `BarcodeScanning`. The dependency is
  `com.google.mlkit:barcode-scanning:17.3.0`: the bundled model, about 2–3 MB, in Google Maven like
  text recognition.
  - Formats EAN-13, EAN-8, UPC-A and UPC-E. Codes are stored as digits; a 12-digit UPC-A is stored as
    EAN-13 with a leading 0.
  - A known barcode opens its ingredient; in the shopping list it ticks the item. An unknown one
    starts a new ingredient with the barcode filled in.
- **Online lookup**, with *Look up barcodes online* on:
  - Request: `GET https://world.openfoodfacts.org/api/v2/product/{code}?fields=code,product_name,brands,quantity,product_quantity,product_quantity_unit,nutriments`
    with `User-Agent: Budgeting/<versionName> (https://github.com/matteo-pi/budgeting)`.
  - Client: `HttpURLConnection` on `Dispatchers.IO`, with 10 s connect and read timeouts.
  - Rate: one request per scan, never in a loop or in the background. Their limit for product reads
    is per minute and far above that.
  - Responses: HTTP 200 with `"status": 1` is found. A 404, or `"status": 0`, means "Not on Open Food
    Facts — fill it in from the pack". A network error says so and keeps the barcode.
- **Mapping** (`OpenFoodFacts.toDraft(json)`, pure, tested on saved response texts):
  - **Nutrients**: `energy-kcal_100g` (else `energy_100g` in kJ ÷ 4.184), `fat_100g`,
    `saturated-fat_100g`, `carbohydrates_100g`, `sugars_100g`, `fiber_100g`, `proteins_100g`, and
    `salt_100g` (else `sodium_100g` × 2.5).
  - **Pack size**: `product_quantity` and `product_quantity_unit` (g or ml; kg and l × 1000). Failing
    that, the `quantity` text ("400 g", "4 x 100 g" = 400).
  - **Name**: `brands` + `product_name`.
  - Everything is shown for confirmation and marked `BARCODE`. The data is crowd-sourced, so the
    editor says to check it against the pack.
- **Manifest and settings**:
  - `android.permission.INTERNET` is added for this one feature. No other code may use the network.
  - Recipe settings (the Recipes tab's settings action) explains exactly what is sent (the barcode number only) and when (only when the
    user scans with the setting on).

### 11.6 Shopping list (`ShoppingList.build(entries, ingredients, prices)`)
**Adding**: a recipe with a servings count (*Add to shopping list* on the recipe), or an extra item
(an ingredient with a quantity, or free text).

**Building the list**
- Sums each ingredient across entries, scaled.
- packs needed = ⌈total ÷ pack size − 1e-9⌉, or 0 when *I have it*.
- estimated cost = packs × pack price, using the resolved price.
- Grouped by shelf in shop order: fruit & veg, meat, dairy, frozen, cupboard, oils, herbs & spices,
  other. Lines with no ingredient are listed as text at the end.

**Header**: "About £18.40 for 9 items", plus what the recipes themselves use ("£12.10 of it goes into
these meals": Σ the recipes' unrounded cost at their servings).

**Actions**: ticking is per line (`shopping_marks` for ingredient lines, the entry for free text); *I have it*
per ingredient line; *Clear ticked* (removes free-text items that are ticked and resets the marks) and *Clear
list* (everything). Scanning a barcode ticks its line.

### 11.7 The starter recipe book and recipe bundles (`RecipeBundle`)
**Format** (JSON, `{"format": "budgeting-recipes", "version": 1, "about", "ingredients": […], "recipes": […]}`):
- **Ingredient**:
  - `key`, `name`, `unit` (`G`/`ML`), `shelf`
  - `pack {label, size, pricePence, priceDate, store}`
  - `product`, `url`
  - `per100 {kcal, fat, saturates, carbs, sugars, fibre, protein, salt}`
  - `nutritionSource`, `veg` (`NONE`/`VEG`/`PULSE`)
  - `portions [{name, amount}]`, `note`
- **Recipe**:
  - `key`, `title`, `shortTitle`, `tagline`, `style`
  - `serves`, `prepMins`, `cookMins`, `timeNote`, `servedWith`
  - `tags`, `rating`, `notes`, `source`, `added`
  - `lines [{group, text, ingredient (a key or null), quantity (base units or null), optional}]`
  - `steps [{title, time, body}]`, `tips`, `equipment`, `storage` (lists of strings)

Unknown fields are ignored. `RecipeBundle.decode(text)` returns the bundle or a list of problems:
wrong format, a newer version, a line pointing at a missing key, a bad unit.

**The starter book**
- It is `core/src/main/resources/recipes/starter-book.json`: 4 recipes (stir-fry, traybake, braise,
  chilli) and 40 ingredients. It was generated from the two Claude pages, unchanged, except that the
  chilli's per-ingredient values were written out for the first time.
- `RecipeBundle.starter()` reads it from the classpath; it ends up in the APK as a Java resource of
  the core jar.
- It is offered by the Recipes empty state ("Add the starter recipes") and in Recipe settings.
  Adding it again asks first.

**Other bundles**: *Import recipes (JSON)* in Recipe settings (the Recipes tab's settings action) uses the system file picker
(`OpenDocument` with `application/json`, `text/plain` and `application/octet-stream`). It is
deliberately not a share target: shared files already go to receipts and statements.

**Merge**: an ingredient matching an existing name or alias is offered as "Use existing" (the
default) or "Import as new". Recipes are always added, with source `CLAUDE` or `IMPORTED` as the
bundle says.

**Checkpoints**: `core` tests decode the starter book and must get exactly these per-portion numbers.
The `cupboard` column is from an empty cupboard (whole recipe); colours are fat / saturates / sugars /
salt.

| Recipe (optional extras) | £/portion | £ whole | cupboard | kcal | protein | salt | veg | colours |
|---|---|---|---|---|---|---|---|---|
| Stir-fry (on) | 3.09 | 6.18 | 20.56 | 804 | 56.8 | 5.84 | 6.06 | R G G R |
| Stir-fry (off) | 3.01 | 6.01 | 18.91 | 793 | 56.7 | 5.12 | 6.06 | R G G R |
| Traybake (on) | 3.83 | 7.65 | 23.10 | 768 | 54.9 | 2.61 | 5.75 | R G G R |
| Traybake (off) | 3.05 | 6.10 | 20.25 | 734 | 52.6 | 2.59 | 4.81 | R G G R |
| Braise (on) | 3.72 | 7.45 | 29.32 | 891 | 57.0 | 5.14 | 3.81 | R R G R |
| Braise (off) | 3.64 | 7.29 | 27.73 | 857 | 56.5 | 5.13 | 2.81 | R R G R |
| Chilli (on) | 2.54 | 10.17 | 31.78 | 897 | 54.0 | 1.78 | 5.88 | G G G G |
| Chilli (off) | 2.53 | 10.11 | 28.98 | 897 | 54.0 | 1.41 | 5.88 | G G G G |

The chilli page itself showed £2.53 / 889 kcal / 2.0 g salt, from values it didn't keep. The table is
what the written-out values give, and is what the app shows. In the chilli, the beans' 120 g per
portion makes the pulses cap give exactly 1.

### 11.8 Navigation and screens (`ui/food/`)
**Two sections** (`AppSection { BUDGETING, RECIPES }`)
- **The switch**: every main screen of either section has a switch button at the top left, in its
  `TopAppBar` `navigationIcon`.
  - On the five Budgeting tabs it is a fork-and-knife icon with content description "Switch to
    Recipes".
  - On the four Recipes tabs it is a wallet icon, "Switch to Budgeting".
  - Sub-screens keep their back arrow instead. The button is `SectionSwitchButton` in
    `ui/navigation/`.
- **Switching**:
  - It clears the back stack (`navigate(first tab) { popUpTo(navController.graph.id) { inclusive = true } }`)
    and opens the other section's first tab, Home or Recipes.
  - It saves `appSection`. Back on a section's first tab leaves the app, as in two apps.
  - The app opens in the last-used section: the start destination is `Route.Recipes` when
    `appSection` is `RECIPES` and onboarding is done.
- **Other entry points**: shared receipts and statements, quick add and the widget still open their
  Budgeting screens on top of whatever is showing. A bottom-bar tap whose section's first tab isn't on the back
  stack (a receipt opened over Recipes) switches section first, so screens never pile up.

**Bottom bars**
- **Budgeting** (unchanged): Home · Activity · Forecast · Plan · Insights.
- **Recipes**: **Recipes · Compare · Shopping · Ingredients**. Its tabs pop up to `Route.Recipes`, as
  Budgeting's pop up to Home.
- `BudgetNavHost` shows the Recipes bar when the current destination is a Recipes route (a `foodRoutes`
  list of route classes). It shows no bar in the cooking view or onboarding.
- **Icons**: `material-icons-core` has none of these, and `material-icons-extended` is too big for an
  unminified APK. `ui/components/AppIcons.kt` therefore defines five `ImageVector`s from Material icon
  path data (Apache 2.0): `AppIcons.Restaurant`, `Wallet`, `MenuBook`, `TableChart` and `Kitchen` (already written).
  `ShoppingCart` is already in core.

**Recipes tabs**
- *Recipes*:
  - Search, tag chips, and sort (newest, cost per portion, protein, kcal, time, rating).
  - One card per recipe: short title (else title), style, cost per portion, kcal, protein, salt with
    its colour, veg dots, total time and rating.
  - FAB *Add recipe*.
  - Empty state: "No recipes yet" with *Add the starter recipes* and *Paste a recipe*.
- *Compare*:
  - A table with one row per recipe: cost, kcal, protein, fat, saturates, carbs, sugars, fibre, salt,
    veg, time and rating.
  - Sortable by any column. The best in each column is marked, and coloured values carry their
    colour.
  - The first column is pinned while the rest scroll sideways. A switch sets *Count optional extras*.
- *Shopping*: §11.6.
- *Ingredients*: grouped by shelf, with search; each shows its price with source and date, flagged
  *old*. FAB *Add ingredient*.

**Recipe** screen
- **Header**: title, tagline, style, times and time note, served with, and the number chips.
- **Actions**: *Cook* (the cooking view) and *Add to shopping list* (asks for servings).
- **Ingredients**: grouped as written, with a servings stepper that scales them (§11.2) and an
  *optional* tag.
- **Method**: each step's title, time chip, paragraphs, and cue and rescue boxes.
- Then the kit (equipment), tips, and storage ("Freezing and storing").
- **Numbers**:
  - Cost: per portion, whole dish, from an empty cupboard, and how many prices are old.
  - The nutrition label: per portion with % RI and colours, with a per-100 g toggle.
  - Veg, the breakdown chips, rating (stars) and notes (editable).
- **Menu**: *Edit* (the review screen again), *Share as text* (the paste format), *Archive*.

**Cooking** view
- Large text, with the screen kept on (`FLAG_KEEP_SCREEN_ON` while it is open) and its own servings
  stepper.
- Ingredients and steps can be ticked: tap a row; a ticked step greys out.
- *Done cooking* clears the ticks. Ticks survive leaving and coming back.

**Add recipe**
- A paste box (with a *Paste* button), or *Type it* (empty review).
- Then the review (§11.4).
- **Shared text**: if it has an "Ingredients" heading line (case-insensitive, `#` optional) and
  either numbered lines or a "Method"/"Steps"/"Instructions" heading, it opens the recipe review in
  the Recipes section instead of the receipt review.

**Recipe settings** (`Route.RecipeSettings`, from the Recipes tab's top bar)
- *Count optional extras*.
- *Look up barcodes online*, with the explanation.
- *Add the starter recipes*.
- *Import recipes (JSON)*.

### 11.9 Order of work
**Status (2026-10-18)**: F0 to F5 are built and CI run 63 is green: 387 of 387 device tests, core and app JVM
tests. The APK is build 63. Not tested on a device: the photo flows (label, barcode, the Open Food Facts call);
their parsing is covered by `NutritionLabelTest`, `OpenFoodFactsTest` and `ScanRulesTest`.

Each package is one agent, reviewed and pushed by the orchestrator with CI green before the next
depends on it.

**F0 (done)**: the two failing device tests in `StatementImportChecksTest` (data and ui; run 55) are
fixed.

**F1 core** (`core/.../recipes/`)
- Everything in §11.2 and §11.4 to §11.7 except `NutritionLabel` and `OpenFoodFacts`. That is:
  `Quantities`, `IngredientLines` (parse, resolve, scaleText), `RecipeText`, `RecipeSteps`,
  `IngredientMatching`, `RecipeMaths`, `TrafficLights`, `Prices`, `ShoppingList`, and `RecipeBundle`
  with the starter book.
- `PurchasedItem.weightGrams`.
- **Tests**: the checkpoint table, every starter line, round-trips of the paste format for all four
  recipes, scaling texts, the rounding case (braise), the pulses cap, the traffic-light boundaries,
  prices from receipts (weighed, multi-quantity, discounted, free, old) and the shopping list.

**F2 app data**
- Entities, `FoodDao`, `@AutoMigration(2, 3)` and the repositories, including a starter-book import
  (`RecipeRepository.importBundle`).
- Backup version 3 in core with tests, plus the settings keys.
- `ReceiptMapping` filling `weightGrams`.
- Device tests: `MigrationTest` 2 → 3, `data/FoodRepositoryTest` (import the starter book and read
  back the checkpoint numbers through the repositories) and a backup round trip with food data.

**F3 app UI**
- The section switch and Recipes bottom bar.
- Recipes, Recipe, Cooking, Add recipe → review, Ingredients and the ingredient editor (typed
  values, portions, product link picker), Compare, Shopping, and Recipe settings.
- Device tests with screenshots: switching sections both ways and Back leaving the app from Recipes;
  adding the starter recipes and reading the stir-fry card (£3.09, 804 kcal); scaling to 3; the
  cooking view ticks; pasting the chilli's text and saving it; compare sorting; building a shopping
  list from two recipes.

**F4 receipts link**: product link in the editor and prices from receipts (`Prices` with
`PurchasedItem`), with a device test: a receipt with the linked product changes the recipe's cost.

**F5 labels and barcodes**
- `NutritionLabel` and `OpenFoodFacts` in core, with tests.
- The label photo flow, barcode scanning, and the Open Food Facts setting with the INTERNET
  permission.

### 11.10 Questions answered (2026-10-17)
1. **Navigation**: a top-left switch between Budgeting and Recipes; Recipes has its own bottom tabs.
2. **Open Food Facts contact**: the project page, never an email address.
3. **Budget link**: none in v1.

### 11.11 Additions (asked for 2026-10-18)
Six changes the user asked for once the section was built. Room goes to **version 4** with
`@AutoMigration(3, 4)`: two nullable columns on `ingredients` and one new table, nothing else. Backups go to
**version 4**; versions 1 to 3 still restore.

**1. Veg: total and effective portions** (`RecipeMaths`, `VegSummary`)
- The recipe's *5 a day* card shows two numbers per portion:
  - **In total**: every `VEG` and `PULSE` gram ÷ servings ÷ 80, with no cap.
  - **Towards your 5 a day**: the same, but beans and lentils count once at most (the rule in §11.2). This
    is the number the cards, Compare and the checkpoints already use (`RecipeNumbers.veg`).
- Under them, one row per counted veg or pulse ingredient with its portions, largest first. The pulses
  are summed in one row ("Beans and lentils: 1.5 portions, counted as 1") when they pass the cap.
- `RecipeNumbers.vegSummary = VegSummary(total, effective, veg, pulses, pulsesCounted, items)`, where
  `items` is `VegItem(ingredientId, kind, portions)`. `RecipeNumbers.veg` stays as it is.

**2. Sugars and added sugars** (`Nutrient.ADDED_SUGARS`)
- A ninth nutrient, **added sugars** per 100 g or ml: sugar, honey and syrups put into a food. It is
  shown under "of which sugars" as "of which added sugars", against the NHS limit for adults of **30 g a
  day** (worded like fibre: "12% of the 30 g a day limit"). It has no traffic light; the FSA colours stay
  on total sugars.
- **Unknown values**: UK labels don't list added sugars, so most ingredients won't have one.
  - An ingredient whose added sugars are unknown counts as 0 when its sugars are 0, or when it is on the
    Fruit & veg shelf.
  - Otherwise it is unknown, and only the added-sugars row says "at least". The "at least" note for the
    other eight nutrients ignores added sugars.
  - `NutritionSummary.addedSugarsUnknown` lists those ingredients.
- **Where it is set**:
  - The ingredient editor has an *Added sugars* field after *Sugars*.
  - A label photo reads an "added sugars" or "free sugars" row if there is one; it is matched before
    "sugars".
  - The Open Food Facts lookup reads `added-sugars_100g`.
  - The bundle's `per100` has `addedSugars`.
- **Starter book**: added sugars are written out for all 40 ingredients.
  - Honey 82.4, oyster sauce 22, light soy sauce 10, stock cubes 3, streaky bacon 0.2.
  - Every other ingredient is 0: their sugars are natural (tomatoes, onions, spices).
  - The sauces' values are their label sugars, because sugar is in their ingredients list. So they are
    upper bounds.
- **Compare** gets an *Added sugars* column. The Recipes sort is unchanged.
- **Data**: column `ingredients.added_sugars REAL` (null = unknown); `Nutrition.addedSugars`;
  `NutrientTotals.addedSugars`; `ReferenceIntakes.ADDED_SUGARS_LIMIT = 30.0`.

**3. Timers** (`StepTimers` in core; `CookingTimers` in the app)
- **Durations in a step**: `StepTimers.of(step)` finds every duration in the step's time text and body.
  - Accepted forms: "30 sec", "2 min", "10–12 min", "about 20 minutes", "1 hr 15 min", "1½ hours".
  - A range starts at its **lower** end, because the step's "Ready when:" cue decides when it is done.
    The chip still shows the range as written.
  - "every 5 min" is skipped: it is a reminder, not a timer.
  - The step's own time comes first, then the body's durations in reading order. A duration equal to
    one already listed is dropped.
  - Each timer is `StepTimer(label, seconds, maxSeconds)`.
- **On the recipe screen (before *Cook*) and in the cooking view (after it)**:
  - Each step shows its timers as chips with a timer icon. A tap starts that timer.
  - A custom *Timer* button asks for minutes.
  - Running timers show in a bar at the bottom of both screens: the step title, the time left counting
    down, and *Cancel*.
- **When a timer ends**:
  - On any screen of the app, a dialog says "Time's up: Simmer (Beef, Bean & Lentil Chilli)" with
    *Dismiss* and *1 more minute*.
  - The phone's alarm sound plays if the phone's clock isn't also timing it.
- **On the phone's clock too**: with *Start timers on the phone's clock* on (Recipe settings, on by
  default), starting a timer also sends `AlarmClock.ACTION_SET_TIMER`:
  - extras: `EXTRA_LENGTH` (seconds), `EXTRA_MESSAGE` ("Simmer · Chilli") and `EXTRA_SKIP_UI = true`.
  - The Clock app then rings even if this app is closed.
  - It needs `com.android.alarm.permission.SET_ALARM`, a normal permission that is granted on install.
  - Limits:
    - The phone's timer can't be cancelled from here. The app says so when *Cancel* is pressed on a
      timer the phone also has.
    - If no app handles the intent, the app says so and its own timer still runs.
- **App timers**:
  - They live in `CookingTimers` (in `AppContainer`, app scope), so they keep running between the
    recipe screen, the cooking view and other tabs.
  - They are measured with `SystemClock.elapsedRealtime`. They are not saved, so they are lost if
    Android stops the app. That is what the phone's timer is for.

**4. What I have** (`owned_items`, `Pantry`)
- **Table** `owned_items`: `ingredient_id` (primary key) → `ingredients` CASCADE, `quantity` REAL, and
  `updated_at`.
  - A row means "I have this".
  - `quantity` is in the ingredient's base unit. Null means "some, not measured" (salt, oil, spices).
  - Core model: `OwnedItem(ingredientId, quantity, updatedAtEpochMillis)`.
- **Setting it**:
  - Each Ingredients row has a *How much I have* button. It opens a dialog with a number and a unit:
    g or ml, kg or l, packs (of the pack size), and each of the ingredient's portions ("each" = 150 g).
  - The dialog also has *Some (not measured)* and *None left* (which deletes the row).
  - `Pantry.units(ingredient)` gives those units and their factors.
- **The Ingredients tab**:
  - A filter row: *All*, *I have (N)* and *I don't have*.
  - Owned rows show "Have 450 g · about 3", or "Have some", and the date it was last set.
  - *I have* lists only those, still grouped by shelf and searchable.
- **Recipes**:
  - With anything owned, each recipe card says "You have 8 of 10 ingredients".
  - The Recipes filters (below) have *What I have*: *Any*, *I have everything* and *Missing 1 or 2*.
  - `Pantry.coverage(recipe, ingredients, owned, countOptional)` counts each distinct counted
    ingredient once. It is covered when it is owned with at least the recipe's quantity at its own
    servings, or owned with no quantity.
- **Shopping list** (`ShoppingList.build(…, owned)`):
  - Packs needed = ⌈max(0, need − owned quantity) ÷ pack size − 1e-9⌉.
  - The line says "you have 300 g". When that covers it, the line shows *Have enough* and costs nothing.
  - Owned with no quantity changes nothing but says "you have some".
  - *I have it* still works as before.
- **Not done**: taking what a recipe used off what is owned after cooking. Quantities change only when
  the user sets them.

**5. Recipe filters** (`RecipeFacets` in core)
- **The controls**: under the search box, a row of dropdown chips.
  - *Protein*, *Carb*, *Method*, *Time*, *What I have*, *Tags* and *Sort*.
  - A chip shows its choice ("Chicken ✕") while it filters, and *Clear filters* resets them all.
  - Options list only what the recipes have, each with its count.
  - Tags are a multi-choice list. The others choose one.
  - The tag chips row is gone, replaced by the *Tags* dropdown.
- **Protein and carb** come from the recipe's counted, non-optional lines through each ingredient's
  **food group**. `FoodGroups.of(ingredient)` is the ingredient's own choice, else a guess from its name:
  - **Groups**:
    - Protein: Chicken, Turkey, Beef, Pork (bacon, ham, sausages, chorizo), Lamb, Fish, Prawns &
      seafood, Eggs, Beans & lentils, and Meat-free (tofu, Quorn, tempeh, seitan, paneer, halloumi).
    - Carb: Rice, Pasta, Noodles, Potatoes (sweet potatoes too), Bread & wraps, and Couscous & grains.
  - **Guessing**: the name, without brackets, as lower-case singular words. The first match wins:
    1. "stock", "cube", "gravy", "bouillon", "sauce", "paste", "powder", "seasoning", "oil",
       "vinegar", "wine", "flour", "starch" or "extract" → none.
    2. A meat-free word (or "vegetarian", "vegan", "plant") → Meat-free.
    3. The carbs: noodles before rice ("rice noodles"), then pasta shapes, potatoes, bread and wraps,
       grains.
    4. Fish and seafood.
    5. Chicken, turkey, lamb, beef (beef, steak, brisket), pork (pork, bacon, ham, gammon, sausage,
       chorizo, pancetta, lardons, salami). Then plain "mince", "meatballs" or "burgers" → Beef.
    6. "egg" → Eggs.
    7. A `PULSE` ingredient → Beans & lentils.
  - **Override**: the ingredient editor's *Counts as* menu: *Automatic (Chicken)*, any group, or
    *Neither*. Column `ingredients.food_group TEXT`: null = automatic, `NONE` = neither.
  - **Extra protein choice**: *No meat or fish*. A recipe qualifies when no line is on the Meat shelf,
    no line's group is a meat or fish, and no line's name says chicken, beef, pork, lamb, turkey, fish,
    anchovy or prawn ("chicken stock" counts as meat here).
- **Method** (`CookMethod`): Hob, Oven, Air fryer, Slow cooker, Pressure cooker, Microwave, Grill and
  No cook.
  - **From the Style line first** ("Hob · one pot", "Oven · traybake"), which the user can edit.
  - **Only if the style names none**, from the kit and the step text:
    - Oven: "oven", "traybake", "roast", "bake", "gas mark", "°C".
    - Hob: "hob", "pan", "wok", "saucepan", "simmer", "boil", "fry" (not "air fry"), "sauté", "sear".
    - The others by their own names.
  - A recipe can have several methods.
- **Time**: total time (prep + cook) of at most 20, 30, 45, 60, 90 or 120 minutes. Recipes with no time
  don't match a time filter.
- **Combining**: all chosen filters must match, as well as the search.
  - `RecipeCard` gains `proteins`, `carbs`, `methods`, `meatFree`, `ingredientCount` and `missingCount`.
  - `RecipeFilters` holds the choices. `RecipeListMapping.filter(cards, query, filters)` applies them.

**6. Pasting a recipe copied from a Claude chat** (`RecipeText.parse`)
- **The flow**: the user presses Claude's copy button under a recipe, then *Add recipe* → *Paste*. The
  clipboard holds that answer as Markdown.
  - *Paste* now reads it straight away and opens the review.
  - *Read recipe* stays, for typed text.
  - Text shared from the Claude app goes the same way (`looksLikeRecipe`).
- **What the reader now accepts** (Markdown as Claude writes it in chat):
  - **Chatter**: an opening line such as "Here's a…" or "Sure!" before the title is skipped. Closing
    lines such as "Enjoy!" or "Let me know if…" are skipped too.
  - **Title**: a heading of any level before the first section, or a line that is all bold. It replaces a
    plain first line.
  - **Tagline**: the first plain sentence after the title becomes the tagline (*About*), if there is none.
    A "**Label:** value" line it doesn't know is skipped.
  - **Section headings**: at any level, bold (`**Ingredients**`), or a short line ending with ":". They
    can carry extra words or emoji: "Ingredients (serves 4)", "🥘 Method", "Instructions:",
    "How to make it", "Tips & variations", "Storing and reheating", "Make ahead". "Nutrition" and "Cost"
    sections are skipped.
  - **Markdown inside lines**: `**bold**`, `*italic*`, `` `code` ``, `[links](…)` and "- [ ]" check
    boxes become plain text. A line of `---`, `***` or `___` ends the section it is in.
  - **Ingredient groups**: "**For the sauce:**", "### For the sauce" or "For the sauce:" inside
    Ingredients.
  - **Ingredient tables**: a Markdown table row (`| Beef mince | 500 g |`) becomes "500 g Beef mince",
    the cell with the amount first. Header and `|---|` rows are skipped.
  - **Steps**: "1. **Brown the mince (10 min):** body", "1. **Brown the mince.** body", "**Step 1: Brown
    the mince**" followed by its paragraphs, and bullets under Method. A step that is all bold is its
    title. Nested bullets are paragraphs of their step.
  - **Header facts**: "**Serves:** 4 | **Prep time:** 15 minutes | **Cook time:** 30 minutes",
    "Servings: 4", "Yield: 4 servings" and "Makes 12". Unknown parts of such a line ("Difficulty: easy")
    are ignored.
- **Copy**: the recipe screen's menu also has *Copy recipe*. It puts the paste format on the clipboard,
  so the recipe can be pasted into *Add recipe* (as a copy to change) or into a chat.
- **Tests**: `RecipeTextTest` gains chat-style fixtures written the way Claude answers. Each must read to
  the right title, servings, times, groups, lines and steps, with every starter recipe's paste-format
  round trip unchanged.

**Order of work (F6)**
- **Core first, tested locally**:
  - `VegSummary`, `ADDED_SUGARS`, `StepTimers`, `FoodGroups`, `CookMethods`, `RecipeFacets`, `Pantry`.
  - The `ShoppingList` owned quantities and the `RecipeText` reader.
  - Bundle `addedSugars` and the starter book's values; backup version 4.
- **Then the app**:
  - Room 4: the entity columns, `OwnedItemEntity` and the DAO, and the repositories' `owned` calls.
  - The screens above, and the settings key `phoneTimers` (default true; device tests turn it off).
- **Device tests with screenshots**:
  - The veg card's two numbers on the chilli.
  - The added-sugars row on the stir-fry.
  - Starting the stir-fry's 30-second "Aromatics" timer and seeing it end.
  - Setting 1 kg of rice owned and filtering the Ingredients tab to *I have*.
  - The shopping list covering the rice.
  - Filtering the recipes by Beef, by Oven and by 45 minutes or less.
  - Pasting a chat-style recipe from the clipboard.
  - *Copy recipe* then pasting it back.
  - `MigrationTest` 3 → 4.
- **After the first green build**, commit `4.json` from `refs/ci/room-schemas`.
