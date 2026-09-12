package com.blackout.app.intelligence.npu

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Runs one prompt through Qualcomm's Genie runtime on the Hexagon NPU and reports what actually
 * happened.
 *
 * ## Why this exists separately from the redact path
 *
 * The rule has not changed: the HUD may not claim NPU without proof. So the first increment is a
 * probe that touches nothing — no span decisions, no `MergePolicy`, no HUD. It answers one
 * question with evidence: *do real tokens come off the DSP on this handset, and how fast?*
 * Only once that logs a token count and a tok/s figure does routing the workhorse through it
 * become an honest change.
 *
 * ## What unblocked this
 *
 * `ARCH.md` recorded the NPU as blocked on three things — an ABI-matched dispatch library, the
 * QAIRT trio (`libQnnHtpPrepare.so` / `libQnnIr.so` / `libQnnSaver.so`), and an SM8850 model pack.
 * All three were reachable without the Qualcomm software-centre login that was returning 403:
 *
 *  - `com.qualcomm.qti:geniex-android` is on **Maven Central**, and its AAR carries the whole QNN
 *    HTP v81 stack: `libQnnHtp.so`, `libQnnHtpV81{,Skel,Stub}.so`, `libQnnSystem.so`,
 *    `libQnnHtpPrepare.so`, `libQnnIr.so`, `libQnnSaver.so`.
 *  - Qualcomm publishes an AOT bundle for this exact SoC on a public S3 bucket:
 *    `qwen3_0_6b-geniex_qairt-w4a16-qualcomm_snapdragon_8_elite_gen5.zip` (643 MB) — Snapdragon
 *    8 Elite Gen 5 *is* SM8850, and it is the same Qwen3-0.6B already used as the workhorse.
 *  - Its `htp_backend_ext_config.json` declares `soc_model: 87, dsp_arch: v81, perf_profile:
 *    burst`, matching `ro.soc.model=SM8850` and the device's `libQnnHtpV81Skel.so`.
 *
 * Because the bundle is AOT (`part1_of_2.bin` / `part2_of_2.bin` are prebuilt QNN context
 * binaries) the JIT-only libraries are not on the critical path — useful later, since dropping
 * them would take ~90 MB off the APK.
 *
 * ## Known constraint, designed around rather than fought
 *
 * QAIRT grammar/JSON-constrained generation fails on device — proven on this SoC by the Tokito
 * runtime, which ships plain-text dialogue for exactly that reason. So when the workhorse does
 * move here it must not ask for JSON. It only ever needs span ids back, so a bounded plain-text
 * reply ("1 4 7") is both sufficient and cheaper than the JSON the GPU path uses.
 */
object GenieNpuProbe {

    private const val TAG = "BlackoutNpu"

    /** ComputeUnitValue.NPU.value is platform-typed; pin it once. */
    private val COMPUTE_NPU: String = ComputeUnitValue.NPU.value ?: "NPU"

    /** Bundle directory name under the app's external files dir. */
    const val BUNDLE_DIR = "npu/qwen3_0_6b-geniex_qairt-w4a16-qualcomm_snapdragon_8_elite_gen5"

    data class Result(
        val ok: Boolean,
        val detail: String,
        val tokens: Int = 0,
        val elapsedMs: Long = 0,
        val tokensPerSecond: Double = 0.0,
        val text: String = "",
        val computeUnit: String = "",
    )

    fun bundleDir(context: Context): File? {
        val root = context.getExternalFilesDir(null)
        // Create the whole path ourselves. Any directory made by `adb shell mkdir` - or implicitly
        // by `adb push <dir>` - is owned by `shell`, and the app cannot traverse into it: the
        // bundle reads as present but empty. So the app creates the directory and side-loading
        // pushes the *files* into it, never the directory itself.
        root?.let { File(it, BUNDLE_DIR).mkdirs() }
        val dir = root?.let { File(it, BUNDLE_DIR) }
        Log.i(
            TAG,
            "bundle lookup root=${root?.absolutePath} dir=${dir?.absolutePath} " +
                "exists=${dir?.exists()} isDir=${dir?.isDirectory} " +
                "children=${dir?.list()?.size ?: -1}",
        )
        return dir?.takeIf { it.isDirectory }
    }

    /**
     * @param prompt already chat-templated. The bundle ships `sample_prompt.txt` in Qwen3 format
     *   with an empty `<think></think>` block, which is what keeps the model from reasoning.
     */
    suspend fun run(
        context: Context,
        prompt: String = SAMPLE_PROMPT,
        maxTokens: Int = 64,
    ): Result = withContext(Dispatchers.Default) {
        val dir = bundleDir(context)
            ?: return@withContext Result(false, "bundle not found at files/$BUNDLE_DIR")

        val genieConfig = File(dir, "genie_config.json")
        val tokenizer = File(dir, "tokenizer.json")
        if (!genieConfig.isFile || !tokenizer.isFile) {
            return@withContext Result(false, "bundle incomplete in ${dir.name}")
        }

        val sdk = GenieXSdk.getInstance()
            ?: return@withContext Result(false, "GenieXSdk.getInstance() returned null")
        val initError = initSdk(sdk, context)
        if (initError != null) return@withContext Result(false, "sdk init failed: $initError")

        val registered = runCatching { sdk.registerPlugin(GenieXSdk.PLUGIN_ID_QAIRT) }
            .getOrElse { return@withContext Result(false, "registerPlugin threw: ${it.message}") }
        val pluginVersion = runCatching { sdk.getPluginVersion(GenieXSdk.PLUGIN_ID_QAIRT) }
            .getOrNull().orEmpty()
        Log.i(TAG, "QAIRT plugin register=$registered version='$pluginVersion'")

        // nCtx MUST be 0 for QAIRT: context size, sampler and backend all come from the bundle's
        // own genie_config.json, and passing our own values is rejected with
        // "Parameter not supported by this plugin". model_path is the bundle DIRECTORY, not the
        // config file - Genie resolves ctx-bins and the tokenizer relative to it.
        val input = LlmCreateInput(
            model_path = genieConfig.absolutePath,
            tokenizer_path = tokenizer.absolutePath,
            config = ModelConfig(nCtx = 0, nGpuLayers = 0),
            runtime_id = GenieXSdk.PLUGIN_ID_QAIRT,
            compute_unit = COMPUTE_NPU,
        )
        Log.i(TAG, "creating llm path=${genieConfig.absolutePath} runtime=qairt compute=$COMPUTE_NPU")

        val wrapper: LlmWrapper = LlmWrapper.builder()
            .llmCreateInput(input)
            .dispatcher(Dispatchers.Default)
            .build()
            .getOrElse { return@withContext Result(false, "llm build failed: ${it.message}") }

        try {
            // Greedy: this stands in for a classification call, not creative writing.
            val sampler = SamplerConfig(1.0f, 1.0f, 1, 0f, 1.0f, 0f, 0f, 0, "greedy", "")
            val config = GenerationConfig(
                maxTokens, emptyArray(), 0, sampler,
                emptyArray(), 0, emptyArray(), 0, false, 0,
            )

            val builder = StringBuilder()
            var tokens = 0
            var profile = ""
            val started = System.currentTimeMillis()

            wrapper.generateStreamFlow(prompt, config).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> {
                        tokens++
                        builder.append(result.text)
                    }
                    is LlmStreamResult.Completed -> profile = result.profile?.toString().orEmpty()
                    is LlmStreamResult.Error -> Log.w(TAG, "stream error: $result")
                    else -> Unit
                }
            }

            val elapsed = System.currentTimeMillis() - started
            val tps = if (elapsed > 0) tokens * 1000.0 / elapsed else 0.0
            Log.i(
                TAG,
                "NPU PROBE ok compute=$COMPUTE_NPU tokens=$tokens " +
                    "elapsed_ms=$elapsed tok_per_s=${"%.2f".format(tps)} profile=$profile",
            )
            Log.i(TAG, "NPU PROBE text: ${builder.toString().replace('\n', ' ').take(300)}")

            Result(
                ok = tokens > 0,
                detail = if (tokens > 0) "ok" else "no tokens returned",
                tokens = tokens,
                elapsedMs = elapsed,
                tokensPerSecond = tps,
                text = builder.toString(),
                computeUnit = COMPUTE_NPU,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "NPU probe threw", t)
            Result(false, "generate threw: ${t.message}")
        } finally {
            runCatching { wrapper.close() }
        }
    }

    private suspend fun initSdk(sdk: GenieXSdk, context: Context): String? =
        suspendCancellableCoroutine { cont ->
            runCatching {
                sdk.init(
                    context.applicationContext,
                    object : GenieXSdk.InitCallback {
                        override fun onSuccess() {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onFailure(reason: String) {
                            if (cont.isActive) cont.resume(reason)
                        }
                    },
                )
            }.onFailure { if (cont.isActive) cont.resume(it.message ?: "init threw") }
        }

    /** Qwen3 chat template with thinking disabled, mirroring the bundle's own sample_prompt.txt. */
    private val SAMPLE_PROMPT = buildString {
        append("<|im_start|>system\n")
        append("You are a helpful AI assistant.<|im_end|>\n")
        append("<|im_start|>user\n")
        append("What is gravity? Keep the answer under ten words.<|im_end|>\n")
        append("<|im_start|>assistant\n<think>\n\n</think>\n")
    }
}
