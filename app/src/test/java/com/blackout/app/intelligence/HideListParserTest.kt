package com.blackout.app.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON salvage tests in [DecisionParserTest] do not cover this path - the NPU runtime cannot
 * do constrained decoding, so the plain-text protocol needs its own pinning.
 *
 * Lines are tagged A, B, C and the reply is letters. The regression that forced that is pinned in
 * `a reply of page digits is not an answer`.
 */
class HideListParserTest {

    private val ids = setOf(1, 2, 3, 4, 5)

    private fun parse(raw: String) = HideListParser.parse(raw, ids, DecisionSource.WORKHORSE)

    private fun hidden(raw: String) =
        parse(raw).filterValues { it.action == Action.HIDE }.keys

    @Test
    fun `bare list hides exactly those lines`() {
        assertEquals(setOf(1, 4), hidden("A D"))
    }

    @Test
    fun `every asked id gets a decision`() {
        val out = parse("B")
        assertEquals(ids, out.keys)
        assertEquals(Action.HIDE, out.getValue(2).action)
        assertEquals(Action.KEEP, out.getValue(1).action)
    }

    @Test
    fun `separators do not matter`() {
        assertEquals(setOf(1, 3, 5), hidden("A, C, E"))
        assertEquals(setOf(1, 3, 5), hidden("A,C,E"))
        assertEquals(setOf(1, 3, 5), hidden("A\tC\tE"))
    }

    @Test
    fun `prose padding is tolerated`() {
        assertEquals(setOf(2, 4), hidden("Hide: B and D."))
    }

    @Test
    fun `an explicit none hides nothing but still decides every span`() {
        val out = parse("none")
        assertTrue(out.values.all { it.action == Action.KEEP })
        assertEquals(ids, out.keys)
    }

    @Test
    fun `a restated question does not poison the answer`() {
        // The echo comes first, the answer last - so the last tag-bearing line wins.
        assertEquals(setOf(2, 5), hidden("For lines A B C D E, which are sensitive?\nB E"))
    }

    @Test
    fun `tags we never asked about are ignored`() {
        assertEquals(setOf(1), hidden("A Y Z"))
    }

    @Test
    fun `think blocks and fences are stripped`() {
        assertEquals(setOf(3), hidden("<think>hmm, A and B look fine</think>\nC"))
        assertEquals(setOf(2), hidden("```\nB\n```"))
    }

    @Test
    fun `an unreadable reply yields nothing so the referee picks it up`() {
        // Empty is NOT "hide nothing" - the caller must be able to tell those apart.
        assertTrue(parse("").isEmpty())
        assertTrue(parse("I'm sorry, I cannot help with that.").isEmpty())
        assertTrue(parse("<think>unterminated").isEmpty())
    }

    @Test
    fun `a reply of page digits is not an answer`() {
        // Verbatim from the device, asked for "the numbers of the lines to hide" over a bank
        // statement. The model answered a different question - which *numbers on the page* are
        // sensitive - returning a PIN code, a date and two amounts. Under the numeric protocol
        // the fragments "01" and "03" were kept as line ids and two spans were redacted for no
        // reason. It must now read as no answer at all, so the batch escalates.
        assertTrue(parse("42 560038 01 2026-31 124500 208315 560038 03 420").isEmpty())
        assertTrue(parse("11 145000 19 5012 **** 1234 8999 27 7 35000 9 9").isEmpty())
    }

    @Test
    fun `a capital inside a word is not a tag`() {
        // "Aadhaar" echoed off the page must not read as tag A.
        assertTrue(parse("Aadhaar Bengaluru Karnataka").isEmpty())
    }

    @Test
    fun `all lines listed is still parsed - mode collapse is the caller's job`() {
        assertEquals(ids, hidden("A B C D E"))
    }

    @Test
    fun `decisions carry the source they came from`() {
        val out = HideListParser.parse("A", ids, DecisionSource.WORKHORSE)
        assertEquals(DecisionSource.WORKHORSE, out.getValue(1).source)
    }
}
