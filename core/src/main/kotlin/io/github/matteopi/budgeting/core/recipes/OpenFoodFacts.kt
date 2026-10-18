package io.github.matteopi.budgeting.core.recipes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** A product read from Open Food Facts, to be checked against the pack before it is saved (docs/PLAN.md §11.5). */
data class OffDraft(
    val barcode: String,
    /** Brand and product name, such as "Tesco Chopped Tomatoes"; empty when the database has neither. */
    val name: String,
    /** The pack size in [unit], when the database gives one. */
    val packSize: Double?,
    val unit: BaseUnit?,
    /** The pack size as the database writes it ("400 g"), for the pack label. */
    val quantityText: String?,
    val per100: Nutrition,
)

sealed interface OffResult {
    data class Found(val draft: OffDraft) : OffResult
    data object NotFound : OffResult
    data class Invalid(val reason: String) : OffResult
}

/**
 * The barcode lookup on Open Food Facts (docs/PLAN.md §11.5): the request it makes and the pure mapping of the
 * answer. The network call itself lives in the app; only the barcode number is ever sent.
 */
object OpenFoodFacts {

    const val CONTACT = "https://github.com/matteo-pi/budgeting"
    const val FIELDS = "code,product_name,brands,quantity,product_quantity,product_quantity_unit,nutriments"

    private const val KJ_PER_KCAL = 4.184
    private const val SALT_PER_SODIUM = 2.5

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The product URL for [barcode] (digits only, as [normaliseBarcode] gives). */
    fun productUrl(barcode: String): String = "https://world.openfoodfacts.org/api/v2/product/$barcode?fields=$FIELDS"

    /** The User-Agent Open Food Facts asks for: the app, its version and a contact (the project page). */
    fun userAgent(versionName: String): String = "Budgeting/$versionName ($CONTACT)"

    /**
     * The barcode as stored and looked up: digits only, a 12-digit UPC-A turned into its EAN-13 form (a leading
     * 0) so either scan of the same pack matches; null unless it is 8, 12 or 13 digits.
     */
    fun normaliseBarcode(raw: String): String? {
        val digits = raw.filter { it in '0'..'9' }
        return when (digits.length) {
            8, 13 -> digits
            12 -> "0$digits"
            else -> null
        }
    }

    /**
     * Maps the API's answer ([body], with the HTTP [status]) to a draft. A 404, or `"status": 0`, is
     * [OffResult.NotFound]; anything that isn't the expected JSON is [OffResult.Invalid].
     */
    fun toResult(status: Int, body: String?, barcode: String): OffResult {
        if (status == 404) return OffResult.NotFound
        if (status !in 200..299) return OffResult.Invalid("The lookup failed (HTTP $status).")
        val root = try {
            json.parseToJsonElement(body.orEmpty()) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return OffResult.Invalid("The answer couldn't be read.")
        val found = (root["status"] as? JsonPrimitive)?.intOrNull
        val product = root["product"] as? JsonObject
        if (found == 0 || product == null) return OffResult.NotFound
        return OffResult.Found(toDraft(product, barcode))
    }

    /** The draft for one `product` object of the API. */
    fun toDraft(product: JsonObject, barcode: String): OffDraft {
        val brand = text(product["brands"])?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val productName = text(product["product_name"])?.trim()?.takeIf { it.isNotEmpty() }
        val name = when {
            brand == null -> productName.orEmpty()
            productName == null -> brand
            productName.startsWith(brand, ignoreCase = true) -> productName
            else -> "$brand $productName"
        }
        val quantityText = text(product["quantity"])?.trim()?.takeIf { it.isNotEmpty() }
        val (packSize, unit) = pack(number(product["product_quantity"]), text(product["product_quantity_unit"]), quantityText)
        val n = product["nutriments"] as? JsonObject ?: JsonObject(emptyMap())
        val kcal = number(n["energy-kcal_100g"]) ?: number(n["energy_100g"])?.let { it / KJ_PER_KCAL }
        val salt = number(n["salt_100g"]) ?: number(n["sodium_100g"])?.let { it * SALT_PER_SODIUM }
        return OffDraft(
            barcode = barcode,
            name = name,
            packSize = packSize,
            unit = unit,
            quantityText = quantityText,
            per100 = Nutrition(
                kcal = kcal,
                fat = number(n["fat_100g"]),
                saturates = number(n["saturated-fat_100g"]),
                carbs = number(n["carbohydrates_100g"]),
                sugars = number(n["sugars_100g"]),
                fibre = number(n["fiber_100g"]),
                protein = number(n["proteins_100g"]),
                salt = salt,
                addedSugars = number(n["added-sugars_100g"]),
            ),
        )
    }

    private val QUANTITY = Regex("""(?:(\d+)\s*[x×]\s*)?(\d+(?:[.,]\d+)?)\s*(kg|g|ml|cl|l)\b""", RegexOption.IGNORE_CASE)

    /** The pack size in grams or millilitres from the API's number and unit, else from its text ("4 x 100 g" = 400 g). */
    fun pack(quantity: Double?, unit: String?, text: String?): Pair<Double?, BaseUnit?> {
        if (quantity != null && quantity > 0) {
            when (unit?.trim()?.lowercase()) {
                "g", null, "" -> return quantity to BaseUnit.G
                "kg" -> return quantity * 1000 to BaseUnit.G
                "ml" -> return quantity to BaseUnit.ML
                "cl" -> return quantity * 10 to BaseUnit.ML
                "l" -> return quantity * 1000 to BaseUnit.ML
            }
        }
        val match = text?.let { QUANTITY.find(it) } ?: return null to null
        val count = match.groupValues[1].toIntOrNull() ?: 1
        val value = match.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null to null
        return when (match.groupValues[3].lowercase()) {
            "kg" -> value * 1000 * count to BaseUnit.G
            "g" -> value * count to BaseUnit.G
            "ml" -> value * count to BaseUnit.ML
            "cl" -> value * 10 * count to BaseUnit.ML
            else -> value * 1000 * count to BaseUnit.ML
        }
    }

    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

    /** A number the API may send as a number or as text. */
    private fun number(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.doubleOrNull ?: primitive.contentOrNull?.replace(',', '.')?.trim()?.toDoubleOrNull()
    }
}
