package io.github.matteopi.budgeting.core.importer

/** A parsed CSV/TSV grid: raw string cells, row by row (a header row, if any, is row 0). */
data class CsvTable(val rows: List<List<String>>, val delimiter: Char)

/**
 * RFC-4180-ish CSV parsing that also copes with real bank exports: a UTF-8 BOM, `,`/`;`/tab
 * delimiters (auto-detected), `""`-escaped quotes, quoted newlines, and CRLF or LF line endings.
 * See docs/PLAN.md §5.16.
 */
object CsvParser {

    private val CANDIDATE_DELIMITERS = listOf(',', ';', '\t')
    private const val BOM = '﻿'

    fun parse(text: String): CsvTable {
        val cleaned = if (text.isNotEmpty() && text[0] == BOM) text.substring(1) else text
        val delimiter = detectDelimiter(cleaned)
        return CsvTable(tokenize(cleaned, delimiter), delimiter)
    }

    private fun detectDelimiter(text: String): Char {
        var best = CANDIDATE_DELIMITERS.first()
        var bestScore = -1
        var bestCols = -1
        for (d in CANDIDATE_DELIMITERS) {
            val rows = tokenize(text, d)
            // Sampling every tokenized row (not just visually "non-blank" ones) keeps the
            // comparison fair: a genuinely blank source line (e.g. ",,") still has the right
            // delimiter's usual column count once split, but looks like one non-blank field
            // under the wrong delimiter, which would otherwise unfairly favour it.
            val sample = rows.take(20)
            if (sample.isEmpty()) continue
            val freq = sample.map { it.size }.groupingBy { it }.eachCount()
            val mode = freq.entries.maxByOrNull { it.value } ?: continue
            if (mode.value > bestScore || (mode.value == bestScore && mode.key > bestCols)) {
                bestScore = mode.value
                bestCols = mode.key
                best = d
            }
        }
        return best
    }

    private fun tokenize(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = text.length

        fun endField() {
            row.add(field.toString().trim())
            field.setLength(0)
        }
        fun endRow() {
            endField()
            rows.add(row)
            row = mutableListOf()
        }

        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < n && text[i + 1] == '"' -> { field.append('"'); i += 2 }
                    c == '"' -> { inQuotes = false; i++ }
                    else -> { field.append(c); i++ }
                }
                continue
            }
            when {
                c == '"' -> { inQuotes = true; i++ }
                c == delimiter -> { endField(); i++ }
                c == '\r' -> {
                    endRow()
                    i += if (i + 1 < n && text[i + 1] == '\n') 2 else 1
                }
                c == '\n' -> { endRow(); i++ }
                else -> { field.append(c); i++ }
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
