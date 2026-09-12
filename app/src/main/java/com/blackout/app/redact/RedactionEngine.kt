package com.blackout.app.redact

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.blackout.app.ocr.TextSpan

/**
 * Burns redaction bars into pixels.
 *
 * The one rule that matters: **the original bitmap is never mutated.** [render] always allocates
 * a copy and paints on that. The original stays in memory purely so the user can uncensor, and it
 * must never be the thing that leaves the app - see [com.blackout.app.share.ShareRedacted].
 *
 * Redaction is destructive on purpose. Nothing here blurs, pixelates or overlays: a filled opaque
 * rect is the only form that can't be inverted out of the exported file.
 */
object RedactionEngine {

    /** Bars extend slightly past the glyph box so descenders and antialiasing don't peek out. */
    private const val PADDING_RATIO = 0.12f
    private const val MIN_PADDING_PX = 3

    fun render(
        original: Bitmap,
        spans: List<TextSpan>,
        hiddenIds: Set<Int>,
    ): Bitmap {
        val copy = original.copy(Bitmap.Config.ARGB_8888, /* isMutable = */ true)
            ?: return original
        if (hiddenIds.isEmpty()) return copy

        val canvas = Canvas(copy)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }

        for (span in spans) {
            if (span.id !in hiddenIds) continue
            val pad = paddingFor(span)
            val r = span.rect.inflate(pad, copy.width, copy.height)
            val radius = (r.height * 0.12f).coerceAtMost(10f)
            canvas.drawRoundRect(
                RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()),
                radius,
                radius,
                paint,
            )
        }
        return copy
    }

    private fun paddingFor(span: TextSpan): Int =
        (span.rect.height * PADDING_RATIO).toInt().coerceAtLeast(MIN_PADDING_PX)

    /**
     * Which span a tap landed on, in bitmap coordinates.
     *
     * Smallest match wins so a tap inside a short span nested in a taller block picks the precise
     * one rather than the enclosing box.
     */
    fun hitTest(spans: List<TextSpan>, x: Int, y: Int, touchSlopPx: Int = 0): TextSpan? =
        spans.filter { it.rect.inflate(touchSlopPx, Int.MAX_VALUE, Int.MAX_VALUE).contains(x, y) }
            .minByOrNull { it.rect.area }
}
