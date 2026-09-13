package com.blackout.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SpanQuad.inflate] has to grow the bar along the *text's* axes, not the screen's. Inflating in
 * screen space shears a tilted bar into a parallelogram that no longer covers its own glyphs,
 * which is a leak rather than a cosmetic bug - so the arithmetic is pinned here.
 */
class SpanQuadTest {

    private fun assertPoint(expX: Float, expY: Float, actual: SpanPoint, label: String) {
        assertEquals("$label.x", expX, actual.x, 0.01f)
        assertEquals("$label.y", expY, actual.y, 0.01f)
    }

    @Test
    fun `an upright quad inflates like a rectangle`() {
        val quad = SpanQuad(
            topLeft = SpanPoint(0f, 0f),
            topRight = SpanPoint(100f, 0f),
            bottomRight = SpanPoint(100f, 20f),
            bottomLeft = SpanPoint(0f, 20f),
        ).inflate(5f)

        assertPoint(-5f, -5f, quad.topLeft, "topLeft")
        assertPoint(105f, -5f, quad.topRight, "topRight")
        assertPoint(105f, 25f, quad.bottomRight, "bottomRight")
        assertPoint(-5f, 25f, quad.bottomLeft, "bottomLeft")
    }

    @Test
    fun `a quarter-turned quad still grows by the same margin on every side`() {
        // Text running straight down the page: the "along" axis is +y and "down" is -x. If
        // inflate used screen axes this would grow in the wrong directions entirely.
        val quad = SpanQuad(
            topLeft = SpanPoint(10f, 0f),
            topRight = SpanPoint(10f, 100f),
            bottomRight = SpanPoint(0f, 100f),
            bottomLeft = SpanPoint(0f, 0f),
        ).inflate(2f)

        assertPoint(12f, -2f, quad.topLeft, "topLeft")
        assertPoint(12f, 102f, quad.topRight, "topRight")
        assertPoint(-2f, 102f, quad.bottomRight, "bottomRight")
        assertPoint(-2f, -2f, quad.bottomLeft, "bottomLeft")
    }

    @Test
    fun `inflating a tilted quad keeps it a rectangle`() {
        // 45 degrees. Opposite edges must stay equal in length - that is what "still a rectangle"
        // means, and it is exactly what a screen-space inflate would break.
        val s = 70.71f
        val quad = SpanQuad(
            topLeft = SpanPoint(0f, 0f),
            topRight = SpanPoint(s, s),
            bottomRight = SpanPoint(s - 14.14f, s + 14.14f),
            bottomLeft = SpanPoint(-14.14f, 14.14f),
        ).inflate(4f)

        fun len(a: SpanPoint, b: SpanPoint) = kotlin.math.hypot(b.x - a.x, b.y - a.y)
        assertEquals(
            "top and bottom edges must stay equal",
            len(quad.topLeft, quad.topRight),
            len(quad.bottomLeft, quad.bottomRight),
            0.05f,
        )
        assertEquals(
            "left and right edges must stay equal",
            len(quad.topLeft, quad.bottomLeft),
            len(quad.topRight, quad.bottomRight),
            0.05f,
        )
    }

    @Test
    fun `a degenerate quad is returned untouched rather than producing NaN`() {
        val collapsed = SpanQuad(
            SpanPoint(5f, 5f), SpanPoint(5f, 5f), SpanPoint(5f, 5f), SpanPoint(5f, 5f),
        )
        assertEquals(collapsed, collapsed.inflate(3f))
    }

    @Test
    fun `points are emitted in path order`() {
        val quad = SpanQuad(
            SpanPoint(0f, 0f), SpanPoint(9f, 1f), SpanPoint(9f, 4f), SpanPoint(0f, 3f),
        )
        assertEquals(
            listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft),
            quad.points,
        )
    }
}
