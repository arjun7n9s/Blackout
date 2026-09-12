package com.blackout.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exports a redacted bitmap and hands it to the system share sheet.
 *
 * The safety property this file exists to enforce: **the only bitmap that can reach this code is
 * a rendered, redaction-burned copy.** [share] takes the composed bitmap - it has no access to
 * the original and no parameter through which one could be passed. Combined with
 * [com.blackout.app.redact.RedactionEngine.render] always allocating a copy, there is no code
 * path that writes the unredacted image to disk.
 *
 * That matters more than it sounds: the classic way redaction tools leak is drawing boxes in the
 * view layer and then exporting the underlying image. Here the bars are real pixels before
 * anything is encoded.
 *
 * JPEG is re-encoded from the redacted bitmap, so no EXIF from the original survives - no
 * capture timestamp, no device model, and no GPS.
 */
object ShareRedacted {

    private const val SHARE_DIR = "shared"
    private const val FILE_NAME = "blackout-redacted.jpg"
    private const val QUALITY = 92

    suspend fun share(context: Context, redacted: Bitmap, chooserTitle: String) {
        val uri = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            // Overwrite rather than accumulate; the previous export is never needed again.
            val file = File(dir, FILE_NAME)
            FileOutputStream(file).use { out ->
                redacted.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
