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
 *  3. **Deterministic** ([CpuDeterministicStage]) - a high-confidence identifier pattern, or the
 *     value paired with a sensitive caption. Above both models because Phone C measured them
 *     getting exactly these spans wrong on every two-column form, in both directions.
 *  4. **Referee** (Gemma) - contested spans only; labels are excluded from that queue.
 *  5. **Workhorse** (Qwen).
 *  6. **Hints**, but *only* when [degraded] is true (no model available). Otherwise hints are
 *     context for the prompt, never a decision, per the architecture rules.
 *  7. **Default -> KEEP.**
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
        deterministic: Map<Int, SpanDecision> = emptyMap(),
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

            // Settled on the CPU without a model. These spans were never enqueued for Qwen or
            // Gemma, so in practice there is nothing to conflict with - but when a stale model
            // answer does exist (e.g. a re-merge after a tap), the deterministic verdict wins.
            val settled = deterministic[id]
            if (settled != null && settled.action != Action.UNSURE) {
                out[id] = settled
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
    fun isModeCollapsed(
        actions: Collection<Action>,
        minBatch: Int = 4,
        collapseRatio: Float = COLLAPSE_RATIO,
    ): Boolean {
        if (actions.size < minBatch) return false
        if (actions.distinct().size == 1) return true
        // Near-uniform counts too. Requiring *every* verdict to match let the real failure
        // through: over a bank statement the workhorse answered "A B D E F H I J" - eight of ten
        // lines hidden, including a heading, two transaction dates and footer boilerplate. That
        // is the same degenerate decoding wearing two keeps as a disguise, and on the user's own
        // documents it blacked out 21 of 25 spans.
        val hides = actions.count { it == Action.HIDE }
        return hides >= actions.size * collapseRatio
    }

    /**
     * Share of a batch that must be HIDE before the answer is treated as decoding noise.
     *
     * A genuinely dense page exists - an Aadhaar card really is mostly private - but those lines
     * are what the deterministic detectors and the layout pass already catch at full precision.
     * What this threshold protects is the opposite case: an ordinary page where the model has
     * stopped reading and started repeating.
     */
    const val COLLAPSE_RATIO = 0.8f

    /**
     * Looser threshold for the hide-list wire format used by the NPU workhorse.
     *
     * The 0.6B on Hexagon answers with letter ids (`A B D`), so an all-hide verdict means
     * "I read the page and picked every line" rather than "I am repeating one token". Set
     * this just above 1.0 so an honest page-of-PII is not discarded; the genuine
     * repeat-one-action failure still trips because the threshold is never reached when
     * the model produces mixed verdicts (HIDE + KEEP).
     */
    const val COLLAPSE_RATIO_HIDE_LIST = 1.01f

    /**
     * The spans that actually get painted over.
     *
     * Only [Action.HIDE] hides. [Action.UNSURE] deliberately does not.
     */
    fun hiddenIds(decisions: Map<Int, SpanDecision>): Set<Int> =
        decisions.values.filter { it.action == Action.HIDE }.mapTo(LinkedHashSet()) { it.id }

    /**
     * Hard cap on Gemma's queue. Dense pages previously sent 14+ spans (~13–19 s on GPU).
     * Leak-risk items always go; possible false-positives fill remaining slots by document order.
     * Layout KEEP is unchanged: labels never enter this queue.
     */
    const val REFEREE_QUEUE_CAP = 8

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
     *
     * [maxSize] drops lowest-priority items (uncorroborated HIDE, then mode-collapse) so wall
     * time stays bounded. Leak-risk (null / UNSURE / KEEP+STRONG) is never dropped, even if
     * that exceeds [maxSize].
     */
    fun refereeQueue(
        spans: List<TextSpan>,
        workhorse: Map<Int, SpanDecision>,
        hints: Map<Int, List<CandidateHint>>,
        /** Spans from batches whose output looked degenerate - see [isModeCollapsed]. */
        lowConfidence: Set<Int> = emptySet(),
        maxSize: Int = REFEREE_QUEUE_CAP,
    ): List<Int> {
        data class Ranked(val id: Int, val priority: Int)
        val ranked = spans.mapNotNull { span ->
            val decision = workhorse[span.id]
            val spanHints = hints[span.id].orEmpty()
            val strong = spanHints.any { it.strength == HintStrength.STRONG }
            val priority = when {
                // A label's verdict is already settled by layout, so refereeing it is pure cost -
                // and on the fixture the referee was the thing wrongly hiding them. Note that labels
                // are still sent to the *workhorse*: dropping them would destroy the label/value
                // adjacency ReadingOrder exists to create, and leave batches of pure values that
                // legitimately come back all-hide and trip isModeCollapsed.
                span.role == SpanRole.LABEL -> null
                // No usable answer at all. A 0.6B routinely returns `{"decisions":[]}` for a whole
                // batch; treating that as "keep everything" is a silent, total failure of the
                // redaction. Escalate instead - the referee is exactly the fallback for this.
                decision == null -> 0
                decision.action == Action.KEEP && strong -> 1
                decision.action == Action.UNSURE -> 2
                span.id in lowConfidence -> 3
                // Possible false positive: "hide" with nothing corroborating it. This is how field
                // labels ("Account Holder", "PAN") get blacked out - the workhorse tars them with the
                // value beside them. Escalating keeps the referee arbitrating in BOTH directions
                // rather than only ever adding redactions.
                decision.action == Action.HIDE && spanHints.isEmpty() -> 4
                else -> null
            } ?: return@mapNotNull null
            Ranked(span.id, priority)
        }
        val must = ranked.filter { it.priority <= 2 }
        val optional = ranked.filter { it.priority > 2 }
        val remaining = (maxSize - must.size).coerceAtLeast(0)
        val chosen = (must + optional.take(remaining)).map { it.id }.toSet()
        return ranked.filter { it.id in chosen }.map { it.id }
    }
}
