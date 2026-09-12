package com.blackout.app.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HUD's honesty is a test, not a convention.
 *
 * On 2026-09-12 a dispatch library from LiteRT 2.1.6 made `Engine.initialize()` and a warm-up
 * generation both succeed while LiteRT silently ran XNNPACK, and the HUD read
 * `on-device · local models · NPU`. Everything below exists so that cannot be re-introduced.
 */
class BackendReportTest {

    private fun det(count: Int) = StageReport(
        Silicon.CPU, StageReport.DETERMINISTIC, StageStatus.OK, count, 12,
    )

    private val npuSkipped = StageReport(
        Silicon.NPU, StageReport.CLASSIFIER, StageStatus.SKIPPED, 0, 0, "no dispatch library",
    )

    @Test
    fun `hybrid line names the silicon that actually ran each stage`() {
        val report = BackendReport(
            listOf(
                det(24),
                npuSkipped,
                StageReport(Silicon.GPU, StageReport.WORKHORSE, StageStatus.OK, 21, 18338),
                StageReport(Silicon.GPU, StageReport.REFEREE, StageStatus.OK, 8, 10109),
            ),
            totalMs = 28803,
        )
        assertEquals(
            "CPU·det 24 | NPU·cls skip | GPU·qwen 21 · gemma 8 | total 28.8s",
            report.hudLine(),
        )
    }

    @Test
    fun `a skipped NPU stage can never read as an NPU inference`() {
        val report = BackendReport(listOf(det(24), npuSkipped), totalMs = 12)
        assertTrue(report.hudLine().contains("NPU·cls skip"))
        assertFalse(report.hudLine().contains("cls 0"))
        assertFalse(report.chip().contains("NPU"))
    }

    @Test
    fun `a cascade that fell back to XNNPACK reports CPU, not GPU`() {
        // Phone C's loaner: no OpenCL, so LiteRT loaded the CPU backend. The report must say so.
        val report = BackendReport(
            listOf(
                det(24),
                npuSkipped,
                StageReport(Silicon.CPU, StageReport.WORKHORSE, StageStatus.OK, 61, 36967, "CPU"),
                StageReport(Silicon.CPU, StageReport.REFEREE, StageStatus.OK, 8, 28290, "CPU"),
            ),
            totalMs = 66607,
        )
        assertEquals(
            "CPU·det 24 · qwen 61 · gemma 8 | NPU·cls skip | total 66.6s",
            report.hudLine(),
        )
        assertFalse(report.hudLine().contains("GPU"))
    }

    @Test
    fun `a refereeless page says so instead of printing a zero`() {
        val report = BackendReport(
            listOf(
                det(30),
                npuSkipped,
                StageReport(Silicon.GPU, StageReport.WORKHORSE, StageStatus.OK, 111, 89835),
                StageReport(
                    Silicon.GPU, StageReport.REFEREE, StageStatus.SKIPPED, 0, 0,
                    "dense page: 141 spans > 100",
                ),
            ),
            totalMs = 89835,
        )
        assertTrue(report.hudLine().contains("gemma skip"))
    }

    @Test
    fun `a failed stage is never dressed up as OK`() {
        val report = BackendReport(
            listOf(StageReport(Silicon.GPU, StageReport.WORKHORSE, StageStatus.FAILED, 0, 0)),
            totalMs = 0,
        )
        assertTrue(report.hudLine().contains("qwen fail"))
        assertEquals("", report.chip())
    }

    @Test
    fun `chip counts only what ran`() {
        val report = BackendReport(
            listOf(
                det(24),
                npuSkipped,
                StageReport(Silicon.GPU, StageReport.WORKHORSE, StageStatus.OK, 21, 18338),
                StageReport(Silicon.GPU, StageReport.REFEREE, StageStatus.OK, 8, 10109),
            ),
            totalMs = 28803,
        )
        assertEquals("CPU 24 · GPU 29", report.chip())
    }
}
