package com.blackout.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Capture target. The sensor can do far more, but a full-res frame decodes to a bitmap large
 * enough to OOM, and we hold it in memory rather than writing to disk. ~1536x2048 keeps the
 * bitmap around 12 MB while staying sharp enough for the OCR pass that comes next.
 */
private val CAPTURE_TARGET = Size(1536, 2048)

/** Bridges CameraX's ListenableFuture to a coroutine. */
suspend fun Context.awaitCameraProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener(
        {
            runCatching { future.get() }
                .onSuccess(cont::resume)
                .onFailure(cont::resumeWithException)
        },
        ContextCompat.getMainExecutor(this),
    )
}

fun buildImageCapture(): ImageCapture =
    ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        CAPTURE_TARGET,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )
                )
                .build()
        )
        .build()

/**
 * Takes a single frame and hands back an upright [Bitmap].
 *
 * Capturing in-memory (rather than to a file) avoids FileProvider plumbing and matches where
 * this is going: detect text, draw boxes over it, and only then let the user save or share.
 */
fun ImageCapture.captureBitmap(
    context: Context,
    mirror: Boolean,
    onCaptured: (Bitmap) -> Unit,
    onError: (Throwable) -> Unit,
) {
    takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val result = runCatching {
                    val rotation = image.imageInfo.rotationDegrees
                    image.toBitmap().uprighted(rotation, mirror)
                }
                image.close()
                result.fold(onCaptured, onError)
            }

            override fun onError(exception: ImageCaptureException) = onError(exception)
        },
    )
}

/** Applies sensor rotation, and mirrors front-camera shots so they match the preview. */
private fun Bitmap.uprighted(rotationDegrees: Int, mirror: Boolean): Bitmap {
    if (rotationDegrees == 0 && !mirror) return this
    val matrix = Matrix().apply {
        if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        if (mirror) postScale(-1f, 1f)
    }
    val out = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    if (out != this) recycle()
    return out
}
