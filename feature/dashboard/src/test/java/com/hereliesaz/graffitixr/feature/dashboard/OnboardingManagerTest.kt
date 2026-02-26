package com.hereliesaz.graffitixr.feature.dashboard

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OnboardingManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var manager: OnboardingManager

    @Before
    fun setup() {
        context = mockk()
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.apply() } returns Unit

        manager = OnboardingManager(context)
    }

    @Test
    fun `getCompletedModes returns modes from prefs`() {
        every { prefs.getBoolean(EditorMode.EDIT.name, false) } returns true
        every { prefs.getBoolean(EditorMode.AR.name, false) } returns false
        // Mock others to false
        EditorMode.values().forEach {
            if (it != EditorMode.EDIT) {
                every { prefs.getBoolean(it.name, false) } returns false
            }
        }

        val completed = manager.getCompletedModes()
        assertEquals(setOf(EditorMode.EDIT), completed)
    }

    @Test
    fun `completeMode updates prefs`() {
        manager.completeMode(EditorMode.AR)

        verify { editor.putBoolean(EditorMode.AR.name, true) }
        verify { editor.apply() }
    }

    @Test
    fun `resetOnboarding clears all modes`() {
        manager.resetOnboarding()

        EditorMode.values().forEach {
            verify { editor.remove(it.name) }
        }
        verify { editor.apply() }
    }
}
