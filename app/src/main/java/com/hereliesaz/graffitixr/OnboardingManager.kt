package com.hereliesaz.graffitixr

import android.content.Context
import android.content.SharedPreferences

class OnboardingManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)

    fun isFirstTime(screen: String): Boolean {
        return prefs.getBoolean("first_time_$screen", true)
    }

    fun markAsSeen(screen: String) {
        prefs.edit().putBoolean("first_time_$screen", false).apply()
    }
}
