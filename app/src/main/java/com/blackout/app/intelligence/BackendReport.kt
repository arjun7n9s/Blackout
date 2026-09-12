package com.blackout.app.intelligence

/**
 * Which piece of silicon did a stage's work. **Measured, never intended.**
 *
 * For the LLM stages this comes from [LlmRuntime.backendLabel], which is only set after a
 * one-token warm-up generation actually returned - so a LiteRT silent fallback to XNNPACK shows
 * up here as [CPU], not as the backend we asked for. See `NpuSupport` for the incident that made
 * this rule non-negotiable.
 */
enum class Silicon { CPU, NPU, GPU }

enum class StageStatus {
    /** The stage ran and produced decisions. */
    OK,

    /** The stage was deliberately not run (no artifacts, or a budget said don't). */
    SKIPPED,

    /** The stage tried and failed. Never silently upgraded to OK. */
    FAILED,
}

/**
 * One line of the hybrid pipeline's receipt: who ran, on what, over how many spans, for how long.
 */
data class StageReport(
    val silicon: Silicon,
    /** Short task name as it appears in the HUD: `det`, `cls`, `qwen`, `gemma`. */
    val task: String,
    val status: StageStatus,
    val spanCount: Int,
    val elapsedMs: Long,
    val note: String? = null,
) {
    companion object {
        const val DETERMINISTIC = "det"
        const val CLASSIFIER = "cls"
        const val WORKHORSE = "qwen"
        const val REFEREE = "gemma"
    }
}

/**
 * The honest per-image backend summary.
 *
 * Prints as `CPU·det 12 · qwen 21 | NPU·cls skip | GPU·gemma 8 | total 28.8s`: stages grouped by
 * the silicon that *actually* ran them, in CPU → NPU → GPU order.
 *
 * The only invariant that really matters: **`NPU·cls ok` can never appear unless a stage with
 * [Silicon.NPU] reported [StageStatus.OK]**, because the string is built from the reports rather
 * than from configuration or hope. `BackendReportTest` pins that.
 */
data class BackendReport(val stages: List<StageReport>, val totalMs: Long) {

    /** Full receipt, for the debug panel and the `BlackoutStats` log line. */
    fun hudLine(): String {
        val groups = Silicon.entries.mapNotNull { silicon ->
            val mine = stages.filter { it.silicon == silicon }
            if (mine.isEmpty()) return@mapNotNull null
            silicon.name + "·" + mine.joinToString(" · ") { describe(it) }
        }
        val seconds = "%.1fs".format(totalMs / 1000.0)
        return (groups + "total $seconds").joinToString(" | ")
    }

    /** Compact form for the on-screen chip, where there is room for counts but not for notes. */
    fun chip(): String {
        val groups = Silicon.entries.mapNotNull { silicon ->
            val mine = stages.filter { it.silicon == silicon && it.status == StageStatus.OK }
            if (mine.isEmpty()) return@mapNotNull null
            "${silicon.name} ${mine.sumOf { it.spanCount }}"
        }
        return groups.joinToString(" · ")
    }

    private fun describe(stage: StageReport): String = when (stage.status) {
        StageStatus.OK -> "${stage.task} ${stage.spanCount}"
        StageStatus.SKIPPED -> "${stage.task} skip"
        StageStatus.FAILED -> "${stage.task} fail"
    }

    companion object {
        val Empty = BackendReport(emptyList(), 0)
    }
}
