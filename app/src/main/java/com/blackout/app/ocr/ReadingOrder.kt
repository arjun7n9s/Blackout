package com.blackout.app.ocr

import kotlin.math.abs

/**
 * Re-sorts OCR spans into human reading order and renumbers them.
 *
 * ML Kit returns text in *block* order, which on a two-column form means every field label comes
 * out as one block and every value as another. Batching that raw produces one request containing
 * only labels ("Account Holder", "PAN", "Aadhaar") and a later request containing only bare
 * values ("Priya Ramachandran", "ABCDE1234F") - each stripped of the context that makes it
 * judgeable. That was measured on a real statement: the model kept everything, correctly, because
 * a list of field captions genuinely isn't sensitive.
 *
 * Banding spans into visual rows and sorting left-to-right restores "Account Holder |
 * Priya Ramachandran" adjacency, which is what the whole "lines are in reading order" premise in
 * [com.blackout.app.intelligence.Prompts] depends on.
 */
object ReadingOrder {

    /** Fraction of median glyph height within which two spans count as the same row. */
    private const val ROW_TOLERANCE = 0.6f

    fun sort(spans: List<TextSpan>): List<TextSpan> {
        if (spans.size < 2) return spans

        val heights = spans.map { it.rect.height }.sorted()
        val median = heights[heights.size / 2]
        val tolerance = (median * ROW_TOLERANCE).toInt().coerceAtLeast(4)

        val rows = mutableListOf<MutableList<TextSpan>>()
        for (span in spans.sortedBy { it.rect.top }) {
            val centre = (span.rect.top + span.rect.bottom) / 2
            val row = rows.lastOrNull()
            val rowCentre = row?.let { r ->
                r.sumOf { (it.rect.top + it.rect.bottom) / 2 } / r.size
            }
            if (row != null && rowCentre != null && abs(centre - rowCentre) <= tolerance) {
                row += span
            } else {
                rows += mutableListOf(span)
            }
        }

        // Renumber so ids run in reading order too - the models see small sequential ids and the
        // neighbour lookup in Prompts becomes a simple index step.
        return rows
            .flatMap { row -> row.sortedBy { it.rect.left } }
            .mapIndexed { index, span -> span.copy(id = index + 1) }
    }
}
