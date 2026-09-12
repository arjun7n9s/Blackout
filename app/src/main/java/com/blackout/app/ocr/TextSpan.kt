package com.blackout.app.ocr

/**
 * A rectangle in BITMAP pixel coordinates.
 *
 * Deliberately not [android.graphics.Rect] so the whole decision pipeline stays unit-testable on
 * the JVM without Robolectric. Conversion happens at the ML Kit / Canvas boundary only.
 */
data class SpanRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom

    /** Grown by [px] on every side, clamped to a [maxW] x [maxH] canvas. */
    fun inflate(px: Int, maxW: Int, maxH: Int): SpanRect = SpanRect(
        left = (left - px).coerceAtLeast(0),
        top = (top - px).coerceAtLeast(0),
        right = (right + px).coerceAtMost(maxW),
        bottom = (bottom + px).coerceAtMost(maxH),
    )

    val area: Long get() = width.toLong() * height.toLong()
}

/**
 * What a span is *structurally*, independent of whether its content is sensitive.
 *
 * This is the fix for the worst demo bug: on a two-column form the models were blacking out the
 * left-hand field label ("Account Holder", "PAN") along with its value. A field label is a
 * property of the *form*, not of the person, so it is almost never the secret - and knowing that
 * geometrically is far more reliable than hoping a 0.6B infers it.
 */
enum class SpanRole {
    /** Left-column field caption in a label/value pair. Defaults to visible. */
    LABEL,

    /** The value paired with a [LABEL]. This is where secrets live. */
    VALUE,

    /** A section heading or title - full-width, no pair. */
    HEADING,

    /** Everything else: prose, table cells, anything unpaired. Judged normally. */
    STANDALONE,
}

/**
 * One OCR'd chunk of text with a stable id for the session.
 *
 * The id is what the models reason about - they return decisions keyed by id and never touch
 * pixels. [lineIndex] / [blockIndex] let us cheaply reconstruct neighbouring text for context.
 */
data class TextSpan(
    val id: Int,
    val text: String,
    val rect: SpanRect,
    val confidence: Float,
    val blockIndex: Int,
    val lineIndex: Int,
    val role: SpanRole = SpanRole.STANDALONE,
    /** For a [SpanRole.VALUE], the text of the label it was paired with. Prompt context. */
    val labelText: String? = null,
) {
    val isBlank: Boolean get() = text.isBlank()
}

/** Full OCR result for one captured image. */
data class OcrResult(
    val spans: List<TextSpan>,
    val imageWidth: Int,
    val imageHeight: Int,
    val elapsedMs: Long,
) {
    companion object {
        fun empty(width: Int = 0, height: Int = 0) = OcrResult(emptyList(), width, height, 0L)
    }
}
