package com.blackout.app.ocr

import kotlin.math.abs

/**
 * How far off-horizontal a capture is, from ML Kit's per-line angles.
 *
 * ## Why this matters
 *
 * `TextSpan.rect` is axis-aligned, but text on a tilted photograph is not. Measured on the bank
 * fixture rotated by a known amount (iQOO 15, 2026-09-12):
 *
 * | capture | median angle | spans the CPU stage could settle | median span height |
 * |---|---|---|---|
 * | upright | ~0° | **28 / 47** | 22 px |
 * | 12° | 11.9° | 19 / 47 | 65 px |
 * | 30° | 30.0° | **8 / 47** | 123 px |
 * | 90° | 90.0° | **6 / 49** | 198 px |
 *
 * Two things go wrong together. The axis-aligned box around a slanted line inflates - 22 px to
 * 198 px - so bars become loose blocks rather than tight redactions. And OCR degrades, so the
 * deterministic detectors stop matching and sensitive values simply stop being found: the CPU
 * stage settles 28 spans upright and 6 at 90°.
 *
 * The dangerous part is that *some* bars still get drawn, so the page looks processed.
 * [com.blackout.app.share.ShareGuard] only fires when nothing was redacted, which means a tilted
 * capture sails straight through. This is the signal that closes that hole.
 */
object SkewMetrics {

    /**
     * Median deviation from horizontal, in degrees, over [spans]. Range 0..90.
     *
     * Returns 0 for an empty list - no evidence of skew is not evidence of skew.
     */
    fun medianAbsAngle(spans: List<TextSpan>): Float {
        if (spans.isEmpty()) return 0f
        val deviations = spans.map { deviationFromHorizontal(it.angleDeg) }.sorted()
        return deviations[deviations.size / 2]
    }

    /**
     * Folds an arbitrary angle onto 0..90, where 0 is horizontal and 90 is vertical.
     *
     * Deliberately folds modulo 180, not 360: text rotated a full 180° still runs along
     * horizontal lines, so an axis-aligned box fits it exactly as well as upright text. It is
     * *tilt* that breaks the boxes, not being upside down.
     */
    fun deviationFromHorizontal(angleDeg: Float): Float {
        if (!angleDeg.isFinite()) return 0f
        var a = angleDeg % 180f
        if (a < 0f) a += 180f
        return if (a <= 90f) a else 180f - a
    }

    /**
     * Beyond this many degrees the capture is treated as tilted enough to warn about.
     *
     * 8° sits above handheld jitter on a flat page (measured ~0°) and below the 12° fixture, which
     * already cost a third of the deterministic detections.
     */
    const val WARN_DEGREES = 8f

    fun isSkewed(spans: List<TextSpan>): Boolean =
        abs(medianAbsAngle(spans)) >= WARN_DEGREES
}
