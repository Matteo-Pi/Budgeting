package io.github.matteopi.budgeting.core.stress

import io.github.matteopi.budgeting.core.entry.AmountEntry
import io.github.matteopi.budgeting.core.entry.KeypadKey
import io.github.matteopi.budgeting.core.model.Money
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.fail

/** Random key sequences on the quick-add keypad: the entry must stay valid after every key. */
class AmountEntryStressTest {
    private val seedBase = env("STRESS_SEED", 1L)
    private val shape = Regex("""^(0|[1-9][0-9]{0,5})(\.[0-9]{0,2})?$""")

    private fun randomKey(r: Random): KeypadKey = when (r.nextInt(20)) {
        0, 1, 2 -> KeypadKey.Dot
        3, 4, 5 -> KeypadKey.Backspace
        6 -> KeypadKey.Clear
        7, 8 -> KeypadKey.Digit(0)
        else -> KeypadKey.Digit(r.nextInt(0, 10))
    }

    /** The pence a valid entry text stands for, worked out by hand from the text alone. */
    private fun penceOf(text: String): Long {
        if (text.isEmpty()) return 0
        val whole = text.substringBefore('.').toLong()
        val fraction = text.substringAfter('.', "").padEnd(2, '0')
        return whole * 100 + fraction.toLong()
    }

    @Test
    fun entryStaysValidUnderRandomKeySequences() {
        val requested = System.getenv("STRESS_COUNT")?.toIntOrNull()
        val count = if (requested == 1) 1 else maxOf(20_000, requested ?: 0)
        val failures = mutableListOf<CaseFailure>()
        for (i in 0 until count) {
            val seed = seedBase + i
            val random = Random(seed)
            var entry = AmountEntry()
            val keys = mutableListOf<KeypadKey>()
            try {
                repeat(random.nextInt(0, 60)) {
                    val key = randomKey(random)
                    keys += key
                    val before = entry
                    entry = entry.press(key)
                    check(seed, before, key, entry, failures)
                }
            } catch (t: Throwable) {
                failures += CaseFailure(seed, "amountEntry.exception", "${t::class.simpleName}: ${t.message} after $keys")
            }
        }
        if (failures.isNotEmpty()) {
            fail(StressReport.render("AmountEntry", failures, { seed -> "keys of case $seed" }, "STRESS_SEED=<seed> STRESS_COUNT=1 ./gradlew -p core test --tests '*AmountEntryStressTest*'"))
        }
    }

    private fun check(seed: Long, before: AmountEntry, key: KeypadKey, entry: AmountEntry, failures: MutableList<CaseFailure>) {
        val text = entry.text
        val context = "'${before.text}' + $key -> '$text'"
        if (text.isNotEmpty() && !shape.matches(text)) failures += CaseFailure(seed, "amountEntry.shape", "invalid text after $context")
        val integerDigits = text.substringBefore('.').length
        val decimals = if ('.' in text) text.length - text.indexOf('.') - 1 else 0
        if (integerDigits > 6) failures += CaseFailure(seed, "amountEntry.integerDigits", "$integerDigits integer digits: $context")
        if (decimals > 2) failures += CaseFailure(seed, "amountEntry.decimals", "$decimals decimals: $context")
        if (text.count { it == '.' } > 1) failures += CaseFailure(seed, "amountEntry.dots", "more than one dot: $context")
        if (text.startsWith(".")) failures += CaseFailure(seed, "amountEntry.leadingDot", "leading dot: $context")

        val expectedDisplay = if (text.isEmpty()) "£0" else "£$text"
        if (entry.display != expectedDisplay) failures += CaseFailure(seed, "amountEntry.display", "display '${entry.display}' for $context")
        val pence = penceOf(text)
        val expectedMoney = if (text.isEmpty() || pence == 0L) null else Money(pence)
        if (entry.money != expectedMoney) failures += CaseFailure(seed, "amountEntry.money", "money ${entry.money} but text says $expectedMoney: $context")

        when (key) {
            KeypadKey.Clear -> if (text.isNotEmpty()) failures += CaseFailure(seed, "amountEntry.clear", "Clear left '$text'")
            KeypadKey.Backspace -> if (text != before.text.dropLast(1)) failures += CaseFailure(seed, "amountEntry.backspace", "not one character shorter: $context")
            KeypadKey.Dot -> if (before.text.contains('.') && text != before.text) failures += CaseFailure(seed, "amountEntry.secondDot", "a second dot changed the text: $context")
            is KeypadKey.Digit -> {
                if (text.length > before.text.length + 1) failures += CaseFailure(seed, "amountEntry.digitGrowth", "a digit added more than one character: $context")
                // A digit is never silently lost when there is room for it.
                val room = if ('.' in before.text) decimalsOf(before.text) < 2 else before.text.length < 6 || before.text == "0"
                if (room && text == before.text && !(before.text == "0" && key.value == 0)) failures += CaseFailure(seed, "amountEntry.digitLost", "a digit with room to spare was ignored: $context")
            }
        }
    }

    private fun decimalsOf(text: String): Int = text.length - text.indexOf('.') - 1

    @Test
    fun digitsOutsideZeroToNineAreRejected() {
        for (bad in listOf(-1, 10, 42, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { AmountEntry().press(KeypadKey.Digit(bad)) }
        }
    }
}
