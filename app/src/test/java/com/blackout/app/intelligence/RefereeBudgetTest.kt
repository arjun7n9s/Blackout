package com.blackout.app.intelligence

import com.blackout.app.ocr.SpanRect
import com.blackout.app.ocr.TextSpan
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two page shapes where Phone C measured Gemma doing net harm. */
class RefereeBudgetTest {

    private val a4Height = 1754

    private fun spans(count: Int, height: Int) = List(count) { i ->
        TextSpan(
            id = i,
            text = "line $i",
            rect = SpanRect(60, 100 + i * (height + 6), 500, 100 + i * (height + 6) + height),
            confidence = 1f,
            blockIndex = 0,
            lineIndex = i,
        )
    }

    @Test
    fun `C-015 dense ledger skips the referee`() {
        val reason = RefereeBudget.skipReason(141, 22, a4Height)
        assertNotNull(reason)
        assertTrue(reason!!.contains("dense"))
    }

    @Test
    fun `a page just inside the limit still gets the referee`() {
        assertNull(RefereeBudget.skipReason(RefereeBudget.DENSE_SPAN_LIMIT, 22, a4Height))
    }

    @Test
    fun `C-008 small print skips the referee`() {
        // 6-9 pt body at 150 dpi: ~15 px lines on a 1754 px page.
        val reason = RefereeBudget.skipReason(51, 15, a4Height)
        assertNotNull(reason)
        assertTrue(reason!!.contains("small print"))
    }

    @Test
    fun `C-001 bank statement and C-017 payslip keep the referee`() {
        assertNull("85-span statement, 10-11pt", RefereeBudget.skipReason(85, 23, a4Height))
        assertNull("46-span payslip", RefereeBudget.skipReason(46, 26, a4Height))
    }

    @Test
    fun `a small card of tiny text is not a small-print contract`() {
        // Few spans: the referee is cheap here and C never saw it misbehave on cards.
        assertNull(RefereeBudget.skipReason(12, 12, a4Height))
    }

    @Test
    fun `median line height ignores the outlier headline`() {
        val page = spans(20, 24) + TextSpan(
            id = 99, text = "HORIZON BANK",
            rect = SpanRect(60, 40, 500, 140), confidence = 1f, blockIndex = 0, lineIndex = 0,
        )
        assertTrue(RefereeBudget.medianSpanHeight(page) == 24)
    }

    @Test
    fun `no spans means no median and no skip`() {
        assertTrue(RefereeBudget.medianSpanHeight(emptyList()) == 0)
        assertNull(RefereeBudget.skipReason(0, 0, a4Height))
    }
}
