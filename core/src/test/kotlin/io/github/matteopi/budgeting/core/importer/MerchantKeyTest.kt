package io.github.matteopi.budgeting.core.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MerchantKeyTest {

    @Test
    fun `card payment strips the prefix, the date tail and the card reference number`() {
        assertEquals(
            "TESCO STORES",
            MerchantKey.normalise("CARD PAYMENT TO TESCO STORES 3012 ON 15-09-2026"),
        )
    }

    @Test
    fun `direct debit strips the prefix and the reference and mandate tail`() {
        assertEquals(
            "VODAFONE LTD",
            MerchantKey.normalise("DIRECT DEBIT PAYMENT TO VODAFONE LTD REF 123456789, MANDATE NO 0012"),
        )
    }

    @Test
    fun `faster payments receipt strips the prefix and the reference tail`() {
        assertEquals(
            "J SMITH",
            MerchantKey.normalise("FASTER PAYMENTS RECEIPT FROM J SMITH REF RENT"),
        )
    }

    @Test
    fun `contactless payment and standing order prefixes are stripped`() {
        assertEquals("GREGGS", MerchantKey.normalise("CONTACTLESS PAYMENT TO GREGGS"))
        assertEquals("LANDLORD LTD", MerchantKey.normalise("STANDING ORDER TO LANDLORD LTD"))
    }

    @Test
    fun `bill payment via faster payment prefix is stripped`() {
        assertEquals(
            "COUNCIL TAX",
            MerchantKey.normalise("BILL PAYMENT VIA FASTER PAYMENT TO COUNCIL TAX"),
        )
    }

    @Test
    fun `already uppercase or lowercase input normalises the same way`() {
        assertEquals(MerchantKey.normalise("card payment to Tesco Stores 3012 on 15-09-2026"), "TESCO STORES")
    }

    @Test
    fun `GBP and amounts are dropped`() {
        val result = MerchantKey.normalise("PURCHASE AMAZON.CO.UK 12.50 GBP")
        assertTrue("GBP" !in result)
        assertTrue("12.50" !in result)
    }

    @Test
    fun `result is capped at 40 characters`() {
        val long = "CARD PAYMENT TO " + "A VERY LONG MERCHANT NAME THAT GOES ON AND ON AND ON"
        val result = MerchantKey.normalise(long)
        assertTrue(result.length <= 40)
    }

    @Test
    fun `collapses repeated whitespace`() {
        assertEquals("TESCO STORES", MerchantKey.normalise("TESCO    STORES"))
    }
}
