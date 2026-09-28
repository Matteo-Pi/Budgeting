package io.github.matteopi.budgeting.core.importer

import kotlin.test.Test
import kotlin.test.assertEquals

class CsvParserTest {

    @Test
    fun `parses plain comma-separated rows`() {
        val table = CsvParser.parse("Date,Description,Amount\n01/01/2026,Tesco,-12.50\n")
        assertEquals(',', table.delimiter)
        assertEquals(
            listOf(
                listOf("Date", "Description", "Amount"),
                listOf("01/01/2026", "Tesco", "-12.50"),
            ),
            table.rows,
        )
    }

    @Test
    fun `strips a leading UTF-8 BOM`() {
        val table = CsvParser.parse("﻿Date,Amount\n01/01/2026,1.00\n")
        assertEquals("Date", table.rows[0][0])
    }

    @Test
    fun `quoted field with embedded comma is kept together`() {
        val table = CsvParser.parse("Date,Description,Amount\n01/01/2026,\"Tesco, Superstore\",-1.00\n")
        assertEquals("Tesco, Superstore", table.rows[1][1])
    }

    @Test
    fun `doubled quotes escape a literal quote`() {
        val table = CsvParser.parse("Description\n\"He said \"\"hi\"\"\"\n")
        assertEquals("He said \"hi\"", table.rows[1][0])
    }

    @Test
    fun `quoted field can contain a newline`() {
        val table = CsvParser.parse("Description\n\"Line one\nLine two\"\nSecondRow\n")
        assertEquals("Line one\nLine two", table.rows[1][0])
        assertEquals("SecondRow", table.rows[2][0])
    }

    @Test
    fun `handles CRLF line endings`() {
        val table = CsvParser.parse("A,B\r\n1,2\r\n3,4\r\n")
        assertEquals(3, table.rows.size)
        assertEquals(listOf("3", "4"), table.rows[2])
    }

    @Test
    fun `detects a semicolon delimiter`() {
        val table = CsvParser.parse("Date;Description;Amount\n01/01/2026;Tesco;-12.50\n02/01/2026;Shell;-30.00\n")
        assertEquals(';', table.delimiter)
        assertEquals(3, table.rows[0].size)
    }

    @Test
    fun `detects a tab delimiter`() {
        val table = CsvParser.parse("Date\tDescription\tAmount\n01/01/2026\tTesco\t-12.50\n02/01/2026\tShell\t-30.00\n")
        assertEquals('\t', table.delimiter)
        assertEquals(3, table.rows[0].size)
    }

    @Test
    fun `trims whitespace around cells`() {
        val table = CsvParser.parse("Date, Description ,Amount\n01/01/2026 , Tesco , -1.00\n")
        assertEquals(listOf("Date", "Description", "Amount"), table.rows[0])
        assertEquals(listOf("01/01/2026", "Tesco", "-1.00"), table.rows[1])
    }

    @Test
    fun `no trailing blank row when text ends with a newline`() {
        val table = CsvParser.parse("A,B\n1,2\n")
        assertEquals(2, table.rows.size)
    }
}
