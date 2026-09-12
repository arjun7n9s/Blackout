package com.blackout.app.intelligence

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File

/** What a runtime can do, kept as an interface so the pipeline can be tested with a fake. */
interface LlmRuntime : AutoCloseable {
    val displayName: String
    val backendLabel: String
    fun load()
    fun generate(system: String, prompt: String, schema: String?, maxOutputTokens: Int): String
}

/**
 * LiteRT-LM backed runtime for one model file.
 *
 * Two deliberate choices:
 *
 * **A fresh [Conversation] per request.** The engine is expensive to build but a conversation is
 * cheap, and reusing one would accumulate KV across batches. With Qwen3's 1280-token cache that
 * would silently truncate the third or fourth batch. Per-request conversations keep every batch
 * starting from a clean cache.
 *
 * **NPU → GPU → CPU**, each proven with a warm-up generation before commit. NPU is only
 * *attempted* when [NpuSupport.willAttemptNpu] is true: dispatch `.so` on disk, plus either
 * an SoC-matched AOT pack or the Qualcomm compiler plugin (JIT of the generic file).
 * Missing dispatch is not probed: `Backend.NPU` without
 * `libLiteRtDispatch_Qualcomm.so` SIGABRTs the process. [backendLabel] is what actually
 * sampled, never what we hoped for.
 */
class LiteRtLlmRuntime(
    private val context: Context,
    private val spec: ModelSpec,
    private val modelFile: File,
) : LlmRuntime {

    private var engine: Engine? = null

    override val displayName: String get() = spec.displayName

    override var backendLabel: String = "unloaded"
        private set

    @Synchronized
    override fun load() {
        if (engine != null) return
        require(modelFile.isFile) { "model missing: ${modelFile.absolutePath}" }

        var lastError: Throwable? = null
        for (candidate in backendCandidates()) {
            var built: Engine? = null
            try {
                val started = System.currentTimeMillis()
                built = Engine(
                    EngineConfig(
                        modelPath = candidate.modelFile.absolutePath,
                        backend = candidate.backend,
                        visionBackend = null,
                        audioBackend = null,
                        maxNumTokens = spec.maxNumTokens,
                        cacheDir = context.cacheDir.absolutePath,
                    )
                )
                built.initialize()

                // initialize() succeeding is NOT proof the backend works. Before we declared
                // uses-native-library libOpenCL.so, GPU init succeeded then every generate failed
                // with "Can not find OpenCL library". Warm-up is the gate: HUD says NPU/GPU
                // only if this one-token generate returned.
                warmUp(built)

                engine = built
                backendLabel = candidate.label
                Log.i(
                    TAG,
                    "${spec.displayName} loaded on ${candidate.label} in " +
                        "${System.currentTimeMillis() - started}ms " +
                        "(${candidate.modelFile.name})",
                )
                return
            } catch (t: Throwable) {
                Log.w(TAG, "${spec.displayName} unusable on ${candidate.label}: ${t.message}")
                runCatching { built?.close() }
                lastError = t
            }
        }
        backendLabel = "failed"
        throw IllegalStateException(
            "could not load ${spec.displayName} on any backend", lastError
        )
    }

    override fun generate(
        system: String,
        prompt: String,
        schema: String?,
        maxOutputTokens: Int,
    ): String {
        val active = engine ?: throw IllegalStateException("${spec.displayName} not loaded")

        var conversation: Conversation? = null
        try {
            conversation = active.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    // Greedy via topK=1. Temperature stays at 1.0 deliberately - with topK=1 the
                    // argmax is already forced, and a 0.0 temperature divides through some
                    // sampler implementations.
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 0),
                    maxOutputToken = maxOutputTokens,
                    // Qwen3 is a hybrid-thinking model and reasons by default. An unbudgeted
                    // <think> block would consume the entire 1280-token cache and emit zero
                    // decisions. Belt and braces: the config flag, the template variable, and a
                    // literal /no_think appended to the prompt, plus the parser strips any
                    // <think> that still leaks through.
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    extraContext = mapOf("enable_thinking" to false),
                    enableResponseFormat = schema != null,
                )
            )
            val reply = conversation.sendMessage(
                prompt,
                responseFormat = schema?.let { ResponseFormat.json(it) },
            )
            val text = reply.contents.contents
                .filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                .joinToString("") { it.text }
                .ifBlank { reply.toString() }
            if (com.blackout.app.BuildConfig.DEBUG) {
                // Info level on purpose: this handset's log daemon drops DEBUG from app tags.
                Log.i(TAG, "PROMPT[${spec.displayName}] " + prompt.replace('\n', '|'))
                Log.i(TAG, "REPLY[${spec.displayName}] " + text.replace('\n', '|').take(600))
            }
            return text
        } finally {
            runCatching { conversation?.close() }
        }
    }

    @Synchronized
    override fun close() {
        runCatching { engine?.close() }
        engine = null
        backendLabel = "unloaded"
    }

    /** Smallest possible real generation, used to prove a backend can sample. */
    private fun warmUp(candidate: Engine) {
        var conversation: Conversation? = null
        try {
            conversation = candidate.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 0),
                    maxOutputToken = 1,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    extraContext = mapOf("enable_thinking" to false),
                )
            )
            conversation.sendMessage("hi")
        } finally {
            runCatching { conversation?.close() }
        }
    }

    private data class BackendChoice(
        val label: String,
        val backend: Backend,
        val modelFile: File,
    )

    private fun backendCandidates(): List<BackendChoice> {
        val npuWeights = ModelCatalog(context).locateNpu(spec)
        NpuSupport.logSkip(context, spec, npuWeights)

        val out = mutableListOf<BackendChoice>()
        if (NpuSupport.willAttemptNpu(context, npuWeights)) {
            val npuFile = npuWeights ?: modelFile
            val how = if (npuWeights != null) "AOT" else "JIT"
            Log.i(TAG, "trying NPU ($how) for ${spec.displayName} with ${npuFile.name}")
            out += BackendChoice(
                "NPU",
                Backend.NPU(context.applicationInfo.nativeLibraryDir),
                npuFile,
            )
        }
        out += BackendChoice("GPU", Backend.GPU(), modelFile)
        out += BackendChoice("CPU", Backend.CPU(), modelFile)
        return out
    }

    private companion object {
        const val TAG = "BlackoutLlm"
    }
}
