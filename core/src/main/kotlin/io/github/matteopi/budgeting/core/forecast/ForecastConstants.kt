package io.github.matteopi.budgeting.core.forecast

/**
 * Tunable constants for the forecast engine. See `docs/PLAN.md` §5.8–§5.9 for how each one
 * is used.
 */
object ForecastConstants {
    /** Shrinkage weight: `w = n_r / (n_r + SHRINK_K)`. */
    const val SHRINK_K: Double = 4.0

    /** `n_r >= TRUSTED_WEEKS` => [DataSufficiency.TRUSTED] / [EstimateSource.DATA]. */
    const val TRUSTED_WEEKS: Int = 6

    /** `n_r` in `[THIN_WEEKS, TRUSTED_WEEKS)` => [DataSufficiency.THIN]; below => [DataSufficiency.TOO_THIN]. */
    const val THIN_WEEKS: Int = 2

    /** Minimum all-regime recent weeks needed before the all-regime fallback `F` is usable. */
    const val FALLBACK_MIN_WEEKS: Int = 4

    /** Window (most recent weeks) used for the per-regime median `m_r` and the all-regime `m_all`. */
    const val RECENT_WEEKS: Int = 12

    /** Prior weight, in "weeks" of evidence, for the day-of-week share Bayesian blend. */
    const val DOW_PRIOR_WEEKS: Double = 4.0

    /** Day-of-week shares are learned from at most this many of the most recent complete weeks. */
    const val DOW_WEEKS: Int = 26

    /** The history window itself looks back at most this many complete weeks. */
    const val MAX_HISTORY_WEEKS: Int = 52

    /** Tier 1 uses at most this many most-recent complete weeks for its flat median. */
    const val TIER1_WEEKS: Int = 8

    /** Minimum pool size (weeks) for the tier-3 whole-week bootstrap, per regime or overall. */
    const val BOOTSTRAP_MIN_POOL: Int = 4

    /** Multiplicative/lognormal parametric-fallback coefficient of variation for ordinary estimates. */
    const val PARAMETRIC_CV: Double = 0.5

    /** Parametric-fallback CV used instead when the estimate came from a REPLACE prediction. */
    const val PARAMETRIC_CV_REPLACE: Double = 0.35

    /** Below this |mean| (in pence) the multiplicative bootstrap is considered unstable; use parametric. */
    const val MULTIPLICATIVE_MEAN_FLOOR_PENCE: Long = 100L
}
