package com.blackout.app.share

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Numbers are Phone C's, from `C-Outputs/edge-cases/C-005-motion-blur.md` and `compare.csv`. */
class ShareGuardTest {

    @Test
    fun `C-005 motion blur warns before sharing`() {
        // spans=1 hide=0 on a full-page statement photo.
        val warning = ShareGuard.warning(spanCount = 1, hideCount = 0, imageWidth = 1240, imageHeight = 1754)
        assertNotNull(warning)
        assertTrue(warning!!.contains("1 text region was"))
    }

    @Test
    fun `a readable page that simply had nothing to hide is silent`() {
        // C-014-blank is a real "nothing to redact" page; so is a photo of a whiteboard with text.
        assertNull(ShareGuard.warning(spanCount = 85, hideCount = 0, imageWidth = 1240, imageHeight = 1754))
    }

    @Test
    fun `once bars exist the guard stays out of the way`() {
        assertNull(ShareGuard.warning(spanCount = 1, hideCount = 1, imageWidth = 1240, imageHeight = 1754))
    }

    @Test
    fun `an empty page is called out explicitly`() {
        val warning = ShareGuard.warning(0, 0, 1240, 1754)
        assertNotNull(warning)
        assertTrue(warning!!.startsWith("No text was recognised"))
    }

    @Test
    fun `thumbnails are not judged on text density`() {
        assertNull(ShareGuard.warning(spanCount = 0, hideCount = 0, imageWidth = 320, imageHeight = 240))
    }

    @Test
    fun `the A fixture is far above the threshold`() {
        // testdoc-bank.png: 47 spans on ~2.2 MP = ~21 spans/MP.
        assertNull(ShareGuard.warning(spanCount = 47, hideCount = 0, imageWidth = 1240, imageHeight = 1754))
    }
}
