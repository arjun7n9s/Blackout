package com.blackout.app.intelligence

import android.content.Context
import android.util.Log
import com.blackout.app.ocr.FieldLayout
import com.blackout.app.ocr.SpanRole
import com.blackout.app.ocr.TextSpan
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coarse progress for the UI while the pipeline runs. */
sealed interface AnalysisStage {
    data object Hints : AnalysisStage
    data object Deterministic : AnalysisStage
    data class LoadingModel(val name: String) : AnalysisStage
    data class Workhorse(val batch: Int, val batches: Int) : AnalysisStage
    data object Summarising : AnalysisStage
    data class Referee(val batch: Int, val batches: Int) : AnalysisStage
    data object Done : AnalysisStage
}

/**
 * One image, four pieces of silicon, one set of decisions.
 *
 * ```
 *   A  OcrStage              ML Kit                      (caller: RedactViewModel)
 *   B  CpuDeterministicStage regex + FieldLayout pairing  CPU   -> settles most of a form
 *   C  NpuClassifyStage      gated span classifier        NPU   -> SKIPPED until QNN artifacts
 *   D  GpuLlmCascadeStage    Qwen3-0.6B -> Gemma-4-E2B    GPU   -> leftovers only
 *   E  MergePolicy           one decision per span        CPU
 * ```
 *
 * ## Why it is split this way
 *
 * Phone C's 17-case sweep (`C-Outputs/SUMMARY.md`) showed the failures are not the models being
 * insufficiently clever:
 *
 *  - The repeatable quality bug is *structural*: 29 label/value inversions across 9 documents. A
 *    regex and a row of geometry fix those, and stage B does it before any model is loaded.
 *  - The latency is *volume*: median 53 s full cascade vs 30 s workhorse-only. The win comes from
 *    asking the models about fewer spans, not from a bigger or a smaller model - so stage D only
 *    ever sees what stage B could not settle, and the referee is additionally gated by
 *    [RefereeBudget].
 *
 * ## Honesty
 *
 * Every stage reports the silicon that *actually* executed it ([StageReport]), and the HUD line is
 * built from those reports. Stage C reports [StageStatus.SKIPPED] on this device and will keep
 * doing so until a real dispatch library and a real NPU model exist - see [NpuClassifyStage].
 *
 * Failure is graceful throughout. Missing weights or a backend that will not load degrade to the
 * regex path, flagged [AnalysisResult.degraded]; the deterministic stage still applies, so
 * "degraded" is now meaningfully better than it was.
 */
class HybridRedactPipeline(
    private val context: Context,
    private val catalog: ModelCatalog = ModelCatalog(context),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    runtimeFactory: (ModelSpec, java.io.File) -> LlmRuntime = { spec, file ->
        LiteRtLlmRuntime(context, spec, file)
    },
) {

    private val cascade = GpuLlmCascadeStage(context, catalog, runtimeFactory)
    private val npu = NpuClassifyStage(context)

    suspend fun analyze(
        rawSpans: List<TextSpan>,
        imageWidth: Int,
        imageHeight: Int,
        onStage: (AnalysisStage) -> Unit = {},
    ): AnalysisResult = withContext(dispatcher) {
        if (rawSpans.isEmpty()) return@withContext AnalysisResult.Empty

        onStage(AnalysisStage.Hints)
        val hints = buildHints(rawSpans)

        // Geometric label/value roles. Hints are computed first because they are the safety
        // interlock: a span carrying a strong pattern is never accepted as a label, so a name or
        // email printed in the left column can't be made un-redactable by geometry.
        val layout = FieldLayout.detect(rawSpans, imageWidth)
        val spans = FieldLayout.applyTo(rawSpans, layout) { span ->
            hints[span.id].orEmpty().any { it.strength == HintStrength.STRONG }
        }
        if (layout.labelColumnX != null) {
            Log.i(
                TAG,
                "label column at x=${layout.labelColumnX}, " +
                    "${spans.count { it.role == SpanRole.LABEL }} labels / " +
                    "${spans.count { it.role == SpanRole.VALUE }} values",
            )
        }

        // ---- B. CPU: deterministic detectors + label/value pairing -------------------------
        onStage(AnalysisStage.Deterministic)
        val cpuStarted = System.currentTimeMillis()
        val cpu = CpuDeterministicStage.run(spans, hints, imageHeight)
        val queue = CpuDeterministicStage.llmQueue(spans, cpu)
        val cpuReport = StageReport(
            silicon = Silicon.CPU,
            task = StageReport.DETERMINISTIC,
            status = if (cpu.decisions.isEmpty()) StageStatus.SKIPPED else StageStatus.OK,
            spanCount = cpu.decisions.size,
            elapsedMs = System.currentTimeMillis() - cpuStarted,
            note = "hide ${cpu.hideCount} · keep ${cpu.keepCount}",
        )
        Log.i(
            TAG,
            "cpu·det settled ${cpu.decisions.size}/${spans.size} spans " +
                "(hide ${cpu.hideCount}, keep ${cpu.keepCount}) in ${cpuReport.elapsedMs}ms; " +
                "${queue.size} left for the models",
        )

        // ---- C. NPU: gated span classifier ------------------------------------------------
        val npuOutcome = npu.classify(queue)

        // ---- D. GPU: Qwen -> Gemma over the leftovers only --------------------------------
        val medianHeight = RefereeBudget.medianSpanHeight(spans)
        val refereeSkip = RefereeBudget.skipReason(spans.size, medianHeight, imageHeight)
        if (refereeSkip != null) Log.i(TAG, "referee vetoed: $refereeSkip")

        val stages = mutableListOf(cpuReport, npuOutcome.report)

        if (queue.isEmpty()) {
            // Nothing ambiguous left. Legitimate on a sparse card; no engine is loaded at all.
            Log.i(TAG, "no spans left for the cascade; CPU stage settled the page")
            onStage(AnalysisStage.Done)
            return@withContext AnalysisResult(
                deterministic = cpu.decisions,
                hints = hints,
                stages = stages,
                spans = spans,
                refereeSkipReason = refereeSkip,
                medianSpanHeight = medianHeight,
            )
        }

        val outcome = try {
            cascade.run(queue, spans, hints, refereeSkip, onStage)
        } catch (t: GpuLlmCascadeStage.Unavailable) {
            return@withContext degraded(
                spans = spans,
                hints = hints,
                deterministic = cpu.decisions,
                stages = stages,
                reason = t.message ?: "cascade unavailable",
                refereeSkip = refereeSkip,
                medianHeight = medianHeight,
            )
        }

        onStage(AnalysisStage.Done)
        AnalysisResult(
            workhorse = outcome.workhorse,
            referee = outcome.referee,
            deterministic = cpu.decisions,
            hints = hints,
            stats = outcome.stats,
            stages = stages + outcome.reports,
            docSummary = outcome.docSummary,
            degraded = false,
            spans = spans,
            refereeSkipReason = refereeSkip,
            medianSpanHeight = medianHeight,
        )
    }

    private fun buildHints(spans: List<TextSpan>): Map<Int, List<CandidateHint>> =
        spans.withIndex().associate { (index, span) ->
            val raw = CandidateHints.detect(span.text)
            val previous = spans.getOrNull(index - 1)?.text
            span.id to CandidateHints.promoteByNeighbour(raw, previous)
        }.filterValues { it.isNotEmpty() }

    private fun degraded(
        spans: List<TextSpan>,
        hints: Map<Int, List<CandidateHint>>,
        deterministic: Map<Int, SpanDecision>,
        stages: List<StageReport>,
        reason: String,
        refereeSkip: String?,
        medianHeight: Int,
    ): AnalysisResult {
        Log.w(TAG, "degraded: $reason")
        return AnalysisResult(
            deterministic = deterministic,
            hints = hints,
            stages = stages + StageReport(
                silicon = Silicon.GPU,
                task = StageReport.WORKHORSE,
                status = StageStatus.FAILED,
                spanCount = 0,
                elapsedMs = 0,
                note = reason,
            ),
            degraded = true,
            degradedReason = reason,
            spans = spans,
            refereeSkipReason = refereeSkip,
            medianSpanHeight = medianHeight,
        )
    }

    fun release() = cascade.release()

    private companion object {
        const val TAG = "BlackoutAnalyzer"
    }
}
