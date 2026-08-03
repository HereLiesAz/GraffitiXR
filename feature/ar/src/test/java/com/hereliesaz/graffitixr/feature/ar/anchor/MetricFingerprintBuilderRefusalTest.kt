package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `MetricFingerprintBuilder.lastRefusalReason` — the shared answer to "why did the last capture
 * attempt produce no fingerprint", written by BOTH capture flows in the app.
 *
 * A device report showed the gap this closes: the artist used the ordinary "tap directly on your
 * painted marks" flow (`MainViewModel.handleSingleCapture`), a precondition refused the capture
 * before `buildSingle` was ever called, the only record of it was an ephemeral `Toast`, and a
 * diagnostic report pulled moments later said "no refusal recorded" — true of the field it read,
 * false of what had just happened on the device.
 *
 * ## What this does not cover
 *
 * `recordAttemptOutcome` — the branch that derives a message from [MetricFingerprintBuilder.lastDetected]
 * / `lastPlaced` / `lastRequired` — is private and only reachable through `buildSingle`, which needs a
 * real `SlamManager` (native) and OpenCV's `ORB`/`CLAHE` (JNI). Neither is available in this harness,
 * the same limitation noted for `ArRenderer` elsewhere in this codebase. What IS tested here is the
 * half every caller outside this class actually depends on: [MetricFingerprintBuilder.recordPreconditionFailure],
 * the escape hatch for a refusal this class never saw.
 */
class MetricFingerprintBuilderRefusalTest {

    @Test fun `recordPreconditionFailure sets the reason verbatim`() {
        MetricFingerprintBuilder.recordPreconditionFailure("no wall plane under the tap")
        assertEquals("no wall plane under the tap", MetricFingerprintBuilder.lastRefusalReason)
    }

    /**
     * Two independent flows write here. A precondition failure from ONE (MainViewModel) must not be
     * silently overwritten back to null by the OTHER's unrelated success — but a precondition failure
     * from EITHER must overwrite whatever the field held before, since it describes what just
     * happened, not what happened earlier. This pins the second half: the last write wins.
     */
    @Test fun `a later precondition failure replaces an earlier one`() {
        MetricFingerprintBuilder.recordPreconditionFailure("first reason")
        MetricFingerprintBuilder.recordPreconditionFailure("second reason")
        assertEquals("second reason", MetricFingerprintBuilder.lastRefusalReason)
    }
}
