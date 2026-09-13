package com.blackout.app.intelligence

/**
 * Names of institutions, which are never the secret on a document.
 *
 * ## Why this exists
 *
 * Measured on Tejesh's own photographs, the workhorse blacked out the issuer of almost every
 * identity document it was shown:
 *
 * | document | hidden, wrongly |
 * |---|---|
 * | PAN card | `INCOME TAX DEPARTMENT`, `GOVT. OF INDIA` |
 * | passport | `REPUBLIC OF INDIA` and most of the request boilerplate |
 * | magazine | `INDIA`, `TODAY` - the masthead |
 *
 * On the PAN card that was 8 of 9 spans hidden. A redaction that covers the card's own title and
 * the department that issued it is not protecting anybody; it destroys the document while leaving
 * the holder no better off.
 *
 * [CpuDeterministicStage.isDocumentHeader] was supposed to catch these and does not, because it
 * gates on *position* - `rect.top` inside the top band of the page. That holds for a scanned
 * statement and fails for a photographed card, which is framed at an angle, cropped tight, or
 * upside down. An issuer's name is not PII wherever it happens to sit, so the test has to be
 * about the words.
 *
 * ## Two rules
 *
 * **A closed phrase list**, fuzzy-matched, because OCR mangles exactly these lines - the real
 * capture read `Govermnenit of India`. Reuses the same Levenshtein tolerance as [PiiBlocks].
 *
 * **An institution word plus no digits.** `DEPARTMENT`, `AUTHORITY`, `MINISTRY`, `CORPORATION`
 * and friends name organisations, not people. The no-digits condition is what keeps
 * `Bank A/c 50100123456789` out of it, and any span carrying a strong identifier is rejected by
 * the caller before this is ever consulted.
 *
 * ## What it must never do
 *
 * Return true for anything that identifies a *person*. The word list is deliberately institutional
 * only - no honorifics, no relationship markers - so `Father: BADAL MANDAL` cannot reach it.
 */
object PublicEntity {

    /**
     * Whole phrases that name an issuer. Matched fuzzily against the whole line.
     *
     * Kept to issuers and document titles seen on real Indian documents rather than padded out
     * with plausible-looking entries: every one of these was observed being wrongly hidden.
     */
    private val ISSUER_PHRASES = listOf(
        "government of india", "govt of india", "govt. of india",
        "republic of india", "bharat sarkar", "भारत सरकार",
        "income tax department", "unique identification authority of india",
        "unique identification authority", "election commission of india",
        "permanent account number", "permanent account number card",
        "ministry of external affairs", "passport office",
        "reserve bank of india", "आयकर विभाग", "भारत सरकार",
    )

    /**
     * Words that only appear in the name of an organisation.
     *
     * A line containing one of these and no digits is an institution, not a person.
     */
    private val INSTITUTION_WORDS = setOf(
        "department", "authority", "government", "govt", "republic", "commission",
        "ministry", "bureau", "corporation", "municipal", "municipality",
        "university", "college", "institute", "hospital", "clinic",
        "limited", "ltd", "pvt", "private", "llp", "inc", "gmbh",
        "bank", "insurance", "assurance", "trust", "foundation", "society",
        "board", "council", "federation", "association", "agency",
    )

    private val DIGIT = Regex("""\d""")
    private val WHITESPACE = Regex("""\s+""")

    /** True when [raw] names an institution rather than a person, place of residence or account. */
    fun isPublicEntity(raw: String): Boolean {
        val text = raw.trim().trimEnd('.', ',', ':', '-')
        if (text.length < MIN_CHARS || text.length > MAX_CHARS) return false
        if (text.none { it.isLetter() }) return false

        val lower = text.lowercase()
        if (ISSUER_PHRASES.any { fuzzyPhrase(lower, it) }) return true

        // An organisation's name carries no account number, so digits disqualify. This is what
        // separates "STATE BANK OF INDIA" from "State Bank A/c 50100123456789".
        if (DIGIT.containsMatchIn(text)) return false

        val words = lower.split(WHITESPACE).map { it.trim('.', ',', '(', ')', '&', '-') }
        if (words.size > MAX_WORDS) return false
        return words.any { it in INSTITUTION_WORDS }
    }

    /**
     * Fuzzy whole-line comparison against a known phrase.
     *
     * Tolerance scales with length because OCR errors accumulate: `Govermnenit of India` is four
     * edits from `government of india` across 20 characters.
     */
    private fun fuzzyPhrase(line: String, phrase: String): Boolean {
        if (line == phrase) return true
        if (kotlin.math.abs(line.length - phrase.length) > phrase.length / 3 + 2) return false
        val tolerance = maxOf(2, phrase.length / 5)
        return levenshtein(line, phrase) <= tolerance
    }

    /** Shorter than this and a fuzzy match is meaningless; longer and it is prose, not a name. */
    private const val MIN_CHARS = 4
    private const val MAX_CHARS = 60
    private const val MAX_WORDS = 8

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
