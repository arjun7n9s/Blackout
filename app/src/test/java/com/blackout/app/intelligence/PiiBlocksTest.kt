package com.blackout.app.intelligence

import com.blackout.app.ocr.SpanRect
import com.blackout.app.ocr.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strings here are **verbatim ML Kit output** from a real Aadhaar photograph, mangling and
 * all. That is the point: exact-match lexicons are what let this block leak in the first place.
 */
class PiiBlocksTest {

    private var next = 1
    private fun span(text: String, y: Int = next * 40) = TextSpan(
        id = next++,
        text = text,
        rect = SpanRect(60, y, 600, y + 30),
        confidence = 1f,
        blockIndex = 0,
        lineIndex = 0,
    )

    /** The exact OCR of the card that leaked. */
    private fun aadhaarOcr(): List<TextSpan> {
        next = 1
        return listOf(
            span("UictecttiiotonL Athioriyotvtl"),
            span("Basart Ra"),
            span("CO: Aasdhar Cmrd"),
            span("VIC Flet No 25"),
            span("PO: Grugram"),
            span("Sub Disric: Grugram"),
            span("Disria: Grugran"),
            span("State: New Delhi"),
            span("PIN Code: 110042."),
        )
    }

    // ---------- inline Label: value ----------

    @Test
    fun `inline field splits label from value`() {
        val f = PiiBlocks.inlineField("State: New Delhi")!!
        assertEquals("State", f.label)
        assertEquals("New Delhi", f.value)
    }

    @Test
    fun `a bare caption with no value is not an inline field`() {
        assertNull(PiiBlocks.inlineField("Date of Birth:"))
        assertNull(PiiBlocks.inlineField("Address :"))
    }

    @Test
    fun `not every colon is a caption`() {
        assertNull(PiiBlocks.inlineField("12:30"))          // digits before the colon
        assertNull(PiiBlocks.inlineField("no colon here"))
    }

    @Test
    fun `garbled captions still match their stem`() {
        // These are the real OCR outputs. Exact lexicon lookup rejects every one of them.
        assertTrue(PiiBlocks.isSensitiveLabel("Disria"))      // District
        assertTrue(PiiBlocks.isSensitiveLabel("Sub Disric"))  // Sub District
        assertTrue(PiiBlocks.isSensitiveLabel("CO"))          // C/O
        assertTrue(PiiBlocks.isSensitiveLabel("PO"))
        assertTrue(PiiBlocks.isSensitiveLabel("PIN Code"))
    }

    @Test
    fun `clean captions match too`() {
        listOf("Father", "Name", "DOB", "Mobile", "Address", "State", "Village")
            .forEach { assertTrue(it, PiiBlocks.isSensitiveLabel(it)) }
    }

    @Test
    fun `unrelated captions do not match`() {
        listOf("Note", "Remarks", "Total", "Signature", "Issued By")
            .forEach { assertFalse(it, PiiBlocks.isSensitiveLabel(it)) }
    }

    // ---------- address block ----------

    @Test
    fun `address block covers the whole run including anchorless lines`() {
        val spans = aadhaarOcr()
        val block = PiiBlocks.addressBlock(spans)
        val text = spans.filter { it.id in block }.map { it.text }

        // "VIC Flet No 25" carries no caption of its own but sits inside the run.
        assertTrue(text.contains("VIC Flet No 25"))
        assertTrue(text.contains("CO: Aasdhar Cmrd"))
        assertTrue(text.contains("PIN Code: 110042."))
    }

    @Test
    fun `the name line above the block is included`() {
        val spans = aadhaarOcr()
        val block = PiiBlocks.addressBlock(spans)
        val text = spans.filter { it.id in block }.map { it.text }
        assertTrue("name must be covered", text.contains("Basart Ra"))
    }

    @Test
    fun `the authority header above the name is not swallowed`() {
        val spans = aadhaarOcr()
        val block = PiiBlocks.addressBlock(spans)
        val text = spans.filter { it.id in block }.map { it.text }
        assertFalse(text.contains("UictecttiiotonL Athioriyotvtl"))
    }

    @Test
    fun `a lone address caption does not drag in its neighbours`() {
        next = 1
        val spans = listOf(
            span("INVOICE"),
            span("State: Karnataka"),
            span("Item description"),
            span("Total 4200"),
        )
        assertTrue(PiiBlocks.addressBlock(spans).isEmpty())
    }

    @Test
    fun `a document with no address is untouched`() {
        next = 1
        val spans = listOf(span("RECENT TRANSACTIONS"), span("03/08"), span("UPI to RAHUL"))
        assertTrue(PiiBlocks.addressBlock(spans).isEmpty())
    }

    // ---------- name-like ----------

    @Test
    fun `name-like accepts short letter-only lines and rejects the rest`() {
        assertTrue(PiiBlocks.isNameLike("Basart Ra"))
        assertTrue(PiiBlocks.isNameLike("PRIYA RAMACHANDRAN"))
        assertFalse(PiiBlocks.isNameLike("PIN Code: 110042"))   // has a caption
        assertFalse(PiiBlocks.isNameLike("Flat 25"))            // has digits
        assertFalse(PiiBlocks.isNameLike(""))
    }
}
