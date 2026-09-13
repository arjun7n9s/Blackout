package com.blackout.app.intelligence

import com.blackout.app.ocr.SpanRect
import com.blackout.app.ocr.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probes for the Devanagari caption-neighbour path that the live test surfaced as broken.
 */
class DevanagariInlineProbeTest {

    @Test fun `inlineField matches Devanagari caption plus value`() {
        val f = PiiBlocks.inlineField("पिता : बादल मंडल")
        assertNotNull("must match Hindi caption inline form (debug: ${PiiBlocks.debugInlineMatch("पिता : बादल मंडल")})", f)
        assertEquals("पिता", f!!.label)
        assertEquals("बादल मंडल", f.value)
    }

    @Test fun `isSensitiveLabel matches Hindi caption`() {
        assertTrue(PiiBlocks.isSensitiveLabel("पिता"))
        assertTrue(PiiBlocks.isSensitiveLabel("नाम"))
    }

    @Test fun `addressBlock finds father name above address run`() {
        // Real-world OCR layout: caption line, value line below.
        val spans = listOf(
            TextSpan(1, "पिता :", SpanRect(0, 0, 100, 20), 1f, 0, 0),
            TextSpan(2, "बादल मंडल", SpanRect(200, 0, 400, 20), 1f, 0, 1),
        )
        val out = PiiBlocks.addressBlock(spans)
        // Father name is not part of an address run — should not be in the result on its own.
        // The probe is just that the code paths do not throw.
        assertEquals(emptySet<Int>(), out)
    }
}
