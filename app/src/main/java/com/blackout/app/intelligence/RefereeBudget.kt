package com.blackout.app.intelligence

import com.blackout.app.ocr.TextSpan

/**
 * When Gemma is worth its ~19 s, and when it is not.
 *
 * Every number here is from Phone C's A/B runs on the same PNG (`C-Outputs/compare.csv`,
 * `C-Outputs/SUMMARY.md`), not from taste:
 *
 * | case | spans | referee | outcome |
 * |---|---|---|---|
 * | `C-001` two-col bank | 85 | 28 s | restored the bank header, hid the email — **worth it** |
 * | `C-017` payslip | 46 | 15 s | removed 6 wrong bars, added PAN/email/ESI — **worth it** |
 * | `C-008` 6 pt contract | 51 | 37 s | hide 13 → **39**, page became a black slab — **harmful** |
 * | `C-015` dense ledger | 141 | **175 s** | hide 131 → 120, identifiers still visible — **harmful** |
 *
 * So the referee is gated on page shape, not on how much time is left:
 *
 *  - **> [DENSE_SPAN_LIMIT] spans** → skip. C-015 spent 175 s to remove 11 bars.
 *  - **small print** → skip. C-008's 6-9 pt body is where the referee's own HIDEs turn a contract
 *    into a rectangle. Detected as median span height under [SMALL_PRINT_HEIGHT_RATIO] of the page
 *    height, and only on pages with enough spans to be a document rather than a card.
 *
 * Skipping is a *quality* decision as much as a latency one: on both of those pages the workhorse
 * output was strictly better than the refereed output.
 *
 * Everything else keeps the referee, with [MergePolicy.REFEREE_QUEUE_CAP] bounding the queue.
 */
object RefereeBudget {

    /**
     * What the interactive path is allowed to cost, end to end.
     *
     * The referee cannot fit inside this and never will: measured `referee_ms` on this handset
     * runs 3877-11646 ms on ordinary pages and 174627 ms on C-015. Everything else in the
     * pipeline together - OCR, the deterministic detectors, the geometry pass and the NPU
     * workhorse - has measured at 200-850 ms, so the budget is met by leaving exactly one stage
     * out.
     *
     * That is a product decision, not a quality one. The referee still earns its keep on the
     * pages where C measured it winning (`C-001`, `C-017`), so it is not deleted - it moves
     * behind [refine], run on demand against a result the user is already looking at.
     */
    const val INTERACTIVE_BUDGET_MS = 1000L

    private const val INTERACTIVE_SKIP =
        "interactive budget: ${INTERACTIVE_BUDGET_MS}ms (referee measures 3.9-11.6s); refine to run it"

    /**
     * The skip reason for a page being analysed for immediate display.
     *
     * @param refine true when the user explicitly asked for the slow, better answer - then the
     *   page-shape vetoes in [skipReason] still apply, because those are about the referee making
     *   the page *worse*, and no amount of waiting fixes that.
     */
    fun interactiveSkipReason(
        refine: Boolean,
        spanCount: Int,
        medianSpanHeight: Int,
        imageHeight: Int,
    ): String? =
        if (!refine) INTERACTIVE_SKIP
        else skipReason(spanCount, medianSpanHeight, imageHeight)

    /** C-015: 141 spans, referee_ms=174627, and it made the page *less* redacted. */
    const val DENSE_SPAN_LIMIT = 100

    /** Below this many spans a page is a card or a receipt; the referee is cheap and useful. */
    const val SMALL_PRINT_MIN_SPANS = 40

    /**
     * Median line height as a fraction of page height. A 6-9 pt body at 150 dpi is ~0.008-0.010
     * of an A4 page; the 10-11 pt bank statements and payslips C measured are ~0.013 and stay.
     */
    const val SMALL_PRINT_HEIGHT_RATIO = 0.010f

    fun medianSpanHeight(spans: List<TextSpan>): Int {
        if (spans.isEmpty()) return 0
        val heights = spans.map { it.rect.height }.sorted()
        return heights[heights.size / 2]
    }

    /**
     * @return null when the referee should run, otherwise the reason it was skipped - which is
     *   logged and surfaced in the debug panel rather than silently applied.
     */
    fun skipReason(spanCount: Int, medianSpanHeight: Int, imageHeight: Int): String? {
        if (spanCount > DENSE_SPAN_LIMIT) {
            return "dense page: $spanCount spans > $DENSE_SPAN_LIMIT (C-015 cost 175s and removed redactions)"
        }
        if (spanCount >= SMALL_PRINT_MIN_SPANS && imageHeight > 0) {
            val ratio = medianSpanHeight.toFloat() / imageHeight
            if (ratio < SMALL_PRINT_HEIGHT_RATIO) {
                return "small print: median line ${medianSpanHeight}px is " +
                    "%.3f".format(ratio) + " of page (C-008 went hide 13 -> 39)"
            }
        }
        return null
    }
}
