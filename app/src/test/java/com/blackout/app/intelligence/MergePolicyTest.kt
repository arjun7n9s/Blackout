package com.blackout.app.intelligence

import com.blackout.app.ocr.SpanRect
import com.blackout.app.ocr.SpanRole
import com.blackout.app.ocr.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergePolicyTest {

    private fun span(
        id: Int,
        text: String = "x",
        role: SpanRole = SpanRole.STANDALONE,
    ) = TextSpan(id, text, SpanRect(0, id * 10, 100, id * 10 + 8), 1f, 0, id, role = role)

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

        // Near-uniform counts too. Verbatim from the device: asked about ten lines of a bank
        // statement the workhorse replied "A B D E F H I J" - eight hidden, among them a heading,
        // two transaction dates and footer boilerplate. Requiring every verdict to match let this
        // through as a considered answer, and on real documents it blacked out 21 of 25 spans.
        val eightOfTen = List(8) { Action.HIDE } + List(2) { Action.KEEP }
        assertTrue(MergePolicy.isModeCollapsed(eightOfTen))

        // A page that is genuinely mostly private is still allowed to say so, as long as it is
        // discriminating about it.
        val sixOfTen = List(6) { Action.HIDE } + List(4) { Action.KEEP }
        assertFalse(MergePolicy.isModeCollapsed(sixOfTen))

        // The ratio is about HIDE specifically - an all-but-one KEEP batch is not a page being
        // blacked out, so it only trips the uniform rule.
        val eightKeeps = List(8) { Action.KEEP } + List(2) { Action.HIDE }
        assertFalse(MergePolicy.isModeCollapsed(eightKeeps))

        val queue = MergePolicy.refereeQueue(
            spans = spans,
            workhorse = mapOf(1 to decision(1, Action.HIDE)),
            hints = mapOf(1 to listOf(CandidateHint(HintKind.EMAIL, "a@b.com"))),
            lowConfidence = setOf(1),
        )
        assertTrue(1 in queue)
    }

    @Test
    fun `layout keep beats both models hiding a field label`() {
        val labelled = listOf(
            span(1, "Account Holder", SpanRole.LABEL),
            span(2, "Priya Ramachandran", SpanRole.VALUE),
            span(3),
        )
        val merged = MergePolicy.merge(
            spans = labelled,
            workhorse = mapOf(
                1 to decision(1, Action.HIDE),
                2 to decision(2, Action.HIDE),
            ),
            referee = mapOf(1 to decision(1, Action.HIDE, DecisionSource.REFEREE)),
        )
        assertEquals(Action.KEEP, merged.getValue(1).action)
        assertEquals(DecisionSource.LAYOUT, merged.getValue(1).source)
        assertEquals(Action.HIDE, merged.getValue(2).action)
        assertFalse(1 in MergePolicy.hiddenIds(merged))
        assertTrue(2 in MergePolicy.hiddenIds(merged))
    }

    @Test
    fun `user tap still hides a layout label`() {
        val labelled = listOf(span(1, "PAN", SpanRole.LABEL), span(2), span(3))
        val merged = MergePolicy.merge(
            spans = labelled,
            workhorse = mapOf(1 to decision(1, Action.KEEP)),
            userOverrides = mapOf(1 to Action.HIDE),
        )
        assertEquals(Action.HIDE, merged.getValue(1).action)
        assertEquals(DecisionSource.USER, merged.getValue(1).source)
        assertTrue(1 in MergePolicy.hiddenIds(merged))
    }

    @Test
    fun `labels are excluded from the referee queue`() {
        val labelled = listOf(
            span(1, "Aadhaar", SpanRole.LABEL),
            span(2, "2345 6789 0123", SpanRole.VALUE),
            span(3),
        )
        val queue = MergePolicy.refereeQueue(
            spans = labelled,
            workhorse = mapOf(
                1 to decision(1, Action.HIDE),
                2 to decision(2, Action.HIDE),
                3 to decision(3, Action.UNSURE),
            ),
            hints = emptyMap(),
        )
        // 1 is a label: layout has already settled it, even though hide-without-hint
        // would otherwise escalate. 2 is a value with hide-and-no-hint, so it still goes.
        // 3 abstained.
        assertEquals(listOf(2, 3), queue)
    }

    @Test
    fun `queue cap keeps leak-risk and drops extra uncorroborated hides`() {
        val many = (1..12).map { span(it) }
        val workhorse = many.associate { s ->
            s.id to if (s.id <= 3) decision(s.id, Action.KEEP) else decision(s.id, Action.HIDE)
        }
        val hints = mapOf(
            1 to listOf(CandidateHint(HintKind.PAN, "ABCDE1234F")),
            2 to listOf(CandidateHint(HintKind.AADHAAR, "2345 6789 0123")),
            3 to listOf(CandidateHint(HintKind.EMAIL, "a@b.com")),
        )
        val queue = MergePolicy.refereeQueue(
            spans = many,
            workhorse = workhorse,
            hints = hints,
            maxSize = 8,
        )
        assertTrue(queue.containsAll(listOf(1, 2, 3)))
        assertEquals(8, queue.size)
        assertFalse(12 in queue)
    }

    @Test
    fun `leak-risk is never dropped even if it exceeds the cap`() {
        val many = (1..10).map { span(it) }
        val workhorse = many.associate { s -> s.id to decision(s.id, Action.KEEP) }
        val hints = many.associate { s ->
            s.id to listOf(CandidateHint(HintKind.PAN, "ABCDE1234F"))
        }
        val queue = MergePolicy.refereeQueue(
            spans = many,
            workhorse = workhorse,
            hints = hints,
            maxSize = 8,
        )
        assertEquals(10, queue.size)
    }
}
