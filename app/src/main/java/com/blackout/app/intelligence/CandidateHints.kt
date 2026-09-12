package com.blackout.app.intelligence

enum class HintKind(val label: String) {
    EMAIL("email"),
    PHONE("phone"),
    AADHAAR("aadhaar"),
    PAN("pan"),
    CARD("card"),
    ACCOUNT("account-no"),
    IFSC("ifsc"),
    UPI("upi-id"),
    PASSPORT("passport"),
    IP("ip"),
    DATE("date"),
    URL("url"),
    PIN("postcode"),
    MONEY("amount"),
}

/**
 * WEAK hints are context only. STRONG hints escalate a span to the referee even when the
 * workhorse said "keep".
 */
enum class HintStrength { WEAK, STRONG }

data class CandidateHint(
    val kind: HintKind,
    val matched: String,
    val strength: HintStrength = HintStrength.STRONG,
)

/**
 * Cheap regex pass over OCR text.
 *
 * These are **hints only**. They are fed to the model as extra signal and shown in the debug
 * panel, but per the architecture they are never the sole decider - except in the explicitly
 * DEGRADED path where no model file is available, which the UI labels as such.
 *
 * Patterns lean towards Indian document formats (Aadhaar, PAN, +91 phone, 6-digit PIN) because
 * that is the primary target, while keeping the generic international ones.
 */
object CandidateHints {

    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

    // +91 98765 43210 / 098765-43210 / (022) 2345 6789 / plain 10-digit
    private val PHONE = Regex("""(?<!\d)(?:\+?\d{1,3}[\s\-.]?)?(?:\(\d{2,5}\)[\s\-.]?)?\d{3,5}[\s\-.]?\d{3,5}(?:[\s\-.]?\d{2,4})?(?!\d)""")

    // 12 digits, conventionally grouped 4-4-4, never starting 0 or 1
    private val AADHAAR = Regex("""(?<!\d)[2-9]\d{3}[\s\-]?\d{4}[\s\-]?\d{4}(?!\d)""")

    private val PAN = Regex("""(?<![A-Z0-9])[A-Z]{5}\d{4}[A-Z](?![A-Z0-9])""")

    private val PASSPORT = Regex("""(?<![A-Z0-9])[A-PR-WY][0-9]{7}(?![A-Z0-9])""")

    // 13-19 digits with optional separators; validated with Luhn before it counts
    private val CARD = Regex("""(?<!\d)(?:\d[ \-]?){12,18}\d(?!\d)""")

    private val ACCOUNT = Regex("""(?i)\b(?:a/?c|acct|account|ifsc|iban)\b[\s:.#\-]*([A-Z0-9]{6,24})""")

    // RBI IFSC: 4-letter bank code, a reserved 0, 6-char branch code. Printed bare next to an
    // "IFSC" caption, so the ACCOUNT pattern above (which needs the keyword in the same span)
    // misses it - that is exactly the C-001 `HZBN0001429` leak.
    private val IFSC = Regex("""(?<![A-Z0-9])[A-Z]{4}0[A-Z0-9]{6}(?![A-Z0-9])""")

    // UPI VPA: handle@psp with NO dot in the PSP, which is what separates `arjun@ybl` from an
    // email address. Emails are matched first and claim their own text.
    private val UPI = Regex("""(?<![A-Za-z0-9._%+\-])[A-Za-z0-9][A-Za-z0-9._\-]{1,}@[A-Za-z]{2,}(?![A-Za-z0-9.\-])""")

    private val IP = Regex("""(?<![\d.])(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?![\d.])""")

    private val DATE = Regex("""(?<![\d/\-.])(?:[0-3]?\d[/\-.][0-1]?\d[/\-.](?:\d{2}|\d{4})|(?:\d{4})[/\-.][0-1]?\d[/\-.][0-3]?\d)(?![\d/\-.])""")

    private val URL = Regex("""(?i)\b(?:https?://|www\.)[^\s,;]{4,}""")

    private val PIN = Regex("""(?<!\d)[1-9]\d{5}(?!\d)""")

    private val MONEY = Regex("""(?i)(?:₹|rs\.?|inr|usd|\$|€|£)\s?\d[\d,]*(?:\.\d{1,2})?""")

    fun detect(text: String): List<CandidateHint> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<CandidateHint>()

        EMAIL.findAll(text).forEach { out += CandidateHint(HintKind.EMAIL, it.value) }
        UPI.findAll(text).forEach { m ->
            if (out.none { it.kind == HintKind.EMAIL && it.matched.contains(m.value) }) {
                out += CandidateHint(HintKind.UPI, m.value)
            }
        }
        IFSC.findAll(text).forEach { out += CandidateHint(HintKind.IFSC, it.value) }
        URL.findAll(text).forEach { out += CandidateHint(HintKind.URL, it.value) }
        IP.findAll(text).forEach { out += CandidateHint(HintKind.IP, it.value) }
        PAN.findAll(text).forEach { out += CandidateHint(HintKind.PAN, it.value) }
        PASSPORT.findAll(text).forEach { out += CandidateHint(HintKind.PASSPORT, it.value) }
        AADHAAR.findAll(text).forEach { out += CandidateHint(HintKind.AADHAAR, it.value) }
        DATE.findAll(text).forEach { out += CandidateHint(HintKind.DATE, it.value, HintStrength.WEAK) }
        MONEY.findAll(text).forEach { out += CandidateHint(HintKind.MONEY, it.value, HintStrength.WEAK) }
        ACCOUNT.findAll(text).forEach {
            // The capture is the token after "account"/"IFSC"/… . Without a digit it is almost
            // always the rest of a field caption ("Account Holder", "Account Number") — and a
            // STRONG hit on those is what made FieldLayout refuse them LABEL, so the models
            // blacked them out again. Real account/IFSC/IBAN values contain a digit.
            val token = it.groupValues.getOrNull(1) ?: it.value
            if (token.any(Char::isDigit)) {
                out += CandidateHint(HintKind.ACCOUNT, token)
            }
        }

        // Luhn sets CONFIDENCE; it must never gate the hint.
        //
        // Every single-digit corruption of a valid Luhn number fails Luhn. A photographed card
        // with one OCR digit error would therefore always fail - so gating on the checksum would
        // guarantee a miss on exactly the inputs this app exists to protect. Shape fires the
        // hint; the checksum only decides how loudly.
        CARD.findAll(text).forEach { m ->
            val digits = m.value.filter(Char::isDigit)
            if (digits.length in 13..19) {
                out += CandidateHint(
                    kind = HintKind.CARD,
                    matched = m.value.trim(),
                    strength = if (luhnValid(digits)) HintStrength.STRONG else HintStrength.WEAK,
                )
            }
        }

        // Phone last, and only where nothing stronger already claimed the same digits.
        val claimed = out.mapTo(mutableSetOf()) { it.matched.filter(Char::isDigit) }
            .filter { it.isNotEmpty() }
        PHONE.findAll(text).forEach { m ->
            val digits = m.value.filter(Char::isDigit)
            if (digits.length !in 7..13) return@forEach
            if (claimed.any { it.contains(digits) || digits.contains(it) }) return@forEach
            out += CandidateHint(HintKind.PHONE, m.value.trim())
        }

        // 6-digit PIN, again only if unclaimed.
        PIN.findAll(text).forEach { m ->
            if (out.none { it.matched.contains(m.value) }) {
                out += CandidateHint(HintKind.PIN, m.value, HintStrength.WEAK)
            }
        }

        return out.distinctBy { it.kind to it.matched }
    }

    /**
     * Promotes a WEAK DATE to STRONG when the preceding line looks like a date-of-birth label.
     *
     * This is what separates a bare "14/03/1988" (someone's DOB) from a form's print date: the
     * digits are identical, only the neighbour disambiguates them.
     */
    fun promoteByNeighbour(hints: List<CandidateHint>, previousLine: String?): List<CandidateHint> {
        if (previousLine == null || hints.none { it.kind == HintKind.DATE }) return hints
        if (!DOB_LABEL.containsMatchIn(previousLine)) return hints
        return hints.map {
            if (it.kind == HintKind.DATE) it.copy(strength = HintStrength.STRONG) else it
        }
    }

    private val DOB_LABEL = Regex("""(?i)\b(d\.?o\.?b|date\s+of\s+birth|birth\s*date|born|जन्म)\b""")

    fun hasStrong(hints: List<CandidateHint>): Boolean =
        hints.any { it.strength == HintStrength.STRONG }

    private fun luhnValid(digits: String): Boolean {
        var sum = 0
        var alternate = false
        for (i in digits.lastIndex downTo 0) {
            var n = digits[i] - '0'
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0
    }
}
