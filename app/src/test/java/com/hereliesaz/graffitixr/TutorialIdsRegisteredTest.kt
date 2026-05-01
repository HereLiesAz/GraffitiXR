package com.hereliesaz.graffitixr

import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts the tutorial keys reachable from MainActivity's trigger logic
 * (lines 501-506) exist in the registered tutorials map. The trigger logic
 * uses these keys:
 *   "mode.ar.firstRun", "mode.overlay.firstRun",
 *   "mode.mockup.firstRun", "mode.trace.firstRun".
 *
 * The mode-tutorial keys come from GraffitiTutorials.kt; the rest from
 * TutorialDefinitions.kt. MainActivity merges both maps, so this test
 * verifies the merged set covers all trigger paths.
 *
 * GraffitiTutorials.getGraffitiTutorials requires a Context. Since this is
 * a unit test, we mock Context and rely on string-resource calls being
 * relaxed by MockK. The test passes if the keys exist; the resolved string
 * values are not asserted.
 */
class TutorialIdsRegisteredTest {

    private val triggerKeys = setOf(
        "mode.ar.firstRun",
        "mode.overlay.firstRun",
        "mode.mockup.firstRun",
        "mode.trace.firstRun",
    )

    @Test
    fun `getGraffitiTutorials registers every mode trigger key`() {
        val context = mockk<android.content.Context>(relaxed = true)
        val map = getGraffitiTutorials(context)
        triggerKeys.forEach { key ->
            assertTrue("trigger key '$key' is not registered in getGraffitiTutorials", key in map)
        }
    }
}
