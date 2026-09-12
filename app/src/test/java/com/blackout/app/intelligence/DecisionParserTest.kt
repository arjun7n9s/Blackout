package com.blackout.app.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These cases are the actual failure modes of a 0.6B model asked for JSON, not hypotheticals.
 */
class DecisionParserTest {

    private val ids = setOf(1, 2, 3)

    private fun parse(raw: String) = DecisionParser.parse(raw, ids, DecisionSource.WORKHORSE)

    @Test
    fun `clean response`() {
        val out = parse("""{"decisions":[{"id":1,"a":"hide"},{"id":2,"a":"keep"}]}""")
        assertEquals(Action.HIDE, out.getValue(1).action)
        assertEquals(Action.KEEP, out.getValue(2).action)
    }

    @Test
    fun `markdown fence with prose preamble and trailer`() {
        val out = parse(
            """
            Sure! Here are the decisions:
            ```json
            {"decisions":[{"id":1,"a":"hide"}]}
            ```
            Hope this helps.
            """.trimIndent()
        )
        assertEquals(Action.HIDE, out.getValue(1).action)
    }

    @Test
    fun `truncated array still yields the complete objects`() {
        val out = parse("""{"decisions":[{"id":1,"a":"hide"},{"id":2,"a":"keep"},{"id":3,"a":""")
        assertEquals(2, out.size)
        assertEquals(Action.HIDE, out.getValue(1).action)
        assertNull(out[3])
    }

    @Test
    fun `trailing comma is tolerated`() {
        val out = parse("""{"decisions":[{"id":1,"a":"keep"},]}""")
        assertEquals(Action.KEEP, out.getValue(1).action)
    }

    @Test
    fun `bare single object without an array`() {
        assertEquals(Action.HIDE, parse("""{"id":2,"a":"hide"}""").getValue(2).action)
    }

    @Test
    fun `long key name and reversed key order`() {
        val out = parse("""[{"action":"hide","id":1},{"a":"keep","id":2}]""")
        assertEquals(Action.HIDE, out.getValue(1).action)
        assertEquals(Action.KEEP, out.getValue(2).action)
    }

    @Test
    fun `hallucinated ids are dropped`() {
        val out = parse("""[{"id":1,"a":"hide"},{"id":99,"a":"hide"}]""")
        assertEquals(setOf(1), out.keys)
    }

    @Test
    fun `duplicate id resolves most-protective-wins`() {
        // The model waffled on id 1. Under confusion we hide, because a tap can undo that
        // whereas a silent leak cannot.
        val out = parse("""[{"id":1,"a":"keep"},{"id":1,"a":"hide"}]""")
        assertEquals(Action.HIDE, out.getValue(1).action)

        val reversed = parse("""[{"id":1,"a":"hide"},{"id":1,"a":"keep"}]""")
        assertEquals(Action.HIDE, reversed.getValue(1).action)
    }

    @Test
    fun `leaked think block is stripped`() {
        val out = parse(
            """<think>The user wants me to decide. Id 1 looks like a name.</think>
               {"decisions":[{"id":1,"a":"hide"}]}"""
        )
        assertEquals(1, out.size)
        assertEquals(Action.HIDE, out.getValue(1).action)
    }

    @Test
    fun `synonyms for actions are accepted`() {
        val out = parse("""[{"id":1,"a":"redact"},{"id":2,"a":"show"},{"id":3,"a":"maybe"}]""")
        assertEquals(Action.HIDE, out.getValue(1).action)
        assertEquals(Action.KEEP, out.getValue(2).action)
        assertEquals(Action.UNSURE, out.getValue(3).action)
    }

    @Test
    fun `garbage yields nothing so callers fall through to keep`() {
        assertTrue(parse("I'm sorry, I cannot help with that.").isEmpty())
        assertTrue(parse("").isEmpty())
        assertTrue(parse("<think>unterminated...").isEmpty())
    }

    @Test
    fun `referee reason is captured`() {
        val out = DecisionParser.parse(
            """[{"id":1,"a":"hide","reason":"personal phone number"}]""",
            ids,
            DecisionSource.REFEREE,
        )
        assertEquals("personal phone number", out.getValue(1).reason)
        assertEquals(DecisionSource.REFEREE, out.getValue(1).source)
    }
}
