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
}
