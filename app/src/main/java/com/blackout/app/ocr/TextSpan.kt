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

/** A point in BITMAP pixel coordinates. Float, because a rotated corner rarely lands on a pixel. */
data class SpanPoint(val x: Float, val y: Float)

/**
 * The four corners of a text line as it actually sits on the page.
 *
 * [SpanRect] is the axis-aligned box *around* these, which is all you need for hit-testing but is
 * the wrong shape to paint. On a line tilted 30° the box is five times taller than the text, so an
 * axis-aligned bar covers a slab of the page - it never leaks (the box always contains the glyphs)
 * but it buries everything around them.
 *
 * Corners come straight from ML Kit's `Text.Line.getCornerPoints()`, in the line's own reading
 * order: [topLeft] and [topRight] along the baseline direction, then down the far edge. So
 * `topRight - topLeft` is the text direction and `bottomLeft - topLeft` is its thickness, which is
 * what [inflate] needs to grow the bar along the text rather than along the screen.
 */
data class SpanQuad(
    val topLeft: SpanPoint,
    val topRight: SpanPoint,
    val bottomRight: SpanPoint,
    val bottomLeft: SpanPoint,
) {
    val points: List<SpanPoint> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /**
     * Grows the quad by [px] on every side, along its **own** axes.
     *
     * Inflating in screen space would shear a tilted bar; this keeps it a rectangle that still
     * follows the text.
     */
    fun inflate(px: Float): SpanQuad {
        val alongX = topRight.x - topLeft.x
        val alongY = topRight.y - topLeft.y
        val downX = bottomLeft.x - topLeft.x
        val downY = bottomLeft.y - topLeft.y

        val alongLen = kotlin.math.hypot(alongX, alongY)
        val downLen = kotlin.math.hypot(downX, downY)
        if (alongLen <= 0f || downLen <= 0f) return this

        val ax = alongX / alongLen * px
        val ay = alongY / alongLen * px
        val dx = downX / downLen * px
        val dy = downY / downLen * px

        return SpanQuad(
            topLeft = SpanPoint(topLeft.x - ax - dx, topLeft.y - ay - dy),
            topRight = SpanPoint(topRight.x + ax - dx, topRight.y + ay - dy),
            bottomRight = SpanPoint(bottomRight.x + ax + dx, bottomRight.y + ay + dy),
            bottomLeft = SpanPoint(bottomLeft.x - ax + dx, bottomLeft.y - ay + dy),
        )
    }
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
    /**
     * Rotation of this line in degrees, straight from ML Kit (`Text.Line.getAngle()`).
     *
     * [rect] is axis-aligned, so on a rotated capture it is a loose box around slanted glyphs.
     * This is how we notice that and warn, rather than shipping a page that merely looks redacted.
     */
    val angleDeg: Float = 0f,
    /**
     * The line's true oriented footprint, from ML Kit's `Text.Line.getCornerPoints()`.
     *
     * Null when ML Kit did not supply corners. [rect] remains the axis-aligned box around this
     * and is what hit-testing uses; [quad] is what should be *painted*, because on a tilted line
     * the two differ enormously - see [SpanQuad].
     */
    val quad: SpanQuad? = null,
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
