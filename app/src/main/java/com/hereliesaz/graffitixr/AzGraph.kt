package com.hereliesaz.graffitixr

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.hereliesaz.aznavrail.AzGraphInterface

object AzGraph : AzGraphInterface {
    override fun Run(activity: ComponentActivity) {
        val mainActivity = activity as MainActivity
        activity.setContent {
            com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme {
                with(mainActivity) {
                    AppContent()
                }
            }
        }
    }
}
