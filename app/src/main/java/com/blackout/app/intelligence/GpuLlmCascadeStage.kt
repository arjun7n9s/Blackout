package com.blackout.app.intelligence

import android.content.Context
import android.util.Log
import com.blackout.app.intelligence.npu.GenieNpuRuntime
import com.blackout.app.ocr.TextSpan
import java.io.File

/**
 * The GPU's share of the hybrid pipeline: the Qwen3-0.6B → Gemma-4-E2B cascade, run **only over
 * the spans the CPU stage could not settle**.
 *
 * Nothing here decides what a span means on its own any more. It receives leftovers - prose, table
 * cells, unpaired values - and it is bounded twice:
 *
 *  - [RefereeBudget] can switch the referee off entirely for a page shape where Phone C measured
 *    it doing harm (`C-008`, `C-015`).
 *  - [MergePolicy.REFEREE_QUEUE_CAP] bounds the queue when it does run.
 *
 * "GPU" is the *intent*. What actually ran is [LlmRuntime.backendLabel], set only after a warm-up
 * generation returned, and that is what lands in the [StageReport] - so a device without OpenCL
 * reports `CPU·qwen`, which is exactly what Phone C's loaner should show.
 */
class GpuLlmCascadeStage(
    private val context: Context,
    private val catalog: ModelCatalog,
    private val runtimeFactory: (ModelSpec, File) -> LlmRuntime = { spec, file ->
        LiteRtLlmRuntime(context, spec, file)
    },
) {

    /** No weights, or no usable backend. The pipeline turns this into the degraded path. */
    class Unavailable(message: String) : Exception(message)

    data class Outcome(
        val workhorse: Map<Int, SpanDecision>,
        val referee: Map<Int, SpanDecision>,
        val docSummary: String?,
        val stats: List<InferenceStat>,
        val reports: List<StageReport>,
    )

    private var workhorseRuntime: LlmRuntime? = null
    private var refereeRuntime: LlmRuntime? = null

    /**
     * @param queue spans still undecided after the CPU (and, one day, NPU) stages.
     * @param allSpans every span on the page - used for neighbour context only, never re-judged.
     * @param refereeSkipReason non-null when [RefereeBudget] vetoed the referee for this page.
     */
    fun run(
        queue: List<TextSpan>,
        allSpans: List<TextSpan>,
        hints: Map<Int, List<CandidateHint>>,
        refereeSkipReason: String?,
        onStage: (AnalysisStage) -> Unit,
    ): Outcome {
        val workhorseFile = catalog.locate(ModelCatalog.QWEN)
            ?: throw Unavailable("workhorse weights not found in ${catalog.modelsDir().name}/")

        val workhorse = workhorseRuntime ?: try {
            onStage(AnalysisStage.LoadingModel(ModelCatalog.QWEN.displayName))
            acquireWorkhorse(workhorseFile).also { workhorseRuntime = it }
        } catch (t: Throwable) {
            Log.e(TAG, "workhorse load failed", t)
            throw Unavailable("could not load ${ModelCatalog.QWEN.displayName}")
        }

        val stats = mutableListOf<InferenceStat>()
        val reports = mutableListOf<StageReport>()
        val lowConfidence = mutableSetOf<Int>()
        val workhorseDecisions = runWorkhorse(workhorse, queue, allSpans, stats, lowConfidence, onStage)

        if (workhorseDecisions.isEmpty()) {
            throw Unavailable("${ModelCatalog.QWEN.displayName} returned nothing")
        }
        reports += report(StageReport.WORKHORSE, workhorse, stats, queue.size)

        val queueIds = if (refereeSkipReason != null) {
            emptyList()
        } else {
            MergePolicy.refereeQueue(queue, workhorseDecisions, hints, lowConfidence)
        }

        var refereeDecisions = emptyMap<Int, SpanDecision>()
        var docSummary: String? = null
        val refereeFile = catalog.locate(ModelCatalog.GEMMA)

        if (queueIds.isEmpty() || refereeFile == null) {
            val note = refereeSkipReason
                ?: if (refereeFile == null) "referee weights not present" else "nothing contested"
            Log.i(TAG, "referee not run: $note")
            reports += StageReport(
                silicon = Silicon.GPU,
                task = StageReport.REFEREE,
                status = StageStatus.SKIPPED,
                spanCount = 0,
                elapsedMs = 0,
                note = note,
            )
            return Outcome(workhorseDecisions, emptyMap(), null, stats, reports)
        }

        try {
            onStage(AnalysisStage.LoadingModel(ModelCatalog.GEMMA.displayName))
            val referee = refereeRuntime ?: runtimeFactory(ModelCatalog.GEMMA, refereeFile)
                .also { it.load(); refereeRuntime = it }

            onStage(AnalysisStage.Summarising)
            docSummary = runSummary(referee, allSpans, stats)

            val ids = queueIds.toSet()
            val contested = queue.filter { it.id in ids }
            refereeDecisions = runReferee(referee, contested, allSpans, hints, docSummary, stats, onStage)
            reports += report(StageReport.REFEREE, referee, stats, contested.size)
        } catch (t: Throwable) {
            // A referee failure is not fatal - workhorse decisions still stand.
            Log.w(TAG, "referee pass failed: ${t.message}")
            reports += StageReport(
                silicon = Silicon.GPU,
                task = StageReport.REFEREE,
                status = StageStatus.FAILED,
                spanCount = 0,
                elapsedMs = 0,
                note = t.message,
            )
        }

        return Outcome(workhorseDecisions, refereeDecisions, docSummary, stats, reports)
    }

    /**
     * Hexagon first, LiteRT second.
     *
     * The NPU runs the same Qwen3-0.6B at ~130 tok/s decode against LiteRT's ~7.6 s for a
     * 19-span pass, so it is the preferred workhorse whenever the side-loaded Genie bundle is
     * present. Both paths prove themselves with a warm-up generation before reporting a backend,
     * so a missing or broken NPU degrades silently to the GPU/CPU cascade rather than lying.
     */
    private fun acquireWorkhorse(workhorseFile: java.io.File): LlmRuntime {
        val bundle = GenieNpuRuntime.locate(context)
        if (bundle != null) {
            val npu = GenieNpuRuntime(context, bundle)
            try {
                npu.load()
                Log.i(TAG, "workhorse on Hexagon: ${npu.displayName} (${npu.backendLabel})")
                return npu
            } catch (t: Throwable) {
                Log.w(TAG, "NPU workhorse unavailable, falling back to LiteRT: ${t.message}")
                runCatching { npu.close() }
            }
        }
        return runtimeFactory(ModelCatalog.QWEN, workhorseFile).also { it.load() }
    }

    private fun report(
        task: String,
        runtime: LlmRuntime,
        stats: List<InferenceStat>,
        spanCount: Int,
    ): StageReport {
        val label = if (task == StageReport.WORKHORSE) "workhorse" else "referee"
        val elapsed = stats.filter { it.label == label || (task == StageReport.REFEREE && it.label == "doc-summary") }
            .sumOf { it.elapsedMs }
        return StageReport(
            silicon = siliconOf(runtime.backendLabel),
            task = task,
            status = StageStatus.OK,
            spanCount = spanCount,
            elapsedMs = elapsed,
            note = runtime.backendLabel,
        )
    }

    private fun runWorkhorse(
        runtime: LlmRuntime,
        queue: List<TextSpan>,
        allSpans: List<TextSpan>,
        stats: MutableList<InferenceStat>,
        lowConfidence: MutableSet<Int>,
        onStage: (AnalysisStage) -> Unit,
    ): Map<Int, SpanDecision> {
        val batches = queue.chunked(Prompts.WORKHORSE_BATCH)
        val out = LinkedHashMap<Int, SpanDecision>()
        val started = System.currentTimeMillis()

        batches.forEachIndexed { index, batchSpans ->
            onStage(AnalysisStage.Workhorse(index + 1, batches.size))
            val firstIdx = allSpans.indexOfFirst { it.id == batchSpans.first().id }
            val lastIdx = allSpans.indexOfFirst { it.id == batchSpans.last().id }
            // The NPU runtime cannot pin a reply shape, so it gets the bare hide-list protocol
            // and its own parser. Everything downstream is identical.
            val json = runtime.supportsJsonSchema
            val above = allSpans.getOrNull(firstIdx - 1)?.text
            val below = allSpans.getOrNull(lastIdx + 1)?.text
            val batch = if (json) {
                Prompts.workhorseBatch(batchSpans, above, below)
            } else {
                Prompts.workhorseHideListBatch(batchSpans, above, below)
            }
            val reply = runCatching {
                runtime.generate(
                    system = if (json) Prompts.WORKHORSE_SYSTEM else Prompts.WORKHORSE_SYSTEM_HIDELIST,
                    prompt = batch.prompt,
                    schema = if (json) Prompts.decisionSchema(batchSpans.size) else null,
                    maxOutputTokens = if (json) ModelCatalog.QWEN.maxOutputTokens else 48,
                )
            }.getOrElse {
                Log.w(TAG, "workhorse batch ${index + 1} failed: ${it.message}")
                return@forEachIndexed
            }

            val local = if (json) {
                DecisionParser.parse(reply, batch.localIds, DecisionSource.WORKHORSE)
            } else {
                HideListParser.parse(reply, batch.localIds, DecisionSource.WORKHORSE)
            }
            for ((localId, decision) in local) {
                val globalId = batch.localToGlobal[localId] ?: continue
                out[globalId] = decision.copy(id = globalId)
            }

            // A batch that answered identically for every line told us about its own decoding,
            // not about the document. Send it to the referee instead of trusting it.
            if (MergePolicy.isModeCollapsed(local.values.map { it.action })) {
                Log.w(TAG, "batch ${index + 1} mode-collapsed; escalating ${batchSpans.size} spans")
                batchSpans.forEach { lowConfidence += it.id }
            }
        }

        stats += InferenceStat(
            label = "workhorse",
            model = runtime.displayName,
            backend = runtime.backendLabel,
            spanCount = queue.size,
            batchCount = batches.size,
            elapsedMs = System.currentTimeMillis() - started,
        )
        return out
    }

    private fun runSummary(
        runtime: LlmRuntime,
        spans: List<TextSpan>,
        stats: MutableList<InferenceStat>,
    ): String? {
        val started = System.currentTimeMillis()
        val text = runCatching {
            runtime.generate(
                system = Prompts.SUMMARY_SYSTEM_INSTRUCTION,
                prompt = Prompts.summaryPrompt(spans),
                schema = null,
                maxOutputTokens = 32,
            )
        }.getOrNull()?.trim()?.lines()?.firstOrNull()?.take(60)

        stats += InferenceStat(
            label = "doc-summary",
            model = runtime.displayName,
            backend = runtime.backendLabel,
            spanCount = 0,
            batchCount = 1,
            elapsedMs = System.currentTimeMillis() - started,
        )
        return text?.ifBlank { null }
    }

    private fun runReferee(
        runtime: LlmRuntime,
        queue: List<TextSpan>,
        allSpans: List<TextSpan>,
        hints: Map<Int, List<CandidateHint>>,
        docSummary: String?,
        stats: MutableList<InferenceStat>,
        onStage: (AnalysisStage) -> Unit,
    ): Map<Int, SpanDecision> {
        val batches = queue.chunked(Prompts.REFEREE_BATCH)
        val out = LinkedHashMap<Int, SpanDecision>()
        val started = System.currentTimeMillis()

        batches.forEachIndexed { index, batchSpans ->
            onStage(AnalysisStage.Referee(index + 1, batches.size))
            val neighbours = batchSpans.associate {
                it.id to Prompts.neighbourContext(allSpans, it)
            }
            val batch = Prompts.refereeBatch(batchSpans, neighbours, hints, docSummary)
            val reply = runCatching {
                runtime.generate(
                    system = Prompts.REFEREE_SYSTEM,
                    prompt = batch.prompt,
                    schema = Prompts.refereeSchema(batchSpans.size),
                    maxOutputTokens = ModelCatalog.GEMMA.maxOutputTokens,
                )
            }.getOrElse {
                Log.w(TAG, "referee batch ${index + 1} failed: ${it.message}")
                return@forEachIndexed
            }

            val local = DecisionParser.parse(reply, batch.localIds, DecisionSource.REFEREE)
            for ((localId, decision) in local) {
                val globalId = batch.localToGlobal[localId] ?: continue
                out[globalId] = decision.copy(id = globalId)
            }
        }

        stats += InferenceStat(
            label = "referee",
            model = runtime.displayName,
            backend = runtime.backendLabel,
            spanCount = queue.size,
            batchCount = batches.size,
            elapsedMs = System.currentTimeMillis() - started,
        )
        return out
    }

    /** Frees both engines. Gemma alone holds ~2.5 GB, so this matters. */
    fun release() {
        runCatching { workhorseRuntime?.close() }
        runCatching { refereeRuntime?.close() }
        workhorseRuntime = null
        refereeRuntime = null
    }

    private companion object {
        const val TAG = "BlackoutAnalyzer"

        fun siliconOf(backendLabel: String): Silicon = when (backendLabel.uppercase()) {
            "NPU" -> Silicon.NPU
            "GPU" -> Silicon.GPU
            else -> Silicon.CPU
        }
    }
}
