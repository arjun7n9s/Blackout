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
     * [CpuDeterministicStage]: a high-confidence pattern, or the value paired with a sensitive
     * caption. Beats both models, because on these spans a regex and a row of geometry are simply
     * more reliable than a 0.6B - see `C-Outputs/label-bugs.jsonl`.
     */
    DETERMINISTIC,

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
    /** [CpuDeterministicStage] output. These spans were never sent to a model. */
    val deterministic: Map<Int, SpanDecision> = emptyMap(),
    val hints: Map<Int, List<CandidateHint>> = emptyMap(),
    val stats: List<InferenceStat> = emptyList(),
    /** Per-stage receipt: which silicon ran what, over how many spans. Drives the HUD. */
    val stages: List<StageReport> = emptyList(),
    val docSummary: String? = null,
    /** True when no model ran and we fell back to regex only. Surfaced in the UI. */
    val degraded: Boolean = false,
    val degradedReason: String? = null,
    /**
     * Spans after [com.blackout.app.ocr.FieldLayout.applyTo]. Empty only when analysis never
     * ran. Merge, overlay, and share all read from here once the ViewModel has swapped them in.
     */
    val spans: List<TextSpan> = emptyList(),
    /** Non-null when [RefereeBudget] vetoed Gemma for this page shape. Shown in the debug panel. */
    val refereeSkipReason: String? = null,
    /** Median OCR line height in px. Kept so the small-print threshold can be checked on device. */
    val medianSpanHeight: Int = 0,
) {
    val totalMs: Long get() = stats.sumOf { it.elapsedMs }

    /**
     * The per-image backend receipt, e.g.
     * `CPU·det 24 · qwen 21 | NPU·cls skip | GPU·gemma 8 | total 28.8s`.
     *
     * Built from [stages], which record what *ran*, so it cannot advertise a backend that did not.
     */
    val backendReport: BackendReport
        get() {
            // stats already cover the model stages; the deterministic stage is not in there.
            val deterministicMs = stages
                .filter { it.task == StageReport.DETERMINISTIC }
                .sumOf { it.elapsedMs }
            return BackendReport(stages, totalMs + deterministicMs)
        }

    /**
     * What the HUD prints. Distinct backends in load order, so a mixed cascade is `NPU+CPU`
     * rather than silently advertising the first engine. Empty when nothing loaded.
     */
    val hudBackend: String?
        get() {
            val labels = stats.map { it.backend }
                .filter { it.isNotBlank() && it != "unloaded" && it != "failed" && it != "none" }
            if (labels.isEmpty()) return null
            return labels.distinct().joinToString("+")
        }

    companion object {
        val Empty = AnalysisResult()
    }
}
