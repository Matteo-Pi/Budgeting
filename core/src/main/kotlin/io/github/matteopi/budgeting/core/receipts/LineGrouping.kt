package io.github.matteopi.budgeting.core.receipts

/** Turns OCR fragments into printed lines. */
object LineGrouping {

    /** Tolerance in position units used when no fragment carries a height. */
    const val DEFAULT_TOLERANCE = 12f

    /** With heights known, fragments within this share of the median height are on one row. */
    private const val HEIGHT_FRACTION = 0.5f

    private val BLANKS = Regex("""[ \t]+""")

    /**
     * Merges fragments whose vertical centres are within [tolerance] of the row's running mean
     * centre into one row (ordered left to right, joined by single spaces); rows are ordered top
     * to bottom. Blank fragments are dropped.
     *
     * [tolerance] defaults to half the median fragment height when heights are known (see
     * [ReceiptLine.bottom]), else [DEFAULT_TOLERANCE].
     *
     * Text-only input (no fragment has any position) passes through as one row per line, in order.
     */
    fun rows(lines: List<ReceiptLine>, tolerance: Float? = null): List<ReceiptRow> {
        val fragments = lines.filter { it.text.isNotBlank() }
        if (fragments.isEmpty()) return emptyList()
        val unpositioned = fragments.all { it.top == 0f && it.bottom == 0f && it.left == 0f && it.right == 0f }
        if (unpositioned) return fragments.map { ReceiptRow(clean(it.text)) }

        val tol = tolerance ?: defaultTolerance(fragments)
        val sorted = fragments.sortedBy { centre(it) }

        val groups = ArrayList<MutableList<ReceiptLine>>()
        var meanCentre = 0f
        for (fragment in sorted) {
            val c = centre(fragment)
            val current = groups.lastOrNull()
            if (current != null && c - meanCentre <= tol) {
                current += fragment
                meanCentre = current.map { centre(it) }.average().toFloat()
            } else {
                groups += mutableListOf(fragment)
                meanCentre = c
            }
        }
        return groups.map { group ->
            ReceiptRow(group.sortedBy { it.left }.joinToString(" ") { clean(it.text) })
        }
    }

    /** Text sources: one row per non-blank line. */
    fun rows(text: String): List<ReceiptRow> =
        text.lines().filter { it.isNotBlank() }.map { ReceiptRow(clean(it)) }

    private fun clean(text: String) = text.trim().replace(BLANKS, " ")

    private fun centre(line: ReceiptLine): Float =
        if (line.bottom > line.top) (line.top + line.bottom) / 2f else line.top

    private fun defaultTolerance(fragments: List<ReceiptLine>): Float {
        val heights = fragments.filter { it.bottom > it.top }.map { it.bottom - it.top }.sorted()
        if (heights.isEmpty()) return DEFAULT_TOLERANCE
        return heights[heights.size / 2] * HEIGHT_FRACTION
    }
}
