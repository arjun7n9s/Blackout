package com.blackout.app.intelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every string here was observed on one of Tejesh's own photographs. The "must keep" ones were
 * all being blacked out; on the PAN card that was 8 of 9 spans.
 */
class PublicEntityTest {

    @Test
    fun `issuers seen on real documents are kept`() {
        listOf(
            "INCOME TAX DEPARTMENT",
            "GOVT. OF INDIA",
            "GOVERNMENT OF INDIA",
            "REPUBLIC OF INDIA",
            "PERMANENT ACCOUNT NUMBER CARD",
            "Unique Identification Authority of India",
        ).forEach { assertTrue(it, PublicEntity.isPublicEntity(it)) }
    }

    @Test
    fun `OCR mangling still matches the issuer`() {
        // Verbatim from the device. An exact lexicon is what let this through.
        assertTrue(PublicEntity.isPublicEntity("Govermnenit of India"))
        assertTrue(PublicEntity.isPublicEntity("GOVERNMENI OF INDIA"))
    }

    @Test
    fun `an institution word with no digits is an organisation`() {
        listOf(
            "HORIZON BANK",
            "NORTHWIND TECHNOLOGIES PVT LTD",
            "Apollo Hospital",
            "Election Commission",
        ).forEach { assertTrue(it, PublicEntity.isPublicEntity(it)) }
    }

    @Test
    fun `a person is never a public entity`() {
        listOf(
            "Priya Ramachandran",
            "Father: BADAL MANDAL",
            "Parwati Kumari",
            "Buddhavarapu Venkateswara Rao",
            "AMIT KUMAR",
            "Sunil Kapoor",
        ).forEach { assertFalse(it, PublicEntity.isPublicEntity(it)) }
    }

    @Test
    fun `an institution word next to digits is not exempt`() {
        // This is the line that must still hide: the bank names itself, but the account number
        // is right there with it.
        assertFalse(PublicEntity.isPublicEntity("State Bank A/c 50100123456789"))
        assertFalse(PublicEntity.isPublicEntity("HDFC Bank 5012 3456 7890 1234"))
    }

    @Test
    fun `addresses and identifiers are not exempt`() {
        listOf(
            "42 Nehru Cross Road, Indiranagar",
            "Bengaluru 560038, Karnataka",
            "ABCDE1234F",
            "priya.ram@example.com",
        ).forEach { assertFalse(it, PublicEntity.isPublicEntity(it)) }
    }

    @Test
    fun `prose is not an organisation name`() {
        assertFalse(
            PublicEntity.isPublicEntity(
                "This statement is computer generated and does not require a signature."
            )
        )
    }
}
