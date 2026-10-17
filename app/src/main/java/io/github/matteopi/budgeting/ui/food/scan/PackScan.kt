package io.github.matteopi.budgeting.ui.food.scan

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.recipes.BaseUnit
import io.github.matteopi.budgeting.core.recipes.LabelReading
import io.github.matteopi.budgeting.core.recipes.OffDraft
import io.github.matteopi.budgeting.core.recipes.OffResult
import io.github.matteopi.budgeting.core.recipes.Quantities
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.ui.food.ingredient.IngredientFormRules
import io.github.matteopi.budgeting.ui.food.ingredient.ScanRules
import io.github.matteopi.budgeting.ui.receipts.ReceiptFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Test tags of the pack scanning parts. */
object PackScanTags {
    const val READ_LABEL = "pack-read-label"
    const val SCAN_BARCODE = "pack-scan-barcode"
    const val USE_LABEL = "pack-use-label"
    const val USE_DRAFT = "pack-use-draft"
}

/** What a scan is doing or found, for the screen that started it. */
data class PackScanState(
    val busy: Boolean = false,
    /** A label read, waiting for *Use these values*. */
    val label: LabelReading? = null,
    /** An Open Food Facts answer, waiting for *Use these details*. */
    val draft: OffDraft? = null,
    /** A one-off line for the snackbar. */
    val message: String? = null,
)

/**
 * Reads pack photos for a screen (docs/PLAN.md §11.5): the nutrition label, and the barcode with the Open Food
 * Facts lookup when *Look up barcodes online* is on. Results wait in [state] for the person to confirm them.
 */
class PackScanViewModel(private val settingsStore: SettingsStore) : ViewModel() {
    private val mutableState = MutableStateFlow(PackScanState())
    val state: StateFlow<PackScanState> = mutableState.asStateFlow()

    private fun launchScan(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(message = "Couldn't read the photo: ${e.message ?: "something went wrong"}") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /** Reads the label in the photo at [uri]; its values wait in [PackScanState.label]. */
    fun readLabel(context: Context, uri: Uri) = launchScan {
        val reading = FoodScan.readLabel(context, uri)
        if (reading.isEmpty) {
            mutableState.update {
                it.copy(message = "No nutrition table found in the photo. Try again closer, straight on and in good light.")
            }
        } else {
            mutableState.update { it.copy(label = reading) }
        }
    }

    /**
     * Reads the barcode in the photo at [uri] and gives it to [onBarcode]; then, if the setting is on, looks it up
     * ([lookUpNow]).
     */
    fun scanBarcode(context: Context, uri: Uri, onBarcode: (String) -> Unit) = launchScan {
        val barcode = FoodScan.readBarcode(context, uri)
        if (barcode == null) {
            mutableState.update { it.copy(message = "No barcode found in the photo. Try again with the whole barcode in view.") }
        } else {
            onBarcode(barcode)
            lookUp(context, barcode, quietWhenOff = false)
        }
    }

    /** Looks [barcode] up straight away if the setting is on (a new ingredient started from a scan). */
    fun lookUpNow(context: Context, barcode: String) = launchScan { lookUp(context, barcode, quietWhenOff = true) }

    private suspend fun lookUp(context: Context, barcode: String, quietWhenOff: Boolean) {
        if (!settingsStore.lookUpBarcodesOnline.first()) {
            if (!quietWhenOff) {
                mutableState.update {
                    it.copy(message = "Barcode $barcode added. Turn on Look up barcodes online in Recipe settings to fill in the rest.")
                }
            }
            return
        }
        when (val result = FoodScan.lookUp(context, barcode)) {
            is OffResult.Found -> mutableState.update { it.copy(draft = result.draft) }
            OffResult.NotFound -> mutableState.update { it.copy(message = "Not on Open Food Facts — fill it in from the pack.") }
            is OffResult.Invalid -> mutableState.update { it.copy(message = result.reason) }
        }
    }

    fun dismissLabel() = mutableState.update { it.copy(label = null) }

    fun dismissDraft() = mutableState.update { it.copy(draft = null) }

    fun messageShown() = mutableState.update { it.copy(message = null) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                PackScanViewModel(app.container.settingsStore)
            }
        }
    }
}

/**
 * A button that takes a photo of a pack (camera, no permission needed) or picks one from the photos, then calls
 * [onPhoto] with it.
 */
@Composable
fun PackPhotoButton(label: String, tag: String, enabled: Boolean, onPhoto: (Uri) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pending
        pending = null
        if (saved && uri != null) onPhoto(uri)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPhoto(uri)
    }
    Box(modifier = modifier) {
        TextButton(onClick = { menuOpen = true }, enabled = enabled, modifier = Modifier.testTag(tag)) { Text(label) }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Take a photo") },
                onClick = {
                    menuOpen = false
                    val uri = ReceiptFiles.uriFor(context, ReceiptFiles.newFile(context, "jpg"))
                    pending = uri
                    takePicture.launch(uri)
                },
            )
            DropdownMenuItem(
                text = { Text("Choose a photo") },
                onClick = {
                    menuOpen = false
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
        }
    }
}

/** The values read from a label, to check against the pack before they fill the form. */
@Composable
fun LabelReviewDialog(reading: LabelReading, onUse: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("From the label") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (reading.columnFound) {
                        "Per 100 ${if (reading.unit == BaseUnit.ML) "ml" else "g"}. Check them against the pack."
                    } else {
                        "The \"per 100 g\" heading wasn't found, so the first column was read. Check them against the pack."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ScanRules.labelRows(reading).forEach { (name, amount, note) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(if (note != null) "$amount ($note)" else amount, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUse, modifier = Modifier.testTag(PackScanTags.USE_LABEL)) { Text("Use these values") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What Open Food Facts knows about a barcode, to check before it fills the form. */
@Composable
fun DraftReviewDialog(draft: OffDraft, onUse: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("From Open Food Facts") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(draft.name.ifBlank { "No name given" }, style = MaterialTheme.typography.titleMedium)
                val size = draft.packSize
                val unit = draft.unit
                val pack = if (size != null && unit != null) Quantities.format(size, unit) else draft.quantityText ?: "Pack size not given"
                Text(pack, style = MaterialTheme.typography.bodyMedium)
                val per100 = draft.per100
                ScanRules.ORDER.forEach { nutrient ->
                    per100[nutrient]?.let { value ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                IngredientFormRules.nutrientLabel(nutrient),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(Quantities.plain(value, 2), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(
                    "Open Food Facts is filled in by its users, so check these against the pack.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Set the shelf, veg and price yourself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onUse, modifier = Modifier.testTag(PackScanTags.USE_DRAFT)) { Text("Use these details") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
