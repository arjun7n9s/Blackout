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
    const val COMPILER_PLUGIN_LIB = "libLiteRtCompilerPlugin_Qualcomm.so"
    const val PREPARE_LIB = "libQnnHtpPrepare.so"
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

    fun compilerPluginPresent(nativeLibraryDir: File): Boolean =
        File(nativeLibraryDir, COMPILER_PLUGIN_LIB).isFile

    fun compilerPluginPresent(context: Context): Boolean =
        compilerPluginPresent(File(context.applicationInfo.nativeLibraryDir))

    fun preparePresent(nativeLibraryDir: File): Boolean =
        File(nativeLibraryDir, PREPARE_LIB).isFile

    /**
     * True only when it is *safe* to construct Backend.NPU. Missing dispatch → skip,
     * do not probe, because the probe itself can kill the process.
     *
     * A dispatch `.so` from an older LiteRT release (e.g. 2.1.6 against AAR 0.17.0)
     * loads, then [litert_dispatch.cc] logs `Unsupported dispatch runtime version` and
     * LiteRT-LM **silently runs XNNPACK**. initialize()+warm-up still succeed, so we
     * must not treat "file exists" as "NPU is real". Only SoC AOT packs with a
     * matching-ABI dispatch are attempted; JIT additionally needs [COMPILER_PLUGIN_LIB]
     * plus QAIRT `libQnnIr.so` / `libQnnSaver.so` / [PREPARE_LIB] so the plugin can
     * actually dlopen.
     */
    fun canAttemptNpu(context: Context): Boolean = dispatchPresent(context)

    fun jitDepsPresent(nativeLibraryDir: File): Boolean =
        compilerPluginPresent(nativeLibraryDir) &&
            File(nativeLibraryDir, "libQnnIr.so").isFile &&
            File(nativeLibraryDir, "libQnnSaver.so").isFile &&
            preparePresent(nativeLibraryDir)

    fun jitDepsPresent(context: Context): Boolean =
        jitDepsPresent(File(context.applicationInfo.nativeLibraryDir))

    /**
     * Enqueue NPU only for a real AOT pack, or for JIT when every plugin dependency is
     * on disk. A lone dispatch+plugin pair from LiteRT 2.1.6 is not enough — measured
     * 2026-09-12 on SM8850 with litertlm-android 0.17.0.
     */
    fun willAttemptNpu(context: Context, npuFile: File?): Boolean {
        if (!canAttemptNpu(context)) return false
        if (npuFile != null) return true
        return jitDepsPresent(context)
    }

    fun skipReason(context: Context, npuFile: File?): String? {
        if (!dispatchPresent(context)) {
            return "no $DISPATCH_LIB in ${context.applicationInfo.nativeLibraryDir}"
        }
        if (npuFile == null && !compilerPluginPresent(context)) {
            val soc = socModel() ?: "unknown-soc"
            return "no SoC-matched NPU weights for $soc and no $COMPILER_PLUGIN_LIB for JIT"
        }
        if (npuFile == null && !jitDepsPresent(context)) {
            return "JIT plugin present but missing libQnnIr.so / libQnnSaver.so / $PREPARE_LIB (QAIRT); " +
                "a dispatch-only drop-in silently falls back to CPU and must not be labelled NPU"
        }
        return null
    }

    fun logSkip(context: Context, spec: ModelSpec, npuFile: File?) {
        val reason = skipReason(context, npuFile) ?: return
        Log.i(TAG, "NPU skipped for ${spec.displayName}: $reason")
    }

    // ---------------------------------------------------------------------------------------
    // Vendor Hexagon probe
    // ---------------------------------------------------------------------------------------

    private val VENDOR_LIB_DIRS = listOf("/vendor/lib64", "/vendor/lib64/hw", "/odm/lib64")

    private val HTP_STUB = Regex("""libQnnHtpV(\d+)Stub\.so""")

    /**
     * The Hexagon generation this handset actually exposes, e.g. `V81`, or null if no vendor QNN
     * stub is readable.
     *
     * Read rather than hard-coded on purpose. The hybrid brief says "V79"; the iQOO 15 measurably
     * ships `libQnnHtpV81Stub.so` / `libQnnHtpV81Skel.so`, and LiteRT's own `supported_soc.csv`
     * maps `Qualcomm,SM8850,v81`. Pinning a generation would make the gate wrong on the one device
     * we have, so we ask the device.
     */
    fun hexagonGeneration(): String? {
        for (dir in VENDOR_LIB_DIRS) {
            val names = File(dir).list() ?: continue
            for (name in names) {
                val match = HTP_STUB.matchEntire(name) ?: continue
                // The matching skel lives on the DSP side (/vendor/lib/rfsa/adsp), which app
                // processes cannot list, so the stub is the readable half of the pair.
                return "V${match.groupValues[1]}"
            }
        }
        return null
    }

    fun vendorHtpPresent(): Boolean =
        VENDOR_LIB_DIRS.any { File(it, "libQnnHtp.so").isFile }

    // ---------------------------------------------------------------------------------------
    // Crash marker
    // ---------------------------------------------------------------------------------------

    /**
     * A file written immediately before we construct `Backend.NPU`, deleted as soon as that
     * attempt has either succeeded or thrown.
     *
     * A mismatched dispatch runtime does not throw - it calls `abort()`. The process dies inside
     * `Engine.initialize()` with nothing catchable, so the only way to learn from it is to leave a
     * note on disk first. If the note is still there next launch, the previous attempt took the
     * process with it and we do not try again.
     *
     * The note records a fingerprint of the dispatch `.so`, so dropping in a *different* library
     * (the whole point of the AI Hub / QNN follow-up) automatically re-arms the attempt instead of
     * requiring the user to clear app data.
     */
    private fun marker(context: Context) = File(context.filesDir, "npu-init.marker")

    private fun dispatchFingerprint(context: Context): String {
        val lib = File(context.applicationInfo.nativeLibraryDir, DISPATCH_LIB)
        if (!lib.isFile) return "absent"
        return "${lib.length()}:${lib.lastModified()}"
    }

    /** True when the last NPU init attempt with *this* dispatch library killed the process. */
    fun npuInitCrashed(context: Context): Boolean {
        val marker = marker(context)
        if (!marker.isFile) return false
        val recorded = runCatching { marker.readText().trim() }.getOrNull()
        if (recorded == dispatchFingerprint(context)) return true
        // Different library than the one that crashed - allow one clean attempt.
        runCatching { marker.delete() }
        return false
    }

    fun beginNpuAttempt(context: Context) {
        runCatching { marker(context).writeText(dispatchFingerprint(context)) }
    }

    fun endNpuAttempt(context: Context) {
        runCatching { marker(context).delete() }
    }
}
