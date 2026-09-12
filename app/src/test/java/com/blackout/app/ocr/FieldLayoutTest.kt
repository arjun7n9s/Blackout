package com.blackout.app.ocr

import com.blackout.app.intelligence.CandidateHints
import com.blackout.app.intelligence.HintStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layout geometry is pure, so all of this runs on the JVM with no device and no Robolectric.
 *
 * Coordinates mirror the real fixture measured on device: page 1240 wide, label column at x≈60,
 * value column at x≈520, rows 62px apart with ~30px glyphs.
 */
class FieldLayoutTest {

    private val pageWidth = 1240
    private var nextId = 1

    private fun span(text: String, x: Int, y: Int, w: Int = 300, h: Int = 30) = TextSpan(
        id = nextId++,
        text = text,
        rect = SpanRect(x, y, x + w, y + h),
        confidence = 1f,
        blockIndex = 0,
        lineIndex = 0,
    )

    /** Classic two-column form: caption left, value right. */
    private fun twoColumnForm(): List<TextSpan> {
        nextId = 1
        val rows = listOf(
            "Account Holder" to "Priya Ramachandran",
            "Customer ID" to "MB-4471902",
            "PAN" to "ABCDE1234F",
            "Aadhaar" to "2345 6789 0123",
            "Registered Mobile" to "+91 98765 43210",
        )
        return rows.flatMapIndexed { i, (label, value) ->
            val y = 200 + i * 62
            listOf(span(label, 60, y, 200), span(value, 520, y, 320))
        }
    }

    private fun detect(spans: List<TextSpan>) = FieldLayout.detect(spans, pageWidth)

    private fun applied(spans: List<TextSpan>, strongHint: (TextSpan) -> Boolean = { false }) =
        FieldLayout.applyTo(spans, detect(spans), strongHint).associateBy { it.id }

    // ---------- the core case ----------

    @Test
    fun `two-column form labels the left column and pairs the values`() {
        val spans = twoColumnForm()
        val result = detect(spans)

        assertEquals(60, result.labelColumnX)
        assertEquals(SpanRole.LABEL, result.roles[spans[0].id])   // "Account Holder"
        assertEquals(SpanRole.VALUE, result.roles[spans[1].id])   // "Priya Ramachandran"
        assertEquals("Account Holder", result.labelFor[spans[1].id])
        assertEquals("Aadhaar", result.labelFor[spans[7].id])
    }

    @Test
    fun `values keep their paired label as context`() {
        val byId = applied(twoColumnForm())
        val value = byId.values.first { it.text == "ABCDE1234F" }
        assertEquals(SpanRole.VALUE, value.role)
        assertEquals("PAN", value.labelText)
    }

    @Test
    fun `real regex hints do not veto Account Holder or Account Number`() {
        // The ACCOUNT pattern used to capture "Holder"/"Number" as a 6-letter "account number",
        // which made applyTo refuse LABEL and sent both captions back to the models.
        nextId = 1
        val spans = listOf(
            "Account Holder" to "Priya Ramachandran",
            "Account Number" to "5012 3456 7890 1234",
            "Customer ID" to "MB-4471902",
            "PAN" to "ABCDE1234F",
        ).flatMapIndexed { i, (label, value) ->
            val y = 200 + i * 62
            listOf(span(label, 60, y, 200), span(value, 520, y, 320))
        }
        val byId = FieldLayout.applyTo(spans, detect(spans)) { s ->
            CandidateHints.detect(s.text).any { it.strength == HintStrength.STRONG }
        }.associateBy { it.id }
        assertEquals(SpanRole.LABEL, byId.values.first { it.text == "Account Holder" }.role)
        assertEquals(SpanRole.LABEL, byId.values.first { it.text == "Account Number" }.role)
        assertEquals(SpanRole.VALUE, byId.values.first { it.text == "Priya Ramachandran" }.role)
    }

    // ---------- the safety cases ----------

    @Test
    fun `stacked layout labels nothing - a PAN card must stay redactable`() {
        // PAN and Aadhaar cards put the caption ABOVE the value, sharing a left edge. If a lone
        // left-column span could be a label, "PRIYA RAMACHANDRAN" would become un-redactable -
        // the worst possible leak. A label is only recognised with a value beside it.
        nextId = 1
        val spans = listOf(
            span("Name", 60, 100, 160),
            span("PRIYA RAMACHANDRAN", 60, 150, 420),
            span("Father's Name", 60, 210, 220),
            span("RAMACHANDRAN IYER", 60, 260, 400),
            span("Date of Birth", 60, 320, 200),
            span("14/03/1988", 60, 370, 200),
        )
        val result = detect(spans)
        assertNull(result.labelColumnX)
        assertTrue(result.roles.isEmpty())
        assertTrue(applied(spans).values.none { it.role == SpanRole.LABEL })
    }

    @Test
    fun `a person name in the left column is never a label`() {
        // There is no regex for a human name, so the text whitelist is the only thing standing
        // between geometry and a silenced redaction.
        assertFalse(FieldLayout.looksLikeLabel("Priya Ramachandran"))
        assertFalse(FieldLayout.looksLikeLabel("Sunil Kapoor"))
        assertFalse(FieldLayout.looksLikeLabel("RAHUL MEHTA"))
    }

    @Test
    fun `a strong hint vetoes label status`() {
        nextId = 1
        val spans = twoColumnForm()
        // Pretend the first left-column span carries a strong pattern.
        val labelId = spans[0].id
        val byId = FieldLayout.applyTo(spans, detect(spans)) { it.id == labelId }
            .associateBy { it.id }
        assertEquals(SpanRole.STANDALONE, byId.getValue(labelId).role)
    }

    @Test
    fun `single-column document is left entirely alone`() {
        nextId = 1
        val spans = (0..5).map { span("Some prose line number $it here", 60, 100 + it * 50, 800) }
        val result = detect(spans)
        assertNull(result.labelColumnX)
        assertTrue(result.roles.isEmpty())
    }

    @Test
    fun `too few rows to be a form yields no roles`() {
        nextId = 1
        val spans = listOf(
            span("Name", 60, 100, 150), span("Priya R", 520, 100, 200),
            span("PAN", 60, 160, 150), span("ABCDE1234F", 520, 160, 200),
        )
        // Only 2 agreeing rows - below the 3-row threshold.
        assertNull(detect(spans).labelColumnX)
    }

    @Test
    fun `transaction table rows are not mistaken for labels`() {
        // A statement's narration column is leftmost but is content, not captions.
        nextId = 1
        val spans = (0..4).flatMap { i ->
            val y = 200 + i * 52
            listOf(
                span("UPI to RAHUL MEHTA $i", 60, y, 300),
                span("-4,200.00", 950, y, 150),
            )
        }
        assertTrue(detect(spans).roles.isEmpty())
    }

    @Test
    fun `value sitting hard against its label is not paired`() {
        // Without a real gap this is one visual phrase, not a label/value pair.
        nextId = 1
        val spans = (0..3).flatMap { i ->
            val y = 200 + i * 62
            listOf(span("PAN", 60, y, 100), span("ABCDE1234F", 165, y, 200))
        }
        assertTrue(detect(spans).roles.isEmpty())
    }

    // ---------- the whitelist ----------

    @Test
    fun `lexicon captions are labels`() {
        listOf("Account Holder", "PAN", "Aadhaar", "Date of Birth", "IFSC Code",
               "Registered Mobile", "Closing Balance", "Relationship Manager")
            .forEach { assertTrue(it, FieldLayout.looksLikeLabel(it)) }
    }

    @Test
    fun `trailing colon marks a caption but cannot smuggle content through`() {
        assertTrue(FieldLayout.looksLikeLabel("Nominee:"))
        assertTrue(FieldLayout.looksLikeLabel("Issued By:"))
        // Digits or a long phrase after a colon means it carries content.
        assertFalse(FieldLayout.looksLikeLabel("Total: 4,200"))
        assertFalse(FieldLayout.looksLikeLabel("Paid to RAHUL MEHTA on 03/08:"))
    }

    @Test
    fun `emails and long text are never labels`() {
        assertFalse(FieldLayout.looksLikeLabel("priya.ram@example.com"))
        assertFalse(FieldLayout.looksLikeLabel("This statement is computer generated and does not"))
        assertFalse(FieldLayout.looksLikeLabel(""))
        assertFalse(FieldLayout.looksLikeLabel("   "))
    }
}
