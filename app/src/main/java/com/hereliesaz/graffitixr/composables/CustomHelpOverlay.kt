package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.EditorMode
import com.hereliesaz.graffitixr.UiState
import com.hereliesaz.graffitixr.ui.NavStrings

/**
 * A custom Help Overlay to replace the default AzNavRail help screen.
 * It provides a transparent background to keep the camera visible and
 * lists the available tools clearly.
 */
@Composable
fun CustomHelpOverlay(
    uiState: UiState,
    navStrings: NavStrings,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)) // Semi-transparent black
            .clickable { onDismiss() } // Tap anywhere to dismiss
            .padding(start = 100.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), // Avoid rail area
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = navStrings.help,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Modes
            HelpSectionHeader(navStrings.modes)
            HelpItem(navStrings.arMode, navStrings.arModeInfo)
            HelpItem(navStrings.overlay, navStrings.overlayInfo)
            HelpItem(navStrings.mockup, navStrings.mockupInfo)
            HelpItem(navStrings.trace, navStrings.traceInfo)
            Spacer(modifier = Modifier.height(16.dp))

            // Grid (AR Only)
            if (uiState.editorMode == EditorMode.AR) {
                HelpSectionHeader(navStrings.grid)
                HelpItem(navStrings.surveyor, navStrings.surveyorInfo)
                HelpItem(navStrings.create, navStrings.createInfo)
                HelpItem(navStrings.refine, navStrings.refineInfo)
                HelpItem(navStrings.update, navStrings.updateInfo)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Design
            HelpSectionHeader(navStrings.design)
            HelpItem(navStrings.open, navStrings.openInfo)

            if (uiState.editorMode == EditorMode.STATIC) {
                HelpItem(navStrings.wall, navStrings.wallInfo)
            }

            if (uiState.overlayImageUri != null) {
                HelpItem(navStrings.isolate, navStrings.isolateInfo)
                HelpItem(navStrings.outline, navStrings.outlineInfo)
                HelpItem(navStrings.adjust, navStrings.adjustInfo)
                HelpItem(navStrings.balance, navStrings.balanceInfo)
                HelpItem(navStrings.build, navStrings.blendingInfo)
                HelpItem("Lock Image", "Prevent accidental moves")
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Project
            HelpSectionHeader(navStrings.project)
            HelpItem(navStrings.settings, "App Settings")
            HelpItem("Help", "Show this help")
            HelpItem(navStrings.new, navStrings.newInfo)
            HelpItem(navStrings.save, navStrings.saveInfo)
            HelpItem(navStrings.load, navStrings.loadInfo)
            HelpItem(navStrings.export, navStrings.exportInfo)
            Spacer(modifier = Modifier.height(16.dp))

            // Misc
            if (uiState.editorMode == EditorMode.AR || uiState.editorMode == EditorMode.OVERLAY) {
                HelpSectionHeader("Tools")
                HelpItem(navStrings.light, navStrings.lightInfo)
            }

            if (uiState.editorMode == EditorMode.TRACE) {
                HelpSectionHeader("Tools")
                HelpItem(navStrings.lock, navStrings.lockInfo)
            }

            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Tap anywhere to close",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun HelpSectionHeader(text: String) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.Cyan,
            fontWeight = FontWeight.Bold
        )
        Divider(color = Color.DarkGray, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun HelpItem(title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.LightGray
        )
    }
}
