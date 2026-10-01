package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneTargetQualityTest {

    @Test
    fun uniformTargetIsRejectedForContrastAndBlur() {
        val luma = ByteArray(256 * 256) { 128.toByte() }
        val report = StandaloneTargetQuality.analyze(luma, 256, 256)
        assertFalse(report.acceptable)
        assertTrue(StandaloneTargetQualityIssue.LOW_CONTRAST in report.blockingIssues)
        assertTrue(StandaloneTargetQualityIssue.BLURRY in report.blockingIssues)
    }

    @Test
    fun detailedCheckerboardPassesImagePreflight() {
        val width = 256
        val height = 256
        val luma = ByteArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (((x / 8) + (y / 8)) % 2 == 0) 32.toByte() else 224.toByte()
        }
        val report = StandaloneTargetQuality.analyze(luma, width, height)
        assertTrue(report.acceptable)
    }

    @Test
    fun tinyTargetIsRejected() {
        val luma = ByteArray(64 * 64) { i -> (i and 0xff).toByte() }
        val report = StandaloneTargetQuality.analyze(luma, 64, 64)
        assertTrue(StandaloneTargetQualityIssue.TOO_SMALL in report.blockingIssues)
    }

    @Test
    fun clippedExposureProducesWarning() {
        val black = ByteArray(256 * 256) { 0 }
        val report = StandaloneTargetQuality.analyze(black, 256, 256)
        assertTrue(StandaloneTargetQualityIssue.TOO_DARK in report.warnings)
    }
}
