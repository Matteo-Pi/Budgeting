package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.recipes.BaseUnit
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.IngredientAliasRecord
import io.github.matteopi.budgeting.core.recipes.Nutrition
import io.github.matteopi.budgeting.core.recipes.NutritionSource
import io.github.matteopi.budgeting.core.recipes.Portion
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeLine
import io.github.matteopi.budgeting.core.recipes.RecipeSource
import io.github.matteopi.budgeting.core.recipes.RecipeStep
import io.github.matteopi.budgeting.core.recipes.Shelf
import io.github.matteopi.budgeting.core.recipes.ShoppingEntry
import io.github.matteopi.budgeting.core.recipes.ShoppingMark
import io.github.matteopi.budgeting.core.recipes.StepKind
import io.github.matteopi.budgeting.core.recipes.VegKind
import io.github.matteopi.budgeting.data.db.dao.IngredientWithPortions
import io.github.matteopi.budgeting.data.db.dao.RecipeWithParts
import io.github.matteopi.budgeting.data.db.entities.IngredientAliasEntity
import io.github.matteopi.budgeting.data.db.entities.IngredientEntity
import io.github.matteopi.budgeting.data.db.entities.IngredientPortionEntity
import io.github.matteopi.budgeting.data.db.entities.RecipeEntity
import io.github.matteopi.budgeting.data.db.entities.RecipeLineEntity
import io.github.matteopi.budgeting.data.db.entities.RecipeStepEntity
import io.github.matteopi.budgeting.data.db.entities.ShoppingEntryEntity
import io.github.matteopi.budgeting.data.db.entities.ShoppingMarkEntity
import java.time.LocalDate

/*
 * Entity <-> core model mapping for the food tables (docs/PLAN.md §11.3), in the style of
 * Mappers.kt: entities hold primitives (dates as epoch days, money as pence, enums as names), these
 * functions are the one place that translates. They change nothing about the data: tidying (trimming
 * names, dropping blank portions) is the repositories' job, so a backup restore reproduces its file.
 * An enum name this version doesn't know reads as the default instead of failing.
 */

private inline fun <reified E : Enum<E>> enumOrDefault(name: String, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default

// Ingredient

fun IngredientEntity.toModel(portions: List<IngredientPortionEntity> = emptyList()): Ingredient = Ingredient(
    id = id,
    name = name,
    unit = enumOrDefault(unit, BaseUnit.G),
    packSize = packSize,
    packLabel = packLabel,
    price = price?.let { Money(it) },
    priceDate = priceDate?.let { LocalDate.ofEpochDay(it) },
    store = store,
    productId = productId,
    productName = productName,
    sourceUrl = sourceUrl,
    barcode = barcode,
    per100 = Nutrition(
        kcal = kcal,
        fat = fat,
        saturates = saturates,
        carbs = carbs,
        sugars = sugars,
        fibre = fibre,
        protein = protein,
        salt = salt,
    ),
    nutritionSource = enumOrDefault(nutritionSource, NutritionSource.TYPED),
    vegKind = enumOrDefault(vegKind, VegKind.NONE),
    shelf = enumOrDefault(shelf, Shelf.OTHER),
    note = note,
    archived = archived,
    createdAtEpochMillis = createdAt,
    portions = portions.sortedBy { it.id }.map { Portion(it.name, it.amount) },
)

fun IngredientWithPortions.toModel(): Ingredient = ingredient.toModel(portions)

fun Ingredient.toEntity(): IngredientEntity = IngredientEntity(
    id = id,
    name = name,
    unit = unit.name,
    packSize = packSize,
    packLabel = packLabel,
    price = price?.pence,
    priceDate = priceDate?.toEpochDay(),
    store = store,
    productId = productId,
    productName = productName,
    sourceUrl = sourceUrl,
    barcode = barcode,
    kcal = per100.kcal,
    fat = per100.fat,
    saturates = per100.saturates,
    carbs = per100.carbs,
    sugars = per100.sugars,
    fibre = per100.fibre,
    protein = per100.protein,
    salt = per100.salt,
    nutritionSource = nutritionSource.name,
    vegKind = vegKind.name,
    shelf = shelf.name,
    note = note,
    archived = archived,
    createdAt = createdAtEpochMillis,
)

/** The portions as rows for ingredient [ingredientId] (0 for one that isn't saved yet; `saveIngredient` sets it). */
fun Ingredient.toPortionEntities(ingredientId: Long = id): List<IngredientPortionEntity> =
    portions.map { IngredientPortionEntity(ingredientId = ingredientId, name = it.name, amount = it.amount) }

// Ingredient alias

fun IngredientAliasEntity.toModel(): IngredientAliasRecord =
    IngredientAliasRecord(id = id, nameKey = nameKey, ingredientId = ingredientId)

fun IngredientAliasRecord.toEntity(): IngredientAliasEntity =
    IngredientAliasEntity(id = id, nameKey = nameKey, ingredientId = ingredientId)

// Recipe

private fun parseTags(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

private fun joinTags(tags: List<String>): String =
    tags.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

fun RecipeEntity.toModel(
    lines: List<RecipeLineEntity> = emptyList(),
    steps: List<RecipeStepEntity> = emptyList(),
): Recipe = Recipe(
    id = id,
    title = title,
    shortTitle = shortTitle,
    tagline = tagline,
    style = style,
    serves = serves,
    prepMins = prepMins,
    cookMins = cookMins,
    timeNote = timeNote,
    servedWith = servedWith,
    tags = parseTags(tags),
    rating = rating,
    notes = notes,
    source = enumOrDefault(source, RecipeSource.MINE),
    createdAtEpochMillis = createdAt,
    updatedAtEpochMillis = updatedAt,
    archived = archived,
    lines = lines.sortedWith(compareBy<RecipeLineEntity>({ it.position }, { it.id })).map { it.toModel() },
    steps = steps.sortedWith(compareBy<RecipeStepEntity>({ it.position }, { it.id })).map { it.toModel() },
)

fun RecipeWithParts.toModel(): Recipe = recipe.toModel(lines, steps)

fun Recipe.toEntity(): RecipeEntity = RecipeEntity(
    id = id,
    title = title,
    shortTitle = shortTitle,
    tagline = tagline,
    style = style,
    serves = serves,
    prepMins = prepMins,
    cookMins = cookMins,
    timeNote = timeNote,
    servedWith = servedWith,
    tags = joinTags(tags),
    rating = rating,
    notes = notes,
    source = source.name,
    createdAt = createdAtEpochMillis,
    updatedAt = updatedAtEpochMillis,
    archived = archived,
)

fun RecipeLineEntity.toModel(): RecipeLine = RecipeLine(
    group = grp,
    text = text,
    ingredientId = ingredientId,
    quantity = quantity,
    optional = optional,
)

/** The lines as rows for recipe [recipeId], numbered in order (`saveRecipe` sets the recipe id and renumbers). */
fun Recipe.toLineEntities(recipeId: Long = id): List<RecipeLineEntity> = lines.mapIndexed { index, line ->
    RecipeLineEntity(
        recipeId = recipeId,
        position = index,
        grp = line.group,
        text = line.text,
        ingredientId = line.ingredientId,
        quantity = line.quantity,
        optional = line.optional,
    )
}

fun RecipeStepEntity.toModel(): RecipeStep = RecipeStep(
    kind = enumOrDefault(kind, StepKind.STEP),
    title = title,
    time = time,
    body = body,
)

/** The steps, tips, equipment and storage notes as rows for recipe [recipeId], numbered in order. */
fun Recipe.toStepEntities(recipeId: Long = id): List<RecipeStepEntity> = steps.mapIndexed { index, step ->
    RecipeStepEntity(
        recipeId = recipeId,
        position = index,
        kind = step.kind.name,
        title = step.title,
        time = step.time,
        body = step.body,
    )
}

// Shopping list

fun ShoppingEntryEntity.toModel(): ShoppingEntry = ShoppingEntry(
    id = id,
    recipeId = recipeId,
    servings = servings,
    ingredientId = ingredientId,
    quantity = quantity,
    freeText = freeText,
    checked = checked,
    addedAtEpochMillis = addedAt,
)

fun ShoppingEntry.toEntity(): ShoppingEntryEntity = ShoppingEntryEntity(
    id = id,
    recipeId = recipeId,
    servings = servings,
    ingredientId = ingredientId,
    quantity = quantity,
    freeText = freeText,
    checked = checked,
    addedAt = addedAtEpochMillis,
)

fun ShoppingMarkEntity.toModel(): ShoppingMark = ShoppingMark(ingredientId = ingredientId, checked = checked, haveIt = haveIt)

fun ShoppingMark.toEntity(): ShoppingMarkEntity =
    ShoppingMarkEntity(ingredientId = ingredientId, checked = checked, haveIt = haveIt)
