package com.hereliesaz.graffitixr.feature.dashboard

import com.hereliesaz.graffitixr.common.model.EditorMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnboardingManager @Inject constructor() {

    private var completedModes = mutableSetOf<EditorMode>()

    fun isModeCompleted(mode: EditorMode): Boolean {
        return completedModes.contains(mode)
    }

    fun markModeCompleted(mode: EditorMode) {
        completedModes.add(mode)
    }

    fun getDisplayName(mode: EditorMode): String {
        return mode.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    fun resetAll() {
        completedModes.clear()
    }
}