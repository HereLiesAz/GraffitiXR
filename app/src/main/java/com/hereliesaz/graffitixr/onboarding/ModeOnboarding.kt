package com.hereliesaz.graffitixr.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.design.R as DesignR

data class ModeOnboarding(val mode: EditorMode, val lines: List<String>)

@Composable
fun rememberModeOnboardings(): Map<EditorMode, ModeOnboarding> {
    val context = LocalContext.current
    return remember(context) {
        mapOf(
            EditorMode.TRACE to ModeOnboarding(
                EditorMode.TRACE,
                context.resources.getStringArray(DesignR.array.onboarding_trace).toList()
            ),
            EditorMode.AR to ModeOnboarding(
                EditorMode.AR,
                context.resources.getStringArray(DesignR.array.onboarding_ar).toList()
            ),
            EditorMode.OVERLAY to ModeOnboarding(
                EditorMode.OVERLAY,
                context.resources.getStringArray(DesignR.array.onboarding_overlay).toList()
            ),
            EditorMode.MOCKUP to ModeOnboarding(
                EditorMode.MOCKUP,
                context.resources.getStringArray(DesignR.array.onboarding_mockup).toList()
            ),
        )
    }
}
