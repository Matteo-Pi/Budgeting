package io.github.matteopi.budgeting.core.recipes

/** What to do with a bundle ingredient that matches one the user already has (docs/PLAN.md §11.7). */
enum class MergeChoice {
    /** Point the bundle's recipe lines at the existing ingredient. The default. */
    USE_EXISTING,

    /** Add the bundle's ingredient as a new one next to the existing one. */
    IMPORT_AS_NEW,
}

/** A bundle ingredient and the existing ingredient it matches by name or learned alias. */
data class IngredientMerge(
    /** The ingredient's key in the bundle file. */
    val key: String,
    /** The bundle's ingredient, with its provisional id. */
    val bundle: Ingredient,
    val existing: Ingredient,
)

/** Which ingredients of a bundle the user already has, for the merge step of an import. */
object BundleMerge {

    /**
     * Pairs each ingredient of [models] with the ingredient in [existing] that has the same name once
     * normalised ([IngredientMatching.nameKey]) or a learned alias for it ([aliases]: name key to
     * ingredient id). Only an alias or an exact name counts, never a close one, and archived
     * ingredients are never matched. In file order; ingredients with no match are left out.
     */
    fun matches(models: BundleModels, existing: List<Ingredient>, aliases: Map<String, Long>): List<IngredientMerge> {
        val keyById = models.ingredientKeys.entries.associate { (key, id) -> id to key }
        val existingById = existing.associateBy { it.id }
        return models.ingredients.mapNotNull { bundleIngredient ->
            val match = IngredientMatching.match(bundleIngredient.name, existing, aliases)
            val id = when (match.kind) {
                MatchKind.ALIAS, MatchKind.EXACT -> match.ingredientId
                MatchKind.CLOSE, MatchKind.SUGGESTIONS, MatchKind.NONE -> null
            }
            val key = keyById[bundleIngredient.id]
            val found = id?.let { existingById[it] }
            if (key != null && found != null) IngredientMerge(key, bundleIngredient, found) else null
        }
    }
}
