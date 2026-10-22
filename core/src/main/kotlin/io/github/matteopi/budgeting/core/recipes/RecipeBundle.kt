package io.github.matteopi.budgeting.core.recipes

import io.github.matteopi.budgeting.core.model.Money
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * The recipe bundle file format (docs/PLAN.md §11.7): ingredients and recipes as JSON, linked by
 * keys. Enum-like fields are strings in the file and checked by RecipeBundle.decode.
 */

@Serializable
data class BundlePack(
    val label: String = "",
    val size: Double,
    val pricePence: Long? = null,
    val priceDate: String? = null,
    val store: String? = null,
)

@Serializable
data class BundlePer100(
    val kcal: Double? = null,
    val fat: Double? = null,
    val saturates: Double? = null,
    val carbs: Double? = null,
    val sugars: Double? = null,
    val fibre: Double? = null,
    val protein: Double? = null,
    val salt: Double? = null,
    /** Sugar, honey and syrups put into the food (docs/PLAN.md §11.11). */
    val addedSugars: Double? = null,
)

@Serializable
data class BundlePortion(val name: String, val amount: Double)

@Serializable
data class BundleIngredient(
    val key: String,
    val name: String,
    /** `G` or `ML`. */
    val unit: String,
    val shelf: String = "OTHER",
    val pack: BundlePack,
    /** The shop's name for it. */
    val product: String? = null,
    val url: String? = null,
    val per100: BundlePer100 = BundlePer100(),
    val nutritionSource: String = "TYPED",
    /** `NONE`, `VEG` or `PULSE`. */
    val veg: String = "NONE",
    val portions: List<BundlePortion> = emptyList(),
    val note: String = "",
    /** What the recipe filters count it as (a [FoodGroup] name, `NONE` for neither); left out, it is guessed. */
    val group: String? = null,
    /** Other names it goes by, for the built-in food list (docs/PLAN.md §11.12); left out of files the app writes. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val aliases: List<String> = emptyList(),
)

@Serializable
data class BundleLine(
    val group: String? = null,
    val text: String,
    /** The key of an ingredient in the same bundle, or null for a line that isn't counted. */
    val ingredient: String? = null,
    /** In the ingredient's base unit. */
    val quantity: Double? = null,
    val optional: Boolean = false,
)

@Serializable
data class BundleStep(
    val title: String? = null,
    val time: String? = null,
    val body: String,
)

@Serializable
data class BundleRecipe(
    val key: String,
    val title: String,
    val shortTitle: String? = null,
    val tagline: String? = null,
    val style: String? = null,
    val serves: Int,
    val prepMins: Int? = null,
    val cookMins: Int? = null,
    val timeNote: String? = null,
    val servedWith: String? = null,
    val tags: List<String> = emptyList(),
    val rating: Int = 0,
    val notes: String = "",
    /** `MINE`, `CLAUDE` or `IMPORTED`. */
    val source: String = "IMPORTED",
    /** The date it was added, ISO (2026-10-15). */
    val added: String? = null,
    val lines: List<BundleLine> = emptyList(),
    val steps: List<BundleStep> = emptyList(),
    val tips: List<String> = emptyList(),
    val equipment: List<String> = emptyList(),
    val storage: List<String> = emptyList(),
)

/** The outcome of [RecipeBundle.decode]. */
sealed class BundleDecodeResult {
    data class Success(val bundle: RecipeBundle) : BundleDecodeResult()

    /** Human-readable problems; nothing was read. */
    data class Failure(val problems: List<String>) : BundleDecodeResult()
}

/**
 * The bundle's ingredients and recipes as app models with provisional ids: ingredients 1, 2, … and
 * recipes 1, 2, … in file order, each recipe line pointing at the ingredient's provisional id.
 * [ingredientKeys] and [recipeKeys] map a file key to its provisional id, so a merge can swap in an
 * existing ingredient ("Use existing") before saving.
 */
data class BundleModels(
    val ingredients: List<Ingredient>,
    val recipes: List<Recipe>,
    val ingredientKeys: Map<String, Long>,
    val recipeKeys: Map<String, Long>,
)

@Serializable
data class RecipeBundle(
    val format: String = "",
    val version: Int = 0,
    val about: String = "",
    val ingredients: List<BundleIngredient> = emptyList(),
    val recipes: List<BundleRecipe> = emptyList(),
) {

    /** The bundle as models; see [BundleModels]. Meant for a bundle that [decode] accepted. */
    fun toModels(): BundleModels {
        val ingredientKeys = LinkedHashMap<String, Long>()
        val models = ingredients.mapIndexed { index, source ->
            val id = index + 1L
            ingredientKeys.putIfAbsent(source.key, id)
            val pack = source.pack
            Ingredient(
                id = id,
                name = source.name,
                unit = enumOf<BaseUnit>(source.unit) ?: BaseUnit.G,
                packSize = pack.size,
                packLabel = pack.label,
                price = pack.pricePence?.let { Money(it) },
                priceDate = pack.priceDate?.let { dateOrNull(it) },
                store = pack.store,
                productName = source.product,
                sourceUrl = source.url,
                per100 = Nutrition(
                    kcal = source.per100.kcal,
                    fat = source.per100.fat,
                    saturates = source.per100.saturates,
                    carbs = source.per100.carbs,
                    sugars = source.per100.sugars,
                    fibre = source.per100.fibre,
                    protein = source.per100.protein,
                    salt = source.per100.salt,
                    addedSugars = source.per100.addedSugars,
                ),
                nutritionSource = enumOf<NutritionSource>(source.nutritionSource) ?: NutritionSource.TYPED,
                vegKind = enumOf<VegKind>(source.veg) ?: VegKind.NONE,
                shelf = enumOf<Shelf>(source.shelf) ?: Shelf.OTHER,
                note = source.note,
                portions = source.portions.map { Portion(it.name, it.amount) },
                foodGroup = source.group?.let { enumOf<FoodGroup>(it) },
            )
        }

        val recipeKeys = LinkedHashMap<String, Long>()
        val recipeModels = recipes.mapIndexed { index, source ->
            val id = index + 1L
            recipeKeys.putIfAbsent(source.key, id)
            val added = source.added?.let { dateOrNull(it) }?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: 0L
            Recipe(
                id = id,
                title = source.title,
                shortTitle = source.shortTitle,
                tagline = source.tagline,
                style = source.style,
                serves = source.serves,
                prepMins = source.prepMins,
                cookMins = source.cookMins,
                timeNote = source.timeNote,
                servedWith = source.servedWith,
                tags = source.tags,
                rating = source.rating,
                notes = source.notes,
                source = enumOf<RecipeSource>(source.source) ?: RecipeSource.IMPORTED,
                createdAtEpochMillis = added,
                updatedAtEpochMillis = added,
                lines = source.lines.map { line ->
                    RecipeLine(
                        group = line.group,
                        text = line.text,
                        ingredientId = line.ingredient?.let { ingredientKeys[it] },
                        quantity = line.quantity,
                        optional = line.optional,
                    )
                },
                steps = source.steps.map { RecipeStep(StepKind.STEP, it.title, it.time, it.body) } +
                    source.tips.map { RecipeStep(StepKind.TIP, body = it) } +
                    source.equipment.map { RecipeStep(StepKind.EQUIPMENT, body = it) } +
                    source.storage.map { RecipeStep(StepKind.STORAGE, body = it) },
            )
        }
        return BundleModels(models, recipeModels, ingredientKeys, recipeKeys)
    }

    companion object {
        const val FORMAT = "budgeting-recipes"
        const val CURRENT_VERSION = 1

        /** The classpath location of the starter recipe book. */
        const val STARTER_RESOURCE = "/recipes/starter-book.json"

        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun encode(bundle: RecipeBundle): String = json.encodeToString(serializer(), bundle)

        /**
         * Reads a bundle file. Unknown fields are ignored. Returns the bundle, or every problem found:
         * not JSON, the wrong format, a newer version, a duplicate or missing key, a bad unit or other
         * value, or a line pointing at an ingredient key that isn't in the file.
         */
        fun decode(text: String): BundleDecodeResult {
            val bundle = try {
                json.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                return BundleDecodeResult.Failure(listOf(describe(e)))
            } catch (e: IllegalArgumentException) {
                return BundleDecodeResult.Failure(listOf("This file isn't valid JSON and can't be read."))
            }
            if (bundle.format != FORMAT) {
                return BundleDecodeResult.Failure(listOf("This doesn't look like a Budgeting recipe book."))
            }
            if (bundle.version > CURRENT_VERSION) {
                return BundleDecodeResult.Failure(
                    listOf("This recipe book was made with a newer version of the app and can't be read here."),
                )
            }
            if (bundle.version < 1) {
                return BundleDecodeResult.Failure(listOf("This recipe book has an unrecognised version."))
            }
            val problems = validate(bundle)
            return if (problems.isEmpty()) BundleDecodeResult.Success(bundle) else BundleDecodeResult.Failure(problems)
        }

        /** The starter recipe book that ships with the app (4 recipes, 40 ingredients). */
        fun starter(): RecipeBundle {
            val stream = RecipeBundle::class.java.getResourceAsStream(STARTER_RESOURCE)
                ?: throw IllegalStateException("The starter recipe book is missing from the app.")
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return when (val result = decode(text)) {
                is BundleDecodeResult.Success -> result.bundle
                is BundleDecodeResult.Failure ->
                    throw IllegalStateException("The starter recipe book is invalid: ${result.problems.joinToString(" ")}")
            }
        }

        private fun describe(e: SerializationException): String {
            val first = e.message.orEmpty().lineSequence().firstOrNull().orEmpty()
            return if (first.contains("required", ignoreCase = true) || first.contains("missing", ignoreCase = true)) {
                "Something the file needs is missing: $first"
            } else {
                "This file isn't valid JSON for a recipe book and can't be read."
            }
        }

        private inline fun <reified E : Enum<E>> enumOf(name: String): E? =
            enumValues<E>().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

        private fun dateOrNull(text: String): LocalDate? = try {
            LocalDate.parse(text.trim())
        } catch (e: DateTimeException) {
            null
        }

        private fun validate(bundle: RecipeBundle): List<String> {
            val problems = mutableListOf<String>()
            val keys = HashSet<String>()
            for (ingredient in bundle.ingredients) {
                val label = "Ingredient \"${ingredient.key}\""
                if (ingredient.key.isBlank()) problems += "An ingredient has no key."
                else if (!keys.add(ingredient.key)) problems += "$label appears more than once."
                if (ingredient.name.isBlank()) problems += "$label has no name."
                if (enumOf<BaseUnit>(ingredient.unit) == null) {
                    problems += "$label has the unit \"${ingredient.unit}\"; it must be G or ML."
                }
                if (enumOf<Shelf>(ingredient.shelf) == null) problems += "$label has an unknown shelf \"${ingredient.shelf}\"."
                if (enumOf<VegKind>(ingredient.veg) == null) {
                    problems += "$label has an unknown veg kind \"${ingredient.veg}\"; it must be NONE, VEG or PULSE."
                }
                ingredient.group?.let {
                    if (enumOf<FoodGroup>(it) == null) problems += "$label has an unknown group \"$it\"."
                }
                if (enumOf<NutritionSource>(ingredient.nutritionSource) == null) {
                    problems += "$label has an unknown nutrition source \"${ingredient.nutritionSource}\"."
                }
                if (!(ingredient.pack.size > 0.0) || ingredient.pack.size.isInfinite()) problems += "$label needs a pack size above zero."
                ingredient.pack.pricePence?.let { if (it < 0) problems += "$label has a negative price." }
                ingredient.pack.priceDate?.let { if (dateOrNull(it) == null) problems += "$label has the price date \"$it\"; use 2026-10-15." }
                for (portion in ingredient.portions) {
                    if (portion.name.isBlank() || !(portion.amount > 0.0)) problems += "$label has a portion without a name or an amount."
                }
                if (ingredient.aliases.any { it.isBlank() }) problems += "$label has a blank other name."
            }

            val recipeKeys = HashSet<String>()
            for (recipe in bundle.recipes) {
                val label = "Recipe \"${recipe.key}\""
                if (recipe.key.isBlank()) problems += "A recipe has no key."
                else if (!recipeKeys.add(recipe.key)) problems += "$label appears more than once."
                if (recipe.title.isBlank()) problems += "$label has no title."
                if (recipe.serves < 1) problems += "$label must serve at least 1."
                if (enumOf<RecipeSource>(recipe.source) == null) {
                    problems += "$label has an unknown source \"${recipe.source}\"; it must be MINE, CLAUDE or IMPORTED."
                }
                if (recipe.rating !in 0..5) problems += "$label has a rating outside 0 to 5."
                recipe.added?.let { if (dateOrNull(it) == null) problems += "$label has the date \"$it\"; use 2026-10-15." }
                recipe.lines.forEachIndexed { index, line ->
                    val ingredient = line.ingredient
                    if (ingredient != null && ingredient !in keys) {
                        problems += "$label, line ${index + 1} (\"${line.text}\") points at the ingredient \"$ingredient\", which isn't in the file."
                    }
                    line.quantity?.let { if (it < 0.0) problems += "$label, line ${index + 1} has a negative quantity." }
                }
            }
            return problems
        }
    }
}
