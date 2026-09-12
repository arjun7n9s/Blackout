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
     * How far the page as a whole is rotated, in degrees, range [-180, 180).
     *
     * [medianAbsAngle] answers "how tilted is this?"; this answers "which way, and by how much",
     * which is what [Deskew] needs to rotate the page back.
     *
     * ## Why it is two steps
     *
     * A plain median over raw angles breaks at the wrap point: a page near 180° reports lines as
     * +179 and -179, whose median is 0. A circular mean fixes the wrap but loses the robustness -
     * one vertical stamp would drag the whole page off true.
     *
     * So: take the robust median on the modulo-180 fold, which cannot wrap because the cluster is
     * never near both ends at once, then decide the remaining 180° ambiguity by majority vote of
     * the raw angles. That distinguishes a page rotated +90 from one rotated -90 - identical after
     * folding, opposite in what they need - and spots a page that is simply upside down.
     *
     * The vote must be [ORIENTATION_MAJORITY] decisive. Rotating an upright page by 180° is the
     * one mistake here that OCR would not notice, so an unclear page keeps the fold's answer and
     * gets tilt corrected only.
     */
    fun medianSignedAngle(spans: List<TextSpan>): Float {
        if (spans.isEmpty()) return 0f
        val folded = spans.map { signedDeviation(it.angleDeg) }.sorted()
            .let { it[it.size / 2] }
        val flipped = normalize(folded + 180f)

        val usable = spans.filter { it.angleDeg.isFinite() }
        if (usable.isEmpty()) return folded
        val forFlipped = usable.count { span ->
            val raw = normalize(span.angleDeg)
            circularDistance(raw, flipped) < circularDistance(raw, folded)
        }
        val share = forFlipped.toFloat() / usable.size
        return when {
            share >= ORIENTATION_MAJORITY -> flipped
            share <= 1f - ORIENTATION_MAJORITY -> folded
            // Genuinely mixed: correct the tilt, leave the orientation alone.
            else -> folded
        }
    }

    /**
     * How lopsided the orientation vote must be before the page is turned the other way up.
     *
     * Deliberately high. Getting the tilt wrong produces a visibly crooked page that the OCR
     * acceptance check then rejects; getting the orientation wrong produces a perfectly sharp,
     * perfectly upside-down document that reads exactly as well and so passes every check we have.
     */
    const val ORIENTATION_MAJORITY = 0.7f

    /** Folds onto (-90, 90]: 0 is horizontal, +ve leans one way, -ve the other. */
    fun signedDeviation(angleDeg: Float): Float {
        if (!angleDeg.isFinite()) return 0f
        var a = angleDeg % 180f
        if (a <= -90f) a += 180f
        if (a > 90f) a -= 180f
        return a
    }

    /** Folds onto [-180, 180). */
    fun normalize(angleDeg: Float): Float {
        if (!angleDeg.isFinite()) return 0f
        var a = angleDeg % 360f
        if (a < -180f) a += 360f
        if (a >= 180f) a -= 360f
        return a
    }

    /** Shortest angular distance between two directions, 0..180. */
    fun circularDistance(a: Float, b: Float): Float = abs(normalize(a - b))

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
