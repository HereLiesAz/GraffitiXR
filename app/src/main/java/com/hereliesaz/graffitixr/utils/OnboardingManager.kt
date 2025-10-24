package com.hereliesaz.graffitixr.utils

import android.content.Context
import com.hereliesaz.graffitixr.EditorMode

class OnboardingManager(context: Context) {
    private val prefs = context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)

    fun getCompletedModes(): Set<EditorMode> {
        return prefs.getStringSet("completed_modes", emptySet())
            ?.mapNotNull { runCatching { EditorMode.valueOf(it) }.getOrNull() }
            ?.toSet() ?: emptySet()
    }

    fun completeMode(mode: EditorMode) {
        val currentModes = getCompletedModes().toMutableSet()
        currentModes.add(mode)
        prefs.edit().putStringSet("completed_modes", currentModes.map { it.name }.toSet()).apply()
    }

    fun hasSeenDoubleTapHint(): Boolean {
        return prefs.getBoolean("double_tap_hint_seen", false)
    }

    fun setDoubleTapHintSeen() {
        prefs.edit().putBoolean("double_tap_hint_seen", true).apply()
    }
}