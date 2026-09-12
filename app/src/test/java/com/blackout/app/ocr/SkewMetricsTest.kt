package com.blackout.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkewMetricsTest {

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

    @Test
    fun `upright page is not skewed`() {
        val page = spans(0f, 0.3f, -0.2f, 0.1f, 0f)
        assertTrue(SkewMetrics.medianAbsAngle(page) < 1f)
        assertFalse(SkewMetrics.isSkewed(page))
    }

    @Test
    fun `measured fixtures are flagged`() {
        // Angles taken from the real device runs of the rotated bank fixture.
        assertTrue(SkewMetrics.isSkewed(spans(11.9f, 11.8f, 12.0f, 11.7f, 11.6f)))
        assertTrue(SkewMetrics.isSkewed(spans(29.7f, 30.0f, 30.2f, 30.1f, 29.9f)))
        assertTrue(SkewMetrics.isSkewed(spans(90.0f, 89.9f, 90.1f, 89.7f, 90.0f)))
    }

    @Test
    fun `sign does not matter`() {
        assertEquals(12f, SkewMetrics.medianAbsAngle(spans(-12f, -12f, -12f)), 0.01f)
    }

    @Test
    fun `upside down text is horizontal so it is not tilt`() {
        // 180 degrees still runs along horizontal lines - an axis-aligned box fits it exactly as
        // well as upright text. Only tilt breaks the boxes.
        assertEquals(0f, SkewMetrics.deviationFromHorizontal(180f), 0.01f)
        assertEquals(0f, SkewMetrics.deviationFromHorizontal(-180f), 0.01f)
        assertFalse(SkewMetrics.isSkewed(spans(180f, 179.8f, 180.2f)))
    }

    @Test
    fun `folds onto 0 to 90`() {
        assertEquals(90f, SkewMetrics.deviationFromHorizontal(90f), 0.01f)
        assertEquals(90f, SkewMetrics.deviationFromHorizontal(270f), 0.01f)
        assertEquals(45f, SkewMetrics.deviationFromHorizontal(135f), 0.01f)
        assertEquals(10f, SkewMetrics.deviationFromHorizontal(190f), 0.01f)
    }

    @Test
    fun `a few tilted lines do not condemn a straight page`() {
        // Median, not max: one stray vertical stamp shouldn't warn on an otherwise flat scan.
        assertFalse(SkewMetrics.isSkewed(spans(0f, 0f, 0f, 0f, 88f)))
    }

    @Test
    fun `no spans means no evidence of skew`() {
        assertEquals(0f, SkewMetrics.medianAbsAngle(emptyList()), 0.01f)
        assertFalse(SkewMetrics.isSkewed(emptyList()))
    }

    @Test
    fun `non finite angles are ignored rather than crashing`() {
        assertEquals(0f, SkewMetrics.deviationFromHorizontal(Float.NaN), 0.01f)
        assertEquals(0f, SkewMetrics.deviationFromHorizontal(Float.POSITIVE_INFINITY), 0.01f)
    }
}
