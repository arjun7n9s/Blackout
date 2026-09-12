package com.blackout.app.intelligence

import android.content.Context
import android.util.Log
import com.blackout.app.ocr.TextSpan
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coarse progress for the UI while the cascade runs. */
sealed interface AnalysisStage {
    data object Hints : AnalysisStage
    data class LoadingModel(val name: String) : AnalysisStage
    data class Workhorse(val batch: Int, val batches: Int) : AnalysisStage
    data object Summarising : AnalysisStage
    data class Referee(val batch: Int, val batches: Int) : AnalysisStage
    data object Done : AnalysisStage
}

/**
 * Runs the cascade: regex hints -> Qwen3 over every span -> Gemma over the contested ones.
 *
 * Everything is on-device. There is no network call anywhere on this path, by construction -
 * the app holds no INTERNET permission at all (see AndroidManifest), so a cloud call is not
 * merely absent but impossible.
 *
 * Failure is always graceful. Missing weights, a backend that won't load, or a model that
 * returns nothing all degrade to the regex-only path, flagged [AnalysisResult.degraded] so the
 * UI can label it rather than quietly pretending the models ran.
 */
class RedactionAnalyzer(
    private val context: Context,
    private val catalog: ModelCatalog = ModelCatalog(context),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val runtimeFactory: (ModelSpec, java.io.File) -> LlmRuntime = { spec, file ->
        LiteRtLlmRuntime(context, spec, file)
    },
) {

    private var workhorseRuntime: LlmRuntime? = null
    private var refereeRuntime: LlmRuntime? = null

    suspend fun analyze(
        spans: List<TextSpan>,
        onStage: (AnalysisStage) -> Unit = {},
    ): AnalysisResult = withContext(dispatcher) {
        if (spans.isEmpty()) return@withContext AnalysisResult.Empty

        onStage(AnalysisStage.Hints)
        val hints = buildHints(spans)

        val workhorseFile = catalog.locate(ModelCatalog.QWEN)
            ?: return@withContext degraded(
                hints,
                "workhorse weights not found in ${catalog.modelsDir().name}/",
            )

        val workhorse = try {
            onStage(AnalysisStage.LoadingModel(ModelCatalog.QWEN.displayName))
            (workhorseRuntime ?: runtimeFactory(ModelCatalog.QWEN, workhorseFile).also {
                it.load(); workhorseRuntime = it
            })
        } catch (t: Throwable) {
            Log.e(TAG, "workhorse load failed", t)
            return@withContext degraded(hints, "could not load ${ModelCatalog.QWEN.displayName}")
        }

        val stats = mutableListOf<InferenceStat>()
        val lowConfidence = mutableSetOf<Int>()
        val workhorseDecisions = runWorkhorse(workhorse, spans, stats, lowConfidence, onStage)

        if (workhorseDecisions.isEmpty()) {
            return@withContext degraded(hints, "${ModelCatalog.QWEN.displayName} returned nothing")
        }

        // Referee pass, only over contested spans.
        val queueIds = MergePolicy.refereeQueue(spans, workhorseDecisions, hints, lowConfidence)
        var refereeDecisions = emptyMap<Int, SpanDecision>()
        var docSummary: String? = null

        val refereeFile = catalog.locate(ModelCatalog.GEMMA)
        if (queueIds.isNotEmpty() && refereeFile != null) {
            try {
                onStage(AnalysisStage.LoadingModel(ModelCatalog.GEMMA.displayName))
                val referee = refereeRuntime ?: runtimeFactory(ModelCatalog.GEMMA, refereeFile)
                    .also { it.load(); refereeRuntime = it }

                onStage(AnalysisStage.Summarising)
                docSummary = runSummary(referee, spans, stats)

                val queue = spans.filter { it.id in queueIds.toSet() }
                refereeDecisions = runReferee(referee, queue, spans, hints, docSummary, stats, onStage)
            } catch (t: Throwable) {
                // A referee failure is not fatal - workhorse decisions still stand.
                Log.w(TAG, "referee pass failed: ${t.message}")
            }
        }

        onStage(AnalysisStage.Done)
        AnalysisResult(
            workhorse = workhorseDecisions,
            referee = refereeDecisions,
            hints = hints,
            stats = stats,
            docSummary = docSummary,
            degraded = false,
        )
    }

    private fun buildHints(spans: List<TextSpan>): Map<Int, List<CandidateHint>> =
        spans.withIndex().associate { (index, span) ->
            val raw = CandidateHints.detect(span.text)
            val previous = spans.getOrNull(index - 1)?.text
            span.id to CandidateHints.promoteByNeighbour(raw, previous)
        }.filterValues { it.isNotEmpty() }

    private fun runWorkhorse(
        runtime: LlmRuntime,
        spans: List<TextSpan>,
        stats: MutableList<InferenceStat>,
        lowConfidence: MutableSet<Int>,
        onStage: (AnalysisStage) -> Unit,
    ): Map<Int, SpanDecision> {
        val batches = spans.chunked(Prompts.WORKHORSE_BATCH)
        val out = LinkedHashMap<Int, SpanDecision>()
        val started = System.currentTimeMillis()

        batches.forEachIndexed { index, batchSpans ->
            onStage(AnalysisStage.Workhorse(index + 1, batches.size))
            val firstIdx = spans.indexOfFirst { it.id == batchSpans.first().id }
            val lastIdx = spans.indexOfFirst { it.id == batchSpans.last().id }
            val batch = Prompts.workhorseBatch(
                spans = batchSpans,
                above = spans.getOrNull(firstIdx - 1)?.text,
                below = spans.getOrNull(lastIdx + 1)?.text,
            )
            val reply = runCatching {
                runtime.generate(
                    system = Prompts.WORKHORSE_SYSTEM,
                    prompt = batch.prompt,
                    schema = Prompts.decisionSchema(batchSpans.size),
                    maxOutputTokens = ModelCatalog.QWEN.maxOutputTokens,
                )
            }.getOrElse {
                Log.w(TAG, "workhorse batch ${index + 1} failed: ${it.message}")
                return@forEachIndexed
            }

            val local = DecisionParser.parse(reply, batch.localIds, DecisionSource.WORKHORSE)
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
            spanCount = spans.size,
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

    private fun degraded(
        hints: Map<Int, List<CandidateHint>>,
        reason: String,
    ): AnalysisResult {
        Log.w(TAG, "degraded: $reason")
        return AnalysisResult(hints = hints, degraded = true, degradedReason = reason)
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
    }
}
