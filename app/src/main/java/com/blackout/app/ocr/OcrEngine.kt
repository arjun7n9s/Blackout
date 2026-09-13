package com.blackout.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrResult
    fun close()
}

/**
 * ML Kit on-device text recognition. No network, no Play Services model download for the bundled
 * Latin recognizer.
 *
 * Spans are emitted at **line** granularity rather than per word. A line is the smallest unit
 * that still carries enough context for a 0.6B model to judge ("9876543210" alone is ambiguous;
 * "Mobile 9876543210" is not), and it maps to a redaction bar a human would actually draw.
 */
class MlKitOcrEngine : OcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        val started = System.currentTimeMillis()
        val image = InputImage.fromBitmap(bitmap, 0)

        val text = suspendCancellableCoroutine<Text?> { cont ->
            recognizer.process(image)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        } ?: return OcrResult.empty(bitmap.width, bitmap.height)

        var nextId = 1
        val spans = buildList {
            text.textBlocks.forEachIndexed { blockIndex, block ->
                block.lines.forEachIndexed { lineIndex, line ->
                    val box: Rect = line.boundingBox ?: return@forEachIndexed
                    if (line.text.isBlank()) return@forEachIndexed
                    add(
                        TextSpan(
                            id = nextId++,
                            text = line.text,
                            rect = SpanRect(box.left, box.top, box.right, box.bottom),
                            confidence = line.confidence ?: 0f,
                            angleDeg = line.angle,
                            quad = quadOf(line.cornerPoints),
                            blockIndex = blockIndex,
                            lineIndex = lineIndex,
                        )
                    )
                }
            }
        }

        return OcrResult(
            // ML Kit emits block order; the pipeline needs visual reading order so labels sit
            // next to their values.
            spans = ReadingOrder.sort(spans),
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    override fun close() {
        runCatching { recognizer.close() }
    }

    /**
     * ML Kit's four corner points, in its own documented order: top-left, top-right,
     * bottom-right, bottom-left *relative to the line's own rotation*.
     *
     * Null unless there are exactly four - anything else is not a quadrilateral and the caller
     * falls back to the axis-aligned [SpanRect].
     */
    private fun quadOf(corners: Array<android.graphics.Point>?): SpanQuad? {
        if (corners == null || corners.size != 4) return null
        fun at(i: Int) = SpanPoint(corners[i].x.toFloat(), corners[i].y.toFloat())
        return SpanQuad(at(0), at(1), at(2), at(3))
    }
}
