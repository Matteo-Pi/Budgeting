package io.github.matteopi.budgeting.core.importer

import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.MerchantRule
import java.util.Locale

/** Suggests a category for a normalised merchant key: a saved [MerchantRule] first, then keywords. */
object CategorySuggester {

    // Ordered: "Eating out" (UBER EATS) is checked before "Transport" (UBER) so the more
    // specific match wins even without the explicit exclusion below.
    private val KEYWORDS: Map<String, List<String>> = linkedMapOf(
        "Groceries" to listOf(
            "TESCO", "SAINSBURY", "ASDA", "ALDI", "LIDL", "MORRISONS", "CO-OP", "WAITROSE", "M&S",
        ),
        "Eating out" to listOf(
            "UBER EATS", "DELIVEROO", "JUST EAT", "MCDONALD", "GREGGS", "PRET", "STARBUCKS", "COSTA",
            "NANDO", "KFC", "SUBWAY",
        ),
        "Transport" to listOf(
            "TFL", "TRAINLINE", "NATIONAL RAIL", "STAGECOACH", "ARRIVA", "FIRST BUS", "UBER", "BOLT",
        ),
        "Shopping" to listOf("AMAZON", "ASOS", "PRIMARK", "H&M", "ZARA", "ARGOS"),
        "Subscriptions" to listOf("SPOTIFY", "NETFLIX", "APPLE.COM", "DISNEY", "YOUTUBE", "AMAZON PRIME"),
        "Health" to listOf("BOOTS", "SUPERDRUG", "PHARMACY"),
        "Student loan" to listOf("SLC", "STUDENT LOANS"),
        "Wages" to listOf("SALARY", "WAGES", "PAYROLL"),
    )

    fun suggest(merchantKey: String, rules: List<MerchantRule>, categories: List<Category>): Long? {
        rules.firstOrNull { it.merchantKey == merchantKey }?.let { return it.categoryId }

        val key = merchantKey.uppercase(Locale.UK)
        for ((categoryName, keywords) in KEYWORDS) {
            val category = categories.firstOrNull { it.name == categoryName && !it.archived } ?: continue
            for (keyword in keywords) {
                if (categoryName == "Transport" && keyword == "UBER" && key.contains("UBER EATS")) continue
                if (key.contains(keyword)) return category.id
            }
        }
        return null
    }
}
