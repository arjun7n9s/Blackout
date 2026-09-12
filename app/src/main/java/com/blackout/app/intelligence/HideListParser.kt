package com.blackout.app.intelligence

/**
 * Reads the workhorse's verdict when it is running on the NPU.
 *
 * ## Why a different wire format from the GPU path
 *
 * The GPU path asks LiteRT-LM for JSON and pins the shape with
 * [com.google.ai.edge.litertlm.ResponseFormat]. The Genie/QAIRT runtime has no such facility -
 * grammar-constrained generation is rejected on this SoC, which is why Tokito ships plain-text
 * dialogue. Fighting that would mean parsing unconstrained JSON from a 0.6B, which is exactly the
 * failure mode `DecisionParser` was written to survive.
 *
 * So on the NPU we ask a smaller question. We never needed JSON - only *which span ids to hide* -
 * and a bare list answers it:
 *
 * ```
 * 1 4 7
 * ```
 *
 * Anything not listed is KEEP. That is fewer output tokens than `{"id":1,"a":"hide"}` per span,
 * which matters twice over: less to decode, and less for a small model to get wrong.
 *
 * ## Salvage rules
 *
 * Small models pad. They emit "Hide: 1, 4 and 7.", wrap in code fences, restate the question, or
 * leak a `<think>` block. So: strip thinking, prefer the last line that contains digits (a
 * restated prompt tends to come *first*, the answer last), and keep only integers that were
 * actually asked about. "none" is a legitimate answer and must not be confused with a failure.
 */
object HideListParser {

    private val THINK = Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE)
    private val FENCE = Regex("""```[a-zA-Z]*""")
    private val INT = Regex("""\d+""")
    private val NONE = Regex("""(?i)\bnone\b|\bnothing\b|\bno\s+(?:ids?|spans?|lines?)\b""")

    /**
     * @param validIds the local ids this batch actually asked about
     * @return a decision for **every** id in [validIds] - HIDE if listed, KEEP otherwise
     */
    fun parse(
        raw: String,
        validIds: Set<Int>,
        source: DecisionSource,
    ): Map<Int, SpanDecision> {
        if (validIds.isEmpty()) return emptyMap()

        val body = raw
            .replace(THINK, " ")
            .replace(FENCE, " ")
            .trim()

        // A blank reply is not "hide nothing" - it is no answer at all, and the caller must be
        // able to tell those apart so the referee can pick the batch up.
        if (body.isBlank()) return emptyMap()

        val hidden = extractIds(body, validIds)
        if (hidden == null) return emptyMap()

        return validIds.associateWith { id ->
            if (id in hidden) {
                SpanDecision(id, Action.HIDE, source, "listed")
            } else {
                SpanDecision(id, Action.KEEP, source, "not listed")
            }
        }
    }

    /** null means "could not read an answer at all"; empty set means "explicitly hide nothing". */
    private fun extractIds(body: String, validIds: Set<Int>): Set<Int>? {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Prefer the last line carrying digits: a model that restates the question puts the
        // echo first and its answer last.
        val answerLine = lines.lastOrNull { INT.containsMatchIn(it) }

        if (answerLine == null) {
            return if (NONE.containsMatchIn(body)) emptySet() else null
        }

        val ids = INT.findAll(answerLine)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in validIds }
            .toSet()

        // Digits that matched nothing we asked about: treat an explicit "none" as an answer,
        // otherwise admit we could not read it.
        if (ids.isEmpty()) {
            return if (NONE.containsMatchIn(body)) emptySet() else null
        }
        return ids
    }
}
