package com.blackout.app.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NpuSupportTest {

    @Test
    fun `sm8850 names never include the sm8750 pack`() {
        val names = NpuSupport.npuWeightNames("gemma-4-E2B-it.litertlm", "SM8850")
        assertEquals(
            listOf(
                "gemma-4-E2B-it_qualcomm_sm8850.litertlm",
                "gemma-4-E2B-it_sm8850.litertlm",
            ),
            names,
        )
        assertFalse(names.any { it.contains("sm8750") })
    }

    @Test
    fun `qwen generic maps to the two SoC-qualified names`() {
        val names = NpuSupport.npuWeightNames(
            "qwen3_0.6b_q4_block32_ekv1280.litertlm",
            "sm8850",
        )
        assertTrue(names[0].startsWith("qwen3_0.6b_q4_block32_ekv1280_qualcomm_sm8850"))
        assertFalse(names.any { it.contains("sm8750") })
    }

    @Test
    fun `blank SoC yields no NPU filenames`() {
        assertTrue(NpuSupport.npuWeightNames("gemma-4-E2B-it.litertlm", "  ").isEmpty())
    }

    @Test
    fun `missing dispatch file is not present`() {
        val dir = kotlin.io.path.createTempDirectory("npu-support").toFile()
        File(dir, "unrelated.so").writeText("x")
        assertFalse(NpuSupport.dispatchPresent(dir))
        dir.deleteRecursively()
    }

    @Test
    fun `dispatch is present only when the Qualcomm so is on disk`() {
        val dir = kotlin.io.path.createTempDirectory("npu-support").toFile()
        File(dir, NpuSupport.DISPATCH_LIB).writeText("x")
        assertTrue(NpuSupport.dispatchPresent(dir))
        dir.deleteRecursively()
    }
}
