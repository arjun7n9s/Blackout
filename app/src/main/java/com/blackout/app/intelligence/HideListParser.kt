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
 * So on the NPU we ask a smaller question. We never needed JSON - only *which lines to hide* -
 * and a bare list answers it:
 *
 * ```
 * A D G
 * ```
 *
 * Anything not listed is KEEP. That is fewer output tokens than `{"id":1,"a":"hide"}` per span,
 * which matters twice over: less to decode, and less for a small model to get wrong.
 *
 * ## Why letters, not numbers
 *
 * This protocol used numbered lines until a bank statement exposed the flaw. Asked for "the
 * numbers of the lines to hide", the model replied
 * `42 560038 01 2026-31 124500 208315 560038 03 420` - it had answered *which numbers on this
 * page are sensitive*, which is a perfectly sensible reading of the question. The parser then
 * kept whatever fell inside the batch's id range, so the fragments `01` and `03` silently became
 * "hide line 1 and line 3".
 *
 * The bug was structural: line tags and document content were both integers, so no amount of
 * prompt wording could separate them reliably. Letters cannot collide with a page full of digits,
 * and a reply made of numbers is now unambiguously a misunderstanding - [parse] reports it as no
 * answer so the batch escalates, rather than inventing decisions from it.
 *
 * ## Salvage rules
 *
 * Small models pad. They emit "Hide: A, D and G.", wrap in code fences, restate the question, or
 * leak a `<think>` block. So: strip thinking, prefer the last line carrying a tag (a restated
 * prompt tends to come *first*, the answer last), and keep only tags actually asked about. "none"
 * is a legitimate answer and must not be confused with a failure.
 */
object HideListParser {

    private val THINK = Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE)
    private val FENCE = Regex("""```[a-zA-Z]*""")

    /**
     * A standalone capital letter. The boundaries matter: without them the "A" in "Aadhaar"
     * echoed back from the page would read as a tag.
     */
    private val TAG = Regex("""(?<![A-Za-z])[A-Z](?![A-Za-z])""")
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

        // Prefer the last line carrying a tag: a model that restates the question puts the
        // echo first and its answer last.
        val answerLine = lines.lastOrNull { TAG.containsMatchIn(it) }

        if (answerLine == null) {
            // No tags at all. An explicit "none" is an answer; anything else - most importantly a
            // line of digits copied off the page - is a misunderstanding, and inventing decisions
            // from it is exactly the bug this format exists to prevent.
            return if (NONE.containsMatchIn(body)) emptySet() else null
        }

        val ids = TAG.findAll(answerLine)
            .map { it.value[0] - 'A' + 1 }
            .filter { it in validIds }
            .toSet()

        // Tags that matched nothing we asked about: treat an explicit "none" as an answer,
        // otherwise admit we could not read it.
        if (ids.isEmpty()) {
            return if (NONE.containsMatchIn(body)) emptySet() else null
        }
        return ids
    }
}
