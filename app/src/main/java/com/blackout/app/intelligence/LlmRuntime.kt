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
 * **GPU with a CPU fallback.** NPU is not an option here: `litertlm-android` ships only
 * `liblitertlm_jni.so`, not the Qualcomm QNN libraries [Backend.NPU] needs, and the weights we
 * ship are the generic CPU/GPU builds rather than the separate per-SoC NPU files. [backendLabel]
 * reports what actually loaded so the UI can tell the truth rather than claim an NPU path.
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
                        modelPath = modelFile.absolutePath,
                        backend = candidate.backend,
                        visionBackend = null,
                        audioBackend = null,
                        maxNumTokens = spec.maxNumTokens,
                        cacheDir = context.cacheDir.absolutePath,
                    )
                )
                built.initialize()

                // initialize() succeeding is NOT proof the backend works. On this handset the GPU
                // engine initialises happily and then every generate fails with "Can not find
                // OpenCL library on this device" - OriginOS doesn't expose libOpenCL to apps. So
                // we spend one tiny generation proving the backend can actually sample before
                // committing to it, which keeps the fallback honest.
                warmUp(built)

                engine = built
                backendLabel = candidate.label
                Log.i(
                    TAG,
                    "${spec.displayName} loaded on ${candidate.label} in " +
                        "${System.currentTimeMillis() - started}ms",
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

    private data class BackendChoice(val label: String, val backend: Backend)

    private fun backendCandidates(): List<BackendChoice> = listOf(
        BackendChoice("GPU", Backend.GPU()),
        BackendChoice("CPU", Backend.CPU()),
    )

    private companion object {
        const val TAG = "BlackoutLlm"
    }
}
