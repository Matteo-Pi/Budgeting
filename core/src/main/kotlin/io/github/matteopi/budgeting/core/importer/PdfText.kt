package io.github.matteopi.budgeting.core.importer

/**
 * One piece of text drawn on a PDF page, in the order the page draws it. The piece can be a
 * single glyph, a word or a whole table cell, depending on what the extractor reports.
 * Coordinates are in PDF points with y growing down the page; [x] and [endX] are the left and
 * right edges, [height] is the font size, and [page] counts from 1. See docs/PLAN.md §5.16.
 */
data class PdfTextChunk(
    val text: String,
    val x: Float,
    val y: Float,
    val endX: Float,
    val height: Float,
    val page: Int,
)
