package io.github.matteopi.budgeting.core.recipes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IngredientLinesTest {

    private fun parse(text: String) = IngredientLines.parse(text)

    private fun ingredient(
        name: String = "Thing",
        unit: BaseUnit = BaseUnit.G,
        vararg portions: Pair<String, Double>,
    ) = Ingredient(id = 1, name = name, unit = unit, portions = portions.map { Portion(it.first, it.second) })

    private fun resolve(text: String, ingredient: Ingredient) = IngredientLines.resolve(parse(text), ingredient)

    // ---- the starter book -------------------------------------------------------------------

    /** The two lines §11.4 names: the 300 ml is the water, and the salt has no leading amount. */
    private val knownExceptions = setOf(
        "300 ml chicken stock (1 cube)",
        "Salt only after tasting (up to ¼ tsp)",
    )

    @Test
    fun `every starter line parses and resolves to the bundle quantity, bar the two known exceptions`() {
        var checked = 0
        val off = mutableSetOf<String>()
        for (recipe in StarterBook.bundle.recipes) {
            for (line in recipe.lines) {
                val key = line.ingredient ?: continue
                val quantity = line.quantity
                assertNotNull(quantity, "${recipe.key}: \"${line.text}\" has an ingredient but no quantity")
                val ingredient = StarterBook.ingredient(key)
                val resolved = IngredientLines.resolve(parse(line.text), ingredient)
                checked++
                if (resolved == null || Math.abs(resolved.quantity - quantity) > 0.01) {
                    off += line.text
                    if (line.text !in knownExceptions) {
                        assertEquals(quantity, resolved?.quantity, "${recipe.key}: \"${line.text}\" -> ${ingredient.name}")
                    }
                }
            }
        }
        assertEquals(knownExceptions, off)
        assertTrue(checked >= 70, "checked $checked lines")
    }

    @Test
    fun `the two known exceptions are reported unresolved or different`() {
        val stock = StarterBook.ingredient("stock_cube")
        val water = resolve("300 ml chicken stock (1 cube)", stock)
        assertNotNull(water)
        assertEquals(300.0, water.quantity, 1e-9)
        assertTrue(water.estimated, "ml on a gram ingredient is an estimate")

        assertNull(resolve("Salt only after tasting (up to ¼ tsp)", StarterBook.ingredient("salt")))
    }

    @Test
    fun `lines with no ingredient are skipped by the starter check`() {
        val unmatched = StarterBook.bundle.recipes.flatMap { it.lines }.filter { it.ingredient == null }
        assertEquals(listOf("4 tbsp water", "400 ml boiling water, for the stock"), unmatched.map { it.text })
    }

    // ---- amounts ----------------------------------------------------------------------------

    @Test
    fun `amounts in every written form`() {
        assertEquals(2.0, parse("2 onions").amount)
        assertEquals(1.5, parse("1.5 kg potatoes").amount)
        assertEquals(0.5, parse("½ lemon").amount)
        assertEquals(0.25, parse("¼ tsp salt").amount)
        assertEquals(0.75, parse("¾ tsp salt").amount)
        assertEquals(1.0 / 3, parse("⅓ cup milk").amount!!, 1e-9)
        assertEquals(2.0 / 3, parse("⅔ cup milk").amount!!, 1e-9)
        assertEquals(1.5, parse("1 1/2 cups flour").amount)
        assertEquals(1.5, parse("1½ onions").amount)
        assertEquals(1.5, parse("1 ½ onions").amount)
        assertEquals(0.5, parse("1/2 tsp salt").amount)
        assertEquals(320.0, parse("320g chicken").amount)
    }

    @Test
    fun `a range uses its first value`() {
        val p = parse("1-2 tbsp olive oil")
        assertEquals(1.0, p.amount)
        assertEquals(Measure.TBSP, p.measure)
        assertEquals(2.0, parse("2–3 onions").amount)
        assertEquals(1.0, parse("1 to 2 onions").amount)
    }

    @Test
    fun `a and an count as one, and a pinch of is one pinch`() {
        assertEquals(1.0, parse("a lemon").amount)
        assertEquals(1.0, parse("an onion").amount)
        assertEquals(0.5, parse("half a lemon").amount)
        val a = parse("a pinch of salt")
        assertEquals(1.0, a.amount)
        assertEquals(Measure.PINCH, a.measure)
        assertEquals("salt", a.name)
        val b = parse("Pinch of garlic powder")
        assertEquals(1.0, b.amount)
        assertEquals(Measure.PINCH, b.measure)
        assertEquals("garlic powder", b.name)
    }

    @Test
    fun `a line with no amount is kept as text with no quantity`() {
        val p = parse("Salt and pepper to taste")
        assertNull(p.amount)
        assertNull(p.measure)
        assertEquals("Salt and pepper to taste", p.name)
        assertNull(resolve("Salt and pepper to taste", ingredient()))
    }

    @Test
    fun `measures singular or plural, with or without a space`() {
        assertEquals(Measure.G, parse("320g chicken").measure)
        assertEquals(Measure.G, parse("320 g chicken").measure)
        assertEquals(Measure.KG, parse("1 kg potatoes").measure)
        assertEquals(Measure.ML, parse("250ml milk").measure)
        assertEquals(Measure.L, parse("1 litre water").measure)
        assertEquals(Measure.L, parse("2 litres water").measure)
        assertEquals(Measure.TSP, parse("1 teaspoon salt").measure)
        assertEquals(Measure.TSP, parse("2 tsp. salt").measure)
        assertEquals(Measure.TBSP, parse("2 tablespoons oil").measure)
        assertEquals(Measure.TBSP, parse("1 Tbsp oil").measure)
        assertEquals(Measure.OZ, parse("4 oz flour").measure)
        assertEquals(Measure.LB, parse("1 lb mince").measure)
        assertEquals(Measure.CUP, parse("2 cups rice").measure)
        assertEquals("olive oil", parse("2 tbsp of olive oil").name)
        assertEquals(Measure.TBSP, parse("1 heaped tbsp cumin").measure)
        assertEquals("cumin", parse("1 heaped tbsp cumin").name)
    }

    @Test
    fun `a count has no measure and the words stay in the name`() {
        val p = parse("3 garlic cloves, minced")
        assertEquals(3.0, p.amount)
        assertNull(p.measure)
        assertEquals("garlic cloves", p.name)
        assertEquals(listOf("garlic", "cloves"), p.nameWords)
        assertEquals("minced", p.note)
        assertEquals("large", parse("1 large onion").nameWords.first())
    }

    @Test
    fun `a multiplier is read from times or x`() {
        val a = parse("2 × 400 g tins chopped tomatoes")
        assertEquals(2.0, a.multiplier)
        assertEquals(400.0, a.amount)
        assertEquals(Measure.G, a.measure)
        assertEquals("tins chopped tomatoes", a.name)
        val b = parse("2 x 400g tins kidney beans, drained")
        assertEquals(2.0, b.multiplier)
        assertEquals(400.0, b.amount)
        assertEquals(Measure.G, b.measure)
        val c = parse("2x400g tins beans")
        assertEquals(2.0, c.multiplier)
        assertEquals(400.0, c.amount)
        assertNull(parse("2 xanthan sachets").multiplier)
    }

    @Test
    fun `notes come from after a comma and from brackets`() {
        assertEquals("sliced 1 cm thick", parse("1 onion, sliced 1 cm thick").note)
        assertEquals("finely chopped; the heat", parse("1 bird's eye chilli, finely chopped (the heat)").note)
        assertEquals("5% fat", parse("500 g lean beef mince (5% fat)").note)
        assertNull(parse("2 onions").note)
        assertEquals("bird's eye chilli", parse("1 bird's eye chilli, finely chopped").name)
    }

    @Test
    fun `descriptive words before the first comma stay in the name`() {
        val p = parse("4 boneless, skinless chicken thighs (about 480 g)")
        assertEquals("boneless skinless chicken thighs", p.name)
        assertNull(p.note)
        assertEquals(BracketWeight(480.0, Measure.G), p.bracketWeight)
    }

    @Test
    fun `optional anywhere marks the line optional`() {
        assertTrue(parse("1 bird's eye chilli, finely chopped (optional)").optional)
        assertTrue(parse("Optional: 1 tsp honey").optional)
        assertTrue(parse("1 tsp smoked paprika, optional").optional)
        assertFalse(parse("1 tsp smoked paprika").optional)
        assertEquals("smoked paprika", parse("1 tsp smoked paprika (optional)").name)
        assertNull(parse("1 tsp smoked paprika (optional)").note)
    }

    @Test
    fun `bracketed weights are read, other brackets are notes`() {
        assertEquals(BracketWeight(320.0, Measure.G), parse("2 chicken breast fillets (about 320 g)").bracketWeight)
        assertEquals(BracketWeight(350.0, Measure.G), parse("1 head of broccoli (approx. 350g)").bracketWeight)
        assertEquals(BracketWeight(1.0, Measure.KG), parse("2 chickens (~1 kg)").bracketWeight)
        assertEquals(BracketWeight(160.0, Measure.G, each = true), parse("2 fillets (160 g each)").bracketWeight)
        assertNull(parse("150 g rice (75 g per person)").bracketWeight)
        assertEquals("75 g per person", parse("150 g rice (75 g per person)").note)
        assertNull(parse("1 pepper (red or yellow)").bracketWeight)
    }

    // ---- resolve ----------------------------------------------------------------------------

    @Test
    fun `counts use the portion named by a word of the name, else each`() {
        val garlic = ingredient("Garlic", BaseUnit.G, "clove" to 5.0, "bulb" to 60.0)
        assertEquals(15.0, resolve("3 garlic cloves", garlic)!!.quantity)
        assertEquals(10.0, resolve("2 cloves of garlic", garlic)!!.quantity)
        assertEquals(60.0, resolve("1 garlic bulb", garlic)!!.quantity)
        val onion = ingredient("Onion", BaseUnit.G, "each" to 150.0)
        assertEquals(300.0, resolve("2 onions", onion)!!.quantity)
        assertFalse(resolve("2 onions", onion)!!.estimated)
        val beans = ingredient("Kidney beans", BaseUnit.G, "tin" to 240.0)
        assertEquals(480.0, resolve("2 tins kidney beans", beans)!!.quantity)
    }

    @Test
    fun `a count with no portion is unresolved`() {
        assertNull(resolve("2 onions", ingredient()))
        assertNull(resolve("2 onions", ingredient("Onion", BaseUnit.G, "clove" to 5.0)))
    }

    @Test
    fun `a bracketed weight beats the portion estimate`() {
        val chicken = ingredient("Chicken", BaseUnit.G, "fillet" to 160.0, "each" to 150.0)
        assertEquals(300.0, resolve("2 chicken breast fillets (about 300 g)", chicken)!!.quantity)
        assertEquals(320.0, resolve("2 chicken breast fillets", chicken)!!.quantity)
        assertEquals(320.0, resolve("2 chicken breast fillets (160 g each)", ingredient("Chicken", BaseUnit.G))!!.quantity)
        val broccoli = ingredient("Broccoli", BaseUnit.G, "head" to 375.0)
        assertEquals(350.0, resolve("1 head of broccoli (about 350 g), florets", broccoli)!!.quantity)
    }

    @Test
    fun `a leading weight always wins over brackets`() {
        val rice = ingredient("Rice")
        assertEquals(150.0, resolve("150 g long-grain rice (75 g per person)", rice)!!.quantity)
        assertEquals(150.0, resolve("150 g rice (about 300 g cooked)", rice)!!.quantity)
        assertFalse(resolve("150 g rice", rice)!!.estimated)
    }

    @Test
    fun `N times 400 g tins uses the tin portion when the ingredient has one`() {
        val tomatoes = ingredient("Tomatoes", BaseUnit.G, "tin" to 400.0)
        val beans = ingredient("Kidney beans", BaseUnit.G, "tin" to 240.0)
        val plain = ingredient("Sweetcorn")
        assertEquals(800.0, resolve("2 × 400 g tins chopped tomatoes", tomatoes)!!.quantity)
        assertEquals(480.0, resolve("2 × 400 g tins kidney beans, drained", beans)!!.quantity)
        assertEquals(480.0, resolve("2 x 400g tins kidney beans", beans)!!.quantity)
        assertEquals(800.0, resolve("2 × 400 g tins sweetcorn", plain)!!.quantity)
        assertEquals(240.0, resolve("1 × 400 g tin kidney beans", beans)!!.quantity)
    }

    @Test
    fun `weights and volumes convert to the base unit`() {
        val flour = ingredient("Flour")
        assertEquals(1500.0, resolve("1.5 kg flour", flour)!!.quantity, 1e-9)
        assertEquals(113.4, resolve("4 oz flour", flour)!!.quantity, 1e-9)
        assertEquals(453.6, resolve("1 lb flour", flour)!!.quantity, 1e-9)
        assertFalse(resolve("4 oz flour", flour)!!.estimated)
        val milk = ingredient("Milk", BaseUnit.ML)
        assertEquals(250.0, resolve("250 ml milk", milk)!!.quantity)
        assertEquals(1500.0, resolve("1.5 litres milk", milk)!!.quantity)
        assertEquals(240.0, resolve("1 cup milk", milk)!!.quantity)
        assertFalse(resolve("1 cup milk", milk)!!.estimated)
    }

    @Test
    fun `ml on a gram ingredient and grams on a millilitre ingredient are estimates`() {
        val honey = ingredient("Honey", BaseUnit.G)
        val ml = resolve("100 ml honey", honey)!!
        assertEquals(100.0, ml.quantity)
        assertTrue(ml.estimated)
        val oil = ingredient("Oil", BaseUnit.ML)
        val g = resolve("50 g oil", oil)!!
        assertEquals(50.0, g.quantity)
        assertTrue(g.estimated)
    }

    @Test
    fun `spoons use the ingredient's own portion, the other spoon at three to one, or an estimate`() {
        val cornflour = ingredient("Cornflour", BaseUnit.G, "tbsp" to 9.0)
        assertEquals(9.0, resolve("1 tbsp cornflour", cornflour)!!.quantity, 1e-9)
        assertEquals(6.0, resolve("2 tsp cornflour", cornflour)!!.quantity, 1e-9)
        assertFalse(resolve("2 tsp cornflour", cornflour)!!.estimated)
        val salt = ingredient("Salt", BaseUnit.G, "tsp" to 6.0)
        assertEquals(4.5, resolve("¾ tsp salt", salt)!!.quantity, 1e-9)
        assertEquals(18.0, resolve("1 tbsp salt", salt)!!.quantity, 1e-9)
        val unknown = ingredient("Mystery powder", BaseUnit.G)
        val tsp = resolve("2 tsp mystery powder", unknown)!!
        assertEquals(10.0, tsp.quantity)
        assertTrue(tsp.estimated)
        assertEquals(15.0, resolve("1 tbsp mystery powder", unknown)!!.quantity)
        val oil = ingredient("Oil", BaseUnit.ML)
        assertEquals(30.0, resolve("2 tbsp oil", oil)!!.quantity)
        assertFalse(resolve("2 tbsp oil", oil)!!.estimated)
        assertEquals(5.0, resolve("1 tsp oil", oil)!!.quantity)
    }

    @Test
    fun `a pinch uses the pinch portion if set, else a third of a gram`() {
        assertEquals(0.3, resolve("a pinch of salt", ingredient("Salt"))!!.quantity, 1e-9)
        val powder = ingredient("Garlic powder", BaseUnit.G, "pinch" to 0.5)
        assertEquals(0.5, resolve("Pinch of garlic powder", powder)!!.quantity, 1e-9)
        assertEquals(1.0, resolve("2 pinches garlic powder", powder)!!.quantity, 1e-9)
    }

    @Test
    fun `a cup of a gram ingredient uses its cup portion or 240 g estimated`() {
        val rice = ingredient("Rice", BaseUnit.G, "cup" to 185.0)
        assertEquals(185.0, resolve("1 cup rice", rice)!!.quantity)
        val other = resolve("1 cup flour", ingredient("Flour"))!!
        assertEquals(240.0, other.quantity)
        assertTrue(other.estimated)
    }

    // ---- scale ------------------------------------------------------------------------------

    private fun scale(text: String, factor: Double, base: Double? = null, unit: BaseUnit? = null) =
        IngredientLines.scaleText(text, factor, base, unit)

    @Test
    fun `counts and spoons scale to the nearest half`() {
        assertEquals("3 eggs", scale("2 eggs", 1.5))
        assertEquals("1½ onion", scale("1 onion", 1.5))
        assertEquals("3½ eggs", scale("3 eggs", 1.1))
        assertEquals("4 eggs", scale("3 eggs", 1.25))
        assertEquals("1½ tbsp olive oil", scale("1 tbsp olive oil", 1.5))
        assertEquals("1½ tsp salt", scale("¾ tsp salt", 2.0))
        assertEquals("½ lemon", scale("1 lemon", 0.5))
        assertEquals("¼ tsp salt", scale("¼ tsp salt", 1.1))
        assertEquals("¼ tsp salt", scale("¼ tsp salt", 0.5), "never scaled to nothing")
    }

    @Test
    fun `weights and volumes scale to the nearest five, or one decimal place under ten`() {
        assertEquals("480 g", scale("320 g", 1.5))
        assertEquals("480 g chicken", scale("320 g chicken", 1.5))
        assertEquals("130 g green beans", scale("100 g green beans", 1.3))
        assertEquals("135 g green beans", scale("100 g green beans", 1.33))
        assertEquals("7.5 g yeast", scale("5 g yeast", 1.5))
        assertEquals("3 g yeast", scale("2 g yeast", 1.5))
        assertEquals("600 ml boiling water, for the stock", scale("400 ml boiling water, for the stock", 1.5))
        assertEquals("1.8 kg potatoes", scale("1.2 kg potatoes", 1.5))
        assertEquals("750 g lean beef mince (5% fat)", scale("500 g lean beef mince (5% fat)", 1.5))
        assertEquals("225 g long-grain rice (75 g per person)", scale("150 g long-grain rice (75 g per person)", 1.5))
    }

    @Test
    fun `in N times 400 g tins only N is scaled`() {
        assertEquals("3 × 400 g tins chopped tomatoes", scale("2 × 400 g tins chopped tomatoes", 1.5))
        assertEquals("5 × 400 g tins chopped tomatoes", scale("2 × 400 g tins chopped tomatoes", 2.5))
        assertEquals("1 × 400 g tins chopped tomatoes", scale("2 × 400 g tins chopped tomatoes", 0.5))
    }

    @Test
    fun `a bracketed weight is scaled too`() {
        assertEquals(
            "3 chicken breast fillets (about 480 g), sliced about 1 cm thick",
            scale("2 chicken breast fillets (about 320 g), sliced about 1 cm thick", 1.5),
        )
        assertEquals("1½ head of broccoli (about 525 g)", scale("1 head of broccoli (about 350 g)", 1.5))
    }

    @Test
    fun `after a count or a spoon the base amount is appended`() {
        assertEquals("1½ onion, diced (225 g)", scale("1 onion, diced", 1.5, 150.0, BaseUnit.G))
        assertEquals("3 garlic cloves, minced (15 g)", scale("2 garlic cloves, minced", 1.5, 10.0, BaseUnit.G))
        assertEquals("1½ tbsp cornflour (15 g)", scale("1 tbsp cornflour", 1.5, 9.0, BaseUnit.G))
        assertEquals("3 tsp honey (20 g)", scale("2 tsp honey", 1.5, 14.0, BaseUnit.G))
        assertEquals("2 onion (300 g)", scale("1 onion", 2.0, 150.0, BaseUnit.G))
        assertEquals("1½ tbsp olive oil (25 ml)", scale("1 tbsp olive oil", 1.5, 15.0, BaseUnit.ML))
    }

    @Test
    fun `the base amount is not appended to weights, tins or lines that already carry a weight`() {
        assertEquals("480 g chicken", scale("320 g chicken", 1.5, 320.0, BaseUnit.G))
        assertEquals("3 × 400 g tins chopped tomatoes", scale("2 × 400 g tins chopped tomatoes", 1.5, 800.0, BaseUnit.G))
        assertEquals("3 chicken breast fillets (about 480 g)", scale("2 chicken breast fillets (about 320 g)", 1.5, 320.0, BaseUnit.G))
    }

    @Test
    fun `a line with no leading amount gets the factor appended`() {
        assertEquals("Pinch of garlic powder ×1.5", scale("Pinch of garlic powder", 1.5, 0.3, BaseUnit.G))
        assertEquals("Salt only after tasting (up to ¼ tsp) ×2", scale("Salt only after tasting (up to ¼ tsp)", 2.0, 1.5, BaseUnit.G))
        assertEquals("Pinch of garlic powder ×1.25", scale("Pinch of garlic powder", 1.25, 0.3, BaseUnit.G))
    }

    @Test
    fun `lines without a quantity stay as written`() {
        assertEquals("Salt and pepper to taste", scale("Salt and pepper to taste", 2.0))
        assertEquals("Salt and pepper to taste", scale("Salt and pepper to taste", 1.5, null, null))
    }

    @Test
    fun `a range scales at both ends`() {
        assertEquals("2-4 onions", scale("1-2 onions", 2.0))
        assertEquals("1½-3 tbsp oil", scale("1-2 tbsp oil", 1.5))
    }

    @Test
    fun `a factor of one, or an invalid one, leaves the text alone`() {
        assertEquals("2 onions", scale("2 onions", 1.0, 300.0, BaseUnit.G))
        assertEquals("2 onions", scale("2 onions", 0.0))
        assertEquals("2 onions", scale("2 onions", Double.NaN))
    }

    @Test
    fun `scaling every starter line doubles its leading weight and still parses`() {
        var weights = 0
        for (recipe in StarterBook.bundle.recipes) {
            for (line in recipe.lines) {
                val scaled = IngredientLines.scaleText(line.text, 2.0, line.quantity, BaseUnit.G)
                assertTrue(scaled.isNotBlank(), line.text)
                val parsed = parse(line.text)
                val amount = parsed.amount
                if (amount != null && parsed.measure in setOf(Measure.G, Measure.ML) && parsed.multiplier == null) {
                    val doubled = amount * 2
                    val expected = if (doubled < 10) Math.floor(doubled * 10 + 0.5) / 10 else Math.floor(doubled / 5 + 0.5) * 5
                    assertEquals(expected, parse(scaled).amount, "${line.text} -> $scaled")
                    weights++
                }
            }
        }
        assertTrue(weights >= 10, "$weights weights were scaled")
    }
}
