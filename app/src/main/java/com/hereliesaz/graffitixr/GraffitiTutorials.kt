package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.hereliesaz.aznavrail.tutorial.AzHighlight
import com.hereliesaz.aznavrail.tutorial.AzTutorial
import com.hereliesaz.aznavrail.tutorial.azTutorial

import android.content.Context
import com.hereliesaz.graffitixr.design.R as DesignR

fun getGraffitiTutorials(context: Context): Map<String, AzTutorial> {
    return mapOf(
        "mode.ar.firstRun" to azTutorial {
            scene(
                id = "ar_scene",
                content = { Box(Modifier.fillMaxSize()) }
            ) {
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
        },
        "mode.overlay.firstRun" to azTutorial {
            scene(
                id = "overlay_scene",
                content = { Box(Modifier.fillMaxSize()) }
            ) {
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
        },
        "mode.mockup.firstRun" to azTutorial {
            scene(
                id = "mockup_scene",
                content = { Box(Modifier.fillMaxSize()) }
            ) {
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
        },
        "mode.trace.firstRun" to azTutorial {
            scene(
                id = "trace_scene",
                content = { Box(Modifier.fillMaxSize()) }
            ) {
                card(
                    title = "",
                    text = "This is Trace mode.",
                    highlight = AzHighlight.None
                )
                card(
                    title = "",
                    text = "Arrange and edit your photos, sketches, and text by pressing the Design button.",
                    highlight = AzHighlight.None
                )
                card(
                    title = "",
                    text = "When they are where and how you want them, press the Freeze button to turn your phone into a lightbox for tracing onto paper.",
                    highlight = AzHighlight.None
                )
                card(
                    title = "",
                    text = "We recommend using either Trace mode or Mockup mode for editing.",
                    highlight = AzHighlight.None
                )
            }
        }
    )
}
