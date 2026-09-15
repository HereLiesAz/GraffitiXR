package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionOwnershipPolicyTest {

    @Test
    fun `successful cleanup handoff may proceed`() {
        assertEquals(
            SessionOwnershipDecision.PROCEED,
            decideSessionOwnership(true, SessionOwnershipAction.CLEANUP),
        )
    }

    @Test
    fun `successful reconfigure handoff may proceed`() {
        assertEquals(
            SessionOwnershipDecision.PROCEED,
            decideSessionOwnership(true, SessionOwnershipAction.RECONFIGURE),
        )
    }

    @Test
    fun `cleanup timeout defers native teardown`() {
        assertEquals(
            SessionOwnershipDecision.DEFER_CLEANUP,
            decideSessionOwnership(false, SessionOwnershipAction.CLEANUP),
        )
    }

    @Test
    fun `reconfigure timeout aborts live mutation`() {
        assertEquals(
            SessionOwnershipDecision.ABORT_RECONFIGURE,
            decideSessionOwnership(false, SessionOwnershipAction.RECONFIGURE),
        )
    }
}
