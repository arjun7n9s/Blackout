package com.blackout.app.ocr

import kotlin.math.abs

/**
 * Works out which spans are *field labels* and which are *values*, from geometry plus cheap text
 * tests. No model call.
 *
 * ## Why this exists
 *
 * Measured on a two-column bank statement fixture, **13 of 24 must-keep strings were blacked
 * out - 54% over-redaction - and 11 of those 13 sat at x≈60, the left label column.** The models
 * were not confused about sensitivity in general; they were tarring "Account Holder" with
 * "Priya Ramachandran" because the two arrive together. The referee made it *worse*, because the
 * neighbour context it receives literally contains the value.
 *
 * A field label is a property of the form, not of the person. That is a structural fact, and
 * detecting it structurally is far more reliable than hoping a 0.6B infers it from a prompt.
 *
 * ## How
 *
 * 1. Band spans into visual rows ([ReadingOrder.rows]).
 * 2. Find the **label column**: the x where the leftmost span of many multi-span rows starts.
 *    Requires [MIN_LABEL_ROWS] agreeing rows, so single-column documents and receipts - where
 *    there is no label column - are left entirely alone.
 * 3. In each qualifying row, the leftmost span becomes a [SpanRole.LABEL] *if it also reads like
 *    one*; everything to its right becomes a [SpanRole.VALUE] carrying the label as context.
 *
 * ## Safety
 *
 * Geometry alone must never silence a redaction. A span is refused LABEL status if it looks
 * contentful - digits, too many words, or an `@`. And [FieldLayout.applyTo] additionally refuses
 * the role to any span carrying a strong regex hint, so a name or email printed in the left
 * column is still treated as a value. When no label column is found, every span keeps its
 * default [SpanRole.STANDALONE] and behaviour is exactly as before.
 */
object FieldLayout {

    /** Rows that must agree on a left edge before we believe there's a label column. */
    private const val MIN_LABEL_ROWS = 3

    /** Left edges within this fraction of page width count as the same column. */
    private const val COLUMN_TOLERANCE = 0.035f

    /** A label must be separated from its value by at least this fraction of page width. */
    private const val MIN_PAIR_GAP = 0.04f

    /** Longest a label may be, in words. "Relationship Manager" is 2; prose is more. */
    private const val MAX_LABEL_WORDS = 4

    private const val MAX_LABEL_CHARS = 34

    /**
     * Field captions that are never sensitive on their own. Matched against the whole span after
     * stripping a trailing colon, so this only ever *promotes confidence* in an already
     * geometrically-identified label - it is not a blocklist.
     */
    private val LABEL_LEXICON = setOf(
        "name", "full name", "account holder", "holder name", "customer name",
        "father name", "father's name", "mother name", "mother's name", "guardian",
        "account", "account no", "account number", "a/c no", "customer id", "client id",
        "ifsc", "ifsc code", "micr", "branch", "branch name", "bank", "bank name",
        "pan", "pan no", "aadhaar", "aadhar", "uid", "uidai", "passport", "passport no",
        "voter id", "driving licence", "driving license", "dl no", "gstin", "gst no",
        "dob", "d.o.b", "date of birth", "birth date", "age", "gender", "sex",
        "address", "residential address", "permanent address", "correspondence address",
        "city", "state", "district", "pin", "pincode", "pin code", "postal code", "country",
        "communication address", "mailing address", "office address",
        "mobile", "mobile no", "mobile number", "phone", "phone no", "phone number",
        "telephone", "contact", "contact no", "registered mobile",
        "email", "email id", "email address", "e-mail",
        "amount", "total", "subtotal", "grand total", "balance", "opening balance",
        "closing balance", "available balance", "credit", "debit", "salary", "gross salary",
        "net salary", "net pay", "gross pay", "deductions", "tax", "tds",
        "date", "issue date", "expiry", "expiry date", "valid till", "valid from",
        "invoice no", "bill no", "receipt no", "reference", "reference no", "ref no",
        "transaction id", "utr", "order id", "policy no", "policy number",
        "employee id", "employee name", "emp id", "employee code", "designation", "department",
        // Payslip pay components. C-017 blacked out the HRA / LTA / PF captions on the workhorse
        // run: they are two- and three-letter captions, so nothing but a lexicon recognises them.
        "basic", "basic pay", "hra", "lta", "da", "pf", "epf", "esi", "esi no", "uan", "uan no",
        "special allowance", "conveyance", "medical allowance", "professional tax", "prof tax",
        "income tax", "bonus", "incentive", "overtime", "gratuity", "arrears",
        "earnings", "total earnings", "total deductions", "net payable", "net pay",
        "days worked", "lop", "pay period", "pay date", "mode of payment",
        "patient name", "patient id", "doctor", "hospital", "diagnosis", "uhid",
        "statement period", "period", "description", "particulars", "narration",
        "relationship manager", "nominee", "signature", "remarks", "status", "type",
        "नाम", "पता", "जन्म तिथि", "मोबाइल",
    )

    private val DIGIT = Regex("""\d""")

    /**
     * @param labelColumnX detected label-column left edge, or null if the document has none
     */
    data class Result(
        val roles: Map<Int, SpanRole>,
        val labelFor: Map<Int, String>,
        val labelColumnX: Int?,
    ) {
        companion object {
            val None = Result(emptyMap(), emptyMap(), null)
        }
    }

    fun detect(spans: List<TextSpan>, imageWidth: Int): Result {
        if (spans.size < MIN_LABEL_ROWS * 2 || imageWidth <= 0) return Result.None

        val rows = ReadingOrder.rows(spans)
        val multi = rows.filter { it.size >= 2 }
        if (multi.size < MIN_LABEL_ROWS) return Result.None

        val columnTolerance = (imageWidth * COLUMN_TOLERANCE).toInt().coerceAtLeast(6)
        val minGap = (imageWidth * MIN_PAIR_GAP).toInt().coerceAtLeast(10)

        // Candidate label column = the most popular left edge among row-leading spans that
        // actually read like labels. Requiring label-ish text here stops a table of amounts
        // from declaring its first column to be labels.
        val leadingEdges = multi
            .map { it.first() }
            .filter { looksLikeLabel(it.text) }
            .map { it.rect.left }
        if (leadingEdges.size < MIN_LABEL_ROWS) return Result.None

        val labelColumnX = dominantEdge(leadingEdges, columnTolerance)
            ?: return Result.None

        val roles = mutableMapOf<Int, SpanRole>()
        val labelFor = mutableMapOf<Int, String>()

        for (row in rows) {
            // Single-span rows are deliberately left alone.
            //
            // PAN and Aadhaar cards - the two most photographed documents here - stack the label
            // ABOVE the value, so both share a left edge. Classifying a lone left-column span as
            // a label would make "PRIYA RAMACHANDRAN" on a PAN card un-redactable, which is the
            // single worst leak this app could have. A label is only recognised when there is an
            // actual value sitting beside it.
            if (row.size < 2) continue

            val head = row.first()
            val inColumn = abs(head.rect.left - labelColumnX) <= columnTolerance
            val gapOk = row[1].rect.left - head.rect.right >= minGap
            if (!inColumn || !gapOk || !looksLikeLabel(head.text)) continue

            roles[head.id] = SpanRole.LABEL
            val caption = head.text.trim().trimEnd(':', '-', '–').trim()
            for (rest in row.drop(1)) {
                roles[rest.id] = SpanRole.VALUE
                labelFor[rest.id] = caption
            }
        }

        return if (roles.isEmpty()) Result.None
        else Result(roles, labelFor, labelColumnX)
    }

    /**
     * Applies roles to spans, refusing LABEL to anything [hasStrongHint] flags.
     *
     * That veto is the safety interlock: a person's name or an email printed in the left column
     * of a letterhead must not be made un-redactable by geometry alone.
     */
    fun applyTo(
        spans: List<TextSpan>,
        result: Result,
        hasStrongHint: (TextSpan) -> Boolean,
    ): List<TextSpan> {
        if (result.roles.isEmpty()) return spans
        return spans.map { span ->
            when (val role = result.roles[span.id]) {
                null -> span
                SpanRole.LABEL ->
                    if (hasStrongHint(span)) span.copy(role = SpanRole.STANDALONE)
                    else span.copy(role = SpanRole.LABEL)
                else -> span.copy(role = role, labelText = result.labelFor[span.id])
            }
        }
    }

    /**
     * Whether a span reads like a field caption. **A whitelist, not a classifier.**
     *
     * The tempting version - "short, title case, no digits" - is exactly wrong, because that
     * describes a person's name as well as it describes a caption. "Priya Ramachandran" is two
     * words, digit-free and 18 characters. There is no regex for a human name, so a permissive
     * rule here would hand geometry the power to silence the most important redaction in the app
     * with nothing downstream able to catch it.
     *
     * So a span qualifies only by *explicit* evidence: membership of a closed lexicon of form
     * captions, or a trailing colon (an unambiguous "I am a caption" marker). Anything else is
     * treated as content and goes to the models as normal.
     */
    fun looksLikeLabel(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_LABEL_CHARS) return false
        if (text.contains('@')) return false

        val stripped = text.trimEnd(':', '-', '–').trim()
        if (stripped.isEmpty() || stripped.none { it.isLetter() }) return false

        if (stripped.lowercase() in LABEL_LEXICON) return true

        // Trailing colon: explicit caption marker, but still refuse digits and long phrases so
        // "Paid to: RAHUL MEHTA" or "Total: 4,200" can't sneak through on the colon alone.
        return text.endsWith(':') &&
            !DIGIT.containsMatchIn(stripped) &&
            stripped.split(Regex("\\s+")).size <= MAX_LABEL_WORDS
    }

    /** Most popular left edge, if one edge accounts for at least [MIN_LABEL_ROWS] rows. */
    private fun dominantEdge(edges: List<Int>, tolerance: Int): Int? {
        var bestEdge: Int? = null
        var bestCount = 0
        for (candidate in edges) {
            val cluster = edges.filter { abs(it - candidate) <= tolerance }
            if (cluster.size > bestCount) {
                bestCount = cluster.size
                bestEdge = cluster.sorted()[cluster.size / 2]
            }
        }
        return if (bestCount >= MIN_LABEL_ROWS) bestEdge else null
    }
}
