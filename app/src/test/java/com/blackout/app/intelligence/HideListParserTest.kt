package com.blackout.app.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON salvage tests in [DecisionParserTest] do not cover this path - the NPU runtime cannot
 * do constrained decoding, so the plain-text protocol needs its own pinning.
 */
class HideListParserTest {

    private val ids = setOf(1, 2, 3, 4, 5)

    private fun parse(raw: String) = HideListParser.parse(raw, ids, DecisionSource.WORKHORSE)

    private fun hidden(raw: String) =
        parse(raw).filterValues { it.action == Action.HIDE }.keys

    @Test
    fun `bare list hides exactly those ids`() {
        assertEquals(setOf(1, 4), hidden("1 4"))
    }

    @Test
    fun `every asked id gets a decision`() {
        val out = parse("2")
        assertEquals(ids, out.keys)
        assertEquals(Action.HIDE, out.getValue(2).action)
        assertEquals(Action.KEEP, out.getValue(1).action)
    }

    @Test
    fun `separators do not matter`() {
        assertEquals(setOf(1, 3, 5), hidden("1, 3, 5"))
        assertEquals(setOf(1, 3, 5), hidden("1,3,5"))
        assertEquals(setOf(1, 3, 5), hidden("1\t3\t5"))
    }

    @Test
    fun `prose padding is tolerated`() {
        assertEquals(setOf(2, 4), hidden("Hide: 2 and 4."))
        assertEquals(setOf(3), hidden("The sensitive line is number 3."))
    }

    @Test
    fun `an explicit none hides nothing but still decides every span`() {
        val out = parse("none")
        assertTrue(out.values.all { it.action == Action.KEEP })
        assertEquals(ids, out.keys)
    }

    @Test
    fun `a restated question does not poison the answer`() {
        // The echo comes first, the answer last - so the last digit-bearing line wins.
        val out = hidden("For ids 1 2 3 4 5, which are sensitive?\n2 5")
        assertEquals(setOf(2, 5), out)
    }

    @Test
    fun `ids we never asked about are ignored`() {
        assertEquals(setOf(1), hidden("1 99 1000"))
    }

    @Test
    fun `think blocks and fences are stripped`() {
        assertEquals(setOf(3), hidden("<think>hmm, 1 and 2 look fine</think>\n3"))
        assertEquals(setOf(2), hidden("```\n2\n```"))
    }

    @Test
    fun `an unreadable reply yields nothing so the referee picks it up`() {
        // Empty is NOT "hide nothing" - the caller must be able to tell those apart.
        assertTrue(parse("").isEmpty())
        assertTrue(parse("I'm sorry, I cannot help with that.").isEmpty())
        assertTrue(parse("<think>unterminated").isEmpty())
    }

    @Test
    fun `all ids listed is still parsed - mode collapse is the caller's job`() {
        assertEquals(ids, hidden("1 2 3 4 5"))
    }

    @Test
    fun `decisions carry the source they came from`() {
        val out = HideListParser.parse("1", ids, DecisionSource.WORKHORSE)
        assertEquals(DecisionSource.WORKHORSE, out.getValue(1).source)
    }
}
