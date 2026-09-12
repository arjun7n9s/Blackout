package com.blackout.app.intelligence

import com.blackout.app.ocr.TextSpan

/** What to do with a span. */
enum class Action { HIDE, KEEP, UNSURE }

/** Who produced a decision. Ordering matters for [MergePolicy] and for the debug panel. */
enum class DecisionSource {
    /** Regex candidate hint. Never the sole decider unless we are explicitly degraded. */
    HINT,

    /** Qwen3-0.6B, the workhorse that sees every span. */
    WORKHORSE,

    /** Gemma-4-E2B, which only re-judges spans the workhorse was unsure about. */
    REFEREE,

    /** Regex-only fallback used when no model file is present. Surfaced as DEGRADED in the UI. */
    HEURISTIC,

    /**
     * Geometry: the span is a field label in a label/value pair, so it stays visible.
     *
     * Beats both models deliberately - see [MergePolicy] for why.
     */
    LAYOUT,

    /** An explicit tap by the user. Beats everything. */
    USER,

    /** Nothing decided; fell through to the default. */
    DEFAULT,
}

data class SpanDecision(
    val id: Int,
    val action: Action,
    val source: DecisionSource,
    val reason: String? = null,
)

/** Per-model timing + counts, surfaced in the debug panel to prove real on-device load. */
data class InferenceStat(
    val label: String,
    val model: String,
    val backend: String,
    val spanCount: Int,
    val batchCount: Int,
    val elapsedMs: Long,
)

/**
 * Everything the pipeline learned about one image.
 *
 * The raw per-stage outputs are kept rather than a single merged map so that a user tap can be
 * re-merged by [MergePolicy] instantly, without re-running any inference.
 *
 * [spans] are the layout-applied copies ([com.blackout.app.ocr.SpanRole] set). [MergePolicy]
 * must merge *these*, not the raw OCR list - otherwise every span is STANDALONE and LAYOUT KEEP
 * never fires. That wiring gap is how the first cut of FieldLayout compiled, tested, and still
 * blacked out the label column on device.
 */
data class AnalysisResult(
    val workhorse: Map<Int, SpanDecision> = emptyMap(),
    val referee: Map<Int, SpanDecision> = emptyMap(),
    val hints: Map<Int, List<CandidateHint>> = emptyMap(),
    val stats: List<InferenceStat> = emptyList(),
    val docSummary: String? = null,
    /** True when no model ran and we fell back to regex only. Surfaced in the UI. */
    val degraded: Boolean = false,
    val degradedReason: String? = null,
    /**
     * Spans after [com.blackout.app.ocr.FieldLayout.applyTo]. Empty only when analysis never
     * ran. Merge, overlay, and share all read from here once the ViewModel has swapped them in.
     */
    val spans: List<TextSpan> = emptyList(),
) {
    val totalMs: Long get() = stats.sumOf { it.elapsedMs }

    companion object {
        val Empty = AnalysisResult()
    }
}
