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

    /**
     * Workhorse instruction for runtimes that cannot do constrained decoding - i.e. the NPU.
     *
     * Genie/QAIRT rejects grammar-constrained generation on this SoC, so instead of asking for
     * JSON and hoping, we ask a smaller question: list the ids to hide. Everything unlisted is
     * kept. Fewer output tokens than JSON, and far less for a 0.6B to get structurally wrong.
     */
    /**
     * Lines are tagged with **letters**, and the reply is letters.
     *
     * Numbering them was a silent correctness bug. Asked for "the numbers of the lines to hide"
     * over a bank statement, Qwen3-0.6B replied:
     *
     * ```
     * 42 560038 01 2026-31 124500 208315 560038 03 420
     * ```
     *
     * It had answered a different, entirely reasonable question - *which numbers on this page are
     * sensitive* - returning a PIN code, an amount, a balance. [HideListParser] then kept whatever
     * fell inside the batch's id range, so the fragments `01` and `03` became "hide line 1 and
     * line 3". Two spans redacted for no reason, nothing logged, and the hide count looked
     * plausible.
     *
     * Line labels and page content shared one symbol space, so no prompt wording could reliably
     * separate them. Letters remove the ambiguity by construction: a document is full of digits
     * and never full of bare capitals, so a digit in the reply is now obviously not a label.
     * It is also shorter to decode - the verbose numeric replies above cost 43-48 tokens per
     * batch at ~100 tok/s, which was most of the workhorse's latency.
     */
    val WORKHORSE_SYSTEM_HIDELIST = """
        You redact documents before sharing. Lines are in reading order, so a value usually
        follows its label.
        Hide private data: person names, addresses, phone, email, ID/Aadhaar/PAN/passport
        numbers, card or account numbers, date of birth, medical info, salary or balances.
        Keep generic text: headings, field labels, form captions, company or product names.
        Each line is tagged with a capital letter. Reply with those letters only.
        Examples:
        "A: Account Holder" -> keep (it is a label)
        "B: Priya Ramachandran" -> hide (a person's name), so reply includes B
        "C: PAN" -> keep (a label)
        "D: ABCDE1234F" -> hide (an ID number), so reply includes D
        For those four lines the correct reply is exactly: B D
        Reply with ONLY the letters of the lines to hide, separated by spaces.
        Never reply with numbers or with text copied from the document.
        If nothing should be hidden reply exactly: none
    """.trimIndent()

    /**
     * Second attempt at a batch whose first answer was degenerate.
     *
     * Deliberately not "the same prompt again": greedy decoding would reproduce the same reply
     * token for token. The framing is inverted - name what survives rather than what goes - which
     * is a different enough question to break the repetition, and it pushes back on the specific
     * failure, a model that has stopped reading and is listing every letter it was given.
     */
    val WORKHORSE_SYSTEM_RETRY = """
        You are checking which lines of a document are truly private.
        Almost all lines are ordinary and must be kept: headings, labels, captions, company names,
        boilerplate, transaction dates, statement periods, column titles.
        Only these are private: a person's name, a postal address, a phone number, an email, an
        ID number (Aadhaar/PAN/passport), a card or account number, a date of birth, medical
        details, a salary or an account balance.
        Each line is tagged with a capital letter.
        Reply with ONLY the letters of the genuinely private lines, separated by spaces.
        Most pages have very few. If none are private reply exactly: none
        Never reply with numbers or with text copied from the document.
    """.trimIndent()

    val REFEREE_SYSTEM = """
        You are a privacy reviewer settling ambiguous redaction calls.
        A smaller model was unsure about these lines, or disagreed with a pattern match.
        Decide "hide" or "keep" for each. Do not answer "unsure" - make a call.
        Prefer "keep" for generic labels; prefer "hide" for anything identifying a specific
        person, account, or amount. Give a reason of at most 6 words.
        Reply with JSON only: {"decisions":[{"id":1,"a":"hide","reason":"personal phone number"}]}
    """.trimIndent()

    /**
     * Referee instruction for the NPU, which cannot pin a reply shape.
     *
     * The referee must arbitrate in BOTH directions - it exists as much to un-hide a wrongly
     * blacked field label as to catch a missed value. A hide-list does that naturally: listing an
     * id hides it, omitting one clears it.
     */
    val REFEREE_SYSTEM_HIDELIST = """
        You are a privacy reviewer settling ambiguous redaction calls.
        A smaller model was unsure about these lines, or disagreed with a pattern match.
        Hide anything identifying a specific person, account, address or amount.
        Keep generic labels, headings, issuer names and boilerplate.
        Each line is tagged with a capital letter. Reply with those letters only.
        Reply with ONLY the letters of the lines to hide, separated by spaces.
        Never reply with numbers or with text copied from the document.
        If nothing should be hidden reply exactly: none
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

    /**
     * Same batch, asking for a bare id list instead of JSON. Used on the NPU path.
     */
    fun workhorseHideListBatch(
        spans: List<TextSpan>,
        above: String? = null,
        below: String? = null,
    ): Batch {
        val mapping = LinkedHashMap<Int, Int>(spans.size)
        val body = buildString {
            if (!above.isNullOrBlank()) appendLine("above: " + clip(above))
            appendLine("Lines:")
            spans.forEachIndexed { index, span ->
                val local = index + 1
                mapping[local] = span.id
                appendLine("${letterFor(local)}: " + clip(span.text))
            }
            if (!below.isNullOrBlank()) appendLine("below: " + clip(below))
            append("\nLetters to hide (or none):")
        }
        return Batch(body, mapping)
    }

    /**
     * Local id 1..N as a tag the model cannot confuse with page content. See
     * [WORKHORSE_SYSTEM_HIDELIST] for why this is not a number.
     *
     * Only ever called with ids up to [WORKHORSE_BATCH], well inside A-Z.
     */
    fun letterFor(localId: Int): Char = 'A' + (localId - 1)

    /** Renders a referee batch: escalated lines, their hint tags, and the document type. */
    /**
     * @param tagged label lines A, B, C rather than 1, 2, 3 - required whenever the reply is a
     *   hide-list rather than JSON, so the tags cannot collide with digits on the page. See
     *   [WORKHORSE_SYSTEM_HIDELIST].
     */
    fun refereeBatch(
        spans: List<TextSpan>,
        neighbours: Map<Int, String>,
        hints: Map<Int, List<CandidateHint>>,
        docSummary: String?,
        tagged: Boolean = false,
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
                append(if (tagged) letterFor(local).toString() else local.toString())
                append(": ").append(clip(span.text))
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
            if (tagged) {
                append("\nLetters to hide (or none):")
            } else {
                append("\nJSON for ids 1-").append(spans.size)
            }
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
