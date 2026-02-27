package com.hereliesaz.graffitixr.feature.dashboard

import com.hereliesaz.graffitixr.common.model.EditorMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OnboardingManagerTest {

    private lateinit var manager: OnboardingManager

    @Before
    fun setup() {
        manager = OnboardingManager()
    }

    @Test
    fun `isModeCompleted returns false for fresh manager`() {
        EditorMode.values().forEach { mode ->
            assertFalse("$mode should not be completed initially", manager.isModeCompleted(mode))
        }
    }

    @Test
    fun `markModeCompleted marks a single mode`() {
        manager.markModeCompleted(EditorMode.AR)
        assertTrue(manager.isModeCompleted(EditorMode.AR))
        assertFalse(manager.isModeCompleted(EditorMode.MOCKUP))
    }

    @Test
    fun `resetAll clears all completed modes`() {
        EditorMode.values().forEach { manager.markModeCompleted(it) }
        manager.resetAll()
        EditorMode.values().forEach { mode ->
            assertFalse("$mode should not be completed after reset", manager.isModeCompleted(mode))
        }
    }

    @Test
    fun `getDisplayName returns capitalized mode name`() {
        val name = manager.getDisplayName(EditorMode.AR)
        assertTrue(name.isNotEmpty())
        assertTrue(name[0].isUpperCase())
    }
}
