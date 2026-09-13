package com.blackout.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the decision half of [Deskew] - when to rotate and whether to keep the result. The
 * rotation itself is three lines of `Bitmap.createBitmap` and needs a device.
 */
class DeskewTest {

    private fun spans(vararg angles: Float) = angles.mapIndexed { i, a ->
        TextSpan(
            id = i + 1,
            text = "line $i",
            rect = SpanRect(0, i * 40, 200, i * 40 + 30),
            confidence = 1f,
            blockIndex = 0,
            lineIndex = i,
            angleDeg = a,
        )
    }

    /** [height] is the span box height - the geometry half of the acceptance decision. */
    private fun ocr(vararg text: String, height: Int = 30) = OcrResult(
        spans = text.mapIndexed { i, t ->
            TextSpan(i + 1, t, SpanRect(0, i * 400, 200, i * 400 + height), 1f, 0, i)
        },
        imageWidth = 1000,
        imageHeight = 1000,
        elapsedMs = 0,
    )

    // ---------- when to rotate ----------

    @Test
    fun `an upright page is left alone`() {
        assertEquals(0f, Deskew.correctionFor(spans(0f, 0.3f, -0.2f, 0.1f, 0f)), 0.001f)
    }

    @Test
    fun `the measured fixtures are corrected by what they are tilted`() {
        // Real device readings of the rotated bank fixture.
        assertEquals(-11.9f, Deskew.correctionFor(spans(11.9f, 11.8f, 11.9f, 11.7f, 12.0f)), 0.01f)
        assertEquals(-30.0f, Deskew.correctionFor(spans(29.7f, 30.0f, 30.0f, 30.1f, 29.9f)), 0.01f)
        assertEquals(-90.0f, Deskew.correctionFor(spans(90f, 90f, 89.9f, 90.1f, 90f)), 0.05f)
    }

    @Test
    fun `quarter turns are told apart by which way they turned`() {
        // Identical once folded onto 0..90, opposite in what they need. Getting this wrong is
        // the difference between upright and upside down.
        assertEquals(-90.0f, Deskew.correctionFor(spans(90f, 90f, 90f, 90f)), 0.05f)
        assertEquals(90.0f, Deskew.correctionFor(spans(-90f, -90f, -90f, -90f)), 0.05f)
    }

    @Test
    fun `an upside down page is turned back over`() {
        val c = Deskew.correctionFor(spans(179.5f, -179.8f, 180f, 179.9f))
        assertEquals(180f, kotlin.math.abs(c), 1f)
    }

    @Test
    fun `a page with mixed orientations is only untilted, never flipped`() {
        // Half the lines each way: no majority, so we correct the tilt and leave the page's
        // handedness alone rather than gambling on a 180.
        val c = Deskew.correctionFor(spans(175f, -5f, 175f, -5f, -5f))
        assertTrue("expected a small correction, got $c", kotlin.math.abs(c) < 45f)
    }

    @Test
    fun `correction undoes the tilt in both directions`() {
        assertTrue(Deskew.correctionFor(spans(7f, 7f, 7f, 7f)) < 0f)
        assertTrue(Deskew.correctionFor(spans(-7f, -7f, -7f, -7f)) > 0f)
    }

    @Test
    fun `a hair of tilt is not worth a resample`() {
        assertEquals(0f, Deskew.correctionFor(spans(1.2f, 1.1f, 1.3f, 1.0f)), 0.001f)
    }

    @Test
    fun `too few lines is one line's noise, not a page angle`() {
        assertEquals(0f, Deskew.correctionFor(spans(30f, 30f, 30f)), 0.001f)
    }

    @Test
    fun `one stray vertical stamp does not rotate the page`() {
        // Median, not max - the same guard SkewMetrics relies on.
        assertEquals(0f, Deskew.correctionFor(spans(0f, 0f, 0f, 0f, 88f)), 0.001f)
    }

    @Test
    fun `an empty page is left alone`() {
        assertEquals(0f, Deskew.correctionFor(emptyList()), 0.001f)
    }

    // ---------- whether to keep it ----------

    @Test
    fun `recovering text accepts the rotation`() {
        val before = ocr("Nam", "Acco t", "12 4")
        val after = ocr("Name", "Account", "1234", "Branch")
        assertTrue(Deskew.accepts(before, after))
    }

    @Test
    fun `losing text declines the rotation`() {
        val before = ocr("Name", "Account", "1234", "Branch")
        val after = ocr("Nme", "Acct")
        assertFalse(Deskew.accepts(before, after))
    }

    @Test
    fun `merging fragments into whole lines is a win, not a regression`() {
        // Straightening lets ML Kit join a broken line back together: half as many spans, the
        // same glyphs, and boxes that now fit. Scoring on span count would have thrown this away.
        val before = ocr("Priya", "Ramachandran", "9876", "543210", height = 60)
        val after = ocr("Priya Ramachandran", "9876543210", height = 24)
        assertTrue(Deskew.accepts(before, after))
    }

    @Test
    fun `a wrecked second pass is declined`() {
        assertFalse(Deskew.accepts(ocr("Account Number 1234"), ocr()))
    }

    @Test
    fun `tighter boxes alone justify the rotation`() {
        // The quarter-turn fixture, measured: ML Kit read a sideways page nearly as well (649
        // characters against 648), but the axis-aligned boxes were 198 px tall against 22 px
        // upright. Scoring on text alone threw a correct 90 degree correction away over one glyph.
        val sideways = ocr("Account Number 1234 Branch Kormangala", height = 198)
        val upright = ocr("Account Number 1234 Branch Kormangal", height = 22)
        assertTrue(Deskew.accepts(sideways, upright))
    }

    @Test
    fun `a material loss of text is declined however tight the boxes get`() {
        val before = ocr("Account Number 1234 Branch Kormangala Priya", height = 198)
        val after = ocr("Acct", height = 22)
        assertFalse(Deskew.accepts(before, after))
    }

    @Test
    fun `overwhelming geometric evidence buys a little tolerance for OCR noise`() {
        // A real handheld capture of a sideways card, verbatim from the device: 139 characters
        // upright against 136 rotated, while the median span height fell 426 px -> 48 px. A flat
        // 2% loss gate threw away a correction carrying nine-to-one evidence and left the page on
        // its side.
        val before = ocr("x".repeat(139), height = 426)
        val after = ocr("x".repeat(136), height = 48)
        assertTrue(Deskew.accepts(before, after))
    }

    @Test
    fun `a rotation with no geometric win is still held to the strict bar`() {
        // Same 2.2% loss, but the boxes did not move - so there is nothing to weigh against it.
        val before = ocr("x".repeat(139), height = 40)
        val after = ocr("x".repeat(136), height = 40)
        assertFalse(Deskew.accepts(before, after))
    }

    @Test
    fun `a rotation with nothing to show for itself is declined`() {
        // Neither measure moves - this is what turning an upright page over looks like, and it is
        // the one mistake OCR cannot see. No evidence, no rotation.
        val page = ocr("Account Number 1234", height = 30)
        assertFalse(Deskew.accepts(page, ocr("Account Number 1234", height = 30)))
        // ...but a page that really was upside down recovers text, and that is evidence enough.
        assertTrue(Deskew.accepts(page, ocr("Account Number 1234 Branch", height = 30)))
    }
}
