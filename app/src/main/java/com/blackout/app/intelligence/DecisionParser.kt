package com.blackout.app.intelligence

/**
 * Salvages span decisions out of whatever a small model actually emits.
 *
 * We ask for constrained JSON via LiteRT-LM's [com.google.ai.edge.litertlm.ResponseFormat], which
 * makes well-formed output the common case. This parser exists for when that isn't enough: a
 * 0.6B model still wraps replies in markdown fences, adds a prose preamble, repeats ids, invents
 * ids nobody asked about, and - most often - gets truncated mid-array by the output token cap.
 *
 * So rather than parsing the document as a whole (all-or-nothing), we scan for `{...}` objects
 * independently and keep every one that yields a usable id + action. A truncated array still
 * contributes every complete object before the cut.
 *
 * Hand-rolled instead of org.json so the whole thing unit-tests on the JVM with no Android
 * runtime and no extra dependency.
 */
object DecisionParser {

    /** Matches one flat JSON object. Decisions never nest, so this is sufficient and cheap. */
    private val OBJECT = Regex("""\{[^{}]*\}""")

    private val ID = Regex(""""id"\s*:\s*"?(-?\d+)"?""", RegexOption.IGNORE_CASE)
    private val ACTION = Regex(""""(?:a|action|decision|label)"\s*:\s*"([a-zA-Z_]+)"""", RegexOption.IGNORE_CASE)
    private val REASON = Regex(""""(?:reason|why)"\s*:\s*"((?:[^"\\]|\\.)*)"""", RegexOption.IGNORE_CASE)

    /**
     * @param raw the model's reply, in any state of disrepair
     * @param validIds ids we actually asked about; anything else is hallucinated and dropped
     * @param source stamped onto every decision produced
     */
    fun parse(
        raw: String,
        validIds: Set<Int>,
        source: DecisionSource,
    ): Map<Int, SpanDecision> {
        if (raw.isBlank() || validIds.isEmpty()) return emptyMap()

        val body = stripFences(raw)
        val out = LinkedHashMap<Int, SpanDecision>()

        for (match in OBJECT.findAll(body)) {
            val chunk = match.value
            val id = ID.find(chunk)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (id !in validIds) continue

            val action = ACTION.find(chunk)?.groupValues?.get(1)?.let(::toAction) ?: continue

            // Duplicate ids resolve most-protective-wins, not first-wins. A repeated id means the
            // model was confused about that span, and the safe branch under confusion is to hide
            // it - the referee or a single tap can undo that, whereas a silent leak cannot.
            val existing = out[id]
            if (existing != null && protectiveRank(existing.action) >= protectiveRank(action)) {
                continue
            }
            val reason = REASON.find(chunk)?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\n", " ")
                ?.trim()
                ?.take(120)
                ?.ifBlank { null }

            out[id] = SpanDecision(id = id, action = action, source = source, reason = reason)
        }

        return out
    }

    /**
     * Drops markdown fences and any prose before the first JSON delimiter.
     *
     * Kept deliberately loose - we only need to get the scanner into the right neighbourhood,
     * since [parse] tolerates junk between objects anyway.
     */
    private fun stripFences(raw: String): String {
        var s = raw.trim()

        // ```json ... ``` or ``` ... ```
        val fence = Regex("""```(?:json|JSON)?\s*([\s\S]*?)(?:```|$)""").find(s)
        if (fence != null) {
            val inner = fence.groupValues[1].trim()
            if (inner.isNotEmpty()) s = inner
        }

        // Some models emit a <think> block even when thinking is disabled.
        s = s.replace(Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE), "")

        val start = s.indexOfFirst { it == '[' || it == '{' }
        return if (start > 0) s.substring(start) else s
    }

    private fun protectiveRank(action: Action): Int = when (action) {
        Action.KEEP -> 0
        Action.UNSURE -> 1
        Action.HIDE -> 2
    }

    private fun toAction(token: String): Action? = when (token.trim().lowercase()) {
        "hide", "redact", "sensitive", "private", "mask", "block" -> Action.HIDE
        "keep", "show", "safe", "public", "visible", "none" -> Action.KEEP
        "unsure", "unknown", "maybe", "uncertain", "review" -> Action.UNSURE
        else -> null
    }
}
