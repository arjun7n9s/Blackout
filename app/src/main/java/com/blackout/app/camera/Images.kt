package com.blackout.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlin.math.max

/** Upper bound on the long edge of a decoded gallery image, to stay clear of OOM. */
private const val MAX_DIMENSION = 2048

/**
 * Decodes [uri] into a downsampled, software-backed [Bitmap], or null if it can't be read.
 *
 * Software-backed matters: [ImageDecoder] hands back a HARDWARE bitmap by default, which has no
 * accessible pixels - fine to draw, useless for the OCR/redaction pass this is scaffolding for.
 */
fun decodeSampledBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        // ImageDecoder applies the EXIF orientation for us.
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.setTargetSampleSize(sampleSizeFor(info.size.width, info.size.height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }
}.getOrNull()

/** Nearest power-of-two subsample that brings the long edge under [MAX_DIMENSION]. */
private fun sampleSizeFor(width: Int, height: Int): Int {
    var sample = 1
    var longEdge = max(width, height)
    while (longEdge / 2 >= MAX_DIMENSION) {
        longEdge /= 2
        sample *= 2
    }
    return sample
}
