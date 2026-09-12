package com.blackout.app.intelligence

import android.content.Context
import java.io.File

enum class ModelRole { WORKHORSE, REFEREE }

data class ModelSpec(
    val role: ModelRole,
    val fileName: String,
    val displayName: String,
    /** KV cache budget. Must not exceed what the model file was built for. */
    val maxNumTokens: Int,
    val maxOutputTokens: Int,
)

/**
 * Where the weights live on device, and whether they're actually there.
 *
 * Weights are **not** bundled in the APK - Gemma-4-E2B alone is 2.5 GB, which no APK should
 * carry. They're side-loaded into app-specific external storage, which needs no runtime
 * permission and is wiped on uninstall:
 *
 * ```
 * adb push gemma-4-E2B-it.litertlm \
 *   /sdcard/Android/data/com.blackout.app/files/models/
 * ```
 *
 * See ARCH.md for the full push procedure.
 */
class ModelCatalog(private val context: Context) {

    fun modelsDir(): File {
        val external = context.getExternalFilesDir(MODELS_SUBDIR)
        if (external != null) {
            if (!external.exists()) external.mkdirs()
            return external
        }
        return File(context.filesDir, MODELS_SUBDIR).apply { if (!exists()) mkdirs() }
    }

    /** Returns the weights file for [spec], or null when it hasn't been pushed. */
    fun locate(spec: ModelSpec): File? {
        val candidates = listOfNotNull(
            context.getExternalFilesDir(MODELS_SUBDIR)?.let { File(it, spec.fileName) },
            File(File(context.filesDir, MODELS_SUBDIR), spec.fileName),
        )
        return candidates.firstOrNull { it.isFile && it.length() > 0L }
    }

    fun isAvailable(spec: ModelSpec): Boolean = locate(spec) != null

    /** Human-readable inventory for the debug panel. */
    fun describe(): String {
        val dir = modelsDir()
        val present = ALL.filter { isAvailable(it) }.map { it.displayName }
        return if (present.isEmpty()) "no models in ${dir.absolutePath}"
        else present.joinToString(", ")
    }

    companion object {
        private const val MODELS_SUBDIR = "models"

        val QWEN = ModelSpec(
            role = ModelRole.WORKHORSE,
            fileName = "qwen3_0.6b_q4_block32_ekv1280.litertlm",
            displayName = "Qwen3-0.6B-int4",
            // The file is built ekv1280; asking for more than it was compiled for is invalid.
            maxNumTokens = 1280,
            maxOutputTokens = 320,
        )

        val GEMMA = ModelSpec(
            role = ModelRole.REFEREE,
            fileName = "gemma-4-E2B-it.litertlm",
            displayName = "Gemma-4-E2B-it",
            maxNumTokens = 4096,
            maxOutputTokens = 640,
        )

        val ALL = listOf(QWEN, GEMMA)
    }
}
