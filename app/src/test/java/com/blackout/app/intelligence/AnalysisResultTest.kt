package com.blackout.app.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisResultTest {

    private fun stat(backend: String) = InferenceStat(
        label = "workhorse",
        model = "Qwen3-0.6B-int4",
        backend = backend,
        spanCount = 1,
        batchCount = 1,
        elapsedMs = 1L,
    )

    @Test
    fun `HUD is null when nothing loaded`() {
        assertNull(AnalysisResult.Empty.hudBackend)
    }

    @Test
    fun `HUD never says NPU from a failed attempt`() {
        val result = AnalysisResult(
            stats = listOf(stat("failed"), stat("CPU")),
        )
        assertEquals("CPU", result.hudBackend)
    }

    @Test
    fun `mixed cascade reports both backends in load order`() {
        val result = AnalysisResult(
            stats = listOf(stat("NPU"), stat("CPU"), stat("NPU")),
        )
        assertEquals("NPU+CPU", result.hudBackend)
    }
}
