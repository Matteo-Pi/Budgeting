package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Money

/** Synthetic till receipts written from common UK conventions (no real receipts yet). */
object ReceiptFixtures {

    /** Items sum to 12.84: see [TESCO_ITEMS]. */
    val TESCO = """
        TESCO EXTRA
        Tesco Stores Ltd
        Bournemouth Road, Southampton SO15 2AB
        Tel: 0345 677 9388
        VAT No: GB 220 4302 31
        Clubcard number: **** **** **** 1234
        12/10/2026 14:32
        MILK SEMI SKIMMED 2PT   1.45
        HOVIS MEDIUM SLICED 800G   1.30 A
        Clubcard Price   -0.30
        TS FINEST SALMON FILLETS   4.50
        CC PRICE  -£1.00
        BANANAS LOOSE
        0.452 kg @ £1.20/kg   0.54
        CHEDDAR MATURE 350G   2.20
        2 @ £1.10
        YOGHURT STRAWBERRY   0.60
        YOGHURT STRAWBERRY   0.60
        YOGHURT STRAWBERRY   0.60
        3 for £1.50   -0.30
        3 @ £0.85
        SATSUMAS EASY PEEL   2.55
        5P CARRIER BAG   0.10
        SUBTOTAL   12.84
        TOTAL TO PAY   £12.84
        VISA CONTACTLESS   £12.84
        CHANGE DUE   0.00
        VAT SUMMARY
        A 20.00%   VAT 0.38   NET 1.90
        Total Clubcard savings today £1.60
        Clubcard points earned: 12
        Thank you for shopping at Tesco
        5012345678900
    """.trimIndent()

    val TESCO_TOTAL = Money(1284)

    val SAINSBURYS = """
        Sainsbury's
        Sainsbury's Supermarkets Ltd
        Kings Road, London SW6 4LZ
        Tel 020 7731 1234
        VAT NO GB 232 6381 92
        12 Oct 2026 18:05
        SO ORGANIC SEMI SKIMMED MILK 2L   1.55
        BASICS WHITE BREAD   0.65
        FRESH CHICKEN BREAST 650G   4.20
        Nectar Price   -0.70
        LOOSE ONIONS
        0.320 kg @ £1.10/kg   0.35
        PIZZA MARGHERITA   2.50
        2 x £1.25
        ICE CREAM VANILLA   2.00
        ICE CREAM VANILLA   2.00
        MULTIBUY 2 for £3.00   -1.00
        BAG FOR LIFE   0.30
        BALANCE DUE   £11.85
        DEBIT CARD   £11.85
        Nectar points earned this shop: 11
        Nectar card ending 4321
        Thank you for shopping at Sainsbury's
    """.trimIndent()

    val SAINSBURYS_TOTAL = Money(1185)

    val MORRISONS = """
        MORRISONS
        Wm Morrison Supermarkets PLC
        Gloucester Rd, Bristol BS7 8AB
        Tel: 0117 969 1234
        VAT Reg No 345 1234 56
        Date: 12-10-26 Time: 09.14
        M SEMI SKIMMED MILK 4PT   1.65
        M CHICKEN THIGHS 1KG   3.50
        More Card Price   -0.50
        BANANAS LOOSE 0.612 kg @ £0.95/kg   0.58
        4 @ £0.45
        M SAVERS BAKED BEANS   1.80
        M CARRIER BAG   0.10
        M FRUIT AND NUT   1.20
        More Card   0.20-
        TOTAL   £8.13
        MASTERCARD   £8.13
        CHANGE   0.00
        Your More Card number ends 9876
        More Points earned 8
    """.trimIndent()

    val MORRISONS_TOTAL = Money(813)

    /** Not a recognised store; "£0.50 off" and a coupon. */
    val GENERIC = """
        CORNER SHOP LTD
        High Street
        12.10.2026
        SANDWICH CHICKEN SALAD   2.99
        £0.50 off
        CRISPS SALT & VINEGAR   1.00
        COUPON   -0.25
        TOTAL   3.24
        CASH   5.00
        CHANGE   1.76
    """.trimIndent()
}
