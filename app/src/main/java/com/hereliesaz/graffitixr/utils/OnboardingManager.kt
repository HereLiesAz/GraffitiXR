package com.hereliesaz.graffitixr.utils

import android.content.Context
import com.hereliesaz.graffitixr.EditorMode

class OnboardingManager(context: Context) {
    private val prefs = context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)

    fun getCompletedModes(): Set<EditorMode> {
        return prefs.getStringSet("completed_modes", emptySet())
            ?.mapNotNull { EditorMode.valueOf(it) }
            ?.toSet() ?: emptySet()
    }

    fun completeMode(mode: EditorMode) {
        val currentModes = getCompletedModes().toMutableSet()
        currentModes.add(mode)
        prefs.edit().putStringSet("completed_modes", currentModes.map { it.name }.toSet()).apply()
    }

    fun hasSeenDoubleTapHint(): Boolean {
        return prefs.getBoolean("seen_double_tap_hint", false)
    }

    fun setDoubleTapHintSeen() {
        prefs.edit().putBoolean("seen_double_tap_hint", true).apply()
    }
}
