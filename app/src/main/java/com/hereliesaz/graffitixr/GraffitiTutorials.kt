package com.hereliesaz.graffitixr

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.hereliesaz.aznavrail.tutorial.AzHighlight
import com.hereliesaz.aznavrail.tutorial.AzTutorial
import com.hereliesaz.aznavrail.tutorial.azTutorial
import com.hereliesaz.graffitixr.design.R as DesignR

fun getGraffitiTutorials(context: Context): Map<String, AzTutorial> {
    val ar = azTutorial {
        scene(id = "ar_scene", content = { Box(Modifier.fillMaxSize()) }) {
            card(
                title = context.getString(DesignR.string.tut_ar_title),
                text = context.getString(DesignR.string.tut_ar_text),
                highlight = AzHighlight.Item("mode.ar")
            )
            card(
                title = context.getString(DesignR.string.tut_ar_step1_title),
                text = context.getString(DesignR.string.tut_ar_step1_text),
                highlight = AzHighlight.FullScreen
            )
            card(
                title = context.getString(DesignR.string.tut_ar_step2_title),
                text = context.getString(DesignR.string.tut_ar_step2_text),
                highlight = AzHighlight.FullScreen
            )
            card(
                title = context.getString(DesignR.string.tut_ar_step3_title),
                text = context.getString(DesignR.string.tut_ar_step3_text),
                highlight = AzHighlight.FullScreen
            )
            card(
                title = context.getString(DesignR.string.tut_ar_tip_title),
                text = context.getString(DesignR.string.tut_ar_tip_text),
                highlight = AzHighlight.FullScreen
            )
        }
    }

    val overlay = azTutorial {
        scene(id = "overlay_scene", content = { Box(Modifier.fillMaxSize()) }) {
            card(
                title = context.getString(DesignR.string.tut_overlay_title),
                text = context.getString(DesignR.string.tut_overlay_text),
                highlight = AzHighlight.Item("mode.overlay")
            )
            card(
                title = context.getString(DesignR.string.tut_overlay_step1_title),
                text = context.getString(DesignR.string.tut_overlay_step1_text),
                highlight = AzHighlight.FullScreen
            )
            card(
                title = context.getString(DesignR.string.tut_overlay_step2_title),
                text = context.getString(DesignR.string.tut_overlay_step2_text),
                highlight = AzHighlight.FullScreen
            )
        }
    }

    val mockup = azTutorial {
        scene(id = "mockup_scene", content = { Box(Modifier.fillMaxSize()) }) {
            card(
                title = context.getString(DesignR.string.tut_mockup_title),
                text = context.getString(DesignR.string.tut_mockup_text),
                highlight = AzHighlight.Item("mode.mockup")
            )
            card(
                title = context.getString(DesignR.string.tut_mockup_step1_title),
                text = context.getString(DesignR.string.tut_mockup_step1_text),
                highlight = AzHighlight.FullScreen
            )
            card(
                title = context.getString(DesignR.string.tut_mockup_step2_title),
                text = context.getString(DesignR.string.tut_mockup_step2_text),
                highlight = AzHighlight.FullScreen
            )
        }
    }

    val trace = azTutorial {
        scene(id = "trace_scene", content = { Box(Modifier.fillMaxSize()) }) {
            card(
                title = context.getString(DesignR.string.tut_trace_title),
                text = context.getString(DesignR.string.tut_trace_text),
                highlight = AzHighlight.Item("mode.trace")
            )
            card(
                title = context.getString(DesignR.string.tut_trace_step1_title),
                text = context.getString(DesignR.string.tut_trace_step1_text),
                highlight = AzHighlight.FullScreen
            )
            card(
                title = context.getString(DesignR.string.tut_trace_step2_title),
                text = context.getString(DesignR.string.tut_trace_step2_text),
                highlight = AzHighlight.FullScreen
            )
            card(
                title = context.getString(DesignR.string.tut_trace_step3_title),
                text = context.getString(DesignR.string.tut_trace_step3_text),
                highlight = AzHighlight.FullScreen
            )
        }
    }

    return mapOf(
        "mode.ar.firstRun" to ar,
        "mode.ar" to ar,
        "mode.overlay.firstRun" to overlay,
        "mode.overlay" to overlay,
        "mode.mockup.firstRun" to mockup,
        "mode.mockup" to mockup,
        "mode.trace.firstRun" to trace,
        "mode.trace" to trace,
    )
}
