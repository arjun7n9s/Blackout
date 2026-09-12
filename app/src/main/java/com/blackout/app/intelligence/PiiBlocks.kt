package com.blackout.app.intelligence

import com.blackout.app.ocr.TextSpan
import kotlin.math.max
import kotlin.math.min

/**
 * Structural PII detection for documents where the label and its value share one OCR line, and
 * for address blocks that span several lines.
 *
 * ## The leak this closes
 *
 * Measured on a real Aadhaar photograph. OCR returned:
 *
 * ```
 * 2: Basart Ra              <- the holder's name
 * 3: CO: Aasdhar Cmrd
 * 4: VIC Flet No 25
 * 5: PO: Grugram
 * 6: Sub Disric: Grugram
 * 7: Disria: Grugran
 * 8: State: New Delhi
 * 9: PIN Code: 110042.
 * ```
 *
 * Every one of those stayed visible. The regexes miss them because there is no number to match;
 * both models miss them because the characters are mangled - "Basant Raj" came out "Basart Ra",
 * "Gurugram" as "Grugram", "District" as "Disria". Making inference faster cannot fix that, and
 * asking a 0.6B to recognise corrupted text is not a plan.
 *
 * But the *structure* survives the corruption. `CO:`, `PO:`, `State:`, `PIN Code:` are still
 * recognisable, they still appear in a contiguous run, and on every Indian ID card the holder's
 * name sits directly above that run. So we hide by position, which is immune to the OCR damage.
 *
 * ## Two rules
 *
 * **Inline fields.** A span shaped `Label: value` is a *value* even though it also contains its
 * caption. `Date of Birth:` with nothing after it is a bare label and stays visible;
 * `DOB : 09/03/2005` does not. The whole span is hidden, because label and value share one box
 * and cannot be separated without word-level geometry.
 *
 * **Address blocks.** Two or more address anchors in a run mean everything between them is
 * address - including lines like `VIC Flet No 25` that carry no anchor of their own - plus the
 * single line above, which is where the name lives.
 *
 * Label matching is fuzzy on purpose: `Disria` has to match `district`. Exact lexicon lookup is
 * what let these through in the first place.
 */
object PiiBlocks {

    data class InlineField(val label: String, val value: String)

    /** At least two anchors before we believe a run of lines is really an address. */
    private const val MIN_ADDRESS_ANCHORS = 2

    /** `Label: value` - caption at most four words, then a colon, then something. */
    private val INLINE = Regex("""^\s*([A-Za-z][A-Za-z ./'\-]{0,28}?)\s*[:：]\s*(.+)$""")

    /** Captions whose value identifies a person, place or account. */
    private val SENSITIVE_STEMS = listOf(
        "c/o", "co", "s/o", "so", "d/o", "w/o",
        "address", "addr", "po", "post", "vtc", "village", "town", "city",
        "district", "dist", "sub district", "subdistrict", "state", "street",
        "house", "flat", "landmark", "pin", "pincode", "pin code", "postal code",
        "name", "father", "mother", "husband", "guardian", "holder",
        "dob", "date of birth", "birth", "age",
        "mobile", "phone", "tel", "email", "e-mail",
        "account", "card", "customer id", "employee",
    )

    /** The subset that specifically means "this is part of a postal address". */
    private val ADDRESS_STEMS = setOf(
        "c/o", "co", "s/o", "so", "d/o", "w/o",
        "address", "addr", "po", "post", "vtc", "village", "town", "city",
        "district", "dist", "sub district", "subdistrict", "state", "street",
        "house", "flat", "landmark", "pin", "pincode", "pin code", "postal code",
    )

    private val DIGIT = Regex("""\d""")

    /** Splits `Label: value`, or null when the span is not that shape. */
    fun inlineField(text: String): InlineField? {
        val m = INLINE.find(text.trim()) ?: return null
        val label = m.groupValues[1].trim()
        val value = m.groupValues[2].trim()
        if (label.isEmpty() || value.isEmpty()) return null
        if (label.split(Regex("\\s+")).size > 4) return null
        // A caption is letters; digits before the colon means this is content, e.g. "12:30".
        if (DIGIT.containsMatchIn(label)) return null
        return InlineField(label, value)
    }

    /** True when [label] names a field whose value is personal. Fuzzy - OCR mangles captions. */
    fun isSensitiveLabel(label: String): Boolean = matchesAny(label, SENSITIVE_STEMS)

    fun isAddressLabel(label: String): Boolean = matchesAny(label, ADDRESS_STEMS.toList())

    /**
     * Span ids that belong to a postal address block, including the name line above it.
     *
     * Requires [MIN_ADDRESS_ANCHORS] anchors so that a lone "State:" on an invoice does not drag
     * its neighbours in.
     */
    fun addressBlock(spans: List<TextSpan>): Set<Int> {
        if (spans.size < 2) return emptySet()

        val anchorPositions = spans.mapIndexedNotNull { index, span ->
            val field = inlineField(span.text) ?: return@mapIndexedNotNull null
            index.takeIf { isAddressLabel(field.label) }
        }
        if (anchorPositions.size < MIN_ADDRESS_ANCHORS) return emptySet()

        val first = anchorPositions.min()
        val last = anchorPositions.max()
        val out = LinkedHashSet<Int>()

        // Everything between the outermost anchors is address, anchor or not: that is how
        // "VIC Flet No 25" gets covered.
        for (i in first..last) out += spans[i].id

        // One line above the block is the holder's name on every Indian ID card. Only a bare,
        // digit-free, short line qualifies, so a heading or a rule does not get eaten.
        val above = spans.getOrNull(first - 1)
        if (above != null && isNameLike(above.text)) out += above.id

        return out
    }

    /** A bare personal-name line: a few words, letters only, no caption of its own. */
    fun isNameLike(text: String): Boolean {
        val t = text.trim()
        if (t.length !in 3..40) return false
        if (DIGIT.containsMatchIn(t)) return false
        if (t.contains(':')) return false
        val words = t.split(Regex("\\s+"))
        if (words.size !in 1..4) return false
        return t.count { it.isLetter() } >= t.length / 2
    }

    // -------------------------------------------------------------------------------------

    private fun matchesAny(label: String, stems: List<String>): Boolean {
        val l = label.trim().lowercase().trim('.', ',', '-')
        if (l.isEmpty()) return false
        for (stem in stems) {
            if (l == stem) return true
            // Whole-word containment, so "registered mobile" hits "mobile".
            if (l.length > stem.length && l.contains(stem) &&
                (l.startsWith(stem) || l.endsWith(stem) || l.contains(" $stem"))
            ) return true
            if (fuzzyEquals(l, stem)) return true
        }
        return false
    }

    /**
     * Edit-distance match sized to the word, plus a first-four-characters check.
     *
     * The prefix check is what rescues `Disria` -> `district`: OCR errors are mostly
     * substitutions, so the head of the word survives even when the tail does not, and a full
     * distance of 3 over 8 characters would otherwise be rejected.
     */
    private fun fuzzyEquals(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 3) return false
        val tolerance = max(1, min(a.length, b.length) / 3)
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
