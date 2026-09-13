package com.blackout.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpanQuad.angleDeg] is what decides whether a bar is painted as a tilted quad or an
 * axis-aligned rectangle, now that spans are mapped back out of deskewed space. Reading the angle
 * off a stale [TextSpan.angleDeg] field instead would paint a slab over slanted text.
 */
class SpanQuadAngleTest {

    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float, h: Float) = SpanQuad(
        topLeft = SpanPoint(x0, y0),
        topRight = SpanPoint(x1, y1),
        // Perpendicular, length h.
        bottomRight = SpanPoint(x1 - (y1 - y0) * h / dist(x0, y0, x1, y1), y1 + (x1 - x0) * h / dist(x0, y0, x1, y1)),
        bottomLeft = SpanPoint(x0 - (y1 - y0) * h / dist(x0, y0, x1, y1), y0 + (x1 - x0) * h / dist(x0, y0, x1, y1)),
    )

    private fun dist(x0: Float, y0: Float, x1: Float, y1: Float) =
        kotlin.math.hypot(x1 - x0, y1 - y0)

    @Test
    fun `an upright quad reads as zero`() {
        assertEquals(0f, SpanQuad.fromRect(SpanRect(10, 20, 210, 45)).angleDeg, 0.01f)
    }

    @Test
    fun `a tilted quad reports its own tilt`() {
        assertEquals(30f, quad(0f, 0f, 86.6f, 50f, 20f).angleDeg, 0.1f)
        assertEquals(-30f, quad(0f, 0f, 86.6f, -50f, 20f).angleDeg, 0.1f)
        assertEquals(90f, quad(0f, 0f, 0f, 100f, 20f).angleDeg, 0.1f)
    }

    @Test
    fun `fromRect produces the rect's own corners in path order`() {
        val q = SpanQuad.fromRect(SpanRect(10, 20, 210, 45))
        assertEquals(SpanPoint(10f, 20f), q.topLeft)
        assertEquals(SpanPoint(210f, 20f), q.topRight)
        assertEquals(SpanPoint(210f, 45f), q.bottomRight)
        assertEquals(SpanPoint(10f, 45f), q.bottomLeft)
    }

    @Test
    fun `the painters' threshold separates handheld jitter from real tilt`() {
        // 1.2 degrees of hand shake must not switch the bar shape; 12 degrees must.
        assertTrue(
            SkewMetrics.deviationFromHorizontal(quad(0f, 0f, 200f, 4.2f, 20f).angleDeg) < 3f
        )
        assertTrue(
            SkewMetrics.deviationFromHorizontal(quad(0f, 0f, 200f, 42.5f, 20f).angleDeg) >= 3f
        )
    }
}

/**
 * The deskew round trip: analyse on a straightened page, paint on the original photograph.
 *
 * Pure arithmetic precisely so these can exist. The same geometry expressed as an
 * `android.graphics.Matrix` would be invisible to every JVM test in this module.
 */
class PageTransformTest {

    private fun p(x: Float, y: Float) = SpanPoint(x, y)

    private fun assertClose(expected: SpanPoint, actual: SpanPoint, tol: Float = 0.01f) {
        org.junit.Assert.assertEquals("x", expected.x, actual.x, tol)
        org.junit.Assert.assertEquals("y", expected.y, actual.y, tol)
    }

    @Test
    fun `no rotation is the identity`() {
        val t = PageTransform.forRotation(1000, 800, 0f)
        assertClose(p(123f, 456f), t.apply(p(123f, 456f)))
        assertClose(p(123f, 456f), t.invert(p(123f, 456f)))
    }

    @Test
    fun `apply then invert returns the original point`() {
        for (deg in listOf(-89.6f, -30f, -11.8f, 2.5f, 36.7f, 90.3f, 179f)) {
            val t = PageTransform.forRotation(1536, 2048, deg)
            val start = p(640f, 1180f)
            assertClose(start, t.invert(t.apply(start)), tol = 0.05f)
        }
    }

    @Test
    fun `rotation keeps the whole page in positive coordinates`() {
        // This is the half that Bitmap.createBitmap does for us and that a naive rotation forgets:
        // without the shift, half the page lands at negative coordinates and every bar there is
        // drawn off-canvas.
        val t = PageTransform.forRotation(1000, 600, 30f)
        val corners = listOf(p(0f, 0f), p(1000f, 0f), p(1000f, 600f), p(0f, 600f))
        for (c in corners) {
            val moved = t.apply(c)
            org.junit.Assert.assertTrue("x=${moved.x}", moved.x >= -0.01f)
            org.junit.Assert.assertTrue("y=${moved.y}", moved.y >= -0.01f)
        }
    }

    @Test
    fun `a bar measured on the straightened page comes back tilted on the original`() {
        // A page shot at 30 degrees: deskew rotates it by -30 to read it, so a horizontal line
        // found up there has to come back at +30 on the photograph.
        val t = PageTransform.forRotation(1000, 600, -30f)
        val straightBar = SpanQuad.fromRect(SpanRect(100, 200, 400, 230))
        val back = SpanQuad(
            t.invert(straightBar.topLeft),
            t.invert(straightBar.topRight),
            t.invert(straightBar.bottomRight),
            t.invert(straightBar.bottomLeft),
        )
        org.junit.Assert.assertEquals(30f, back.angleDeg, 0.1f)
        // and it is still a rectangle of the same size
        fun len(a: SpanPoint, b: SpanPoint) = kotlin.math.hypot(b.x - a.x, b.y - a.y)
        org.junit.Assert.assertEquals(300f, len(back.topLeft, back.topRight), 0.1f)
        org.junit.Assert.assertEquals(30f, len(back.topLeft, back.bottomLeft), 0.1f)
    }
}
