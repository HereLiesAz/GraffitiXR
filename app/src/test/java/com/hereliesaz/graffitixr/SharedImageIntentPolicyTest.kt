package com.hereliesaz.graffitixr

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedImageIntentPolicyTest {

    // FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY is 0x00100000 (android.content.Intent); written out so the
    // test does not derive its expectation from the constant the code reads.
    private val launchedFromHistory = 0x00100000

    @Test
    fun `a fresh share is consumed`() {
        assertTrue(SharedImageIntentPolicy.shouldConsume(isRestoredInstance = false, launchFlags = 0))
    }

    @Test
    fun `a recreated activity does not re-apply its launch share`() {
        assertFalse(SharedImageIntentPolicy.shouldConsume(isRestoredInstance = true, launchFlags = 0))
    }

    @Test
    fun `a relaunch from Recents does not re-apply the original share`() {
        assertFalse(SharedImageIntentPolicy.shouldConsume(isRestoredInstance = false, launchFlags = launchedFromHistory))
        assertFalse(
            SharedImageIntentPolicy.shouldConsume(
                isRestoredInstance = false,
                launchFlags = launchedFromHistory or Intent.FLAG_ACTIVITY_NEW_TASK,
            ),
        )
    }
}
