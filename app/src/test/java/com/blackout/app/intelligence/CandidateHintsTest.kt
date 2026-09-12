package com.blackout.app.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateHintsTest {

    private fun kinds(text: String) = CandidateHints.detect(text).map { it.kind }.toSet()

    @Test
    fun `detects email and pan`() {
        assertTrue(HintKind.EMAIL in kinds("write to arjun@example.com"))
        assertTrue(HintKind.PAN in kinds("PAN ABCDE1234F"))
    }

    @Test
    fun `detects indian phone and aadhaar shapes`() {
        assertTrue(HintKind.PHONE in kinds("call +91 98765 43210"))
        assertTrue(HintKind.AADHAAR in kinds("2345 6789 0123"))
    }

    @Test
    fun `card with valid luhn is strong`() {
        val hint = CandidateHints.detect("4539 1488 0343 6467")
            .first { it.kind == HintKind.CARD }
        assertEquals(HintStrength.STRONG, hint.strength)
    }

    @Test
    fun `card that fails luhn still fires but only weakly`() {
        // A single OCR digit error breaks Luhn. Gating on the checksum would mean missing
        // exactly the mis-read cards this app exists to catch, so shape fires the hint and the
        // checksum only sets confidence.
        val hint = CandidateHints.detect("4539 1488 0343 6468")
            .firstOrNull { it.kind == HintKind.CARD }
        assertEquals(HintStrength.WEAK, hint?.strength)
    }

    @Test
    fun `dates are weak until a dob label precedes them`() {
        val raw = CandidateHints.detect("14/03/1988")
        assertEquals(HintStrength.WEAK, raw.first { it.kind == HintKind.DATE }.strength)

        val promoted = CandidateHints.promoteByNeighbour(raw, "Date of Birth")
        assertEquals(HintStrength.STRONG, promoted.first { it.kind == HintKind.DATE }.strength)
    }

    @Test
    fun `generic label produces nothing`() {
        assertTrue(CandidateHints.detect("Full Name").isEmpty())
        assertTrue(CandidateHints.detect("GOVERNMENT OF INDIA").isEmpty())
    }

    @Test
    fun `account captions are not account numbers`() {
        // "Holder" and "Number" are 6 letters, which the capture group would otherwise eat.
        assertTrue(CandidateHints.detect("Account Holder").isEmpty())
        assertTrue(CandidateHints.detect("Account Number").isEmpty())
        assertTrue(HintKind.ACCOUNT in kinds("Account 501234567890"))
        assertTrue(HintKind.ACCOUNT in kinds("IFSC MERI0004471"))
    }
}
