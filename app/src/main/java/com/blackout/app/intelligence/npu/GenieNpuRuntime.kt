package com.blackout.app.intelligence.npu

import android.content.Context
import android.util.Log
import com.blackout.app.intelligence.LlmRuntime
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The workhorse running on the Hexagon NPU, through Qualcomm's Genie/QAIRT runtime.
 *
 * Measured on the loaner (SM8850 / HTP v81): **prefill 2120 tok/s, decode 130 tok/s, TTFT 17 ms**
 * on Qwen3-0.6B w4a16 — the same model the LiteRT path runs, where a workhorse pass over 19 spans
 * costs ~7.6 s.
 *
 * ## Honesty
 *
 * [backendLabel] starts as `unloaded` and only becomes `NPU` after [load] has both created the
 * engine *and* had a warm-up generation return tokens. `GpuLlmCascadeStage.siliconOf` maps that
 * string onto [com.blackout.app.intelligence.Silicon], so the HUD can only read `NPU·qwen` once
 * real tokens came off the DSP. A failed or absent NPU leaves the label unset and the caller falls
 * back to LiteRT, which does its own warm-up proof.
 *
 * ## Three traps, all of them load-bearing
 *
 * 1. **`nCtx` must be 0.** Context size, sampler and backend all come from the bundle's own
 *    `genie_config.json`; supplying our own is rejected with "Parameter not supported by this
 *    plugin".
 * 2. **`model_path` is `genie_config.json`**, not the bundle directory — a directory gives
 *    "File not found or inaccessible".
 * 3. **The bundle directory must be created by the app.** Anything made by `adb shell mkdir`, or
 *    implicitly by `adb push <dir>`, is owned by `shell` and the app cannot traverse it: the
 *    bundle reads as present but empty.
 *
 * ## No JSON
 *
 * [supportsJsonSchema] is false. Grammar-constrained generation is rejected by QAIRT on this SoC,
 * so the cascade asks this runtime for a bare hide-list and parses it with
 * [com.blackout.app.intelligence.HideListParser].
 */
class GenieNpuRuntime(
    private val context: Context,
    private val bundle: File,
) : LlmRuntime {

    private var wrapper: LlmWrapper? = null

    override val displayName: String = "Qwen3-0.6B-w4a16 (Genie)"

    override var backendLabel: String = "unloaded"
        private set

    /** QAIRT cannot pin a reply shape, so the cascade must use the plain-text protocol. */
    override val supportsJsonSchema: Boolean = false

    @Synchronized
    override fun load() {
        if (wrapper != null) return

        val genieConfig = File(bundle, "genie_config.json")
        val tokenizer = File(bundle, "tokenizer.json")
        require(genieConfig.isFile && tokenizer.isFile) {
            "genie bundle incomplete at ${bundle.absolutePath}"
        }

        val sdk = GenieXSdk.getInstance() ?: error("GenieXSdk.getInstance() returned null")
        runBlocking {
            initSdk(sdk)?.let { error("GenieX init failed: $it") }
        }
        sdk.registerPlugin(GenieXSdk.PLUGIN_ID_QAIRT)

        val started = System.currentTimeMillis()
        val built = runBlocking {
            LlmWrapper.builder()
                .llmCreateInput(
                    LlmCreateInput(
                        model_path = genieConfig.absolutePath,
                        tokenizer_path = tokenizer.absolutePath,
                        // nCtx = 0: the bundle's genie_config.json owns context and sampler.
                        config = ModelConfig(nCtx = 0, nGpuLayers = 0),
                        runtime_id = GenieXSdk.PLUGIN_ID_QAIRT,
                        compute_unit = COMPUTE_NPU,
                    )
                )
                .dispatcher(Dispatchers.Default)
                .build()
                .getOrThrow()
        }
        wrapper = built

        // Creating the engine is not proof it produces tokens. Only a returned token sets the
        // label the HUD reads.
        val warm = runCatching { generateInternal(built, WARM_UP_PROMPT, maxTokens = 4) }
            .getOrElse {
                runCatching { built.close() }
                wrapper = null
                throw IllegalStateException("NPU warm-up failed: ${it.message}", it)
            }
        if (warm.isBlank()) {
            runCatching { built.close() }
            wrapper = null
            error("NPU warm-up returned no tokens")
        }

        backendLabel = "NPU"
        Log.i(
            TAG,
            "$displayName ready on Hexagon in ${System.currentTimeMillis() - started}ms " +
                "(warm-up: ${warm.trim().take(40)})",
        )
    }

    override fun generate(
        system: String,
        prompt: String,
        schema: String?,
        maxOutputTokens: Int,
    ): String {
        val active = wrapper ?: throw IllegalStateException("$displayName not loaded")
        // Genie has no separate system channel here, so the instruction is prepended into the
        // Qwen3 chat template with thinking closed off - the same shape the bundle ships in
        // sample_prompt.txt.
        val templated = buildString {
            append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
            append("<|im_start|>user\n").append(prompt).append("<|im_end|>\n")
            append("<|im_start|>assistant\n<think>\n\n</think>\n")
        }
        val text = generateInternal(active, templated, maxOutputTokens)
        if (com.blackout.app.BuildConfig.DEBUG) {
            Log.i(TAG, "PROMPT[npu] " + prompt.replace('\n', '|').take(400))
            Log.i(TAG, "REPLY[npu] " + text.replace('\n', '|').take(300))
        }
        return text
    }

    private fun generateInternal(active: LlmWrapper, prompt: String, maxTokens: Int): String =
        runBlocking {
            val builder = StringBuilder()
            var tokens = 0
            val started = System.currentTimeMillis()
            active.generateStreamFlow(prompt, generationConfig(maxTokens)).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> {
                        tokens++
                        builder.append(result.text)
                    }
                    is LlmStreamResult.Error -> Log.w(TAG, "npu stream error: $result")
                    else -> Unit
                }
            }
            val ms = System.currentTimeMillis() - started
            if (tokens > 0 && ms > 0) {
                Log.i(TAG, "npu decode ${"%.1f".format(tokens * 1000.0 / ms)} tok/s ($tokens in ${ms}ms)")
            }
            builder.toString()
        }

    /** Greedy: these are classification calls, so the same page must give the same verdict. */
    private fun generationConfig(maxTokens: Int) = GenerationConfig(
        maxTokens,
        emptyArray(), 0,
        SamplerConfig(1.0f, 1.0f, 1, 0f, 1.0f, 0f, 0f, 0, "greedy", ""),
        emptyArray(), 0, emptyArray(), 0, false, 0,
    )

    private suspend fun initSdk(sdk: GenieXSdk): String? = suspendCancellableCoroutine { cont ->
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

    @Synchronized
    override fun close() {
        runCatching { wrapper?.close() }
        wrapper = null
        backendLabel = "unloaded"
    }

    companion object {
        private const val TAG = "BlackoutNpu"
        private val COMPUTE_NPU: String = ComputeUnitValue.NPU.value ?: "npu"

        private const val WARM_UP_PROMPT =
            "<|im_start|>user\nSay ok.<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n"

        /** The side-loaded bundle, or null when it hasn't been pushed. */
        fun locate(context: Context): File? {
            val root = context.getExternalFilesDir(null) ?: return null
            // Create it ourselves so side-loading pushes into an app-owned directory.
            val dir = File(root, GenieNpuProbe.BUNDLE_DIR)
            dir.mkdirs()
            return dir.takeIf { File(it, "genie_config.json").isFile }
        }
    }
}
