package io.github.matteopi.budgeting.core.importer

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfCellsTest {

    private fun chunk(text: String, x: Float, y: Float = 100f, page: Int = 1, height: Float = 10f, width: Float = text.length * 5f) =
        PdfTextChunk(text, x, y, x + width, height, page)

    private fun assertSameCells(expected: List<PdfTextChunk>, actual: List<PdfTextChunk>, label: String) {
        assertEquals(expected.size, actual.size, "$label: number of cells")
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            assertEquals(e.text, a.text, "$label: text of cell $i")
            assertEquals(e.page, a.page, "$label: page of '${e.text}'")
            assertClose(e.height, a.height, "$label: height of '${e.text}'")
            assertClose(e.x, a.x, "$label: x of '${e.text}'")
            assertClose(e.y, a.y, "$label: y of '${e.text}'")
            assertClose(e.endX, a.endX, "$label: endX of '${e.text}'")
        }
    }

    private fun assertClose(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) <= 0.01f, "$message: expected $expected but was $actual")
    }

    @Test
    fun `the fixture merges to the same cells whether it is reported by glyph, word or cell`() {
        val fromCells = PdfCells.merge(SantanderStatementFixture.cells())
        val fromGlyphs = PdfCells.merge(SantanderStatementFixture.glyphs())
        val fromWords = PdfCells.merge(SantanderStatementFixture.words())

        assertSameCells(fromCells, fromGlyphs, "glyphs")
        assertSameCells(fromCells, fromWords, "words")
        // Nothing is lost or invented: the cells hold exactly the fixture's text, trimmed.
        assertEquals(
            SantanderStatementFixture.cells().map { it.text.trim() },
            fromCells.map { it.text },
        )
    }

    @Test
    fun `cells that are separate in the fixture stay separate when drawn glyph by glyph`() {
        val cells = PdfCells.merge(SantanderStatementFixture.glyphs()).map { it.text }

        assertTrue("Money in" in cells && "Money out" in cells && "£ Balance" in cells)
        assertTrue("MISS ALEX TESTER" in cells)
        assertTrue("BILL PAYMENT VIA FASTER PAYMENT TO ALEX TESTER REFERENCE Transfer" in cells)
        assertTrue(", MANDATE NO 0003" in cells)
    }

    @Test
    fun `a change of page starts a new cell`() {
        val cells = PdfCells.merge(listOf(chunk("end", 10f, page = 1), chunk("start", 60f, page = 2)))
        assertEquals(listOf("end", "start"), cells.map { it.text })
    }

    @Test
    fun `a move to another line starts a new cell, a small wobble does not`() {
        val wobble = PdfCells.merge(listOf(chunk("ab", 10f, y = 100f), chunk("cd", 20f, y = 102.9f)))
        assertEquals(listOf("abcd"), wobble.map { it.text })

        val lines = PdfCells.merge(listOf(chunk("ab", 10f, y = 100f), chunk("cd", 20f, y = 103.1f)))
        assertEquals(listOf("ab", "cd"), lines.map { it.text })
    }

    @Test
    fun `moving back to the left starts a new cell`() {
        val cells = PdfCells.merge(listOf(chunk("right", 100f), chunk("left", 20f)))
        assertEquals(listOf("right", "left"), cells.map { it.text })
    }

    @Test
    fun `a column sized gap starts a new cell`() {
        // "ab" ends at 20 and the font size is 10: a gap under 9 (0.9 of it) keeps the chunks together.
        val together = PdfCells.merge(listOf(chunk("ab", 10f), chunk("cd", 28.5f)))
        assertEquals(listOf("ab cd"), together.map { it.text })

        val apart = PdfCells.merge(listOf(chunk("ab", 10f), chunk("cd", 29.5f)))
        assertEquals(listOf("ab", "cd"), apart.map { it.text })
    }

    @Test
    fun `a word sized gap gets one space and a touching chunk gets none`() {
        val spaced = PdfCells.merge(listOf(chunk("one", 10f), chunk("two", 28f)))
        assertEquals("one two", spaced.single().text)

        val touching = PdfCells.merge(listOf(chunk("one", 10f), chunk("two", 25f)))
        assertEquals("onetwo", touching.single().text)

        // A gap of 1 is under 0.15 of the font size: kerning, not a space.
        val hairline = PdfCells.merge(listOf(chunk("one", 10f), chunk("two", 26f)))
        assertEquals("onetwo", hairline.single().text)
    }

    @Test
    fun `no second space is added where a chunk already has whitespace at the join`() {
        val trailing = PdfCells.merge(listOf(chunk("one ", 10f), chunk("two", 33f)))
        assertEquals("one two", trailing.single().text)

        val leading = PdfCells.merge(listOf(chunk("one", 10f), chunk(" two", 28f)))
        assertEquals("one two", leading.single().text)

        val spaceChunk = PdfCells.merge(listOf(chunk("one", 10f), chunk(" ", 25f), chunk("two", 30f)))
        assertEquals("one two", spaceChunk.single().text)
    }

    @Test
    fun `cells are trimmed and blank cells are dropped`() {
        val cells = PdfCells.merge(
            listOf(
                chunk("   ", 10f, y = 50f),
                chunk(" padded ", 10f, y = 100f),
                chunk("  ", 10f, y = 150f),
            ),
        )
        val padded = cells.single()
        assertEquals("padded", padded.text)
        // 8 characters over 40 units: one character is 5 wide, so each side moves in by 5.
        assertClose(15f, padded.x, "x")
        assertClose(45f, padded.endX, "endX")
    }

    @Test
    fun `a merged cell spans from the first chunk to the last and takes the first chunk's baseline`() {
        val cell = PdfCells.merge(listOf(chunk("ab", 10f, y = 100f), chunk("cd", 20f, y = 100.5f))).single()
        assertEquals("abcd", cell.text)
        assertClose(10f, cell.x, "x")
        assertClose(100f, cell.y, "y")
        assertClose(30f, cell.endX, "endX")
    }

    @Test
    fun `empty input and empty chunks give no cells`() {
        assertEquals(emptyList(), PdfCells.merge(emptyList()))
        assertEquals(emptyList(), PdfCells.merge(listOf(chunk("", 10f))))
    }
}
