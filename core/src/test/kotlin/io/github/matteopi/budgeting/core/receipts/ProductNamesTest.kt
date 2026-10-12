package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Money
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProductNamesTest {

    @Test
    fun `upper cases, collapses spaces and strips the store prefix`() {
        assertEquals("SEMI SKMD MLK 2PT", ProductNames.normalise("TS  Semi  Skmd Mlk 2pt"))
        assertEquals("FINEST SALMON", ProductNames.normalise("Tesco Finest Salmon"))
        assertEquals("BASICS PASTA 500G", ProductNames.normalise("Sainsbury's Basics Pasta 500g"))
        assertEquals("BASICS PASTA 500G", ProductNames.normalise("SAINSBURYS BASICS PASTA 500G"))
        assertEquals("BEANS", ProductNames.normalise("JS Beans"))
        assertEquals("SAVERS BEANS", ProductNames.normalise("M SAVERS BEANS"))
        assertEquals("SAVERS BEANS", ProductNames.normalise("Morrisons Savers Beans"))
    }

    @Test
    fun `punctuation is stripped but decimal points between digits stay`() {
        assertEquals("SEMI SKIMMED MILK", ProductNames.normalise("semi-skimmed, milk!"))
        assertEquals("COKE 1.5L", ProductNames.normalise("COKE 1.5L"))
        assertEquals("COKE ZERO", ProductNames.normalise("COKE. ZERO."))
        assertEquals("MS COOKIES", ProductNames.normalise("M&S Cookies"))
    }

    @Test
    fun `several prefixes are all stripped but a name is never emptied`() {
        assertEquals("FINEST SALMON", ProductNames.normalise("TS TESCO FINEST SALMON"))
        assertEquals("TESCO", ProductNames.normalise("Tesco"))
        assertEquals("TS", ProductNames.normalise("ts"))
        assertEquals("M", ProductNames.normalise("M"))
        assertEquals("", ProductNames.normalise("  ?! "))
    }

    @Test
    fun `a prefix is only a prefix when it is a whole word`() {
        assertEquals("TSATSIKI", ProductNames.normalise("Tsatsiki"))
        assertEquals("MILK", ProductNames.normalise("MILK"))
        assertEquals("MORE CHEESE", ProductNames.normalise("MORE CHEESE"))
    }

    @Test
    fun `the same product on two receipts gets the same key`() {
        assertEquals(
            ProductNames.normalise("TS SEMI SKMD MLK 2PT"),
            ProductNames.normalise("Tesco semi skmd mlk 2pt"),
        )
    }

    @Test
    fun `normalise is idempotent on random input`() {
        val random = Random(3)
        val alphabet = "ABCMST ' &-.0123456789 "
        repeat(2000) {
            val raw = String(CharArray(random.nextInt(0, 25)) { alphabet[random.nextInt(alphabet.length)] })
            val once = ProductNames.normalise(raw)
            assertEquals(once, ProductNames.normalise(once), "input: '$raw'")
        }
    }

    // ---- Aliases ----

    private val milk = ProductAlias(Store.TESCO, "SEMI SKMD MLK 2PT", 1, "Semi-skimmed milk 2 pints", 10)
    private val milkSainsburys = ProductAlias(Store.SAINSBURYS, "SEMI SKMD MLK 2PT", 2, "Milk (Sainsbury's)", null)
    private val bread = ProductAlias(Store.MORRISONS, "SAVERS BREAD", 3, "Bread", 11)

    private fun item(name: String) = DraftItem(name, price = Money(100))

    @Test
    fun `exact store match is preferred`() {
        val aliases = Aliases(listOf(milkSainsburys, milk))
        assertEquals(milk, aliases.suggest(item("TS SEMI SKMD MLK 2PT"), Store.TESCO))
        assertEquals(milkSainsburys, aliases.suggest(item("TS SEMI SKMD MLK 2PT"), Store.SAINSBURYS))
    }

    @Test
    fun `another store's alias is the fallback`() {
        val aliases = Aliases(listOf(bread))
        assertEquals(bread, aliases.suggest(item("M Savers Bread"), Store.TESCO))
        assertEquals(bread, aliases.suggest(item("SAVERS BREAD"), Store.OTHER))
    }

    @Test
    fun `unknown names have no suggestion`() {
        val aliases = Aliases(listOf(milk, bread))
        assertNull(aliases.suggest(item("DIGESTIVES"), Store.TESCO))
        assertNull(Aliases(emptyList()).suggest(item("TS SEMI SKMD MLK 2PT"), Store.TESCO))
    }

    @Test
    fun `matching ignores case spacing and punctuation`() {
        val aliases = Aliases(listOf(milk))
        assertEquals(milk, aliases.suggest("ts  semi-skmd mlk 2pt", Store.TESCO))
    }

    @Test
    fun `the first alias in the list wins a repeated key`() {
        val newer = milk.copy(productId = 99, productName = "Newer")
        assertEquals(newer, Aliases(listOf(newer, milk)).suggest("SEMI SKMD MLK 2PT", Store.TESCO))
        assertEquals(milk, Aliases(listOf(milk, newer)).suggest("SEMI SKMD MLK 2PT", Store.TESCO))
    }

    @Test
    fun `a stored key that is not normalised still matches`() {
        val sloppy = ProductAlias(Store.TESCO, "ts semi skmd mlk 2pt", 1, "Milk")
        assertNotNull(Aliases(listOf(sloppy)).suggest("SEMI SKMD MLK 2PT", Store.TESCO))
    }

    @Test
    fun `learn produces an alias that suggest finds again`() {
        val learned = Aliases.learn(item("TS SEMI SKMD MLK 2PT"), Store.TESCO, 5, "Milk", 10)
        assertEquals(ProductAlias(Store.TESCO, "SEMI SKMD MLK 2PT", 5, "Milk", 10), learned)
        assertEquals(learned, Aliases(listOf(learned)).suggest(item("Tesco semi skmd mlk 2pt"), Store.TESCO))
    }
}
