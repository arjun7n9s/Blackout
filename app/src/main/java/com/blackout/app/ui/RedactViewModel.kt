package com.blackout.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blackout.app.intelligence.Action
import com.blackout.app.intelligence.AnalysisResult
import com.blackout.app.intelligence.AnalysisStage
import com.blackout.app.intelligence.HybridRedactPipeline
import com.blackout.app.intelligence.MergePolicy
import com.blackout.app.intelligence.ModelCatalog
import com.blackout.app.intelligence.NpuGate
import com.blackout.app.intelligence.SpanDecision
import com.blackout.app.ocr.Deskew
import com.blackout.app.ocr.MlKitOcrEngine
import com.blackout.app.ocr.OcrResult
import com.blackout.app.ocr.SkewMetrics
import com.blackout.app.ocr.TextSpan
import com.blackout.app.redact.RedactionEngine
import com.blackout.app.share.ShareGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { IDLE, WORKING, READY, FAILED }

/**
 * What tapping the image does.
 *
 * Both modes read the same spans and the same decisions; only the gesture and the painting
 * differ. Nothing about [BLACKOUT]'s analysis is recomputed when the user switches - [COPY] is a
 * different view of a result already in memory.
 */
enum class RedactMode {
    /** The default. Bars are painted, and a tap toggles one span's verdict. */
    BLACKOUT,

    /**
     * Nothing is painted and a tap copies that line's text.
     *
     * Deliberately shows the page *unredacted*: this mode exists to get a value out of a
     * document, which is the opposite job. Nothing here can reach
     * [com.blackout.app.share.ShareRedacted] - copying to the clipboard is the only output, and
     * the user picks each line by hand.
     */
    COPY,
}

/**
 * Who the document is being shared with.
 *
 * Whether a disclosure is appropriate depends on the recipient, not on the data alone - an
 * address is ordinary to a courier and sensitive to a stranger. Selecting one records that
 * intent; it does not yet change any verdict.
 */
enum class Persona(val label: String, val blurb: String) {
    PUBLIC("Public", "Anyone could see this"),
    BANK("Bank / KYC", "Needs identity, not contacts"),
    EMPLOYER("Employer / HR", "Needs name and role"),
    MEDICAL("Doctor / Insurer", "Needs the clinical detail"),
    GOVERNMENT("Government", "Needs the identifiers"),
}

data class RedactUiState(
    val phase: Phase = Phase.IDLE,
    val statusLine: String = "",
    val spans: List<TextSpan> = emptyList(),
    val analysis: AnalysisResult = AnalysisResult.Empty,
    val overrides: Map<Int, Action> = emptyMap(),
    val decisions: Map<Int, SpanDecision> = emptyMap(),
    val hiddenIds: Set<Int> = emptySet(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val ocrMs: Long = 0,
    val error: String? = null,
    val showDebug: Boolean = false,
    /** Degrees of deskew applied to the working image, 0 when the page was already upright. */
    val deskewDeg: Float = 0f,
    val mode: RedactMode = RedactMode.BLACKOUT,
    val persona: Persona = Persona.PUBLIC,
    /** Set briefly after a copy, so the UI can confirm what went to the clipboard. */
    val lastCopied: String? = null,
) {
    val degraded: Boolean get() = analysis.degraded
    val hideCount: Int get() = decisions.values.count { it.action == Action.HIDE }
    val keepCount: Int get() = decisions.values.count { it.action == Action.KEEP }
    val unsureCount: Int get() = decisions.values.count { it.action == Action.UNSURE }

    /**
     * Non-null when the page looks unread rather than clean - the C-005 motion-blur case, or a
     * tilted capture where bars exist but OCR quietly missed things. The UI makes the user confirm
     * before either leaves the app.
     */
    val shareWarning: ShareGuard.Warning?
        get() = if (phase != Phase.READY) null else ShareGuard.warning(
            spanCount = spans.size,
            hideCount = hideCount,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            medianSkewDeg = SkewMetrics.medianAbsAngle(spans),
        )
}

/**
 * Owns the analysis pipeline and the redaction state for one captured image.
 *
 * The original bitmap lives here and **never leaves**: it backs the uncensor gesture and nothing
 * else. Export always goes through [renderRedacted], which burns bars into a fresh copy.
 *
 * Tapping a span re-runs only [MergePolicy.merge] - pure, in-memory, microseconds - rather than
 * re-running inference. That is the whole reason [AnalysisResult] keeps the raw per-stage maps.
 */
class RedactViewModel(app: Application) : AndroidViewModel(app) {

    private val ocr = MlKitOcrEngine()
    private val pipeline = HybridRedactPipeline(app.applicationContext)
    private val catalog = ModelCatalog(app.applicationContext)
    private val appContext = app.applicationContext

    private val _state = MutableStateFlow(RedactUiState())
    val state: StateFlow<RedactUiState> = _state.asStateFlow()

    /** The capture. Kept for uncensor; never shared. */
    var original: Bitmap? = null
        private set

    init {
        // The user is still framing a photo; spend that time loading the engine instead of
        // making them wait for it after the shutter.
        viewModelScope.launch { runCatching { pipeline.preload() } }
    }

    fun modelInventory(): String = catalog.describe()

    /** Why the NPU stage is skipped on this device. Debug panel only. */
    fun npuGate(): String = NpuGate.describe(appContext)

    fun start(bitmap: Bitmap) {
        original = bitmap
        _state.value = RedactUiState(
            phase = Phase.WORKING,
            statusLine = "Reading text…",
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
        )

        viewModelScope.launch {
            // Wall clock from "user handed us a bitmap" to "bars are on screen". The stage
            // timings the pipeline reports exclude OCR and deskew, so they flatter the number
            // the user actually waits through; this is the one measured against the budget.
            val wallStart = System.currentTimeMillis()
            val firstPass: OcrResult = runCatching { ocr.recognize(bitmap) }
                .getOrElse { OcrResult.empty(bitmap.width, bitmap.height) }

            // Straighten a tilted page before anything judges it. Tilt costs us detections, not
            // just tidy bars, so this runs ahead of the pipeline rather than as a cosmetic step.
            // Declines itself when the second read comes back worse.
            val straightened = Deskew.straighten(bitmap, firstPass, ocr)
            val result = straightened.ocr
            if (straightened.appliedDeg != 0f) {
                // The rotated bitmap is now the working image: span rects are in its coordinates,
                // so the overlay, the uncensor gesture and the export must all use it.
                original = straightened.bitmap
                _state.update {
                    it.copy(
                        imageWidth = straightened.bitmap.width,
                        imageHeight = straightened.bitmap.height,
                        deskewDeg = straightened.appliedDeg,
                    )
                }
            }

            if (result.spans.isEmpty()) {
                _state.update {
                    it.copy(
                        phase = Phase.READY,
                        statusLine = "No text found",
                        ocrMs = result.elapsedMs,
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(spans = result.spans, ocrMs = result.elapsedMs, statusLine = "Thinking…")
            }

            val analysis = runCatching {
                pipeline.analyze(
                    rawSpans = result.spans,
                    imageWidth = result.imageWidth,
                    imageHeight = result.imageHeight,
                ) { stage -> _state.update { it.copy(statusLine = label(stage)) } }
            }.getOrElse { t ->
                // Never swallow this. A throw here disables *every* deterministic detector at
                // once - the page comes back with 47 spans, all KEEP, and nothing on screen says
                // why. Measured on device: PAN, Aadhaar, card, email, phone and address all
                // visible, hide=0, while the app looked like it had simply found nothing to do.
                // A silent catch in front of the redaction path is worse than a crash.
                Log.e(STATS_TAG, "analysis threw - ALL REDACTION DISABLED", t)
                AnalysisResult(
                    degraded = true,
                    degradedReason = t.message ?: t::class.java.simpleName,
                    spans = result.spans,
                )
            }

            // Merge the layout-applied spans. Using the raw OCR list here is how FieldLayout
            // compiled and still left every caption STANDALONE - LAYOUT KEEP never fired.
            applyAnalysis(analysis.spans.ifEmpty { result.spans }, analysis, wallStart)
        }
    }

    private fun applyAnalysis(
        spans: List<TextSpan>,
        analysis: AnalysisResult,
        wallStart: Long = 0L,
    ) {
        val decisions = MergePolicy.merge(
            spans = spans,
            workhorse = analysis.workhorse,
            referee = analysis.referee,
            deterministic = analysis.deterministic,
            hints = analysis.hints,
            userOverrides = emptyMap(),
            degraded = analysis.degraded,
        )
        logStats(spans, analysis, decisions, wallStart)
        _state.update {
            it.copy(
                phase = Phase.READY,
                statusLine = "",
                spans = spans,
                analysis = analysis,
                decisions = decisions,
                hiddenIds = MergePolicy.hiddenIds(decisions),
            )
        }
    }

    /**
     * One machine-parseable line per analysed image.
     *
     * Exists so soak/regression runs can build timings.csv straight from `adb logcat` instead of
     * screenshotting the debug panel and reading numbers off pixels. Key=value, space separated,
     * no spaces inside values (doctype is underscored) so it survives a naive split.
     *
     *   adb logcat -d -s BlackoutStats
     */
    private fun logStats(
        spans: List<TextSpan>,
        analysis: AnalysisResult,
        decisions: Map<Int, SpanDecision>,
        wallStart: Long = 0L,
    ) {
        fun ms(label: String) = analysis.stats.firstOrNull { it.label == label }?.elapsedMs ?: 0L
        val backend = analysis.hudBackend ?: "none"
        val doctype = analysis.docSummary?.replace(' ', '_')?.take(40) ?: "-"
        val llmSpans = analysis.stats.firstOrNull { it.label == "workhorse" }?.spanCount ?: 0
        Log.i(
            STATS_TAG,
            "spans=${spans.size} ocr_ms=${_state.value.ocrMs} " +
                // The number the budget is judged on: everything the user waits through.
                "wall_ms=${if (wallStart > 0) System.currentTimeMillis() - wallStart else -1} " +
                "deskew=${"%.1f".format(_state.value.deskewDeg)} " +
                // Stage ownership, so a soak run can see the CPU/GPU split without the debug panel.
                "cpu_det=${analysis.deterministic.size} llm_spans=$llmSpans " +
                "median_h=${analysis.medianSpanHeight} " +
                "workhorse_ms=${ms("workhorse")} summary_ms=${ms("doc-summary")} " +
                "referee_ms=${ms("referee")} total_ms=${analysis.totalMs} " +
                "hide=${decisions.values.count { it.action == Action.HIDE }} " +
                "keep=${decisions.values.count { it.action == Action.KEEP }} " +
                "unsure=${decisions.values.count { it.action == Action.UNSURE }} " +
                "referee_queue=${analysis.referee.size} backend=$backend " +
                "referee_skip=${analysis.refereeSkipReason?.substringBefore(':') ?: "-"} " +
                "degraded=${analysis.degraded} doctype=$doctype",
        )
        Log.i(STATS_TAG, "hybrid: ${analysis.backendReport.hudLine()}")

        if (com.blackout.app.BuildConfig.DEBUG) {
            // One line per span, so a fixture with known ground truth can be scored for
            // precision/recall instead of eyeballing screenshots. `text` is last because it is
            // the only field that can contain spaces.
            for (span in spans) {
                val d = decisions[span.id] ?: continue
                Log.i(
                    SPANS_TAG,
                    "id=${span.id} action=${d.action} src=${d.source} " +
                        "x=${span.rect.left} y=${span.rect.top} " +
                        "ang=${"%.1f".format(span.angleDeg)} text=${span.text}",
                )
            }
        }
    }

    /** Flip one span. This is the uncensor / manual-hide gesture. */
    fun toggleSpan(spanId: Int) {
        val current = _state.value
        val currentlyHidden = spanId in current.hiddenIds
        val overrides = current.overrides.toMutableMap().apply {
            this[spanId] = if (currentlyHidden) Action.KEEP else Action.HIDE
        }
        val decisions = MergePolicy.merge(
            spans = current.spans,
            workhorse = current.analysis.workhorse,
            referee = current.analysis.referee,
            deterministic = current.analysis.deterministic,
            hints = current.analysis.hints,
            userOverrides = overrides,
            degraded = current.analysis.degraded,
        )
        _state.update {
            it.copy(
                overrides = overrides,
                decisions = decisions,
                hiddenIds = MergePolicy.hiddenIds(decisions),
            )
        }
    }

    fun toggleDebug() = _state.update { it.copy(showDebug = !it.showDebug) }

    fun setMode(mode: RedactMode) = _state.update { it.copy(mode = mode, lastCopied = null) }

    /**
     * Records who this is going to.
     *
     * Stored only. It does not re-run the pipeline and does not change a single verdict yet -
     * the prompt packs that would act on it are not built. Kept honest deliberately: the HUD
     * must never imply a protection that is not running.
     */
    fun setPersona(persona: Persona) = _state.update { it.copy(persona = persona) }

    /** Copies one span's text. Only reachable in [RedactMode.COPY]. */
    fun copySpan(spanId: Int): String? {
        val text = _state.value.spans.firstOrNull { it.id == spanId }?.text?.trim()
        if (text.isNullOrEmpty()) return null
        val clipboard = appContext.getSystemService(android.content.ClipboardManager::class.java)
            ?: return null
        // Flagged sensitive so the system does not surface a clipboard preview toast containing
        // the very value the user is handling, and so it is excluded from clipboard history.
        val clip = android.content.ClipData.newPlainText("Blackout", text).apply {
            description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
        _state.update { it.copy(lastCopied = text) }
        return text
    }

    /** Burns the current redaction into a fresh bitmap. The only thing allowed to be exported. */
    suspend fun renderRedacted(): Bitmap? = withContext(Dispatchers.Default) {
        val source = original ?: return@withContext null
        val current = _state.value
        RedactionEngine.render(source, current.spans, current.hiddenIds)
    }

    fun reset() {
        original = null
        _state.value = RedactUiState()
    }

    private companion object {
        const val STATS_TAG = "BlackoutStats"
        const val SPANS_TAG = "BlackoutSpans"
    }

    override fun onCleared() {
        ocr.close()
        pipeline.release()
        super.onCleared()
    }

    private fun label(stage: AnalysisStage): String = when (stage) {
        AnalysisStage.Hints -> "Scanning patterns…"
        AnalysisStage.Deterministic -> "Matching fields…"
        is AnalysisStage.LoadingModel -> "Loading ${stage.name}…"
        is AnalysisStage.Workhorse -> "Judging ${stage.batch}/${stage.batches}…"
        AnalysisStage.Summarising -> "Reading document type…"
        is AnalysisStage.Referee -> "Second opinion ${stage.batch}/${stage.batches}…"
        AnalysisStage.Done -> ""
    }
}
