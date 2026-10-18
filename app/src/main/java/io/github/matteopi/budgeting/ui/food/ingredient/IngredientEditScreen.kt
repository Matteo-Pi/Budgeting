package io.github.matteopi.budgeting.ui.food.ingredient

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.recipes.BaseUnit
import io.github.matteopi.budgeting.core.recipes.FoodGroup
import io.github.matteopi.budgeting.core.recipes.FoodGroups
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.NutritionSource
import io.github.matteopi.budgeting.core.recipes.Shelf
import io.github.matteopi.budgeting.core.recipes.VegKind
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.OptionalDateField
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.food.scan.DraftReviewDialog
import io.github.matteopi.budgeting.ui.food.scan.LabelReviewDialog
import io.github.matteopi.budgeting.ui.food.scan.PackPhotoButton
import io.github.matteopi.budgeting.ui.food.scan.PackScanTags
import io.github.matteopi.budgeting.ui.food.scan.PackScanViewModel

/** Test tags of the ingredient editor. Portion rows are tagged by their position in the list, counted from 0. */
object IngredientEditTags {
    const val SAVE = "ingredient-save"
    const val ADD_PORTION = "ingredient-add-portion"
    const val ARCHIVE = "ingredient-archive"
    const val LINK_CHOOSE = "ingredient-link-choose"
    const val LINK_REMOVE = "ingredient-link-remove"
    const val PRODUCT_SEARCH = "ingredient-product-search"
    const val FOOD_GROUP = "ingredient-food-group"

    fun portionName(position: Int) = "ingredient-portion-$position-name"

    fun portionAmount(position: Int) = "ingredient-portion-$position-amount"

    fun portionRemove(position: Int) = "ingredient-portion-$position-remove"

    fun usedIn(recipeId: Long) = "ingredient-used-in-$recipeId"
}

/**
 * Add or edit an ingredient (docs/PLAN.md §11.5): name and unit, pack size and label, the typed price with its
 * date and store, the shop's name for it and a link to its page, a barcode (digits), nutrition per 100 g or ml
 * with where it came from, veg kind, shelf and a note; its portions; the link to a receipts product; and the
 * recipes it is used in. An existing ingredient can be archived (after a confirmation). [ingredientId] is null for
 * a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientEditScreen(
    ingredientId: Long?,
    onBack: () -> Unit,
    onOpenRecipe: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    barcode: String? = null,
    viewModel: IngredientEditViewModel = viewModel(factory = IngredientEditViewModel.factory(ingredientId, barcode)),
    scanViewModel: PackScanViewModel = viewModel(factory = PackScanViewModel.Factory),
) {
    val scan by scanViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    // A new ingredient started from a scanned barcode is looked up straight away (when the setting is on).
    LaunchedEffect(Unit) {
        if (ingredientId == null && barcode != null) scanViewModel.lookUpNow(context, barcode)
    }
    LaunchedEffect(scan.message) {
        val message = scan.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            scanViewModel.messageShown()
        }
    }
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val submitted by viewModel.submitted.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val products by viewModel.products.collectAsStateWithLifecycle()
    val usedIn by viewModel.usedIn.collectAsStateWithLifecycle()
    var confirmArchive by remember { mutableStateOf(false) }
    var pickingProduct by remember { mutableStateOf(false) }
    val errors = IngredientFormRules.errors(form)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (ingredientId == null) "Add ingredient" else "Edit ingredient") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (phase == EditPhase.READY) {
                FoodSaveBar(
                    label = "Save ingredient",
                    problem = if (submitted) errors.message else null,
                    error = error,
                    enabled = true,
                    saving = saving,
                    onSave = { viewModel.save(onSaved = onBack) },
                    tag = IngredientEditTags.SAVE,
                )
            }
        },
    ) { innerPadding ->
        when (phase) {
            EditPhase.LOADING -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            EditPhase.MISSING -> EmptyState(
                message = "This ingredient is no longer there.",
                modifier = Modifier.padding(innerPadding),
            )
            EditPhase.READY -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FromThePackCard(
                    busy = scan.busy,
                    onLabel = { uri -> scanViewModel.readLabel(context, uri) },
                    onBarcode = { uri -> scanViewModel.scanBarcode(context, uri) { code -> viewModel.setBarcode(code) } },
                )
                BasicsCard(form, errors, submitted, viewModel)
                PriceCard(form, errors, submitted, viewModel)
                NutritionCard(form, errors, submitted, viewModel)
                PortionsCard(form, errors, submitted, viewModel)
                ProductLinkCard(
                    productId = form.productId,
                    products = products,
                    onChoose = { pickingProduct = true },
                    onRemove = { viewModel.linkProduct(null) },
                )
                if (ingredientId != null) {
                    UsedInCard(usedIn, onOpenRecipe)
                    OutlinedButton(
                        onClick = { confirmArchive = true },
                        modifier = Modifier.fillMaxWidth().testTag(IngredientEditTags.ARCHIVE),
                    ) { Text("Archive ingredient") }
                }
            }
        }
    }

    scan.label?.let { reading ->
        LabelReviewDialog(
            reading = reading,
            onUse = {
                viewModel.applyLabel(reading)
                scanViewModel.dismissLabel()
            },
            onDismiss = scanViewModel::dismissLabel,
        )
    }
    scan.draft?.let { draft ->
        DraftReviewDialog(
            draft = draft,
            onUse = {
                viewModel.applyDraft(draft)
                scanViewModel.dismissDraft()
            },
            onDismiss = scanViewModel::dismissDraft,
        )
    }

    if (pickingProduct) {
        ProductPickerSheet(
            products = products,
            onPick = { id ->
                viewModel.linkProduct(id)
                pickingProduct = false
            },
            onDismiss = { pickingProduct = false },
        )
    }

    if (confirmArchive) {
        ConfirmDialog(
            title = "Archive ${form.name.trim().ifEmpty { "this ingredient" }}?",
            text = if (usedIn.isEmpty()) {
                "It stops showing in the lists and pickers, and new recipes won't match it. You can't undo this here."
            } else {
                "It stops showing in the lists and pickers, and new recipes won't match it. " +
                    "The ${usedIn.size} recipe${if (usedIn.size == 1) "" else "s"} that use it keep it."
            },
            confirmLabel = "Archive",
            onConfirm = {
                confirmArchive = false
                viewModel.archive(onDone = onBack)
            },
            onDismiss = { confirmArchive = false },
        )
    }
}

@Composable
private fun BasicsCard(
    form: IngredientForm,
    errors: IngredientFormErrors,
    submitted: Boolean,
    viewModel: IngredientEditViewModel,
) {
    SectionCard(title = "Basics") {
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.name,
                onValueChange = viewModel::setName,
                label = { Text("Name") },
                singleLine = true,
                isError = submitted && errors.name,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Measured in", style = MaterialTheme.typography.bodyMedium)
                FilterChip(
                    selected = form.unit == BaseUnit.G,
                    onClick = { viewModel.setUnit(BaseUnit.G) },
                    label = { Text("Grams (g)") },
                )
                FilterChip(
                    selected = form.unit == BaseUnit.ML,
                    onClick = { viewModel.setUnit(BaseUnit.ML) },
                    label = { Text("Millilitres (ml)") },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FoodNumberField(
                    label = "Pack size",
                    value = form.packSizeText,
                    onValueChange = viewModel::setPackSize,
                    isError = submitted && errors.packSize,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = form.packLabel,
                    onValueChange = viewModel::setPackLabel,
                    label = { Text("Pack label") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = "Pack size is in ${FoodTexts.unitSymbol(form.unit)}; the label is how the shop says it, such as \"1 kg bag\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OptionPicker(
                label = "Shelf",
                selected = form.shelf,
                options = Shelf.entries.toList(),
                optionName = FoodTexts::shelfName,
                onSelect = viewModel::setShelf,
                modifier = Modifier.fillMaxWidth(),
            )
            OptionPicker(
                label = "Counts towards 5 a day",
                selected = form.vegKind,
                options = VegKind.entries.toList(),
                optionName = FoodTexts::vegKindName,
                onSelect = viewModel::setVegKind,
                modifier = Modifier.fillMaxWidth(),
            )
            val guessed = FoodGroups.guess(Ingredient(name = form.name, vegKind = form.vegKind))
            OptionPicker(
                label = "Counts as (for the recipe filters)",
                selected = form.foodGroup,
                options = listOf<FoodGroup?>(null) + FoodGroup.entries,
                optionName = { group -> FoodTexts.foodGroupName(group, guessed) },
                onSelect = viewModel::setFoodGroup,
                modifier = Modifier.fillMaxWidth().testTag(IngredientEditTags.FOOD_GROUP),
            )
            OutlinedTextField(
                value = form.note,
                onValueChange = viewModel::setNote,
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PriceCard(
    form: IngredientForm,
    errors: IngredientFormErrors,
    submitted: Boolean,
    viewModel: IngredientEditViewModel,
) {
    SectionCard(title = "Price and shop") {
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FoodNumberField(
                label = "Pack price",
                value = form.priceText,
                onValueChange = viewModel::setPrice,
                isError = submitted && errors.price,
                prefix = "£",
                modifier = Modifier.fillMaxWidth(),
            )
            OptionalDateField(
                label = "Price date",
                date = form.priceDate,
                onDateChange = viewModel::setPriceDate,
                initialDate = viewModel.today,
                maxDate = viewModel.today,
            )
            OutlinedTextField(
                value = form.store,
                onValueChange = viewModel::setStore,
                label = { Text("Store") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.productName,
                onValueChange = viewModel::setProductName,
                label = { Text("Shop product name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.sourceUrl,
                onValueChange = viewModel::setSourceUrl,
                label = { Text("Source link") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.barcode,
                onValueChange = viewModel::setBarcode,
                label = { Text("Barcode") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "A price paid on a receipt wins over this one once the ingredient is linked to a product.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NutritionCard(
    form: IngredientForm,
    errors: IngredientFormErrors,
    submitted: Boolean,
    viewModel: IngredientEditViewModel,
) {
    SectionCard(title = "Nutrition per 100 ${FoodTexts.unitSymbol(form.unit)}") {
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Leave a box empty when you don't know the value; the recipe then says its numbers are \"at least\". " +
                    "Added sugars (sugar, honey, syrups) aren't on UK labels: use the sugars if the ingredients list has " +
                    "sugar, 0 if it has none.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IngredientFormRules.NUTRIENT_ORDER.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { nutrient ->
                        FoodNumberField(
                            label = IngredientFormRules.nutrientLabel(nutrient),
                            value = form.nutrition[nutrient].orEmpty(),
                            onValueChange = { viewModel.setNutrition(nutrient, it) },
                            isError = submitted && nutrient in errors.nutrition,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            OptionPicker(
                label = "Where the values came from",
                selected = form.nutritionSource,
                options = listOf(NutritionSource.LABEL, NutritionSource.TYPED, NutritionSource.ESTIMATE) +
                    listOfNotNull(NutritionSource.BARCODE.takeIf { form.nutritionSource == NutritionSource.BARCODE }),
                optionName = FoodTexts::nutritionSourceName,
                onSelect = viewModel::setNutritionSource,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PortionsCard(
    form: IngredientForm,
    errors: IngredientFormErrors,
    submitted: Boolean,
    viewModel: IngredientEditViewModel,
) {
    SectionCard(title = "Portions") {
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "A named weight, such as each = 150 g for an onion or clove = 5 g for garlic. \"2 onions\" and " +
                    "\"3 garlic cloves\" use them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            form.portions.forEachIndexed { position, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = row.name,
                        onValueChange = { viewModel.setPortionName(row.key, it) },
                        label = { Text("Portion name") },
                        singleLine = true,
                        isError = submitted && row.key in errors.portions && row.name.isBlank(),
                        modifier = Modifier.weight(1f).testTag(IngredientEditTags.portionName(position)),
                    )
                    FoodNumberField(
                        label = "Amount (${FoodTexts.unitSymbol(form.unit)})",
                        value = row.amountText,
                        onValueChange = { viewModel.setPortionAmount(row.key, it) },
                        isError = submitted && row.key in errors.portions && row.name.isNotBlank(),
                        modifier = Modifier.width(128.dp).testTag(IngredientEditTags.portionAmount(position)),
                    )
                    IconButton(
                        onClick = { viewModel.removePortion(row.key) },
                        modifier = Modifier.testTag(IngredientEditTags.portionRemove(position)),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove portion ${row.name.ifBlank { (position + 1).toString() }}")
                    }
                }
            }
            OutlinedButton(
                onClick = viewModel::addPortion,
                modifier = Modifier.fillMaxWidth().testTag(IngredientEditTags.ADD_PORTION),
            ) { Text("Add portion") }
        }
    }
}

@Composable
private fun ProductLinkCard(
    productId: Long?,
    products: List<ProductChoice>,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
) {
    val linked = productId?.let { id -> products.firstOrNull { it.id == id } }
    SectionCard(title = "Link to a product") {
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (productId == null) {
                Text(
                    text = "Not linked. Link a product you've bought on a receipt, and this ingredient is priced at " +
                        "the latest price you paid for it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text("Linked to ${linked?.name ?: "a product that is no longer in your receipts"}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = linked?.lastPaid ?: "Not bought on a receipt yet, so the typed price is used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChoose, modifier = Modifier.testTag(IngredientEditTags.LINK_CHOOSE)) {
                    Text(if (productId == null) "Choose product" else "Change product")
                }
                if (productId != null) {
                    TextButton(onClick = onRemove, modifier = Modifier.testTag(IngredientEditTags.LINK_REMOVE)) {
                        Text("Remove link")
                    }
                }
            }
        }
    }
}

@Composable
private fun UsedInCard(usedIn: List<UsedInRecipe>, onOpenRecipe: (Long) -> Unit) {
    SectionCard(title = "Used in") {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            if (usedIn.isEmpty()) {
                Text("No recipe uses this ingredient yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                usedIn.forEach { recipe ->
                    Text(
                        text = recipe.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onOpenRecipe(recipe.id) }
                            .padding(vertical = 12.dp)
                            .testTag(IngredientEditTags.usedIn(recipe.id)),
                    )
                }
            }
        }
    }
}

/** A bottom sheet over the receipts' products, searchable, each with the latest price paid for it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductPickerSheet(
    products: List<ProductChoice>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val shown = products.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose a product", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search products") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(IngredientEditTags.PRODUCT_SEARCH),
            )
            if (shown.isEmpty()) {
                Text(
                    text = if (products.isEmpty()) {
                        "No products yet — they appear when you save a receipt."
                    } else {
                        "No product matches \"${query.trim()}\"."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(shown, key = { it.id }) { product ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable { onPick(product.id) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(product.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = product.lastPaid ?: "not bought yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** Reading the pack: the nutrition label from a photo, and the barcode (with the online lookup when it is on). */
@Composable
private fun FromThePackCard(busy: Boolean, onLabel: (Uri) -> Unit, onBarcode: (Uri) -> Unit) {
    SectionCard(title = "From the pack") {
        Text(
            "Photograph the nutrition table to fill in the values, or the barcode to scan it in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            PackPhotoButton(label = "Read the label", tag = PackScanTags.READ_LABEL, enabled = !busy, onPhoto = onLabel)
            PackPhotoButton(label = "Scan the barcode", tag = PackScanTags.SCAN_BARCODE, enabled = !busy, onPhoto = onBarcode)
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

