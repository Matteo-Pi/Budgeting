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
    fun `a prefix padded with extra spaces is still stripped`() {
        assertEquals("UBER", MerchantKey.normalise("BILL PAYMENT VIA FASTER PAYMENT  TO UBER"))
        assertEquals("TESCO STORES", MerchantKey.normalise("CARD  PAYMENT TO TESCO STORES"))
    }

    @Test
    fun `stacked prefixes are all stripped`() {
        assertEquals("TESCO", MerchantKey.normalise("PURCHASE CARD PAYMENT TO TESCO"))
        assertEquals("TESCO", MerchantKey.normalise("PURCHASE PURCHASE PURCHASE TESCO"))
    }

    @Test
    fun `a number cut to four digits by the length cap does not survive a second pass`() {
        // 35 letters, a space, then "12345X": the cap keeps "1234", which is a number of its own.
        val description = "A".repeat(35) + " 12345X"
        assertEquals("A".repeat(35), MerchantKey.normalise(description))
    }

    @Test
    fun `normalising a key again never changes it`() {
        val descriptions = listOf(
            "BILL PAYMENT VIA FASTER PAYMENT  TO UBER *EATS 12345X REF: RENT ON **** 15-09-2026",
            "PURCHASE CARD PAYMENT TO SLC STUDENT LOANS 12.50 1234567890 AMZN MKTP UK",
            "CARD PAYMENT TO TESCO STORES 3012 ON 15-09-2026",
            "A".repeat(35) + " 12345X",
            "UNION SHOP (VIA GOOGLE PAY), ON 05-10-2026",
            "MORRISONS LEEDS - (VIA GOOGLE PAY), ON 06-10-2026",
            "SQ *CORNER CAFE (VIA APPLE PAY), ON 07-10-2026",
            "CARD PAYMENT TO SQ * SQ *COFFEE - - -",
            "NYX*PHOTOBOOTH (VIA GOOGLE PAY), ON 09-10-2026",
            "A".repeat(38) + " - -x",
            "A".repeat(38) + " -",
            "(VIA X)SQ *Y - (VIA Z)",
            "-",
            " - ",
            "SQ *",
            "XXL SPORTS XXXX1234",
            "",
            "   ",
        )
        for (description in descriptions) {
            val once = MerchantKey.normalise(description)
            assertEquals(once, MerchantKey.normalise(once), "'$description'")
        }
    }

    @Test
    fun `collapses repeated whitespace`() {
        assertEquals("TESCO STORES", MerchantKey.normalise("TESCO    STORES"))
    }

    @Test
    fun `the wallet a card was presented with is not part of the merchant`() {
        assertEquals("UNION SHOP", MerchantKey.normalise("UNION SHOP (VIA GOOGLE PAY), ON 05-10-2026"))
        assertEquals("UNION SHOP", MerchantKey.normalise("UNION SHOP (VIA APPLE PAY), ON 05-10-2026"))
        assertEquals("UNION SHOP", MerchantKey.normalise("union shop (via samsung pay), on 05-10-2026"))
        assertEquals("TESCO STORES", MerchantKey.normalise("TESCO STORES 1234 (VIA GOOGLE PAY), ON 08-10-2026"))
    }

    @Test
    fun `a dash left dangling after the merchant is removed`() {
        assertEquals("MORRISONS LEEDS", MerchantKey.normalise("MORRISONS LEEDS - (VIA GOOGLE PAY), ON 06-10-2026"))
        assertEquals("MORRISONS LEEDS", MerchantKey.normalise("MORRISONS LEEDS -"))
        assertEquals("MORRISONS LEEDS", MerchantKey.normalise("MORRISONS LEEDS - - -"))
        // A hyphen inside a name stays.
        assertEquals("CO-OP FOOD", MerchantKey.normalise("CO-OP FOOD"))
    }

    @Test
    fun `Square's SQ star prefix is removed`() {
        assertEquals("CORNER CAFE", MerchantKey.normalise("SQ *CORNER CAFE (VIA GOOGLE PAY), ON 07-10-2026"))
        assertEquals("CORNER CAFE", MerchantKey.normalise("SQ * CORNER CAFE"))
        assertEquals("COFFEE", MerchantKey.normalise("CARD PAYMENT TO SQ *COFFEE"))
        assertEquals("SQUARE ROOT LTD", MerchantKey.normalise("SQUARE ROOT LTD"))
    }

    @Test
    fun `a star inside a name is not a card mask`() {
        assertEquals("NYX*PHOTOBOOTH", MerchantKey.normalise("NYX*PHOTOBOOTH (VIA GOOGLE PAY), ON 09-10-2026"))
        assertEquals("XXL SPORTS", MerchantKey.normalise("XXL SPORTS"))
        // Real masks still go.
        assertEquals("AMAZON", MerchantKey.normalise("AMAZON XXXX1234"))
        assertEquals("AMAZON", MerchantKey.normalise("AMAZON ****1234"))
    }

    @Test
    fun `cleaned Santander statement descriptions give the same keys as the raw ones`() {
        assertEquals("JO BLOGGS", MerchantKey.normalise("JO BLOGGS, ref pizza"))
        assertEquals("JO BLOGGS", MerchantKey.normalise("BILL PAYMENT VIA FASTER PAYMENT TO JO BLOGGS REFERENCE pizza , MANDATE NO 0002"))
        assertEquals("FROM A TESTER", MerchantKey.normalise("From A Tester"))
    }
}
