package com.blackout.app.ui

import com.blackout.app.ocr.SpanRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Getting this wrong puts redaction bars next to the text instead of on it, so the mapping is
 * pinned in both directions.
 */
class FitTransformTest {

    @Test
    fun `letterboxes a tall image in a wide box`() {
        // 100x200 image in a 200x200 box -> scale 1.0, centred horizontally.
        val t = FitTransform.of(200f, 200f, 100, 200)
        assertEquals(1f, t.scale, 0.001f)
        assertEquals(50f, t.offsetX, 0.001f)
        assertEquals(0f, t.offsetY, 0.001f)
    }

    @Test
    fun `round trips a point`() {
        val t = FitTransform.of(400f, 800f, 200, 400)
        val rect = SpanRect(10, 20, 60, 40)
        val vx = t.viewLeft(rect)
        val vy = t.viewTop(rect)
        val (bx, by) = t.toBitmap(vx, vy)!!
        assertEquals(10, bx)
        assertEquals(20, by)
    }

    @Test
    fun `scales rect dimensions`() {
        val t = FitTransform.of(400f, 800f, 200, 400)
        val rect = SpanRect(0, 0, 50, 10)
        assertEquals(100f, t.viewWidth(rect), 0.001f)
        assertEquals(20f, t.viewHeight(rect), 0.001f)
    }

    @Test
    fun `degenerate sizes do not crash`() {
        val t = FitTransform.of(0f, 0f, 0, 0)
        assertEquals(0f, t.scale, 0.001f)
        assertEquals(null, t.toBitmap(5f, 5f))
    }

    @Test
    fun `taps inside the image resolve`() {
        val t = FitTransform.of(200f, 200f, 100, 200)
        assertNotNull(t.toBitmap(100f, 100f))
    }
}
