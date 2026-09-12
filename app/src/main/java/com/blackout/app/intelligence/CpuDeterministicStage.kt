package com.blackout.app.intelligence

import com.blackout.app.ocr.SpanRole
import com.blackout.app.ocr.TextSpan

/**
 * The CPU's share of the hybrid pipeline: everything that can be settled without a model.
 *
 * Pure - no Android types, no IO - so the whole rule set is unit-tested on the JVM against the
 * fixtures Phone C filed in `C-Outputs/label-bugs.jsonl`.
 *
 * ## Why this stage exists
 *
 * Phone C ran 17 hostile captures and 7 A/B pairs. The finding that matters:
 *
 * > **29** label/value inversions across **9** documents. `Account Holder` gets a black bar while
 * > `Priya Ramachandran` stays readable; `Date of Birth` is hidden and `14-08-1988` is not; `IFSC`
 * > is hidden and `HZBN0001429` is not. The referee restores some *headers* but never fixes the
 * > pairing (`C-001`, `C-002`, `C-003`, `C-017`).
 *
 * That is not a knowledge problem a bigger model fixes - it is a *structure* problem, and
 * structure is cheap and deterministic. Three rules cover it:
 *
 *  1. A span carrying a high-confidence identifier pattern (PAN, Aadhaar, IFSC, UPI, email,
 *     phone, card, account no) is HIDE. No model is asked, because no model can beat a regex on
 *     `BDFPN2201L`.
 *  2. A [SpanRole.VALUE] sitting on the same row as a *sensitive* caption is HIDE, whatever the
 *     value looks like. This is what catches names, DOBs and addresses - the exact strings both
 *     models left visible on every two-column form C tested.
 *  3. A document header (`HORIZON BANK`, `NORTHWIND TECHNOLOGIES PVT LTD`, `CKYC / KYC UPDATION
 *     FORM`) is KEEP. The workhorse blacked all three out; the referee spent ~19 s undoing it.
 *
 * Labels themselves are KEEP, as before - see [MergePolicy].
 *
 * ## Effect on the GPU stage
 *
 * Everything this stage settles is removed from the LLM queue ([llmQueue]). On a two-column bank
 * form that is most of the label/value block, so Qwen sees fewer spans and Gemma's queue shrinks
 * with it. Whole rows drop out together (caption *and* value), which is why removing them does
 * not break the label/value adjacency `ReadingOrder` exists to create.
 */
object CpuDeterministicStage {

    /** Decisions this stage is willing to make alone, keyed by span id. */
    data class Result(
        val decisions: Map<Int, SpanDecision>,
        val hideCount: Int,
        val keepCount: Int,
    ) {
        val settled: Set<Int> get() = decisions.keys

        companion object {
            val Empty = Result(emptyMap(), 0, 0)
        }
    }

    fun run(
        spans: List<TextSpan>,
        hints: Map<Int, List<CandidateHint>>,
        imageHeight: Int,
    ): Result {
        if (spans.isEmpty()) return Result.Empty
        val out = LinkedHashMap<Int, SpanDecision>()

        // Postal address runs, plus the name line above them. Computed over the whole page
        // because the evidence is the *run*, not any single line - "VIC Flet No 25" only reads
        // as an address because it sits between "CO:" and "PO:".
        val addressIds = PiiBlocks.addressBlock(spans)

        for (span in spans) {
            if (span.isBlank) continue
            val spanHints = hints[span.id].orEmpty()

            // A label's verdict is geometry's, and geometry already ran. Recording it here keeps
            // it out of the LLM queue; MergePolicy enforces the same thing independently.
            if (span.role == SpanRole.LABEL) {
                out[span.id] = SpanDecision(span.id, Action.KEEP, DecisionSource.LAYOUT, "field label")
                continue
            }

            val identifier = certainIdentifier(span, spanHints)
            if (identifier != null) {
                out[span.id] = SpanDecision(
                    span.id, Action.HIDE, DecisionSource.DETERMINISTIC, "regex: ${identifier.label}",
                )
                continue
            }

            // Label and value sharing one OCR line, e.g. "State: New Delhi". The span is a
            // value even though it carries its own caption, and the two cannot be separated
            // without word-level boxes, so the line goes.
            val inline = PiiBlocks.inlineField(span.text)
            if (inline != null && PiiBlocks.isSensitiveLabel(inline.label)) {
                out[span.id] = SpanDecision(
                    span.id, Action.HIDE, DecisionSource.DETERMINISTIC,
                    "inline field '${inline.label}'",
                )
                continue
            }

            if (span.id in addressIds) {
                out[span.id] = SpanDecision(
                    span.id, Action.HIDE, DecisionSource.DETERMINISTIC, "address block",
                )
                continue
            }

            if (span.role == SpanRole.VALUE && labelIsSensitive(span.labelText)) {
                out[span.id] = SpanDecision(
                    span.id, Action.HIDE, DecisionSource.DETERMINISTIC,
                    "value of '${span.labelText?.trim()}'",
                )
                continue
            }

            if (isDocumentHeader(span, spanHints, imageHeight)) {
                out[span.id] = SpanDecision(
                    span.id, Action.KEEP, DecisionSource.DETERMINISTIC, "document header",
                )
            }
        }

        return Result(
            decisions = out,
            hideCount = out.values.count { it.action == Action.HIDE },
            keepCount = out.values.count { it.action == Action.KEEP },
        )
    }

    /** Spans the models still have to judge. Everything else is already decided. */
    fun llmQueue(spans: List<TextSpan>, result: Result): List<TextSpan> =
        spans.filter { !it.isBlank && it.id !in result.decisions }

    /**
     * The hint kinds we are willing to act on without a model.
     *
     * Deliberately narrower than "any STRONG hint": DATE, MONEY, PIN, IP and URL are excluded
     * because a transaction date or an amount is normally the *point* of the document. A DATE
     * only qualifies once [CandidateHints.promoteByNeighbour] has seen a date-of-birth caption
     * above it, which is what makes it STRONG.
     */
    private val CERTAIN_KINDS = setOf(
        HintKind.EMAIL,
        HintKind.UPI,
        HintKind.PAN,
        HintKind.AADHAAR,
        HintKind.PASSPORT,
        HintKind.ACCOUNT,
        HintKind.IFSC,
        HintKind.CARD,
        HintKind.PHONE,
        HintKind.DATE,
    )

    private fun certainIdentifier(span: TextSpan, hints: List<CandidateHint>): HintKind? {
        for (hint in hints) {
            if (hint.strength != HintStrength.STRONG) continue
            if (hint.kind !in CERTAIN_KINDS) continue
            if (hint.kind == HintKind.PHONE && !phoneIsConfident(span, hints, hint)) continue
            return hint.kind
        }
        return null
    }

    /**
     * The PHONE pattern is the loosest one we have: any 8-13 grouped digits match it, which on a
     * ledger includes amounts. So it only decides alone when nothing money-shaped is in the same
     * span - otherwise it still goes to the models, as before.
     */
    private fun phoneIsConfident(
        span: TextSpan,
        hints: List<CandidateHint>,
        hint: CandidateHint,
    ): Boolean {
        if (hints.any { it.kind == HintKind.MONEY }) return false
        if (hint.matched.contains(',')) return false
        if (CURRENCY.containsMatchIn(span.text)) return false
        if (hint.matched.contains('+')) return true
        return hint.matched.count(Char::isDigit) in 10..13
    }

    private val CURRENCY = Regex("""(?i)(₹|rs\.?|inr|usd|\$|€|£)""")

    /**
     * Captions whose value is about the *person*, not the form.
     *
     * Word-boundary matched, so "company" does not count as "pan". [NOT_SENSITIVE] wins, which is
     * what keeps `Bank Name -> HORIZON BANK` and `Branch -> Andheri East` visible while
     * `Employee Name -> Rohit Iyer` does not.
     */
    private val SENSITIVE = Regex(
        """(?i)\b(""" + listOf(
            "name", "holder", "nominee", "father", "mother", "spouse", "guardian",
            // A relationship / branch manager row is a named human being: `Relationship Manager ->
            // Sunil Kapoor` was left visible by both models on the phone A fixture.
            "manager",
            "dob", "d\\.?o\\.?b", "date of birth", "birth date", "born",
            "address", "residence", "residential", "permanent address", "communication",
            "mobile", "phone", "telephone", "contact", "email", "e-?mail",
            "pan", "aadhaar", "aadhar", "uid", "uidai", "passport", "voter",
            "driving licence", "driving license", "dl no", "licence no", "license no",
            "account no", "account number", "a/?c no", "acct", "ifsc", "micr", "iban",
            "customer id", "client id", "crn", "cif", "uan", "esi", "pf no", "pf number",
            "upi", "vpa", "gstin", "card no", "card number",
            "employee id", "emp id", "patient", "uhid", "policy no", "policy number",
            "नाम", "पता", "जन्म तिथि", "मोबाइल",
        ).joinToString("|") + """)\b"""
    )

    /**
     * Captions that look sensitive by keyword but describe the institution, the product or the
     * page. Checked first, so `Bank Name` never hides `HORIZON BANK`.
     */
    private val NOT_SENSITIVE = listOf(
        "bank name", "bank branch", "branch", "company", "employer", "organisation",
        "organization", "firm name", "doctor", "hospital name", "designation", "department",
        "scheme", "product", "currency", "statement period", "period", "description",
        "particulars", "narration", "mode", "status", "type", "nature",
    )

    fun labelIsSensitive(label: String?): Boolean {
        val normalised = label?.trim()?.trimEnd(':', '-', '–')?.trim()?.lowercase().orEmpty()
        if (normalised.isEmpty()) return false
        if (NOT_SENSITIVE.any { normalised.contains(it) }) return false
        return SENSITIVE.containsMatchIn(normalised)
    }

    /**
     * Letterhead / form title in the top band of the page.
     *
     * C-001/002/003/017: the workhorse hid `HORIZON BANK`, `NARMADA CO-OP BANK`,
     * `CKYC / KYC UPDATION FORM` and `NORTHWIND TECHNOLOGIES PVT LTD`, and the referee's main
     * measured contribution was putting them back ~19 s later. Requiring an organisation or
     * document word *and* the top band keeps this from touching a mid-page "Statement of
     * account" row, and any strong identifier in the span disqualifies it outright.
     */
    private val HEADER_WORDS = Regex(
        """(?i)\b(""" + listOf(
            "bank", "co-?op", "cooperative", "ltd", "limited", "pvt", "private", "llp", "inc",
            "corp", "corporation", "technologies", "solutions", "services", "enterprises",
            "industries", "hospital", "clinic", "pharmacy", "university", "college", "school",
            "trust", "society", "authority", "government", "department of",
            "statement", "payslip", "pay slip", "salary slip", "invoice", "receipt", "form",
            "kyc", "ckyc", "certificate", "summary",
        ).joinToString("|") + """)\b"""
    )

    private const val HEADER_BAND = 0.25f
    private const val HEADER_MAX_WORDS = 8
    private const val HEADER_MAX_CHARS = 60

    fun isDocumentHeader(span: TextSpan, hints: List<CandidateHint>, imageHeight: Int): Boolean {
        if (span.role == SpanRole.VALUE) return false
        if (imageHeight <= 0) return false
        if (span.rect.top > imageHeight * HEADER_BAND) return false
        val text = span.text.trim()
        if (text.length > HEADER_MAX_CHARS) return false
        if (text.split(Regex("\\s+")).size > HEADER_MAX_WORDS) return false
        if (hints.any { it.strength == HintStrength.STRONG }) return false
        return HEADER_WORDS.containsMatchIn(text)
    }
}
