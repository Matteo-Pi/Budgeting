package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.IngredientAliasRecord
import io.github.matteopi.budgeting.core.recipes.IngredientMatching
import io.github.matteopi.budgeting.core.recipes.Portion
import io.github.matteopi.budgeting.data.db.dao.FoodDao
import io.github.matteopi.budgeting.data.db.entities.IngredientAliasEntity
import io.github.matteopi.budgeting.domain.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Ingredients, their portions and the names learned for them (docs/PLAN.md §11.3, §11.5).
 * Persistence only: prices, nutrition and the recipe maths are in `core.recipes`. Nothing here
 * touches the budget, so the widget is not refreshed.
 *
 * Ingredients are archived, never deleted, while a recipe line points at them ([setArchived],
 * [deleteIfUnused]). The reads include archived ingredients; each has [Ingredient.archived] set.
 */
class IngredientRepository(
    private val foodDao: FoodDao,
    private val clock: Clock,
) {
    /** Every ingredient with its portions, by name (case ignored). */
    fun observeAll(): Flow<List<Ingredient>> = foodDao.observeIngredients().map { list -> list.map { it.toModel() } }

    fun observe(id: Long): Flow<Ingredient?> = foodDao.observeIngredient(id).map { it?.toModel() }

    suspend fun get(id: Long): Ingredient? = foodDao.getIngredient(id)?.toModel()

    /** The ingredient with this barcode (digits, as stored), if any. */
    suspend fun findByBarcode(barcode: String): Ingredient? = foodDao.findIngredientByBarcode(barcode)?.toModel()

    /**
     * Saves [ingredient] and replaces its portions, in one transaction; returns its id. An ingredient
     * with id 0 is added (with [Ingredient.createdAtEpochMillis], or now if that is 0); any other id
     * updates that ingredient, which must exist (a 0 creation time keeps the stored one).
     *
     * Tidied on the way in: the name is trimmed (and must not be blank), the pack size must be above
     * zero, blank text becomes null (barcode, store, shop name, link), and portions with a blank name
     * or no amount are dropped, a repeated portion name (ignoring case) keeping its first amount.
     * A barcode already on another ingredient fails with a constraint exception.
     */
    suspend fun save(ingredient: Ingredient): Long {
        val name = ingredient.name.trim()
        require(name.isNotEmpty()) { "An ingredient needs a name" }
        require(ingredient.packSize > 0.0) { "An ingredient needs a pack size above zero" }
        val seen = HashSet<String>()
        val portions = ingredient.portions.mapNotNull { portion ->
            val portionName = portion.name.trim()
            if (portionName.isEmpty() || !(portion.amount > 0.0) || !seen.add(portionName.lowercase())) {
                null
            } else {
                Portion(portionName, portion.amount)
            }
        }
        val createdAt = when {
            ingredient.createdAtEpochMillis > 0 -> ingredient.createdAtEpochMillis
            ingredient.id != 0L -> foodDao.getIngredient(ingredient.id)?.ingredient?.createdAt ?: clock.nowMillis()
            else -> clock.nowMillis()
        }
        val tidy = ingredient.copy(
            name = name,
            packLabel = ingredient.packLabel.trim(),
            store = ingredient.store.blankToNull(),
            productName = ingredient.productName.blankToNull(),
            sourceUrl = ingredient.sourceUrl.blankToNull(),
            barcode = ingredient.barcode.blankToNull(),
            note = ingredient.note.trim(),
            createdAtEpochMillis = createdAt,
            portions = portions,
        )
        return foodDao.saveIngredient(tidy.toEntity(), tidy.toPortionEntities())
    }

    /** Hides the ingredient from pickers and matching (or brings it back). Recipe lines keep pointing at it. */
    suspend fun setArchived(id: Long, archived: Boolean) {
        foodDao.setIngredientArchived(id, archived)
    }

    /**
     * Deletes the ingredient, its portions, its aliases and its shopping list entries, but only if no
     * recipe line points at it. Returns false (and changes nothing) when one does: archive it instead.
     */
    suspend fun deleteIfUnused(id: Long): Boolean = foodDao.deleteIngredientIfUnused(id)

    /** Links the ingredient to a receipts product (so its price comes from what was paid), or unlinks it with null. */
    suspend fun setProduct(ingredientId: Long, productId: Long?) {
        foodDao.setIngredientProduct(ingredientId, productId)
    }

    // ---- Aliases ----------------------------------------------------------------------------

    fun observeAliases(): Flow<List<IngredientAliasRecord>> =
        foodDao.observeIngredientAliases().map { list -> list.map { it.toModel() } }

    /** The learned names as `IngredientMatching.match` wants them: name key to ingredient id. */
    fun observeAliasMap(): Flow<Map<String, Long>> =
        foodDao.observeIngredientAliases().map { list -> list.associate { it.nameKey to it.ingredientId } }

    suspend fun aliasMap(): Map<String, Long> = foodDao.getIngredientAliases().associate { it.nameKey to it.ingredientId }

    /**
     * Remembers that [name] means [ingredientId], unless that name (as `IngredientMatching.nameKey`)
     * already means an ingredient: an existing mapping is never overwritten. Returns whether a new
     * alias was stored.
     */
    suspend fun learnAlias(name: String, ingredientId: Long): Boolean {
        val key = IngredientMatching.nameKey(name)
        if (key.isEmpty()) return false
        return foodDao.insertIngredientAliasIfFree(IngredientAliasEntity(nameKey = key, ingredientId = ingredientId)) != -1L
    }

    /** The user chose: [name] means [ingredientId] from now on, replacing whatever it meant before. */
    suspend fun mapAlias(name: String, ingredientId: Long) {
        val key = IngredientMatching.nameKey(name)
        if (key.isEmpty()) return
        foodDao.mapIngredientAlias(key, ingredientId)
    }

    suspend fun removeAlias(id: Long) {
        foodDao.deleteIngredientAlias(id)
    }
}

/** Trimmed text, or null when nothing is left. */
internal fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
