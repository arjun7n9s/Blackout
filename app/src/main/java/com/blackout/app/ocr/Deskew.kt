package com.blackout.app.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import kotlin.math.abs

/**
 * Straightens a tilted capture before the pipeline judges it. Fully local, no new dependencies.
 *
 * ## Why bother
 *
 * [SkewMetrics] documents what tilt costs us, measured on the bank fixture:
 *
 * | capture | median angle | spans the CPU stage could settle |
 * |---|---|---|
 * | upright | ~0° | **28 / 47** |
 * | 12° | 11.9° | 19 / 47 |
 * | 30° | 30.0° | **8 / 47** |
 *
 * Two separate failures, one cause. `TextSpan.rect` is axis-aligned, so the box around a slanted
 * line inflates - a 400x22 px line tilted 12° needs an 105 px tall box, so a tight redaction bar
 * becomes a smeared block. And ML Kit itself reads tilted glyphs worse, so the deterministic
 * detectors stop matching and sensitive values stop being *found at all*.
 *
 * [com.blackout.app.share.ShareGuard] warns about this, which is honest but not useful: the user
 * is told to retake the photo. Rotating the page back fixes both failures instead, and the warning
 * then stops firing on its own because the residual angle really is ~0.
 *
 * ## Why rotation and not a full perspective warp
 *
 * A proper document scanner finds the page quad and un-keystones it, which needs contour detection
 * - OpenCV, ~25 MB of arm64 native code. We already have the page angle to ~0.3° for free, from
 * ML Kit's own per-line `getAngle()`. Rotation is the part of the correction that the data
 * supports today; keystone correction stays open (see ARCH.md).
 *
 * ## Why it re-reads the page
 *
 * Rotating the *spans* would tighten nothing - the boxes would still be the loose ones ML Kit
 * derived from slanted glyphs. The win comes from OCR seeing an upright page, so the second pass
 * is the point, not an overhead we tolerate.
 *
 * ## Why it can decline
 *
 * Deskew is a bet: the angle is a median over noisy per-line estimates, and on a page with two
 * text orientations (a stamp, a rotated watermark) the median can describe neither. So the result
 * is *measured* - [accepts] keeps the rotation only when the second pass recognised at least as
 * much text as the first. A bad bet costs one OCR pass and changes nothing.
 */
object Deskew {

    private const val TAG = "BlackoutSkew"

    /**
     * Below this, leave the page alone.
     *
     * Not zero, because resampling costs a little sharpness, and not [SkewMetrics.WARN_DEGREES]
     * either - box inflation bites long before a human would call the page tilted. That same
     * 400x22 px line already needs a 42 px box at 3°, double its true height. 2° is the point
     * where the inflation stops being worth a resample.
     */
    const val MIN_CORRECTION_DEG = 2f

    data class Result(
        val bitmap: Bitmap,
        val ocr: OcrResult,
        /** Degrees actually applied. 0 when the page was left alone or the bet was declined. */
        val appliedDeg: Float,
        /** Set when a correction was tried and rejected. Diagnostics only. */
        val declinedDeg: Float = 0f,
    )

    /**
     * Reads [bitmap], and if it is tilted, rotates it upright and reads it again.
     *
     * [first] is the caller's existing OCR of [bitmap] - the pipeline has already paid for it, so
     * we never re-read an upright page.
     */
    suspend fun straighten(bitmap: Bitmap, first: OcrResult, engine: OcrEngine): Result {
        val correction = correctionFor(first.spans)
        if (correction == 0f) return Result(bitmap, first, appliedDeg = 0f)

        val rotated = runCatching { rotate(bitmap, correction) }.getOrElse {
            Log.w(TAG, "rotate failed: ${it.message}")
            return Result(bitmap, first, appliedDeg = 0f)
        }
        val second = runCatching { engine.recognize(rotated) }.getOrElse {
            Log.w(TAG, "re-OCR failed: ${it.message}")
            rotated.recycle()
            return Result(bitmap, first, appliedDeg = 0f)
        }

        val residual = SkewMetrics.medianAbsAngle(second.spans)
        Log.i(
            TAG,
            "deskew ${"%.1f".format(correction)}° : " +
                "chars ${charCount(first.spans)}->${charCount(second.spans)}, " +
                "spans ${first.spans.size}->${second.spans.size}, " +
                "median_h ${medianHeight(first.spans)}->${medianHeight(second.spans)}, " +
                "angle ${"%.1f".format(SkewMetrics.medianAbsAngle(first.spans))}°->" +
                "${"%.1f".format(residual)}°",
        )

        if (!accepts(first, second)) {
            Log.i(TAG, "deskew declined - second pass read less text")
            rotated.recycle()
            return Result(bitmap, first, appliedDeg = 0f, declinedDeg = correction)
        }
        return Result(rotated, second, appliedDeg = correction)
    }

    /**
     * How far to rotate [spans]' page to bring it upright, or 0 to leave it alone.
     *
     * Negated because [SkewMetrics.medianSignedAngle] reports how far the text *is* rotated, and
     * we want to undo it.
     */
    fun correctionFor(spans: List<TextSpan>): Float {
        if (spans.size < MIN_SPANS) return 0f
        val skew = SkewMetrics.medianSignedAngle(spans)
        if (abs(skew) < MIN_CORRECTION_DEG) return 0f
        return SkewMetrics.normalize(-skew * ANGLE_SIGN)
    }

    /**
     * Did the rotation pay for itself?
     *
     * Two wins are possible and a page usually gets only one of them.
     *
     * **More text.** Characters, not spans: straightening legitimately *merges* fragments back
     * into whole lines, so a falling span count is a success. This is the win on a badly tilted
     * page, where OCR was losing glyphs outright.
     *
     * **Tighter boxes.** ML Kit reads a page turned on its side almost as well as an upright one -
     * the quarter-turn fixture scored 649 characters before and 648 after. What it cannot do is
     * return an axis-aligned box that fits a sideways line, so the median span height was 198 px
     * against 22 px upright, and every redaction bar was a smeared block. Judging on text alone
     * threw that correction away over a single character.
     *
     * So: reject only a *material* loss of text, and otherwise require one of the two to improve.
     *
     * This also settles the 180° case without a special rule. Turning a page over changes neither
     * measure - upside-down text is no harder for ML Kit, and the boxes are already tight - so a
     * flip is kept only when it genuinely recovers text, and an upright page flipped by mistake is
     * rejected because it has nothing to show for itself.
     */
    fun accepts(before: OcrResult, after: OcrResult): Boolean {
        if (after.spans.isEmpty()) return false
        val had = charCount(before.spans)
        val got = charCount(after.spans)

        val wasHigh = medianHeight(before.spans)
        val heightGain =
            if (wasHigh > 0) 1f - medianHeight(after.spans).toFloat() / wasHigh else 0f

        if (got < had - lossTolerance(had, heightGain)) return false
        return got > had || heightGain >= MIN_HEIGHT_GAIN
    }

    /**
     * How much text a rotation may lose and still count, given how much the boxes tightened.
     *
     * Never zero: OCR is not deterministic to the character across a resample, and a rigid `>=`
     * once rejected a correct quarter-turn over one glyph in 649.
     *
     * Nor is it a flat percentage, which was the next thing to go wrong. A real handheld capture
     * of a sideways card read 139 characters upright against 136 rotated - a 2.2% loss - while
     * the median span height fell from 426 px to 48 px. A fixed 2% gate threw away a correction
     * carrying nine-to-one geometric evidence, and the page stayed on its side.
     *
     * So the allowance scales with that evidence: 2% when the boxes barely moved, up to 10% when
     * they collapse. A rotation that cannot show a geometric win is still held to the strict bar,
     * which is what keeps a wrong angle - and a 180° flip, where the height never changes - out.
     */
    private fun lossTolerance(had: Int, heightGain: Float): Int {
        val share = BASE_LOSS + (MAX_LOSS - BASE_LOSS) * heightGain.coerceIn(0f, 1f)
        return maxOf(2, (had * share).toInt())
    }

    private const val BASE_LOSS = 0.02f
    private const val MAX_LOSS = 0.10f

    /** Fractional drop in median span height that counts as the boxes genuinely tightening. */
    private const val MIN_HEIGHT_GAIN = 0.15f

    private fun medianHeight(spans: List<TextSpan>): Int {
        if (spans.isEmpty()) return 0
        val heights = spans.map { it.rect.height }.sorted()
        return heights[heights.size / 2]
    }

    /** Rotates clockwise-positive, expanding the canvas so nothing is clipped. */
    fun rotate(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun charCount(spans: List<TextSpan>): Int =
        spans.sumOf { it.text.count { c -> !c.isWhitespace() } }

    /**
     * Fewer lines than this and the median angle is not a page angle, it is one line's noise.
     */
    private const val MIN_SPANS = 4

    /**
     * Handedness between ML Kit's `Text.Line.getAngle()` and [Matrix.postRotate].
     *
     * Both are clockwise-positive in screen coordinates, so undoing the tilt is a plain negation.
     * Kept named because it is the single thing that would need flipping if that ever stopped
     * holding, and because a sign error here is silent - it produces a *more* tilted page, which
     * [accepts] rejects, so the app degrades to today's behaviour rather than misbehaving.
     */
    private const val ANGLE_SIGN = 1f
}
