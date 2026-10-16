package io.github.matteopi.budgeting.core.importer

import kotlin.math.abs
import kotlin.math.max

/**
 * Joins the pieces of text a PDF extractor reports (glyphs, words or whole cells, whatever its
 * granularity) into table cells, so the statement parsers see the same cells either way.
 * See docs/PLAN.md §5.16.
 */
object PdfCells {

    /** A line change: the baseline moved by more than this share of the font size. */
    private const val SAME_LINE = 0.3f

    /** The text moved back left by more than this share of the font size (a new cell starts there). */
    private const val MOVED_LEFT = 0.5f

    /** A gap wider than this share of the font size separates two columns. */
    private const val COLUMN_GAP = 0.9f

    /** A gap wider than this share of the font size is a space between words. */
    private const val WORD_GAP = 0.15f

    /**
     * Cells in drawing order: consecutive chunks on the same baseline, left to right, with no
     * column-sized gap between them, make one cell. A space is inserted where the gap between two
     * chunks is word-sized and neither side already has whitespace there. Cell text is trimmed
     * (and its edges moved in by the width of what was trimmed); blank cells are dropped.
     */
    fun merge(chunks: List<PdfTextChunk>): List<PdfTextChunk> {
        val cells = ArrayList<PdfTextChunk>()
        var text: StringBuilder? = null
        var first: PdfTextChunk? = null
        var last: PdfTextChunk? = null
        var endX = 0f

        fun flush() {
            val start = first ?: return
            val trimmed = trimmed(text.toString(), start, endX)
            if (trimmed != null) cells += trimmed
            text = null
            first = null
            last = null
        }

        for (chunk in chunks) {
            if (chunk.text.isEmpty()) continue
            val prev = last
            if (prev == null || startsNewCell(prev, chunk)) {
                flush()
                text = StringBuilder(chunk.text)
                first = chunk
            } else {
                val gap = chunk.x - prev.endX
                val builder = text!!
                val needsSpace = gap > WORD_GAP * max(prev.height, chunk.height) &&
                    !builder.last().isWhitespace() && !chunk.text.first().isWhitespace()
                if (needsSpace) builder.append(' ')
                builder.append(chunk.text)
            }
            last = chunk
            endX = chunk.endX
        }
        flush()
        return cells
    }

    private fun startsNewCell(prev: PdfTextChunk, chunk: PdfTextChunk): Boolean {
        val height = max(prev.height, chunk.height)
        return chunk.page != prev.page ||
            abs(chunk.y - prev.y) > SAME_LINE * height ||
            chunk.x < prev.endX - MOVED_LEFT * height ||
            chunk.x - prev.endX > COLUMN_GAP * height
    }

    /**
     * The cell for [raw] (spanning [start].x to [endX]) without surrounding whitespace, or null if
     * it is blank. The edges move in by the average character width for each trimmed character,
     * so a cell reported with a trailing space ends where the same cell reported word by word does.
     */
    private fun trimmed(raw: String, start: PdfTextChunk, endX: Float): PdfTextChunk? {
        val lead = raw.length - raw.trimStart().length
        val trail = raw.length - raw.trimEnd().length
        if (lead == raw.length) return null
        val charWidth = (endX - start.x) / raw.length
        return PdfTextChunk(
            text = raw.trim(),
            x = start.x + lead * charWidth,
            y = start.y,
            endX = endX - trail * charWidth,
            height = start.height,
            page = start.page,
        )
    }
}
