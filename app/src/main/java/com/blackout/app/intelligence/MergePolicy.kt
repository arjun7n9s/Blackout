package com.blackout.app.intelligence

import com.blackout.app.ocr.SpanRole
import com.blackout.app.ocr.TextSpan

/**
 * Turns everything we know about a span into one final, deterministic decision.
 *
 * Pure by design - no Android types, no IO - so the whole policy is unit-testable without a
 * device or Robolectric.
 *
 * Resolution order, highest priority first:
 *  1. **User override.** An explicit tap always wins. Nothing overrules the person holding the phone.
 *  2. **Layout.** A span geometrically identified as a field label stays visible.
 *  3. **Referee** (Gemma) - contested spans only; labels are excluded from that queue.
 *  4. **Workhorse** (Qwen).
 *  5. **Hints**, but *only* when [degraded] is true (no model available). Otherwise hints are
 *     context for the prompt, never a decision, per the architecture rules.
 *  6. **Default -> KEEP.**
 *
 * The central safety choice: **UNSURE is not HIDE.** An unresolved span stays visible, because a
 * false positive silently destroys information the user wanted, while a false negative is visible
 * on screen and one tap from being fixed.
 */
object MergePolicy {

    fun merge(
        spans: List<TextSpan>,
        workhorse: Map<Int, SpanDecision> = emptyMap(),
        referee: Map<Int, SpanDecision> = emptyMap(),
        hints: Map<Int, List<CandidateHint>> = emptyMap(),
        userOverrides: Map<Int, Action> = emptyMap(),
        degraded: Boolean = false,
    ): Map<Int, SpanDecision> {
        val out = LinkedHashMap<Int, SpanDecision>(spans.size)

        for (span in spans) {
            val id = span.id

            val userAction = userOverrides[id]
            if (userAction != null) {
                out[id] = SpanDecision(id, userAction, DecisionSource.USER, "user tap")
                continue
            }

            // Layout beats BOTH models, which is a deliberate and slightly uncomfortable choice.
            //
            // Justification is empirical: on the two-column fixture, 11 of 13 wrongly-hidden
            // strings were left-column labels, and the referee was the source of most of them -
            // it receives the value as neighbour context, so "Aadhaar" gets judged next to
            // "2345 6789 0123". The models are systematically wrong in one direction here and
            // geometry is not.
            //
            // The risk - a genuinely sensitive span misread as a label - is closed upstream:
            // FieldLayout.applyTo refuses LABEL to anything carrying a strong regex hint, and
            // looksLikeLabel rejects digits, '@' and long text. Whatever slips through is one
            // tap from hidden, and the user can see it to make that call.
            if (span.role == SpanRole.LABEL) {
                out[id] = SpanDecision(id, Action.KEEP, DecisionSource.LAYOUT, "field label")
                continue
            }

            val refereed = referee[id]
            if (refereed != null && refereed.action != Action.UNSURE) {
                out[id] = refereed.copy(source = DecisionSource.REFEREE)
                continue
            }

            val judged = workhorse[id]
            if (judged != null && judged.action != Action.UNSURE) {
                out[id] = judged.copy(source = DecisionSource.WORKHORSE)
                continue
            }

            if (degraded) {
                val strong = hints[id].orEmpty().firstOrNull { it.strength == HintStrength.STRONG }
                out[id] = if (strong != null) {
                    SpanDecision(id, Action.HIDE, DecisionSource.HEURISTIC, "regex: ${strong.kind.label}")
                } else {
                    SpanDecision(id, Action.KEEP, DecisionSource.HEURISTIC, "no strong pattern")
                }
                continue
            }

            // Still unresolved. Record that it was genuinely UNSURE (rather than silently
            // KEEP) so the debug panel and the referee queue can see it, but it renders visible.
            val wasUnsure = refereed?.action == Action.UNSURE || judged?.action == Action.UNSURE
            out[id] = if (wasUnsure) {
                SpanDecision(id, Action.UNSURE, refereed?.source ?: DecisionSource.WORKHORSE, "unsure -> kept visible")
            } else {
                SpanDecision(id, Action.KEEP, DecisionSource.DEFAULT, "no decision")
            }
        }

        return out
    }

    /**
     * True when a batch's verdicts are degenerate rather than considered.
     *
     * Greedy decoding over a constrained grammar makes a 0.6B prone to emitting one action and
     * then repeating it for every remaining id. Observed on a real statement: a batch containing
     * a PAN, an Aadhaar and their field labels came back as ten consecutive "hide".
     *
     * A uniform verdict across a reasonably sized batch is therefore treated as a *confidence
     * signal*, not a judgement - those spans go to the referee. Small batches are exempt because
     * three lines genuinely can all be sensitive.
     */
    fun isModeCollapsed(actions: Collection<Action>, minBatch: Int = 4): Boolean =
        actions.size >= minBatch && actions.distinct().size == 1

    /**
     * The spans that actually get painted over.
     *
     * Only [Action.HIDE] hides. [Action.UNSURE] deliberately does not.
     */
    fun hiddenIds(decisions: Map<Int, SpanDecision>): Set<Int> =
        decisions.values.filter { it.action == Action.HIDE }.mapTo(LinkedHashSet()) { it.id }

    /**
     * The referee's work queue.
     *
     * Wider than just "unsure", because the two useful disagreement signals also belong here:
     *  - the workhorse said KEEP but a STRONG regex hint fired (independent signals disagree), and
     *  - the workhorse returned nothing usable for a span that carries any hint.
     *
     * Keeping hints out of the workhorse prompt is what makes this disagreement meaningful, and
     * it is also what structurally guarantees a hint is never the sole decision: a hint can only
     * ever escalate a span to a *model*, never redact it by itself.
     */
    fun refereeQueue(
        spans: List<TextSpan>,
        workhorse: Map<Int, SpanDecision>,
        hints: Map<Int, List<CandidateHint>>,
        /** Spans from batches whose output looked degenerate - see [isModeCollapsed]. */
        lowConfidence: Set<Int> = emptySet(),
    ): List<Int> = spans.mapNotNull { span ->
        val decision = workhorse[span.id]
        val spanHints = hints[span.id].orEmpty()
        val strong = spanHints.any { it.strength == HintStrength.STRONG }
        when {
            // A label's verdict is already settled by layout, so refereeing it is pure cost -
            // and on the fixture the referee was the thing wrongly hiding them. Note that labels
            // are still sent to the *workhorse*: dropping them would destroy the label/value
            // adjacency ReadingOrder exists to create, and leave batches of pure values that
            // legitimately come back all-hide and trip isModeCollapsed.
            span.role == SpanRole.LABEL -> null
            span.id in lowConfidence -> span.id
            // No usable answer at all. A 0.6B routinely returns `{"decisions":[]}` for a whole
            // batch; treating that as "keep everything" is a silent, total failure of the
            // redaction. Escalate instead - the referee is exactly the fallback for this.
            decision == null -> span.id
            decision.action == Action.UNSURE -> span.id
            // Possible false negative: independent regex signal disagrees with "keep".
            decision.action == Action.KEEP && strong -> span.id
            // Possible false positive: "hide" with nothing corroborating it. This is how field
            // labels ("Account Holder", "PAN") get blacked out - the workhorse tars them with the
            // value beside them. Escalating keeps the referee arbitrating in BOTH directions
            // rather than only ever adding redactions.
            decision.action == Action.HIDE && spanHints.isEmpty() -> span.id
            else -> null
        }
    }
}
