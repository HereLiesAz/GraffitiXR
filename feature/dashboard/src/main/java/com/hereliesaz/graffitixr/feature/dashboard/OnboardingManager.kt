package com.hereliesaz.graffitixr.feature.dashboard

import android.content.Context
import android.content.SharedPreferences
import com.hereliesaz.graffitixr.common.model.EditorMode

class OnboardingManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    fun getCompletedModes(): Set<EditorMode> {
        return EditorMode.values().filter {
            prefs.getBoolean(it.name, false)
        }.toSet()
    }

    fun completeMode(mode: EditorMode) {
        prefs.edit().putBoolean(mode.name, true).apply()
    }

    fun resetOnboarding() {
        val editor = prefs.edit()
        EditorMode.values().forEach {
            editor.remove(it.name)
        }
        editor.apply()
    }

    fun hasSeenDoubleTapHint(): Boolean {
        return prefs.getBoolean("seen_double_tap_hint", false)
    }

    fun setDoubleTapHintSeen() {
        prefs.edit().putBoolean("seen_double_tap_hint", true).apply()
    }
}