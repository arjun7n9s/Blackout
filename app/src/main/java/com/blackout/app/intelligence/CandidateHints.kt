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

    /** Characters a statement uses to blank out the middle of a card number. */
    private const val MASK_CHARS = "*xX•×#"

    /**
     * A part-masked card: at least two groups, mixing digits and mask characters.
     *
     * Matches "5012 **** 1234", "XXXX XXXX XXXX 4242", "•••• 4242". The digit/mask counts are
     * checked at the call site rather than in the pattern, because doing it here would need
     * lookaheads that make the expression unreadable for no gain.
     */
    private val MASKED_CARD = Regex(
        """(?<![A-Za-z0-9])[0-9*xX•×#]{2,6}(?:[\s\-][0-9*xX•×#]{2,6}){1,5}(?![A-Za-z0-9])"""
    )

    /**
     * `A/c 50100123456789`, `IFSC HZBN0001429`, `Account No 50100123456789`.
     *
     * Two things here are load-bearing, and the pattern was wrong in both directions without them.
     *
     * **The filler word.** `Account No 50100123456789` matched *nothing*: after the keyword the
     * pattern demanded the value immediately, and could not step over `No`. Real account numbers
     * were being missed on exactly the phrasing statements use most.
     *
     * **The digit requirement**, enforced in [detect]. `PERMANENT ACCOUNT NUMBER CARD` captured
     * the word `NUMBER` as an account number and hid the PAN card's own title; a bare
     * `Account Number` label captured `Number` and hid the caption. An account number that
     * contains no digit does not exist.
     */
    private val ACCOUNT = Regex(
        """(?i)\b(?:a/?c|acct|account|ifsc|iban)\b[\s:.#\-]*(?:(?:no|num|number)\b[\s:.#\-]*)?([A-Z0-9]{6,24})"""
    )

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

        // A card that has already been masked is still a card.
        //
        // [CARD] needs an unbroken run of 13-19 digits, so "Card 5012 **** 1234 POS" - the way a
        // statement actually prints one - matched nothing at all and went to the models, which
        // left it visible. Masking hides the middle digits, never the fact that this is a card
        // number, and the surviving digits are the ones that identify the account.
        //
        // STRONG without a checksum: there are not enough digits left to run Luhn over, and the
        // mask itself is the evidence.
        MASKED_CARD.findAll(text).forEach { m ->
            val value = m.value.trim()
            val digits = value.count(Char::isDigit)
            val masked = value.count { it in MASK_CHARS }
            // Both halves must be present, or this is just a number or just a row of asterisks.
            if (digits < 4 || masked < 2) return@forEach
            if (out.any { it.kind == HintKind.CARD && it.matched.contains(value) }) return@forEach
            out += CandidateHint(HintKind.CARD, value, HintStrength.STRONG)
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
     * Promotes a WEAK hint to STRONG when its own line, or the line above, names what it is.
     *
     * This is what separates a bare "14/03/1988" (someone's DOB) from a form's print date: the
     * digits are identical, only the caption disambiguates them.
     *
     * MONEY works the same way and for the same reason. An amount is usually the *point* of a
     * document - an invoice is nothing but amounts - so hiding every one of them is how `C-008`
     * turned into a black slab. But a figure captioned "Closing Balance" or "Net Salary" is a
     * fact about a person's finances rather than about the transaction, and that caption is
     * exactly the evidence needed to tell the two apart. So a balance or salary is hidden and an
     * invoice line item is not, without either of them going near a model.
     *
     * @param line the span's own text - a caption and its value often share one OCR line.
     */
    fun promoteByNeighbour(
        hints: List<CandidateHint>,
        previousLine: String?,
        line: String? = null,
    ): List<CandidateHint> {
        val context = listOfNotNull(previousLine, line).joinToString("\n")
        if (context.isBlank()) return hints

        val dob = hints.any { it.kind == HintKind.DATE } && DOB_LABEL.containsMatchIn(context)
        val balance = hints.any { it.kind == HintKind.MONEY } && MONEY_LABEL.containsMatchIn(context)
        if (!dob && !balance) return hints

        return hints.map {
            when {
                dob && it.kind == HintKind.DATE -> it.copy(strength = HintStrength.STRONG)
                balance && it.kind == HintKind.MONEY -> it.copy(strength = HintStrength.STRONG)
                else -> it
            }
        }
    }

    private val DOB_LABEL = Regex("""(?i)\b(d\.?o\.?b|date\s+of\s+birth|birth\s*date|born|जन्म)\b""")

    /**
     * Captions that make an amount personal rather than transactional.
     *
     * Deliberately excludes "total", "amount", "subtotal", "price", "due" and the like - those
     * caption an invoice's own figures, which stay readable.
     */
    private val MONEY_LABEL = Regex(
        """(?i)\b(?:""" +
            """(?:closing|opening|available|current|account|avl)\s+bal(?:ance)?|bal(?:ance)?\s*[:.]|""" +
            """net\s+(?:pay|salary)|gross\s+(?:pay|salary)|take[\s\-]?home|""" +
            """salary|wages|income|ctc|credit\s+limit""" +
            """)\b"""
    )

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
