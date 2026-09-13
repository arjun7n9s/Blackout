package com.blackout.app.intelligence

import com.blackout.app.ocr.SpanRole
import com.blackout.app.ocr.TextSpan

/**
 * The validation layer for "should this span be redacted?"
 *
 * Three gates, one decision per span. The engine stages call [evaluate] instead of duplicating
 * the logic that used to be smeared across [CpuDeterministicStage], [PiiBlocks] and [FieldLayout].
 *
 * ## The three gates
 *
 * **Gate 1 — Identifier pattern.** A STRONG regex hit (PAN, Aadhaar, IFSC, UPI, email, phone,
 * card, account). The user said: redact long codes. These are long codes. HIDE.
 *
 * **Gate 2 — Field label.** A short caption from the closed [LABEL_LEXICON] or a trailing-colon
 * marker. The user said: the label decides. A label is the *form's* vocabulary, not the
 * holder's identity. KEEP.
 *
 * **Gate 3 — Sensitive caption paired with the value.** When the caption is in [SENSITIVE_STEMS]
 * (Name, पिता, Address, Mobile, DOB, PAN, IFSC, etc.) and the value sits beside it, HIDE the
 * value. **This fires regardless of [SpanRole]** — including STANDALONE and overlapping-card
 * layouts where [com.blackout.app.ocr.FieldLayout] could not detect a label column. That is the
 * bug the live tests surfaced: 6-Aadhaar pile, India Today collage, single-column passports —
 * all stayed STANDALONE and Gate 3 used to be skipped.
 *
 * ## What this is NOT
 *
 * Not an NER model. Names without an obvious caption ("Parvati Kumari" on a standalone ID card
 * where the caption was OCR'd into garbage) are caught by Gate 3 only when a caption survives.
 * Pure personal-name detection is a separate problem and is left to the model workhorse.
 *
 * Not a confidence-weighted classifier. The gates are ordered and short-circuit: Gate 1 wins
 * over Gate 2 wins over Gate 3. Confidence is in the reason string, not the verdict.
 *
 * Pure by design. No Android types, no IO. Whole module is JVM-unit-testable.
 */
object RedactionGate {

    /**
     * One gate's verdict. The engine picks the strongest one and emits a [SpanDecision].
     *
     * @param confidence 0..100. Higher = safer to apply without model review. Gates 1 and 2 are
     *   ≥95 because the pattern or lexicon is closed. Gate 3 is 90 because caption matching is
     *   fuzzy (OCR mangles captions, see [PiiBlocks.fuzzyEquals]).
     */
    enum class Gate { IDENTIFIER, LABEL, CAPTION_PAIR, NONE }

    data class Verdict(
        val gate: Gate,
        val action: Action,
        val confidence: Int,
        val reason: String,
    ) {
        companion object {
            /** No gate fired. The span goes to the model workhorse. */
            val Undecided = Verdict(Gate.NONE, Action.UNSURE, 0, "no gate fired")
        }
    }

    /**
     * Evaluate a single span against the three gates.
     *
     * @param span the OCR'd span (layout-applied, so [SpanRole] may be set).
     * @param hints the [CandidateHints] for this span, from [CandidateHints.detect].
     * @param previousLine the text immediately above this span in reading order, if any.
     * @param nextLine the text immediately below this span in reading order, if any.
     */
    fun evaluate(
        span: TextSpan,
        hints: List<CandidateHint>,
        previousLine: String?,
        nextLine: String?,
    ): Verdict {
        if (span.isBlank) return Verdict.Undecided

        // ---- Gate 1: identifier pattern (redact long codes) ------------------------------
        // A STRONG regex hit is the strongest signal we have. The CERTAIN_KINDS list mirrors
        // CpuDeterministicStage.CERTAIN_KINDS but is intentionally narrower: only kinds whose
        // pattern length proves "this is a long code, not a date or amount".
        val identifier = identifierHit(hints)
        if (identifier != null) {
            return Verdict(
                gate = Gate.IDENTIFIER,
                action = Action.HIDE,
                confidence = 99,
                reason = "identifier: ${identifier.kind.label} = ${identifier.matched}",
            )
        }

        // ---- Gate 2: field label (the label decides — labels stay visible) ---------------
        // A span that reads as a caption from the closed LABEL_LEXICON, or ends with ":" and is
        // short, is the form's vocabulary. Never the holder's identity.
        if (looksLikeLabel(span.text)) {
            return Verdict(
                gate = Gate.LABEL,
                action = Action.KEEP,
                confidence = 95,
                reason = "field label",
            )
        }

        // ---- Gate 3: caption → value pairing (redact what the label asks to hide) ---------
        // Fires for any role except LABEL — LABEL is handled by Gate 2. Includes STANDALONE,
        // which is the bug the live tests surfaced: on overlapping cards and single-column docs,
        // FieldLayout could not pair left/right, so every VALUE gate used to be skipped.
        if (span.role != SpanRole.LABEL) {
            val inline = PiiBlocks.inlineField(span.text)
            if (inline != null && PiiBlocks.isSensitiveLabel(inline.label)) {
                return Verdict(
                    gate = Gate.CAPTION_PAIR,
                    action = Action.HIDE,
                    confidence = 90,
                    reason = "inline '${inline.label}' → value",
                )
            }
            val fromNeighbour = sensitiveCaptionNeighbour(span.text, previousLine, nextLine)
            if (fromNeighbour != null) {
                return Verdict(
                    gate = Gate.CAPTION_PAIR,
                    action = Action.HIDE,
                    confidence = 88,
                    reason = "neighbour caption: $fromNeighbour",
                )
            }
        }

        return Verdict.Undecided
    }

    // -------------------------------------------------------------------------------------
    // Gate 1: identifier pattern
    // -------------------------------------------------------------------------------------

    /**
     * The kinds we will HIDE on a single STRONG hit.
     *
     * Deliberately excludes:
     *  - DATE: "long codes" the user said, dates are not long codes. A printed date stays
     *    visible unless paired with a sensitive caption (Gate 3).
     *  - MONEY: an amount without a caption is the document's own subject (an invoice's totals).
     *    A bare amount is not PII. Captioned amounts are gated on caption (Gate 3).
     *  - PIN: a 6-digit postcode is not, on its own, identifying.
     *  - URL, IP: not PII on an ID document.
     */
    private val CERTAIN_KINDS = setOf(
        HintKind.PAN,
        HintKind.AADHAAR,
        HintKind.PASSPORT,
        HintKind.ACCOUNT,
        HintKind.IFSC,
        HintKind.CARD,
        HintKind.UPI,
        HintKind.EMAIL,
        HintKind.PHONE,
    )

    private fun identifierHit(hints: List<CandidateHint>): CandidateHint? {
        for (hint in hints) {
            if (hint.strength != HintStrength.STRONG) continue
            if (hint.kind !in CERTAIN_KINDS) continue
            return hint
        }
        return null
    }

    // -------------------------------------------------------------------------------------
    // Gate 2: field label
    // -------------------------------------------------------------------------------------

    /**
     * Closed list of captions whose presence means "I am a label, not a value."
     *
     * Sourced from [com.blackout.app.ocr.FieldLayout.LABEL_LEXICON] plus a few additions for
     * Indian ID forms that did not make the original list ("S/o", "D/o", "W/o", "C/o"). The
     * additions are intentional: these relationship markers mean the line below is a name.
     */
    private val LABEL_LEXICON: Set<String> = run {
        val base = setOf(
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
            "basic", "basic pay", "hra", "lta", "da", "pf", "epf", "esi", "esi no", "uan", "uan no",
            "special allowance", "conveyance", "medical allowance", "professional tax", "prof tax",
            "income tax", "bonus", "incentive", "overtime", "gratuity", "arrears",
            "earnings", "total earnings", "total deductions", "net payable", "net pay",
            "days worked", "lop", "pay period", "pay date", "mode of payment",
            "patient name", "patient id", "doctor", "hospital", "diagnosis", "uhid",
            "statement period", "period", "description", "particulars", "narration",
            "relationship manager", "nominee", "signature", "remarks", "status", "type",
            // Hindi captions that name a person
            "नाम", "पिता", "पति", "माता", "जन्म तिथि", "मोबाइल", "पता",
            // Indian "son of / daughter of / wife of / care of" markers
            "s/o", "d/o", "w/o", "c/o",
        )
        base
    }

    /**
     * Trailing colon + short, no digits, ≤4 words = explicit "I am a caption" marker.
     *
     * Same whitelist logic as [com.blackout.app.ocr.FieldLayout.looksLikeLabel], kept here so
     * the gate is self-contained.
     */
    private val DIGIT = Regex("""\d""")
    private val WHITESPACE = Regex("""\s+""")

    fun looksLikeLabel(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 34) return false
        if (text.contains('@')) return false

        val stripped = text.trimEnd(':', '-', '–').trim()
        if (stripped.isEmpty() || stripped.none { it.isLetter() }) return false

        if (stripped.lowercase() in LABEL_LEXICON) return true

        // Trailing colon is a caption marker. Still refuse digits and long phrases so
        // "Paid to: RAHUL MEHTA" or "Total: 4,200" do not sneak through.
        // Plain \s, no UNICODE_CHARACTER_CLASS: Android's ICU-backed engine throws on that flag
        // (see PiiBlocks.INLINE for the full account). Devanagari separates words with ordinary
        // U+0020, so ASCII \s splits it correctly anyway.
        return text.endsWith(':') &&
            !DIGIT.containsMatchIn(stripped) &&
            stripped.split(WHITESPACE).size <= 4
    }

    // -------------------------------------------------------------------------------------
    // Gate 3: caption → value pairing (with STANDALONE fallback)
    // -------------------------------------------------------------------------------------

    /**
     * Captions whose value identifies a person, place or account.
     *
     * Sourced from [PiiBlocks.SENSITIVE_STEMS] so the gate reuses the same fuzzy match. The
     * additions here are personal-name markers ("नाम", "पिता", "s/o", "d/o", "w/o") that the
     * original list had under "name" / "father" but are worth being explicit about, since
     * this gate is the one that runs on STANDALONE spans where nothing else will catch them.
     */
    private val SENSITIVE_STEMS = listOf(
        // Personal-name captions
        "name", "नाम", "holder", "nominee", "father", "पिता", "mother", "माता",
        "spouse", "husband", "पति", "wife", "guardian",
        // Son/daughter/wife/care-of markers (always followed by a personal name)
        "s/o", "d/o", "w/o", "c/o",
        // Identity / location / contact captions
        "dob", "date of birth", "birth", "जन्म तिथि", "age",
        "address", "addr", "पता",
        "mobile", "phone", "tel", "मोबाइल", "email", "e-mail",
        "account", "card", "customer id", "employee",
        "pan", "aadhaar", "aadhar", "passport", "voter",
        "ifsc", "micr", "iban", "upi", "vpa", "gstin",
        "patient", "uhid", "policy no",
        // Address-block anchors
        "po", "post", "vtc", "village", "town", "city",
        "district", "dist", "sub district", "subdistrict", "state", "street",
        "house", "flat", "landmark", "pin", "pincode", "pin code", "postal code",
    )

    /**
     * Caption on the previous or next line is sensitive.
     *
     * This is the STANDALONE-layout fallback. On a single-column PAN card, the holder's name
     * sits directly below "Name" and there is no label column for [com.blackout.app.ocr.FieldLayout]
     * to detect — every span stays STANDALONE. But "Name:" still sits one line above the name.
     *
     * The match uses the same fuzzy comparison as [PiiBlocks.fuzzyEquals] so "Narne" → "name" still
     * triggers when OCR mangles the caption.
     */
    private fun sensitiveCaptionNeighbour(
        ownText: String,
        previousLine: String?,
        nextLine: String?,
    ): String? {
        // The value line itself should not look like a caption (otherwise we are matching
        // a caption to its own caption). Refuse digits, trailing colon, and lexicon hits.
        if (ownText.contains(':')) return null
        if (Regex("""\d""").containsMatchIn(ownText)) return null
        if (looksLikeLabel(ownText)) return null

        for (neighbour in listOfNotNull(previousLine, nextLine)) {
            val caption = captionOnly(neighbour) ?: continue
            if (matchesAny(caption, SENSITIVE_STEMS)) return caption
        }
        return null
    }

    /**
     * Returns the caption half of a `Label: value` neighbour, or the bare neighbour when it
     * itself reads as a label (so "Name" on its own line above a value still triggers).
     *
     * Without this fallback, single-word captions like "Name" or "पिता" — common on PAN/Aadhaar
     * cards where every field has its own line — would never reach [matchesAny].
     */
    private fun captionOnly(neighbour: String): String? {
        val inline = PiiBlocks.inlineField(neighbour)
        if (inline != null) return inline.label
        if (looksLikeLabel(neighbour)) return neighbour.trim()
        return null
    }

    // -------------------------------------------------------------------------------------
    // Fuzzy match (kept local — PiiBlocks.matchesAny is private)
    // -------------------------------------------------------------------------------------

    private fun matchesAny(label: String, stems: List<String>): Boolean {
        val l = label.trim().lowercase().trim('.', ',', '-')
        if (l.isEmpty()) return false
        for (stem in stems) {
            if (l == stem) return true
            if (l.length > stem.length && l.contains(stem) &&
                (l.startsWith(stem) || l.endsWith(stem) || l.contains(" $stem"))
            ) return true
            if (fuzzyEquals(l, stem)) return true
        }
        return false
    }

    /** Same shape as PiiBlocks.fuzzyEquals — prefix check rescues OCR-mangled captions. */
    private fun fuzzyEquals(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 3) return false
        val tolerance = maxOf(1, minOf(a.length, b.length) / 3)
        if (levenshtein(a, b) <= tolerance) return true
        if (a.length >= 4 && b.length >= 4) {
            return levenshtein(a.take(4), b.take(4)) <= 1
        }
        return false
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous; previous = current; current = swap
        }
        return previous[b.length]
    }
}
