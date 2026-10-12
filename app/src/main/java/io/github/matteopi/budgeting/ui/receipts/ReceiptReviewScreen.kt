package io.github.matteopi.budgeting.ui.receipts

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.receipts.Store
import io.github.matteopi.budgeting.ui.components.AccountPicker
import io.github.matteopi.budgeting.ui.components.CategoryPicker
import io.github.matteopi.budgeting.ui.components.DateField
import io.github.matteopi.budgeting.ui.components.PickerField
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test tags of the review screen's fields: `receipt-item-<key>-<field>`, with keys counted from 1 in reading order. */
object ReceiptReviewTags {
    const val TOTAL = "receipt-total"
    const val ADD_ITEM = "receipt-add-item"
    const val SAVE = "receipt-save"

    fun item(key: Long, field: String) = "receipt-item-$key-$field"
}

/**
 * Review a read receipt before saving (docs/PLAN.md §10.4): the photo, store, date, total and
 * account, whether the items add up to the total, whether it replaces an existing transaction,
 * and every item to fix, categorise, delete or add. [path] and [kind] describe the cache file
 * the receipt was read from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptReviewScreen(
    path: String,
    kind: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReceiptReviewViewModel = viewModel(
        factory = ReceiptReviewViewModel.factory(path, ReceiptInputKind.fromName(kind)),
    ),
) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val edit by viewModel.edit.collectAsStateWithLifecycle()
    val derived by viewModel.derived.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val saveError by viewModel.saveError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var viewingPhoto by remember { mutableStateOf(false) }
    val inputKind = ReceiptInputKind.fromName(kind)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Review receipt") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (phase == ReviewPhase.Ready) {
                SaveBar(
                    problem = derived.problem,
                    error = saveError,
                    enabled = derived.canSave && !saving,
                    saving = saving,
                    onSave = {
                        viewModel.save(
                            onSaved = {
                                Toast.makeText(context.applicationContext, "Receipt saved", Toast.LENGTH_SHORT).show()
                                onBack()
                            },
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        when (val current = phase) {
            ReviewPhase.Loading -> LoadingContent(Modifier.padding(innerPadding))
            is ReviewPhase.Failed -> FailedContent(
                message = current.message,
                onRetry = viewModel::load,
                onEnterByHand = viewModel::startManually,
                modifier = Modifier.padding(innerPadding),
            )
            ReviewPhase.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") {
                    HeaderSection(
                        path = path,
                        kind = inputKind,
                        edit = edit,
                        derived = derived,
                        today = viewModel.today,
                        onViewPhoto = { viewingPhoto = true },
                        onStore = viewModel::setStore,
                        onDate = viewModel::setDate,
                        onTotal = viewModel::setTotal,
                        onAccount = viewModel::setAccount,
                    )
                }
                item(key = "check") { CheckCard(derived) }
                item(key = "match") {
                    MatchCard(derived = derived, replace = edit.replace, onReplace = viewModel::setReplace)
                }
                if (edit.warnings.isNotEmpty()) {
                    item(key = "warnings") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            edit.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                if (derived.hasUnsetCategories) {
                    item(key = "bulk") {
                        SetAllUnset(categories = derived.categories, onSelect = viewModel::setCategoryForAllUnset)
                    }
                }
                if (edit.items.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No items yet. Add the things you bought.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
                items(edit.items, key = { it.key }) { item ->
                    ItemCard(
                        item = item,
                        flags = derived.itemFlags[item.key] ?: ItemFlags(),
                        categories = derived.categories,
                        onName = { viewModel.setName(item.key, it) },
                        onQuantity = { viewModel.setQuantity(item.key, it) },
                        onPrice = { viewModel.setPrice(item.key, it) },
                        onDiscount = { viewModel.setDiscount(item.key, it) },
                        onCategory = { viewModel.setCategory(item.key, it) },
                        onDelete = { viewModel.deleteItem(item.key) },
                    )
                }
                item(key = "add") {
                    OutlinedButton(
                        onClick = viewModel::addItem,
                        modifier = Modifier.fillMaxWidth().testTag(ReceiptReviewTags.ADD_ITEM),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add item")
                    }
                }
            }
        }
    }

    if (viewingPhoto) {
        FullPhotoDialog(path = path, onDismiss = { viewingPhoto = false })
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Reading the receipt…", style = MaterialTheme.typography.bodyLarge)
        Text(
            "This happens on your phone; nothing is uploaded.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FailedContent(
    message: String,
    onRetry: () -> Unit,
    onEnterByHand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Try again") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onEnterByHand) { Text("Enter it by hand") }
    }
}

@Composable
private fun SaveBar(
    problem: String?,
    error: String?,
    enabled: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else if (problem != null) {
                Text(problem, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = onSave,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag(ReceiptReviewTags.SAVE),
            ) {
                Text(if (saving) "Saving…" else "Save receipt")
            }
        }
    }
}

@Composable
private fun HeaderSection(
    path: String,
    kind: ReceiptInputKind,
    edit: ReviewEdit,
    derived: ReviewDerived,
    today: java.time.LocalDate,
    onViewPhoto: () -> Unit,
    onStore: (Store) -> Unit,
    onDate: (java.time.LocalDate) -> Unit,
    onTotal: (String) -> Unit,
    onAccount: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (kind == ReceiptInputKind.IMAGE) {
            Thumbnail(path = path, onClick = onViewPhoto)
        }
        StorePicker(store = edit.store, onSelect = onStore)
        DateField(label = "Date", date = edit.date, onDateChange = onDate, maxDate = today)
        OutlinedTextField(
            value = edit.totalText,
            onValueChange = onTotal,
            label = { Text("Total") },
            prefix = { Text("£") },
            singleLine = true,
            isError = ReceiptReviewRules.parseTotal(edit.totalText) == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag(ReceiptReviewTags.TOTAL),
        )
        AccountPicker(
            accounts = derived.accounts.filter { !it.archived },
            selectedAccountId = derived.accountId,
            onSelect = onAccount,
        )
    }
}

@Composable
private fun Thumbnail(path: String, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { ReceiptImages.load(File(path), THUMBNAIL_SIDE) }
    }
    val image = bitmap
    if (image != null) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Receipt photo. Tap to view it full size.",
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(160.dp).clickable(onClick = onClick),
            )
            Text("Tap the photo to view it full size", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val THUMBNAIL_SIDE = 480

@Composable
private fun FullPhotoDialog(path: String, onDismiss: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { ReceiptImages.load(File(path), FULL_SIDE) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                val image = bitmap
                if (image == null) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    var scale by remember { mutableFloatStateOf(1f) }
                    var offset by remember { mutableStateOf(Offset.Zero) }
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = "Receipt photo, full size",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    offset += pan
                                }
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close photo", tint = Color.White)
                }
            }
        }
    }
}

private const val FULL_SIDE = 2048

@Composable
private fun StorePicker(store: Store, onSelect: (Store) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        PickerField(
            value = receiptStoreName(store),
            label = "Store",
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Store.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(receiptStoreName(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun CheckCard(derived: ReviewDerived) {
    val colors = if (derived.mismatch) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = derived.checkMessage,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun MatchCard(derived: ReviewDerived, replace: Boolean, onReplace: (Boolean) -> Unit) {
    val match = derived.match
    Card(modifier = Modifier.fillMaxWidth()) {
        if (match == null) {
            Text(
                "A new transaction will be added",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Replaces ${match.description}", style = MaterialTheme.typography.bodyMedium)
                    if (!replace) {
                        Text(
                            "It stays, and new transactions are added as well, so your balance will change.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Switch(checked = replace, onCheckedChange = onReplace)
            }
        }
    }
}

@Composable
private fun SetAllUnset(categories: List<Category>, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Set category for all unset") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.name) },
                    onClick = {
                        onSelect(category.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ItemCard(
    item: ReviewItem,
    flags: ItemFlags,
    categories: List<Category>,
    onName: (String) -> Unit,
    onQuantity: (String) -> Unit,
    onPrice: (String) -> Unit,
    onDiscount: (String) -> Unit,
    onCategory: (Long) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = if (flags.highlighted) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = item.name,
                    onValueChange = onName,
                    label = { Text("Name") },
                    singleLine = true,
                    isError = flags.nameBlank,
                    modifier = Modifier.weight(1f).testTag(ReceiptReviewTags.item(item.key, "name")),
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag(ReceiptReviewTags.item(item.key, "delete")),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete ${item.name.ifBlank { "item" }}")
                }
            }
            if (item.rawName.isNotBlank() && item.name != item.rawName) {
                Text("On the receipt: ${item.rawName}", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = item.quantity,
                    onValueChange = onQuantity,
                    label = { Text("Qty") },
                    singleLine = true,
                    isError = flags.quantityInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.7f).testTag(ReceiptReviewTags.item(item.key, "quantity")),
                )
                OutlinedTextField(
                    value = item.price,
                    onValueChange = onPrice,
                    label = { Text("Price") },
                    prefix = { Text("£") },
                    singleLine = true,
                    isError = flags.priceInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).testTag(ReceiptReviewTags.item(item.key, "price")),
                )
                OutlinedTextField(
                    value = item.discount,
                    onValueChange = onDiscount,
                    label = { Text("Discount") },
                    prefix = { Text("£") },
                    singleLine = true,
                    isError = flags.discountInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).testTag(ReceiptReviewTags.item(item.key, "discount")),
                )
            }
            CategoryPicker(
                categories = categories,
                selectedCategoryId = item.categoryId,
                onSelect = onCategory,
                modifier = Modifier.fillMaxWidth().testTag(ReceiptReviewTags.item(item.key, "category")),
            )
            if (item.categorySource == CategorySource.GUESS || item.categorySource == CategorySource.NONE) {
                Text("Category guessed: pick one to confirm it", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
