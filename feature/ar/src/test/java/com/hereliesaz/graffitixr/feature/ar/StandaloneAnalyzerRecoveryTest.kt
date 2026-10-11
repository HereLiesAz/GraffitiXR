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
        val text = standaloneInitializingStatusText(
            analyzerFatal = true,
            fatalMessage = "That target has too little trackable detail",
            currentFailure = null,
            referenceReady = false,
            hasReference = true,
        )
        assertEquals("That target has too little trackable detail", text)
        val fatalEvent = StandaloneFailureClassifier.event(
            StandaloneFailureReason.entries.first { StandaloneFailureClassifier.event(it).severity == StandaloneFailureSeverity.FATAL },
        )
        assertEquals(
            fatalEvent.userMessage,
            standaloneInitializingStatusText(true, null, fatalEvent, referenceReady = false, hasReference = true),
        )
        assertEquals(
            STANDALONE_TRACKING_STOPPED_MESSAGE,
            standaloneInitializingStatusText(true, null, null, referenceReady = false, hasReference = true),
        )
    }

    @Test
    fun `a live analyzer shows mapping, preparing or finding`() {
        assertEquals(
            "SphereSLAM mapping • tap Target to add fingerprint",
            standaloneInitializingStatusText(false, null, null, referenceReady = false, hasReference = false),
        )
        assertEquals(
            "Preparing fingerprint target…",
            standaloneInitializingStatusText(false, null, null, referenceReady = false, hasReference = true),
        )
        assertEquals(
            "Finding fingerprint target…",
            standaloneInitializingStatusText(false, null, null, referenceReady = true, hasReference = true),
        )
        val transient = StandaloneFailureClassifier.event(
            StandaloneFailureReason.entries.first { StandaloneFailureClassifier.event(it).severity == StandaloneFailureSeverity.TRANSIENT },
        )
        assertEquals(
            transient.userMessage,
            standaloneInitializingStatusText(false, null, transient, referenceReady = false, hasReference = true),
        )
    }
}
