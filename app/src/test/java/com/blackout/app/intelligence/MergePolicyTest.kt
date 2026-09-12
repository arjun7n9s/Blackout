package com.blackout.app.intelligence

import com.blackout.app.ocr.SpanRect
import com.blackout.app.ocr.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergePolicyTest {

    private fun span(id: Int, text: String = "x") =
        TextSpan(id, text, SpanRect(0, id * 10, 100, id * 10 + 8), 1f, 0, id)

    private val spans = listOf(span(1), span(2), span(3))

    private fun decision(id: Int, action: Action, source: DecisionSource = DecisionSource.WORKHORSE) =
        SpanDecision(id, action, source)

    @Test
    fun `unsure is never hidden`() {
        val merged = MergePolicy.merge(
            spans = spans,
            workhorse = mapOf(1 to decision(1, Action.UNSURE)),
        )
        assertEquals(Action.UNSURE, merged.getValue(1).action)
        assertFalse(1 in MergePolicy.hiddenIds(merged))
    }

    @Test
    fun `user override beats referee and workhorse`() {
        val merged = MergePolicy.merge(
            spans = spans,
            workhorse = mapOf(1 to decision(1, Action.HIDE)),
            referee = mapOf(1 to decision(1, Action.HIDE, DecisionSource.REFEREE)),
            userOverrides = mapOf(1 to Action.KEEP),
        )
        assertEquals(Action.KEEP, merged.getValue(1).action)
        assertEquals(DecisionSource.USER, merged.getValue(1).source)
    }

    @Test
    fun `user can hide something both models kept`() {
        val merged = MergePolicy.merge(
            spans = spans,
            workhorse = mapOf(2 to decision(2, Action.KEEP)),
            userOverrides = mapOf(2 to Action.HIDE),
        )
        assertTrue(2 in MergePolicy.hiddenIds(merged))
    }

    @Test
    fun `referee upgrades an unsure workhorse verdict`() {
        val merged = MergePolicy.merge(
            spans = spans,
            workhorse = mapOf(3 to decision(3, Action.UNSURE)),
            referee = mapOf(3 to decision(3, Action.HIDE, DecisionSource.REFEREE)),
        )
        assertEquals(Action.HIDE, merged.getValue(3).action)
        assertEquals(DecisionSource.REFEREE, merged.getValue(3).source)
    }

    @Test
    fun `hints alone never hide when a model ran`() {
        val merged = MergePolicy.merge(
            spans = spans,
            workhorse = mapOf(1 to decision(1, Action.KEEP)),
            hints = mapOf(1 to listOf(CandidateHint(HintKind.AADHAAR, "2345 6789 0123"))),
            degraded = false,
        )
        assertEquals(Action.KEEP, merged.getValue(1).action)
        assertTrue(MergePolicy.hiddenIds(merged).isEmpty())
    }

    @Test
    fun `degraded path lets a strong hint hide`() {
        val merged = MergePolicy.merge(
            spans = spans,
            hints = mapOf(1 to listOf(CandidateHint(HintKind.EMAIL, "a@b.com"))),
            degraded = true,
        )
        assertEquals(Action.HIDE, merged.getValue(1).action)
        assertEquals(DecisionSource.HEURISTIC, merged.getValue(1).source)
    }

    @Test
    fun `degraded path keeps weak hints visible`() {
        val merged = MergePolicy.merge(
            spans = spans,
            hints = mapOf(1 to listOf(CandidateHint(HintKind.DATE, "01/02/2020", HintStrength.WEAK))),
            degraded = true,
        )
        assertEquals(Action.KEEP, merged.getValue(1).action)
    }

    @Test
    fun `every span always gets a decision`() {
        val merged = MergePolicy.merge(spans = spans)
        assertEquals(spans.size, merged.size)
        assertTrue(merged.values.all { it.action == Action.KEEP })
    }

    @Test
    fun `referee queue arbitrates in both directions`() {
        val queue = MergePolicy.refereeQueue(
            spans = spans,
            workhorse = mapOf(
                1 to decision(1, Action.UNSURE),
                2 to decision(2, Action.KEEP),
                3 to decision(3, Action.HIDE),
            ),
            hints = mapOf(
                2 to listOf(CandidateHint(HintKind.PAN, "ABCDE1234F")),
                3 to listOf(CandidateHint(HintKind.EMAIL, "a@b.com")),
            ),
        )
        // 1 abstained; 2 is keep-but-strongly-hinted (possible false negative).
        // 3 is hide WITH a corroborating hint, so it is settled and must not be escalated.
        assertEquals(listOf(1, 2), queue)
    }

    @Test
    fun `hide without a corroborating hint is escalated as a possible false positive`() {
        // This is how field labels ("Account Holder") get blacked out: the workhorse tars them
        // with the value beside them. Without this rule the referee could only ever add bars.
        val queue = MergePolicy.refereeQueue(
            spans = spans,
            workhorse = mapOf(
                1 to decision(1, Action.HIDE),
                2 to decision(2, Action.KEEP),
                3 to decision(3, Action.HIDE),
            ),
            hints = mapOf(3 to listOf(CandidateHint(HintKind.AADHAAR, "2345 6789 0123"))),
        )
        assertEquals(listOf(1), queue)
    }

    @Test
    fun `a span with no decision at all is escalated, never silently kept`() {
        // A 0.6B routinely returns an empty decisions array for a whole batch.
        val queue = MergePolicy.refereeQueue(spans = spans, workhorse = emptyMap(), hints = emptyMap())
        assertEquals(listOf(1, 2, 3), queue)
    }

    @Test
    fun `mode-collapsed batches are escalated wholesale`() {
        assertTrue(MergePolicy.isModeCollapsed(List(10) { Action.HIDE }))
        assertTrue(MergePolicy.isModeCollapsed(List(4) { Action.KEEP }))
        // Three lines genuinely can all be sensitive, so small batches are exempt.
        assertFalse(MergePolicy.isModeCollapsed(List(3) { Action.HIDE }))
        assertFalse(MergePolicy.isModeCollapsed(listOf(Action.HIDE, Action.KEEP, Action.HIDE, Action.HIDE)))

        val queue = MergePolicy.refereeQueue(
            spans = spans,
            workhorse = mapOf(1 to decision(1, Action.HIDE)),
            hints = mapOf(1 to listOf(CandidateHint(HintKind.EMAIL, "a@b.com"))),
            lowConfidence = setOf(1),
        )
        assertTrue(1 in queue)
    }
}
