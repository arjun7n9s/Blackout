package com.blackout.app.intelligence

import com.blackout.app.ocr.TextSpan

/**
 * Prompt + schema construction for the two-model cascade.
 *
 * Three choices here are load-bearing, and all three are about respecting how small a 0.6B model
 * really is:
 *
 * **Per-batch local ids (1..N), not global span ids.** `"id":7` is one token where `"id":1247` is
 * three, but the real win is accuracy: a 0.6B copies single-digit integers back faithfully and
 * routinely corrupts four-digit ones. The caller keeps the local -> global mapping.
 *
 * **No `reason` from the workhorse.** A six-word reason costs ~13 tokens per span - at N=10 that
 * is ~10% of the entire KV window, and it roughly doubles decode time. At this size the reason is
 * post-hoc confabulation anyway. The referee, which has headroom and competence, does produce
 * reasons, and that is where the UI's "why" string comes from.
 *
 * **Neighbour context only at batch boundaries.** Spans are emitted in reading order and the
 * system instruction says so, which makes adjacency implicit for everything inside the batch.
 * Only the two edges need an explicit `above:` / `below:` line - ~55 tokens once per batch,
 * instead of tripling input cost by repeating neighbours per span.
 *
 * Token budget per batch, at ~3 chars/token (OCR text with digits tokenizes denser than prose):
 * `267 + 30N`. At N=10 that is ~567 of Qwen3's 1280-token cache, i.e. 44%, leaving real headroom
 * for long address lines. Tokens alone would allow N~21; the binding limit is instruction
 * adherence, which degrades past roughly a dozen items.
 */
object Prompts {

    /** Spans per Qwen request. Token budget allows more; 0.6B reliability does not. */
    const val WORKHORSE_BATCH = 10

    /** Spans per Gemma request - far bigger context, and it only sees escalated spans. */
    const val REFEREE_BATCH = 20

    /** Stop packing a batch if the estimate crosses this, regardless of span count. */
    const val TOKEN_GUARD = 900

    private const val MAX_SPAN_CHARS = 64

    /**
     * Few-shot examples earn their ~70 tokens.
     *
     * The whole task reduces to one distinction - a field *label* is never sensitive, the *value*
     * next to it usually is - and a 0.6B does not reliably infer that from an abstract rule. Shown
     * five concrete pairs it does much better. Each line must also be judged independently,
     * because greedy decoding over a constrained grammar otherwise latches onto whatever it
     * emitted first and repeats it for the whole batch.
     */
    val WORKHORSE_SYSTEM = """
        You redact documents before sharing. Lines are in reading order, so a value usually
        follows its label.
        For each numbered line choose one action:
        hide = private data: person names, addresses, phone, email, ID/Aadhaar/PAN/passport
        numbers, card or account numbers, date of birth, medical info, salary or balances.
        keep = generic text: headings, field labels, form captions, company or product names,
        boilerplate.
        unsure = you genuinely cannot tell.
        Examples:
        "Account Holder" -> keep (it is a label)
        "Priya Ramachandran" -> hide (a person's name)
        "PAN" -> keep (a label)
        "ABCDE1234F" -> hide (an ID number)
        "RECENT TRANSACTIONS" -> keep (a heading)
        Judge every line on its own. Do not give every line the same answer.
        Reply with JSON only: {"decisions":[{"id":1,"a":"hide"}]}
    """.trimIndent()

    val REFEREE_SYSTEM = """
        You are a privacy reviewer settling ambiguous redaction calls.
        A smaller model was unsure about these lines, or disagreed with a pattern match.
        Decide "hide" or "keep" for each. Do not answer "unsure" - make a call.
        Prefer "keep" for generic labels; prefer "hide" for anything identifying a specific
        person, account, or amount. Give a reason of at most 6 words.
        Reply with JSON only: {"decisions":[{"id":1,"a":"hide","reason":"personal phone number"}]}
    """.trimIndent()

    private val SUMMARY_SYSTEM = """
        Name the document type in at most 8 words (e.g. "Indian Aadhaar card", "bank statement",
        "salary slip", "chat screenshot"). Reply with the phrase only.
    """.trimIndent()

    /**
     * Schema for LiteRT-LM's constrained decoder. Object-wrapped because
     * [com.google.ai.edge.litertlm.ResponseFormat.Type.JSON_OBJECT] is the object-shaped mode.
     */
    /**
     * @param count spans in the batch, pinned as `minItems`/`maxItems`.
     *
     * The bound is load-bearing. Without it the decoder is perfectly happy to emit
     * `{"decisions":[]}` - valid against the schema, and observed on real batches containing an
     * account number, a PAN and an Aadhaar. Every span then fell through to "keep", which is a
     * total silent failure of the redaction. Pinning the array length forces one decision per
     * span.
     */
    fun decisionSchema(count: Int): String =
        """{"type":"object","properties":{"decisions":{"type":"array",""" +
            """"minItems":$count,"maxItems":$count,"items":""" +
            """{"type":"object","properties":{"id":{"type":"integer"},""" +
            """"a":{"type":"string","enum":["hide","keep","unsure"]}},""" +
            """"required":["id","a"]}}},"required":["decisions"]}"""

    /** Referee adds a reason and drops "unsure" from the enum so the cascade always terminates. */
    fun refereeSchema(count: Int): String =
        """{"type":"object","properties":{"decisions":{"type":"array",""" +
            """"minItems":$count,"maxItems":$count,"items":""" +
            """{"type":"object","properties":{"id":{"type":"integer"},""" +
            """"a":{"type":"string","enum":["hide","keep"]},""" +
            """"reason":{"type":"string"}},"required":["id","a"]}}},"required":["decisions"]}"""

    /** One batch plus the local-id mapping needed to read its answer back. */
    data class Batch(
        val prompt: String,
        /** local id (1-based) -> global span id */
        val localToGlobal: Map<Int, Int>,
    ) {
        val localIds: Set<Int> get() = localToGlobal.keys
    }

    /**
     * Renders a workhorse batch.
     *
     * Hints are deliberately **not** included. Withholding them keeps the regex signal and the
     * model signal independent, which is exactly what makes their disagreement informative to
     * [MergePolicy.refereeQueue] - and it means a hint can never push the model toward hiding.
     */
    fun workhorseBatch(
        spans: List<TextSpan>,
        above: String? = null,
        below: String? = null,
    ): Batch {
        val mapping = LinkedHashMap<Int, Int>(spans.size)
        val body = buildString {
            if (!above.isNullOrBlank()) append("above: ").append(clip(above)).append('\n')
            append("Lines:\n")
            spans.forEachIndexed { index, span ->
                val local = index + 1
                mapping[local] = span.id
                append(local).append(": ").append(clip(span.text)).append('\n')
            }
            if (!below.isNullOrBlank()) append("below: ").append(clip(below)).append('\n')
            append("\nJSON for ids 1-").append(spans.size).append(" /no_think")
        }
        return Batch(body, mapping)
    }

    /** Renders a referee batch: escalated lines, their hint tags, and the document type. */
    fun refereeBatch(
        spans: List<TextSpan>,
        neighbours: Map<Int, String>,
        hints: Map<Int, List<CandidateHint>>,
        docSummary: String?,
    ): Batch {
        val mapping = LinkedHashMap<Int, Int>(spans.size)
        val body = buildString {
            if (!docSummary.isNullOrBlank()) {
                append("Document: ").append(clip(docSummary)).append("\n\n")
            }
            append("Lines to judge:\n")
            spans.forEachIndexed { index, span ->
                val local = index + 1
                mapping[local] = span.id
                append(local).append(": ").append(clip(span.text))
                val tags = hints[span.id].orEmpty().map { it.kind.label }.distinct()
                if (tags.isNotEmpty()) tags.joinTo(this, ",", " [", "]")
                // Prefer the paired field caption over the raw neighbour blob. "label: PAN" tells
                // the referee what this value *is*; the generic "near: ..." context used to hand
                // it the surrounding values too, which is how labels ended up judged as if they
                // were their own contents.
                val caption = span.labelText?.takeIf { it.isNotBlank() }
                if (caption != null) {
                    append("\n   label: ").append(clip(caption))
                } else {
                    neighbours[span.id]?.takeIf { it.isNotBlank() }?.let {
                        append("\n   near: ").append(clip(it))
                    }
                }
                append('\n')
            }
            append("\nJSON for ids 1-").append(spans.size)
        }
        return Batch(body, mapping)
    }

    /** Cheap document-type probe; longest lines carry the most signal. */
    fun summaryPrompt(spans: List<TextSpan>): String {
        val sample = spans.asSequence()
            .filter { it.text.length > 3 }
            .sortedByDescending { it.text.length }
            .take(10)
            .map { clip(it.text) }
            .joinToString("\n")
        return "Text:\n$sample\n\nDocument type:"
    }

    val SUMMARY_SYSTEM_INSTRUCTION: String get() = SUMMARY_SYSTEM

    /** Line above and below - what separates a bare value from its field label. */
    fun neighbourContext(all: List<TextSpan>, target: TextSpan): String {
        val idx = all.indexOfFirst { it.id == target.id }
        if (idx < 0) return ""
        val before = all.getOrNull(idx - 1)?.text?.takeIf { it.isNotBlank() }
        val after = all.getOrNull(idx + 1)?.text?.takeIf { it.isNotBlank() }
        return listOfNotNull(before, after).joinToString(" | ")
    }

    /** Rough token estimate at ~3 chars/token, used by [TOKEN_GUARD]. */
    fun estimateTokens(text: String): Int = (text.length / 3) + 16

    private fun clip(s: String): String {
        val flat = s.replace('\n', ' ').trim()
        return if (flat.length <= MAX_SPAN_CHARS) flat else flat.take(MAX_SPAN_CHARS - 1) + "…"
    }
}
