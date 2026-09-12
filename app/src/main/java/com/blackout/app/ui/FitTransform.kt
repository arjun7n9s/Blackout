package com.blackout.app.ui

import com.blackout.app.ocr.SpanRect

/**
 * Maps between bitmap pixels and on-screen pixels for an image drawn with
 * [androidx.compose.ui.layout.ContentScale.Fit].
 *
 * OCR rects are in bitmap space; taps and the overlay are in view space. Getting this wrong is
 * how redaction bars end up offset from the text they are meant to cover, so it lives in its own
 * pure, testable class rather than inline in a composable.
 */
data class FitTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
) {
    fun viewLeft(rect: SpanRect): Float = rect.left * scale + offsetX
    fun viewTop(rect: SpanRect): Float = rect.top * scale + offsetY
    fun viewWidth(rect: SpanRect): Float = rect.width * scale
    fun viewHeight(rect: SpanRect): Float = rect.height * scale

    /** Screen point -> bitmap point. Returns null when the tap landed on the letterbox. */
    fun toBitmap(x: Float, y: Float): Pair<Int, Int>? {
        if (scale <= 0f) return null
        val bx = (x - offsetX) / scale
        val by = (y - offsetY) / scale
        return bx.toInt() to by.toInt()
    }

    companion object {
        fun of(boxWidth: Float, boxHeight: Float, imageWidth: Int, imageHeight: Int): FitTransform {
            if (imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f) {
                return FitTransform(0f, 0f, 0f)
            }
            val scale = minOf(boxWidth / imageWidth, boxHeight / imageHeight)
            return FitTransform(
                scale = scale,
                offsetX = (boxWidth - imageWidth * scale) / 2f,
                offsetY = (boxHeight - imageHeight * scale) / 2f,
            )
        }
    }
}
