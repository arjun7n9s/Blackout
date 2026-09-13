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
     * Load the workhorse now, so the first page does not pay for it.
     *
     * Measured on this handset: `Qwen3-0.6B-w4a16 (Genie) ready on Hexagon in 1574ms`. That load
     * used to land inside the first analysis, which is why a cold page measured ~2.7 s wall and a
     * warm one 749 ms for identical work. The weights are not needed until the user has framed and
     * taken a photo, so there is no reason for the wait to be theirs.
     *
     * Safe to call repeatedly and from anywhere; [run] shares the same cached instance under the
     * same lock, so a capture arriving mid-preload waits for it rather than starting a second one.
     */
    @Synchronized
    fun preload() {
        if (workhorseRuntime != null) return
        val file = catalog.locate(ModelCatalog.QWEN) ?: return
        workhorseRuntime = runCatching { acquireWorkhorse(file) }
            .onFailure { Log.w(TAG, "preload failed, will retry on first page: ${it.message}") }
            .getOrNull()
    }

    @Synchronized
    private fun workhorse(file: java.io.File): LlmRuntime =
        workhorseRuntime ?: acquireWorkhorse(file).also { workhorseRuntime = it }

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

        val workhorse = try {
            if (workhorseRuntime == null) {
                onStage(AnalysisStage.LoadingModel(ModelCatalog.QWEN.displayName))
            }
            workhorse(workhorseFile)
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
            val referee = refereeRuntime ?: acquireReferee(refereeFile)
                .also { refereeRuntime = it }

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
            val npu = GenieNpuRuntime(context, bundle, "Qwen3-0.6B-w4a16 (Genie)")
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

    /**
     * Gemma first; the Hexagon referee only when Gemma's weights are absent.
     *
     * This ordering is the opposite of the workhorse's, and it is a measured decision rather than
     * an assumption. Putting the referee on the DSP was tried with Qwen3-4B w4a16 - a *larger*
     * model than Gemma-4-E2B, on faster silicon - and lost on both counts that matter:
     *
     * | | Gemma-4-E2B (GPU) | Qwen3-4B (NPU) |
     * |---|---|---|
     * | engine load | **4.6 s** | **307 s** |
     * | referee inference | 7.9 s | **2.9 s** |
     * | spans hidden on the Aadhaar card | **20** | 16 |
     *
     * Inference really is 2.7x faster. But the bundle is 3.2 GB of context binaries mmap'd from
     * FUSE-backed external storage, and a five-minute first-load stall is not shippable at any
     * quality. It was also *worse*: the holder's name came back visible, and the document-type
     * summary degraded. The workhorse is a different story - its bundle is 753 MB, it loads in
     * ~3 s, and it is 46x faster per pass, which is why that one is NPU-first.
     *
     * The path stays wired because the measurement may change: moving the bundle to internal
     * storage (which is what Tokito does) should remove most of the load cost, and a referee
     * prompt built for a hide-list rather than adapted to one may close the quality gap.
     */
    private fun acquireReferee(refereeFile: java.io.File): LlmRuntime {
        if (refereeFile.isFile) {
            try {
                return runtimeFactory(ModelCatalog.GEMMA, refereeFile).also { it.load() }
            } catch (t: Throwable) {
                Log.w(TAG, "Gemma referee unavailable, trying Hexagon: ${t.message}")
            }
        }
        val bundle = GenieNpuRuntime.locate(context, GenieNpuRuntime.REFEREE_DIR)
            ?: throw Unavailable("no referee weights")
        return GenieNpuRuntime(context, bundle, "Qwen3-4B-w4a16 (Genie)").also {
            it.load()
            Log.i(TAG, "referee on Hexagon: ${it.displayName} (${it.backendLabel})")
        }
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

            fun parse(text: String) = if (json) {
                DecisionParser.parse(text, batch.localIds, DecisionSource.WORKHORSE)
            } else {
                HideListParser.parse(text, batch.localIds, DecisionSource.WORKHORSE)
            }

            var local = parse(reply)

            // A batch that hid almost everything told us about its own decoding, not about the
            // document. Ask once more with the question inverted - the same prompt would decode
            // to the same answer - and only then give up on it.
            //
            // The collapse guard is loosened for the hide-list wire format ([HideListParser]):
            // a model that answers with letter ids has already done the work of *picking* which
            // letters to list, so an all-hide verdict means "I read the page and everything is
            // sensitive" rather than "I am repeating one action". The GPU JSON path keeps the
            // tight guard because constrained decoding on a 0.6B has been observed to latch onto
            // a single token; the NPU hide-list path has not.
            val collapseRatio = if (json) MergePolicy.COLLAPSE_RATIO else MergePolicy.COLLAPSE_RATIO_HIDE_LIST
            if (MergePolicy.isModeCollapsed(local.values.map { it.action }, collapseRatio = collapseRatio)) {
                Log.w(TAG, "batch ${index + 1} mode-collapsed; re-asking")
                val second = runCatching {
                    runtime.generate(
                        system = if (json) Prompts.WORKHORSE_SYSTEM else Prompts.WORKHORSE_SYSTEM_RETRY,
                        prompt = batch.prompt,
                        schema = if (json) Prompts.decisionSchema(batchSpans.size) else null,
                        maxOutputTokens = if (json) ModelCatalog.QWEN.maxOutputTokens else 48,
                    )
                }.getOrNull()

                val retried = second?.let { parse(it) }.orEmpty()
                if (retried.isNotEmpty() &&
                    !MergePolicy.isModeCollapsed(retried.values.map { it.action }, collapseRatio = collapseRatio)
                ) {
                    Log.i(TAG, "batch ${index + 1} recovered on retry")
                    local = retried
                } else {
                    // Twice degenerate. Applying it would black out the page for no reason, so
                    // the model simply does not get a vote on these spans - the deterministic
                    // detectors and the layout pass already cover them at full precision, and
                    // anything they did not claim stays readable.
                    Log.w(TAG, "batch ${index + 1} collapsed twice; discarding ${batchSpans.size} spans")
                    batchSpans.forEach { lowConfidence += it.id }
                    return@forEachIndexed
                }
            }

            for ((localId, decision) in local) {
                val globalId = batch.localToGlobal[localId] ?: continue
                out[globalId] = decision.copy(id = globalId)
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
            // Same protocol split as the workhorse: the NPU referee gets a hide-list, the GPU
            // referee gets constrained JSON with reasons.
            val json = runtime.supportsJsonSchema
            val batch = Prompts.refereeBatch(batchSpans, neighbours, hints, docSummary, tagged = !json)
            val reply = runCatching {
                runtime.generate(
                    system = if (json) Prompts.REFEREE_SYSTEM else Prompts.REFEREE_SYSTEM_HIDELIST,
                    prompt = batch.prompt,
                    schema = if (json) Prompts.refereeSchema(batchSpans.size) else null,
                    maxOutputTokens = if (json) ModelCatalog.GEMMA.maxOutputTokens else 64,
                )
            }.getOrElse {
                Log.w(TAG, "referee batch ${index + 1} failed: ${it.message}")
                return@forEachIndexed
            }

            val local = if (json) {
                DecisionParser.parse(reply, batch.localIds, DecisionSource.REFEREE)
            } else {
                HideListParser.parse(reply, batch.localIds, DecisionSource.REFEREE)
            }
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
