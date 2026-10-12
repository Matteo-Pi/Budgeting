package io.github.matteopi.budgeting.core.receipts

import java.util.Locale

/** Turns a till abbreviation into a stable key that the same product shares across receipts. */
object ProductNames {

    /** Own-brand prefixes, longest first so "SAINSBURYS" wins over a shorter prefix. */
    private val PREFIXES = listOf(
        "TS", "TESCO", "SAINSBURYS", "SAINSBURY S", "SAINSBURY", "JS", "MORRISONS", "MORRISON", "M",
    ).sortedByDescending { it.length }

    private val DROPPED = Regex("""['’`&]""")
    private val SEPARATORS = Regex("""[^A-Z0-9 .]""")
    private val STRAY_DOT = Regex("""(?<!\d)\.|\.(?!\d)""")
    private val SPACES = Regex("""\s+""")

    /**
     * Upper case; apostrophes and ampersands dropped ("SAINSBURY'S" -> "SAINSBURYS", "M&S" -> "MS"),
     * other punctuation replaced by spaces (a dot between digits stays: "1.5L"); spaces collapsed;
     * store prefixes such as "TS ", "TESCO ", "SAINSBURYS ", "JS ", "M ", "MORRISONS " removed from
     * the front (repeatedly, but never down to nothing). The result is a fixed point:
     * `normalise(normalise(x)) == normalise(x)`.
     */
    fun normalise(rawName: String): String {
        var s = DROPPED.replace(rawName.uppercase(Locale.UK), "")
        s = SEPARATORS.replace(s, " ")
        s = STRAY_DOT.replace(s, " ")
        s = SPACES.replace(s, " ").trim()
        var stripped = true
        while (stripped) {
            stripped = false
            for (prefix in PREFIXES) {
                if (s.startsWith("$prefix ") && s.length > prefix.length + 1) {
                    s = s.substring(prefix.length + 1).trim()
                    stripped = true
                    break
                }
            }
        }
        return s
    }
}

/**
 * A learned mapping from a receipt abbreviation to a product the user named, e.g.
 * "TS SEMI SKMD MLK 2PT" at Tesco -> "Semi-skimmed milk 2 pints", Groceries.
 * [rawNameKey] is [ProductNames.normalise] of the raw name. Stored by the app; this is the pure view.
 */
data class ProductAlias(
    val store: Store,
    val rawNameKey: String,
    val productId: Long,
    val productName: String,
    val categoryId: Long? = null,
)

/** Looks up learned [ProductAlias]es. Pure: loading and saving them is the app's job. */
class Aliases(aliases: List<ProductAlias>) {

    // First alias wins for a repeated (store, key): callers decide precedence by list order.
    private val byStoreAndKey: Map<Pair<Store, String>, ProductAlias>
    private val byKey: Map<String, ProductAlias>

    init {
        val exact = LinkedHashMap<Pair<Store, String>, ProductAlias>()
        val any = LinkedHashMap<String, ProductAlias>()
        for (alias in aliases) {
            val key = ProductNames.normalise(alias.rawNameKey)
            exact.putIfAbsent(alias.store to key, alias)
            any.putIfAbsent(key, alias)
        }
        byStoreAndKey = exact
        byKey = any
    }

    /** The alias for [rawName] at [store] if there is one, else the same name at any other store. */
    fun suggest(rawName: String, store: Store): ProductAlias? {
        val key = ProductNames.normalise(rawName)
        return byStoreAndKey[store to key] ?: byKey[key]
    }

    fun suggest(item: DraftItem, store: Store): ProductAlias? = suggest(item.rawName, store)

    companion object {
        /** The alias to store after the user names [item]'s product at [store]. */
        fun learn(item: DraftItem, store: Store, productId: Long, productName: String, categoryId: Long?) =
            ProductAlias(store, ProductNames.normalise(item.rawName), productId, productName, categoryId)
    }
}
