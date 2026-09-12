package com.blackout.app.share

/**
 * Refuses to let a page leave the app *quietly* when the recognizer almost certainly missed it.
 *
 * ## The incident
 *
 * Phone C, case `C-005-motion-blur`: a bank statement photographed with camera shake. ML Kit
 * returned **one** span - the bank's name - so the cascade had nothing to judge:
 *
 * ```
 * spans=1 ocr_ms=118 hide=0 keep=1 total_ms=7490 doctype=Bank_name
 * ```
 *
 * The app then offered a perfectly ordinary `Share` button. The PAN, account number, IFSC, phone,
 * email and address were all in the picture and all unredacted. C called it "the worst privacy
 * failure in the set", and it is: everything downstream behaved correctly, and the user still got
 * a confident-looking result with nothing hidden.
 *
 * ## The rule
 *
 * A document photograph has text roughly everywhere. So compare the span count to the *area* of
 * the image: fewer than [MIN_SPANS_PER_MEGAPIXEL] recognised regions per megapixel, with nothing
 * redacted, means either "this isn't a document" or "we couldn't read the document" - and we
 * cannot tell which. So we warn and make the user confirm rather than blocking outright, because
 * sharing a holiday photo through Blackout is legitimate.
 *
 * Reference points: C-005 scored 0.5 spans/MP (warn). The bank fixture on phone A scores ~21, and
 * C's readable statements 30-60 (silent).
 */
object ShareGuard {

    /** Below this, a page that also redacted nothing is treated as unread rather than clean. */
    const val MIN_SPANS_PER_MEGAPIXEL = 4f

    /** Small images are thumbnails and crops; the density test is meaningless on them. */
    private const val MIN_MEGAPIXELS = 0.3f

    /**
     * @return null when sharing is unremarkable, otherwise the reason to put in front of the user.
     */
    fun warning(spanCount: Int, hideCount: Int, imageWidth: Int, imageHeight: Int): String? {
        if (hideCount > 0) return null
        val megapixels = imageWidth.toFloat() * imageHeight / 1_000_000f
        if (megapixels < MIN_MEGAPIXELS) return null
        if (spanCount / megapixels >= MIN_SPANS_PER_MEGAPIXEL) return null
        if (spanCount == 0) return "No text was recognised in this image, so nothing has been redacted."
        val counted = if (spanCount == 1) "1 text region was" else "$spanCount text regions were"
        return "Only $counted recognised in this image and nothing has been redacted. " +
            "If the photo is blurred or at an angle, details may still be readable."
    }
}
