package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.content.SharedPreferences
import com.hereliesaz.graffitixr.EditorMode

class OnboardingManager(context: Context) {

    fun getCompletedModes(): Set<EditorMode> {
        return prefs.getStringSet("completed_modes", emptySet())
            ?.mapNotNull { EditorMode.valueOf(it) }
            ?.toSet() ?: emptySet()
    }

    fun completeMode(mode: EditorMode) {
        prefs.edit().putBoolean(mode.name, true).apply()
    }

    fun getCompletedModes(): Set<EditorMode> {
        return EditorMode.values().filter {
            prefs.getBoolean(it.name, false)
        }.toSet()
    }

    fun hasSeenDoubleTapHint(): Boolean {
        return prefs.getBoolean("seen_double_tap_hint", false)
    }

    fun setDoubleTapHintSeen() {
        prefs.edit().putBoolean("seen_double_tap_hint", true).apply()
    }
}
