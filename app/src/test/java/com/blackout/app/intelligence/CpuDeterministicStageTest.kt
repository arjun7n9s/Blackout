package com.blackout.app.intelligence

import com.blackout.app.ocr.FieldLayout
import com.blackout.app.ocr.SpanRect
import com.blackout.app.ocr.SpanRole
import com.blackout.app.ocr.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are the 29 rows Phone C filed in `C-Outputs/label-bugs.jsonl`, transcribed as geometry.
 *
 * Each `expected` field in that file reads "label keep, value hide" (or "label keep, value keep"
 * for headers and pay components), and every row in it shipped the opposite way on device. These
 * tests are those rows, so a regression puts the bar back on the caption and fails here rather
 * than on someone's bank statement.
 *
 * Page geometry mirrors the measured fixture: 1240x1754 at 150 dpi, label column x≈60, value
 * column x≈520, rows 62 px apart, ~30 px glyphs.
 */
class CpuDeterministicStageTest {

    private val pageWidth = 1240
    private val pageHeight = 1754
    private var nextId = 1

    private fun span(text: String, x: Int, y: Int, w: Int = 300, h: Int = 30) = TextSpan(
        id = nextId++,
        text = text,
        rect = SpanRect(x, y, x + w, y + h),
        confidence = 1f,
        blockIndex = 0,
        lineIndex = 0,
    )

    /** Runs hints + FieldLayout + the CPU stage exactly as the pipeline does. */
    private fun analyse(spans: List<TextSpan>): Pair<List<TextSpan>, CpuDeterministicStage.Result> {
        val hints = spans.withIndex().associate { (i, s) ->
            s.id to CandidateHints.promoteByNeighbour(
                CandidateHints.detect(s.text),
                spans.getOrNull(i - 1)?.text,
            )
        }.filterValues { it.isNotEmpty() }
        val layout = FieldLayout.detect(spans, pageWidth)
        val applied = FieldLayout.applyTo(spans, layout) { span ->
            hints[span.id].orEmpty().any { it.strength == HintStrength.STRONG }
        }
        val appliedHints = applied.withIndex().associate { (i, s) ->
            s.id to CandidateHints.promoteByNeighbour(
                CandidateHints.detect(s.text),
                applied.getOrNull(i - 1)?.text,
            )
        }.filterValues { it.isNotEmpty() }
        return applied to CpuDeterministicStage.run(applied, appliedHints, pageHeight)
    }

    /** C-001 / C-002 / C-009 / C-011 / C-007: the classic Horizon Bank block. */
    private fun horizonStatement(): List<TextSpan> {
        nextId = 1
        val head = listOf(span("HORIZON BANK", 60, 70, 380, 40))
        val rows = listOf(
            "Account Holder" to "Priya Ramachandran",
            "Date of Birth" to "14-08-1988",
            "IFSC" to "HZBN0001429",
            "Nominee" to "K. Ramachandran",
            "Communication Address" to "Bandra West, Mumbai 400050",
            "Email" to "priya.r@example.com",
            "Registered Mobile" to "+91 79400 11220",
            "Statement Period" to "01-07-2026 to 31-07-2026",
            "Branch" to "Andheri East",
        )
        return head + rows.flatMapIndexed { i, (label, value) ->
            val y = 300 + i * 62
            listOf(span(label, 60, y, 260), span(value, 520, y, 400))
        }
    }

    private fun decisionFor(text: String): SpanDecision? {
        val (spans, result) = analyse(horizonStatement())
        val span = spans.first { it.text == text }
        return result.decisions[span.id]
    }

    // ---------- the bug C filed 29 times ----------

    @Test
    fun `label bars are never the redaction - the caption keeps, the value hides`() {
        val (spans, result) = analyse(horizonStatement())

        val pairs = listOf(
            "Account Holder" to "Priya Ramachandran",
            "Date of Birth" to "14-08-1988",
            "IFSC" to "HZBN0001429",
            "Nominee" to "K. Ramachandran",
            "Communication Address" to "Bandra West, Mumbai 400050",
            "Email" to "priya.r@example.com",
            "Registered Mobile" to "+91 79400 11220",
        )
        for ((label, value) in pairs) {
            val labelSpan = spans.first { it.text == label }
            val valueSpan = spans.first { it.text == value }
            assertEquals(
                "$label must stay visible",
                Action.KEEP,
                result.decisions[labelSpan.id]?.action,
            )
            assertEquals(
                "$value must be hidden without asking a model",
                Action.HIDE,
                result.decisions[valueSpan.id]?.action,
            )
        }
    }

    @Test
    fun `the bank header the workhorse blacked out is kept, without the referee`() {
        assertEquals(Action.KEEP, decisionFor("HORIZON BANK")?.action)
        assertEquals(DecisionSource.DETERMINISTIC, decisionFor("HORIZON BANK")?.source)
    }

    @Test
    fun `C-003 KYC form title and PAN value`() {
        nextId = 1
        val spans = listOf(span("CKYC / KYC UPDATION FORM", 60, 60, 520, 40)) +
            listOf(
                "PAN" to "BDFPN2201L",
                "Date of Birth" to "09-01-1994",
                "Email Address" to "kavya.n@example.com",
                "Permanent Address" to "41, MG Road, Thrissur, Kerala 680001",
            ).flatMapIndexed { i, (label, value) ->
                val y = 300 + i * 62
                listOf(span(label, 60, y, 260), span(value, 520, y, 460))
            }

        val (applied, result) = analyse(spans)
        fun action(text: String) = result.decisions[applied.first { it.text == text }.id]?.action

        assertEquals(Action.KEEP, action("CKYC / KYC UPDATION FORM"))
        assertEquals(Action.KEEP, action("PAN"))
        assertEquals(Action.HIDE, action("BDFPN2201L"))
        assertEquals(Action.HIDE, action("kavya.n@example.com"))
        assertEquals(Action.HIDE, action("41, MG Road, Thrissur, Kerala 680001"))
    }

    @Test
    fun `C-017 payslip keeps the employer header and the pay component labels`() {
        nextId = 1
        val spans = listOf(span("NORTHWIND TECHNOLOGIES PVT LTD", 60, 60, 620, 40)) +
            listOf(
                "Employee Name" to "Rohit Iyer",
                "PAN" to "AHXPI3390B",
                "HRA" to "48,000",
                "LTA" to "12,000",
                "PF" to "21,600",
            ).flatMapIndexed { i, (label, value) ->
                val y = 300 + i * 62
                listOf(span(label, 60, y, 260), span(value, 520, y, 200))
            }

        val (applied, result) = analyse(spans)
        fun decision(text: String) = result.decisions[applied.first { it.text == text }.id]

        assertEquals(Action.KEEP, decision("NORTHWIND TECHNOLOGIES PVT LTD")?.action)
        assertEquals(Action.HIDE, decision("Rohit Iyer")?.action)
        assertEquals(Action.HIDE, decision("AHXPI3390B")?.action)

        // Earnings labels were blacked out on device (C-017 workhorse). They are labels, so they
        // keep - and their amounts are not the CPU's business, so they go to the models untouched.
        for (label in listOf("HRA", "LTA", "PF")) {
            assertEquals(Action.KEEP, decision(label)?.action)
        }
        for (amount in listOf("48,000", "12,000", "21,600")) {
            assertNull("pay amounts must stay unsettled", decision(amount))
        }
    }

    // ---------- what the models must still be asked about ----------

    @Test
    fun `settled spans are removed from the LLM queue and unsettled ones are not`() {
        val (spans, result) = analyse(horizonStatement())
        val queue = CpuDeterministicStage.llmQueue(spans, result)

        assertTrue("nothing settled may reach a model", queue.none { it.id in result.settled })
        assertTrue(
            "the whole label/value block should be settled",
            result.settled.size >= spans.size / 2,
        )
        // Non-personal rows are exactly what the cascade is still for.
        assertTrue(queue.any { it.text == "Andheri East" })
        assertTrue(queue.any { it.text == "01-07-2026 to 31-07-2026" })
    }

    @Test
    fun `a transaction date is not a date of birth`() {
        nextId = 1
        val spans = listOf(
            span("19/08", 60, 500, 90),
            span("UPI to RAHUL MEHTA", 200, 500, 400),
            span("4,200.00", 900, 500, 160),
        )
        val (applied, result) = analyse(spans)
        for (span in applied) {
            assertNull(
                "ledger row must stay with the models: '${span.text}'",
                result.decisions[span.id],
            )
        }
    }

    @Test
    fun `an amount that looks like a phone number is left to the models`() {
        nextId = 1
        val spans = listOf(
            span("Closing Balance", 60, 500, 260),
            span("Rs 1,20,45,000", 520, 500, 260),
        )
        val (applied, result) = analyse(spans)
        assertNull(result.decisions[applied.first { it.text == "Rs 1,20,45,000" }.id])
    }

    @Test
    fun `a bare identifier hides even with no label column at all`() {
        nextId = 1
        val spans = listOf(
            span("Tax invoice", 60, 60, 200),
            span("ABCDE1234F", 60, 300, 200),
            span("2345 6789 0123", 60, 360, 260),
            span("arjun@ybl", 60, 420, 200),
            span("HZBN0001429", 60, 480, 220),
        )
        val (applied, result) = analyse(spans)
        assertEquals(SpanRole.STANDALONE, applied[1].role)
        for (text in listOf("ABCDE1234F", "2345 6789 0123", "arjun@ybl", "HZBN0001429")) {
            assertEquals(
                "$text must hide on the regex alone",
                Action.HIDE,
                result.decisions[applied.first { it.text == text }.id]?.action,
            )
        }
    }

    @Test
    fun `Bank Name keeps its value while Employee Name does not`() {
        assertFalse(CpuDeterministicStage.labelIsSensitive("Bank Name"))
        assertFalse(CpuDeterministicStage.labelIsSensitive("Branch"))
        assertFalse(CpuDeterministicStage.labelIsSensitive("Statement Period"))
        assertFalse(CpuDeterministicStage.labelIsSensitive("Description"))
        assertFalse(CpuDeterministicStage.labelIsSensitive(null))
        assertTrue(CpuDeterministicStage.labelIsSensitive("Employee Name"))
        assertTrue(CpuDeterministicStage.labelIsSensitive("Account Holder"))
        assertTrue(CpuDeterministicStage.labelIsSensitive("Date of Birth:"))
        assertTrue(CpuDeterministicStage.labelIsSensitive("Registered Mobile"))
        assertTrue(CpuDeterministicStage.labelIsSensitive("Permanent Address"))
        assertTrue(CpuDeterministicStage.labelIsSensitive("Relationship Manager"))
    }

    @Test
    fun `a header word deep in the page is not a letterhead`() {
        nextId = 1
        val midPage = span("Statement of account", 60, 1400, 400)
        assertFalse(CpuDeterministicStage.isDocumentHeader(midPage, emptyList(), pageHeight))
        val top = span("Statement of account", 60, 90, 400)
        assertTrue(CpuDeterministicStage.isDocumentHeader(top, emptyList(), pageHeight))
    }
}
