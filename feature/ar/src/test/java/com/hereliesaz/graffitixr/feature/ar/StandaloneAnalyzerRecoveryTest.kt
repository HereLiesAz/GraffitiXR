package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneAnalyzerRecoveryTest {

    @Test
    fun `too weak replacement with a validated previous target restores it and restarts`() {
        assertEquals(
            StandaloneFatalRecovery.RESTORE_PREVIOUS_AND_RESTART,
            standaloneFatalRecovery(referenceTooWeak = true, hasPreviousValidatedReference = true),
        )
    }

    @Test
    fun `too weak with nothing to restore asks for a richer target`() {
        assertEquals(
            StandaloneFatalRecovery.REPORT_TOO_WEAK,
            standaloneFatalRecovery(referenceTooWeak = true, hasPreviousValidatedReference = false),
        )
    }

    @Test
    fun `any other fatal error is reported, never silently restored`() {
        assertEquals(
            StandaloneFatalRecovery.REPORT_FAILURE,
            standaloneFatalRecovery(referenceTooWeak = false, hasPreviousValidatedReference = true),
        )
        assertEquals(
            StandaloneFatalRecovery.REPORT_FAILURE,
            standaloneFatalRecovery(referenceTooWeak = false, hasPreviousValidatedReference = false),
        )
    }

    @Test
    fun `recapture restarts the analyzer only when it is fatal`() {
        assertTrue(shouldRestartAnalyzerOnRecapture(analyzerFatal = true))
        assertFalse(shouldRestartAnalyzerOnRecapture(analyzerFatal = false))
    }

    @Test
    fun `a fatal analyzer never shows the preparing text`() {
        val tooWeak = StandaloneText(StandaloneTextKey.TARGET_TOO_WEAK, listOf(3, 16))
        assertEquals(
            tooWeak,
            standaloneInitializingStatusText(
                analyzerFatal = true,
                fatalMessage = tooWeak,
                currentFailure = null,
                referenceReady = false,
                hasReference = true,
            ),
        )
        val fatalEvent = StandaloneFailureClassifier.event(
            StandaloneFailureReason.entries.first { StandaloneFailureClassifier.event(it).severity == StandaloneFailureSeverity.FATAL },
        )
        assertEquals(
            fatalEvent.userText,
            standaloneInitializingStatusText(true, null, fatalEvent, referenceReady = false, hasReference = true),
        )
        assertEquals(
            StandaloneText(StandaloneTextKey.TRACKING_STOPPED),
            standaloneInitializingStatusText(true, null, null, referenceReady = false, hasReference = true),
        )
    }

    @Test
    fun `a live analyzer shows mapping, preparing or finding`() {
        assertEquals(
            StandaloneTextKey.STATUS_MAPPING,
            standaloneInitializingStatusText(false, null, null, referenceReady = false, hasReference = false).key,
        )
        assertEquals(
            StandaloneTextKey.STATUS_PREPARING,
            standaloneInitializingStatusText(false, null, null, referenceReady = false, hasReference = true).key,
        )
        assertEquals(
            StandaloneTextKey.STATUS_FINDING,
            standaloneInitializingStatusText(false, null, null, referenceReady = true, hasReference = true).key,
        )
        val transient = StandaloneFailureClassifier.event(
            StandaloneFailureReason.entries.first { StandaloneFailureClassifier.event(it).severity == StandaloneFailureSeverity.TRANSIENT },
        )
        assertEquals(
            transient.userText,
            standaloneInitializingStatusText(false, null, transient, referenceReady = false, hasReference = true),
        )
    }

    @Test
    fun `every failure reason has its own message key`() {
        val keys = StandaloneFailureReason.entries.map { it.textKey() }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.name.startsWith("FAILURE_") })
    }
}
