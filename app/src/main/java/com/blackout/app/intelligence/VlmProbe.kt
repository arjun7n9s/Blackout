package com.blackout.app.intelligence

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.SessionConfig
import java.io.File

/**
 * Can the Gemma file we already ship actually see?
 *
 * The static evidence says yes. `gemma-4-E2B-it.litertlm` is a Gemma 3n build - the per-layer
 * embeddings give that away - and the file carries a real vision tower, not just config residue:
 * `vision_adapter_70/140/280`, `mm_embedding`, `soft_tokens`, and compiled graph nodes under
 * `adapt_vision/mm_adapter/project_soft_tokens`. Three resolution tiers at 70/140/280 soft tokens,
 * so a full page costs less than one batch of OCR text. [EngineConfig] has taken a `visionBackend`
 * the whole time; we have been passing `null` to it.
 *
 * None of that is proof it executes. The GPU path already taught us this once: `initialize()`
 * returned happily and every `generate` then failed with "Can not find OpenCL library", which is
 * why [LiteRtLlmRuntime] gates its [LiteRtLlmRuntime.backendLabel] on a warm-up generation rather
 * than on a successful init. The vision tower is a second, separate graph on a second backend, so
 * it gets the same treatment: the answer is the tokens it returns for a real image, or nothing.
 *
 * Read-only and debug-gated. Never on the redact path:
 *
 *   adb shell am start -n com.blackout.app/.MainActivity --ez vlm_probe true
 */
object VlmProbe {

    private const val TAG = "BlackoutVlm"

    data class Result(
        val ok: Boolean,
        val detail: String,
        val declaresVision: Boolean = false,
        val visionTokenBudget: Int = 0,
        val reply: String = "",
        val elapsedMs: Long = 0,
    )

    fun run(context: Context, fixture: String? = null): Result {
        val model = File(context.getExternalFilesDir("models"), ModelCatalog.GEMMA.fileName)
        if (!model.isFile) return Result(false, "model missing: ${model.absolutePath}")

        // Step 1: ask the file what it claims. Cheap, and it tells us whether a failure later is
        // "this build has no vision tower" or "the tower is there and the backend choked".
        val (declares, budget) = runCatching {
            Capabilities(model.absolutePath).use { caps ->
                val modalities = caps.inputModalities()
                Log.i(
                    TAG,
                    "declared modalities: text=${modalities.text} vision=${modalities.vision} " +
                        "audio=${modalities.audio} video=${modalities.video} " +
                        "visionTokenBudget=${caps.maxVisionTokenBudget()}",
                )
                modalities.vision to caps.maxVisionTokenBudget()
            }
        }.getOrElse {
            Log.w(TAG, "Capabilities failed: ${it.message}")
            false to 0
        }

        if (!declares) {
            return Result(false, "model does not declare vision input", declaresVision = false)
        }

        // Step 2: the part that actually counts.
        val image = imageBytes(context, fixture)
            ?: return Result(false, "no probe image", declaresVision = true, visionTokenBudget = budget)

        val started = System.currentTimeMillis()
        var engine: Engine? = null
        return try {
            engine = Engine(
                EngineConfig(
                    modelPath = model.absolutePath,
                    backend = Backend.GPU(),
                    // The whole point of the probe. Null here is what we ship today.
                    visionBackend = Backend.GPU(),
                    audioBackend = null,
                    maxNumTokens = ModelCatalog.GEMMA.maxNumTokens,
                    cacheDir = context.cacheDir.absolutePath,
                )
            )
            engine.initialize()

            val session = engine.createSession(SessionConfig())
            val reply = session.generateContent(
                listOf(
                    InputData.Image(image),
                    InputData.Text(PROBE_PROMPT),
                )
            )
            runCatching { session.close() }

            val ms = System.currentTimeMillis() - started
            val ok = reply.isNotBlank()
            Log.i(
                TAG,
                if (ok) "VISION OK in ${ms}ms, ${image.size / 1024}KB image -> ${reply.trim().take(160)}"
                else "vision returned no tokens in ${ms}ms",
            )
            Result(
                ok = ok,
                detail = if (ok) "vision generation returned tokens" else "empty reply",
                declaresVision = true,
                visionTokenBudget = budget,
                reply = reply.trim(),
                elapsedMs = ms,
            )
        } catch (t: Throwable) {
            val ms = System.currentTimeMillis() - started
            Log.w(TAG, "vision generation failed after ${ms}ms: ${t.message}", t)
            Result(false, t.message ?: "threw", declaresVision = true, visionTokenBudget = budget, elapsedMs = ms)
        } finally {
            runCatching { engine?.close() }
        }
    }

    /** A pushed fixture if one was named, otherwise whichever fixture is present. */
    private fun imageBytes(context: Context, fixture: String?): ByteArray? {
        val dir = File(context.getExternalFilesDir(null), "fixtures")
        val file = fixture?.let { File(dir, it) }
            ?: dir.listFiles()?.firstOrNull { it.extension.lowercase() in setOf("png", "jpg", "jpeg") }
        if (file == null || !file.isFile) {
            Log.w(TAG, "no probe image under ${dir.absolutePath}")
            return null
        }
        Log.i(TAG, "probe image: ${file.name} (${file.length() / 1024}KB)")
        return runCatching { file.readBytes() }.getOrNull()
    }

    /**
     * Deliberately asks for something only a model that actually saw the page could answer, and
     * that a text-only fallback would get wrong. "Describe" would be satisfied by a hallucination.
     */
    private const val PROBE_PROMPT =
        "What kind of document is this, and what is written in its largest heading? " +
            "Answer in one short sentence."
}
