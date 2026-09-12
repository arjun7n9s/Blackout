package com.blackout.app.intelligence

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Whether this process can even *attempt* Qualcomm NPU inference.
 *
 * Two independent requirements, both measured rather than assumed:
 *
 * 1. **Dispatch library.** `litertlm-android` ships only `liblitertlm_jni.so`.
 *    `Backend.NPU(nativeLibraryDir)` looks in that directory for
 *    [DISPATCH_LIB]. If it is missing, `Engine.initialize()` aborts the process
 *    (`dispatch_delegate.cc: "No usable Dispatch runtime found"`) — a SIGABRT
 *    that Kotlin cannot catch. So we never construct [com.google.ai.edge.litertlm.Backend.NPU]
 *    unless the file is actually there.
 *
 * 2. **SoC-matched AOT weights.** Hugging Face names Qualcomm packs
 *    `{stem}_qualcomm_{soc}.litertlm` (Gemma-4) or `{stem}_{soc}.litertlm` (Gemma3-1B).
 *    The iQOO 15 reports `SM8850`. The published Gemma-4 pack is `sm8750` (previous Elite).
 *    Loading the wrong SoC blob is how you get "Unsupported context binary version" after
 *    a successful dispatch init — so we refuse to use another chip's file.
 *
 * Filename helpers are pure so they can be unit-tested on the JVM.
 */
object NpuSupport {

    const val DISPATCH_LIB = "libLiteRtDispatch_Qualcomm.so"
    const val TAG = "BlackoutLlm"

    /** `ro.soc.model`, lowercased, or null on API < 31. */
    fun socModel(): String? {
        if (Build.VERSION.SDK_INT < 31) return null
        return Build.SOC_MODEL.trim().takeIf { it.isNotEmpty() }?.lowercase()
    }

    /**
     * Filenames we will accept as *this* SoC's NPU pack for a generic CPU/GPU file.
     * Never includes a different SoC slug.
     */
    fun npuWeightNames(genericFileName: String, socModel: String): List<String> {
        val soc = socModel.trim().lowercase()
        if (soc.isEmpty()) return emptyList()
        val stem = genericFileName.removeSuffix(".litertlm")
        return listOf(
            "${stem}_qualcomm_$soc.litertlm",
            "${stem}_$soc.litertlm",
        )
    }

    fun dispatchPresent(nativeLibraryDir: File): Boolean =
        File(nativeLibraryDir, DISPATCH_LIB).isFile

    fun dispatchPresent(context: Context): Boolean =
        dispatchPresent(File(context.applicationInfo.nativeLibraryDir))

    /**
     * True only when it is *safe* to construct Backend.NPU. Missing dispatch → skip,
     * do not probe, because the probe itself can kill the process.
     */
    fun canAttemptNpu(context: Context): Boolean = dispatchPresent(context)

    fun skipReason(context: Context, npuFile: File?): String? {
        if (!dispatchPresent(context)) {
            return "no $DISPATCH_LIB in ${context.applicationInfo.nativeLibraryDir}"
        }
        if (npuFile == null) {
            val soc = socModel() ?: "unknown-soc"
            return "no SoC-matched NPU weights for $soc"
        }
        return null
    }

    fun logSkip(context: Context, spec: ModelSpec, npuFile: File?) {
        val reason = skipReason(context, npuFile) ?: return
        Log.i(TAG, "NPU skipped for ${spec.displayName}: $reason")
    }
}
