package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.content.SharedPreferences
import com.hereliesaz.graffitixr.EditorMode

class OnboardingManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)

    fun completeMode(mode: EditorMode) {
        prefs.edit().putBoolean(mode.name, true).apply()
    }

    fun getCompletedModes(): Set<EditorMode> {
        return EditorMode.values().filter {
            prefs.getBoolean(it.name, false)
        }.toSet()
    }

    fun hasSeenDoubleTapHint(): Boolean {
        return prefs.getBoolean("double_tap_hint_seen", false)
    }

    fun setDoubleTapHintSeen() {
        prefs.edit().putBoolean("double_tap_hint_seen", true).apply()
    }
}
