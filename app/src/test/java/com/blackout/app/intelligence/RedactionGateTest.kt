package com.blackout.app.intelligence

import com.blackout.app.ocr.SpanRect
import com.blackout.app.ocr.SpanRole
import com.blackout.app.ocr.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins each gate of [RedactionGate] against the failure modes the live iQOO 15 tests surfaced.
 *
 * Naming follows the test fixtures in `C-Outputs/label-bugs.jsonl` and the four real-document
 * tests run on device (1953 passport, Aadhaar Parvati, 6-Aadhaar pile, India Today collage).
 */
class RedactionGateTest {

    // ----- helpers --------------------------------------------------------------------

    private fun span(
        id: Int,
        text: String,
        role: SpanRole = SpanRole.STANDALONE,
        labelText: String? = null,
    ): TextSpan = TextSpan(
        id = id,
        text = text,
        rect = SpanRect(0, id * 20, 200, id * 20 + 18),
        confidence = 0.9f,
        blockIndex = 0,
        lineIndex = id,
        role = role,
        labelText = labelText,
    )

    private fun hints(text: String): List<CandidateHint> = CandidateHints.detect(text)

    // ----- Gate 1: identifier pattern (redact long codes, not dates) -------------------

    @Test fun `gate1 hides PAN`() {
        val v = RedactionGate.evaluate(span(0, "BDFPN2201L"), hints("BDFPN2201L"), null, null)
        assertEquals(Action.HIDE, v.action)
        assertEquals(RedactionGate.Gate.IDENTIFIER, v.gate)
        assertTrue(v.reason.contains("pan"))
    }

    @Test fun `gate1 hides Aadhaar 12-digit`() {
        val v = RedactionGate.evaluate(span(0, "6438 5942 7040"), hints("6438 5942 7040"), null, null)
        assertEquals(Action.HIDE, v.action)
        assertTrue(v.reason.contains("aadhaar"))
    }

    @Test fun `gate1 hides IFSC`() {
        val v = RedactionGate.evaluate(span(0, "HZBN0001429"), hints("HZBN0001429"), null, null)
        assertEquals(Action.HIDE, v.action)
        assertTrue(v.reason.contains("ifsc"))
    }

    @Test fun `gate1 hides UPI handle`() {
        val v = RedactionGate.evaluate(span(0, "arjun@ybl"), hints("arjun@ybl"), null, null)
        assertEquals(Action.HIDE, v.action)
        assertTrue(v.reason.contains("upi"))
    }

    @Test fun `gate1 hides email`() {
        val v = RedactionGate.evaluate(span(0, "priya@example.com"), hints("priya@example.com"), null, null)
        assertEquals(Action.HIDE, v.action)
        assertTrue(v.reason.contains("email"))
    }

    /**
     * The user said: redact long codes, NOT dates. A bare date without a sensitive caption is
     * a print date or transaction date — stays visible. This is the regression pin.
     */
    @Test fun `gate1 leaves bare date visible`() {
        val v = RedactionGate.evaluate(span(0, "19/08/2026"), hints("19/08/2026"), null, null)
        // Date hint is WEAK (no caption) — Gate 1 returns UNSURE (gate ID NONE), so the date
        // is visible by default. Verified on the live test where bare dates stayed visible
        // and captioned DOBs went hidden.
        assertEquals(RedactionGate.Gate.NONE, v.gate)
        assertEquals(Action.UNSURE, v.action)
    }

    @Test fun `gate1 leaves bare amount visible`() {
        val v = RedactionGate.evaluate(span(0, "₹ 4,200.00"), hints("₹ 4,200.00"), null, null)
        assertEquals(RedactionGate.Gate.NONE, v.gate)
    }

    // ----- Gate 2: field label (the label decides — labels stay visible) ---------------

    @Test fun `gate2 keeps Name label`() {
        val v = RedactionGate.evaluate(span(0, "Name"), emptyList(), null, null)
        assertEquals(Action.KEEP, v.action)
        assertEquals(RedactionGate.Gate.LABEL, v.gate)
    }

    @Test fun `gate2 keeps Hindi name caption`() {
        val v = RedactionGate.evaluate(span(0, "नाम"), emptyList(), null, null)
        assertEquals(Action.KEEP, v.action)
        assertEquals(RedactionGate.Gate.LABEL, v.gate)
    }

    @Test fun `gate2 keeps father caption`() {
        val v = RedactionGate.evaluate(span(0, "Father's Name"), emptyList(), null, null)
        assertEquals(Action.KEEP, v.action)
    }

    @Test fun `gate2 keeps pan caption`() {
        val v = RedactionGate.evaluate(span(0, "PAN"), emptyList(), null, null)
        assertEquals(Action.KEEP, v.action)
    }

    @Test fun `gate2 keeps trailing-colon caption`() {
        // "DOB:" with nothing after — bare caption marker.
        val v = RedactionGate.evaluate(span(0, "DOB:"), emptyList(), null, null)
        assertEquals(Action.KEEP, v.action)
        assertEquals(RedactionGate.Gate.LABEL, v.gate)
    }

    /**
     * The bug we are fixing: a person name printed in the left column ("Priya Ramachandran"
     * on a passport or PAN) must NOT be classified as a label. Gate 2 only fires on the
     * closed LABEL_LEXICON, so a personal name falls through to Gate 3 (or to the model).
     */
    @Test fun `gate2 does not treat personal name as label`() {
        val v = RedactionGate.evaluate(span(0, "Priya Ramachandran"), emptyList(), null, null)
        assertEquals(RedactionGate.Gate.NONE, v.gate)
    }

    // ----- Gate 3: caption → value pairing with STANDALONE fallback --------------------

    /**
     * The headline fix. On a single-column PAN card / passport / Aadhaar, FieldLayout cannot
     * detect a label column, so every span stays STANDALONE. Gate 3 used to skip those
     * (the rule only fired for SpanRole.VALUE). It now fires for STANDALONE too — when the
     * previous or next line carries a sensitive caption.
     */
    @Test fun `gate3 hides STANDALONE name when Name caption is above`() {
        val v = RedactionGate.evaluate(
            span = span(1, "Priya Ramachandran"),
            hints = emptyList(),
            previousLine = "Name",
            nextLine = null,
        )
        assertEquals(Action.HIDE, v.action)
        assertEquals(RedactionGate.Gate.CAPTION_PAIR, v.gate)
    }

    @Test fun `gate3 hides STANDALONE Devanagari name when Hindi caption is above`() {
        val v = RedactionGate.evaluate(
            span = span(1, "परवती कुमारी"),
            hints = emptyList(),
            previousLine = "नाम",
            nextLine = null,
        )
        assertEquals(Action.HIDE, v.action)
    }

    /**
     * The 1953 passport test missed "AROBINDA ROY" because no "Name" caption survived OCR.
     * This test confirms Gate 3 fires when the caption is on the SAME line (the value's own
     * line) as `Label: value` shape — the inline rule.
     */
    @Test fun `gate3 hides inline Label colon value`() {
        val v = RedactionGate.evaluate(
            span = span(0, "DOB: 09/03/2005"),
            hints = emptyList(),
            previousLine = null,
            nextLine = null,
        )
        assertEquals(Action.HIDE, v.action)
        assertEquals(RedactionGate.Gate.CAPTION_PAIR, v.gate)
    }

    /**
     * The 6-Aadhaar pile missed father's names. With the previous line carrying the caption
     * "Father", Gate 3 fires.
     */
    @Test fun `gate3 hides STANDALONE fathers name when Father caption is above`() {
        val v = RedactionGate.evaluate(
            span = span(1, "BADAL MANDAL"),
            hints = emptyList(),
            previousLine = "Father : BADAL MANDAL",  // OCR may merge; caption is first 6 chars
            nextLine = null,
        )
        // The merged line parses as inline `Father :` → value, so Gate 3 fires.
        assertEquals(Action.HIDE, v.action)
    }

    @Test fun `gate3 does NOT fire on bare company name with non-sensitive neighbour`() {
        // "ACME Corp" with no sensitive caption around it — must fall through to the model.
        val v = RedactionGate.evaluate(
            span = span(0, "ACME Corp"),
            hints = emptyList(),
            previousLine = "Total Earnings",
            nextLine = null,
        )
        assertEquals(RedactionGate.Gate.NONE, v.gate)
    }

    @Test fun `gate3 does NOT fire when value itself looks like a label`() {
        // Refuse double-matching: a caption value (e.g. "Name" itself) should not match itself
        // as a sensitive-value caption pairing.
        val v = RedactionGate.evaluate(
            span = span(0, "Name"),
            hints = emptyList(),
            previousLine = "PAN",
            nextLine = null,
        )
        // Gate 2 catches this first (it IS a label) — KEEP wins.
        assertEquals(Action.KEEP, v.action)
        assertEquals(RedactionGate.Gate.LABEL, v.gate)
    }

    /**
     * Fuzzy caption match. OCR mangled "District" into "Disria" on the Aadhaar fixture.
     * Gate 3 must still catch the value below.
     */
    @Test fun `gate3 fires on fuzzy-matched caption (Disria vs District)`() {
        val v = RedactionGate.evaluate(
            span = span(1, "Gurugram"),
            hints = emptyList(),
            previousLine = "Disria:",
            nextLine = null,
        )
        assertEquals(Action.HIDE, v.action)
        assertEquals(RedactionGate.Gate.CAPTION_PAIR, v.gate)
    }

    // ----- Order of evaluation ----------------------------------------------------------

    /**
     * Gate 1 wins over Gate 3. A PAN number next to a "PAN:" caption is still HIDE by Gate 1
     * (long code), not by Gate 3 (caption pairing). The reason string names Gate 1.
     */
    @Test fun `gate1 wins over gate3 for a PAN next to its caption`() {
        val v = RedactionGate.evaluate(
            span = span(0, "BDFPN2201L"),
            hints = hints("BDFPN2201L"),
            previousLine = "PAN:",
            nextLine = null,
        )
        assertEquals(Action.HIDE, v.action)
        assertEquals(RedactionGate.Gate.IDENTIFIER, v.gate)
    }

    /**
     * Gate 2 wins over Gate 3. A field label sits above its value; the label itself must stay
     * visible even if its neighbour is sensitive.
     */
    @Test fun `gate2 wins over gate3 for the caption itself`() {
        val v = RedactionGate.evaluate(
            span = span(0, "Name"),
            hints = emptyList(),
            previousLine = "Account Number",
            nextLine = "Priya Ramachandran",
        )
        assertEquals(Action.KEEP, v.action)
        assertEquals(RedactionGate.Gate.LABEL, v.gate)
    }

    // ----- STANDALONE fallback sanity ---------------------------------------------------

    /**
     * Regression pin for the original failure mode: on a multi-doc collage (India Today 4-docs),
     * FieldLayout returned Result.None, every span stayed STANDALONE, and Gate 3 used to be
     * skipped because the rule gated on SpanRole.VALUE. Now Gate 3 fires on STANDALONE when
     * a sensitive caption is the previous line. This is what closed the 4-of-18 hide count
     * up toward a usable demo.
     */
    @Test fun `gate3 fires on STANDALONE for multi-doc collages`() {
        val v = RedactionGate.evaluate(
            span = span(0, "Buddhavarapu Venkataeswara Rao"),
            hints = emptyList(),
            previousLine = "Name",
            nextLine = null,
        )
        assertEquals(Action.HIDE, v.action)
        assertEquals(RedactionGate.Gate.CAPTION_PAIR, v.gate)
    }
}
