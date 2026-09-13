package com.blackout.app.intelligence

import android.content.Context
import android.util.Log
import com.blackout.app.ocr.TextSpan

/**
 * The NPU's slot in the hybrid pipeline: a small span classifier between the deterministic CPU
 * rules and the GPU cascade.
 *
 * **Today it always reports [StageStatus.SKIPPED], and that is the honest answer.** The slot is
 * wired, gated and instrumented so a Qualcomm AI Hub / QNN artifact drop-in is a model file plus
 * two `.so`s rather than a refactor - but nothing in this class can make the HUD say NPU.
 *
 * ## What is missing, measured 2026-09-12 on the iQOO 15 (`ro.soc.model=SM8850`)
 *
 * The handset half is there: vendor `libQnnHtp.so` and `libQnnHtpV81Stub.so` are on disk. The
 * Google half is not:
 *
 *  - `litertlm-android:0.17.0` ships no `libLiteRtDispatch_Qualcomm.so`.
 *  - LiteRT 2.1.6's dispatch library loads but logs `Unsupported dispatch runtime version` and
 *    then runs XNNPACK, while `initialize()` and the warm-up generation both *succeed*. That is
 *    the trap this gate exists to refuse: on 2026-09-12 it produced a HUD reading
 *    `on-device · local models · NPU` while every token came off the CPU.
 *  - No SM8850 `.litertlm` pack is published for either model in use.
 *
 * See `ARCH.md` for the URLs, dates and the ask list.
 *
 * ## Gate
 *
 * [NpuGate.evaluate] must return [NpuGate.Verdict.enabled] `true` before anything is attempted,
 * and it is false whenever *any* piece is missing - including when a previous launch died inside
 * NPU initialisation ([NpuSupport.npuInitCrashed]), because a mismatched dispatch runtime aborts
 * the process rather than throwing.
 */
class NpuClassifyStage(private val context: Context) {

    /**
     * @param spans the spans the CPU stage could not settle - what an NPU classifier would triage
     *   before the GPU cascade sees them.
     */
    fun classify(spans: List<TextSpan>): Outcome {
        val verdict = NpuGate.evaluate(context)
        if (!verdict.enabled) {
            Log.i(TAG, "NPU classifier skipped: ${verdict.reason}")
            return Outcome(
                decisions = emptyMap(),
                report = StageReport(
                    silicon = Silicon.NPU,
                    task = StageReport.CLASSIFIER,
                    status = StageStatus.SKIPPED,
                    spanCount = 0,
                    elapsedMs = 0,
                    note = verdict.reason,
                ),
            )
        }

        // Unreachable until a classifier model exists. Left as FAILED-not-OK on purpose: if the
        // gate ever opens without a model, the HUD must still not claim an NPU inference.
        Log.w(TAG, "NPU gate open but no classifier model is bundled; not claiming NPU")
        return Outcome(
            decisions = emptyMap(),
            report = StageReport(
                silicon = Silicon.NPU,
                task = StageReport.CLASSIFIER,
                status = StageStatus.SKIPPED,
                spanCount = spans.size,
                elapsedMs = 0,
                note = "gate open, no classifier model bundled",
            ),
        )
    }

    data class Outcome(
        val decisions: Map<Int, SpanDecision>,
        val report: StageReport,
    )

    private companion object {
        const val TAG = "BlackoutLlm"
    }
}

/**
 * Everything that has to be true before this process may touch the Hexagon NPU.
 *
 * Pure-ish and read-only: it looks at files, never loads them. Loading is the dangerous part -
 * `Backend.NPU` with the wrong dispatch library `abort()`s the process from native code.
 */
object NpuGate {

    data class Verdict(
        val enabled: Boolean,
        val reason: String,
        val socModel: String?,
        val hexagon: String?,
    )

    fun evaluate(context: Context): Verdict {
        val soc = NpuSupport.socModel()
        val hexagon = NpuSupport.hexagonGeneration()

        fun no(reason: String) = Verdict(false, reason, soc, hexagon)

        if (NpuSupport.npuInitCrashed(context)) {
            return no("previous launch died inside NPU init with this dispatch library")
        }
        if (!NpuSupport.dispatchPresent(context)) {
            return no("no ${NpuSupport.DISPATCH_LIB} in the APK's lib dir")
        }
        if (!NpuSupport.vendorHtpPresent()) {
            return no("no vendor libQnnHtp.so on this device")
        }
        if (hexagon == null) {
            return no("no vendor libQnnHtpV*Stub.so; cannot tell which Hexagon generation this is")
        }
        if (!NpuSupport.jitDepsPresent(context)) {
            return no("QAIRT deps incomplete (need ${NpuSupport.COMPILER_PLUGIN_LIB}, libQnnIr.so, libQnnSaver.so, ${NpuSupport.PREPARE_LIB})")
        }
        return Verdict(true, "dispatch + vendor HTP $hexagon on ${soc ?: "unknown soc"}", soc, hexagon)
    }

    /**
     * One line for the debug panel / ARCH evidence.
     *
     * Leads with the path that actually executes. Everything [evaluate] inspects belongs to the
     * **retired** LiteRT dispatch route, so on its own this line read "npu closed ... no
     * libLiteRtDispatch_Qualcomm.so" directly underneath a workhorse line saying `NPU · 62ms`.
     * Both were true and together they were nonsense. The Genie/QAIRT bundle is what runs; the
     * LiteRT gate is reported second and named as retired, because it is still the thing that
     * decides whether [NpuClassifyStage] is skipped.
     */
    fun describe(context: Context): String {
        val genieReady = com.blackout.app.intelligence.npu.GenieNpuRuntime.locate(context) != null
        val verdict = evaluate(context)
        val soc = verdict.socModel ?: "?"
        val htp = verdict.hexagon ?: "none"
        val genie = if (genieReady) "genie/qairt ready (workhorse runs here)" else "genie bundle missing"
        val litert = if (verdict.enabled) "litert classify open" else "litert classify retired"
        return "$genie · soc=$soc · htp=$htp · $litert"
    }
}
