package com.hereliesaz.graffitixr.migrated

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import com.hereliesaz.aznavrail.annotation.*

// AUTO-GENERATED MIGRATION FILE
// Copy these functions to your project and delete the old DSL setup.

// Original ID: help
@Az(rail = RailItem(id = "help", text = "Help"))
@Composable
fun HelpScreen() {
    Text("Migrated Screen: Help")
}

// Original ID: light
@Az(rail = RailItem(id = "light", text = "navStrings.light"))
@Composable
fun LightScreen() {
    Text("Migrated Screen: navStrings.light")
}

// Original ID: lock_trace
@Az(rail = RailItem(id = "lock_trace", text = "navStrings.lock"))
@Composable
fun LockTraceScreen() {
    Text("Migrated Screen: navStrings.lock")
}

// Original ID: mode_host
@Az(railHost = RailHost(id = "mode_host", text = "navStrings.modes"))
val ModeHostHost = null

// Original ID: target_host
@Az(railHost = RailHost(id = "target_host", text = "navStrings.grid"))
val TargetHostHost = null

// Original ID: design_host
@Az(railHost = RailHost(id = "design_host", text = "navStrings.design"))
val DesignHostHost = null

// Original ID: project_host
@Az(railHost = RailHost(id = "project_host", text = "navStrings.project"))
val ProjectHostHost = null

// Original ID: ar
@Az(rail = RailItem(id = "ar", text = "navStrings.arMode", parent = "mode_host"))
@Composable
fun ArScreen() {
    Text("Migrated Screen: navStrings.arMode")
}

// Original ID: overlay
@Az(rail = RailItem(id = "overlay", text = "navStrings.overlay", parent = "mode_host"))
@Composable
fun OverlayScreen() {
    Text("Migrated Screen: navStrings.overlay")
}

// Original ID: mockup
@Az(rail = RailItem(id = "mockup", text = "navStrings.mockup", parent = "mode_host"))
@Composable
fun MockupScreen() {
    Text("Migrated Screen: navStrings.mockup")
}

// Original ID: trace
@Az(rail = RailItem(id = "trace", text = "navStrings.trace", parent = "mode_host"))
@Composable
fun TraceScreen() {
    Text("Migrated Screen: navStrings.trace")
}

// Original ID: create
@Az(rail = RailItem(id = "create", text = "navStrings.create", parent = "target_host"))
@Composable
fun CreateScreen() {
    Text("Migrated Screen: navStrings.create")
}

// Original ID: surveyor
@Az(rail = RailItem(id = "surveyor", text = "navStrings.surveyor", parent = "target_host"))
@Composable
fun SurveyorScreen() {
    Text("Migrated Screen: navStrings.surveyor")
}

// Original ID: capture_keyframe
@Az(rail = RailItem(id = "capture_keyframe", text = "Keyframe", parent = "target_host"))
@Composable
fun CaptureKeyframeScreen() {
    Text("Migrated Screen: Keyframe")
}

// Original ID: wall
@Az(rail = RailItem(id = "wall", text = "navStrings.wall", parent = "design_host"))
@Composable
fun WallScreen() {
    Text("Migrated Screen: navStrings.wall")
}

// Original ID: openButtonId
@Az(rail = RailItem(id = "openButtonId", text = "openButtonText", parent = "design_host"))
@Composable
fun OpenbuttonidScreen() {
    Text("Migrated Screen: openButtonText")
}

// Original ID: isolate
@Az(rail = RailItem(id = "isolate", text = "navStrings.isolate", parent = "design_host"))
@Composable
fun IsolateScreen() {
    Text("Migrated Screen: navStrings.isolate")
}

// Original ID: outline
@Az(rail = RailItem(id = "outline", text = "navStrings.outline", parent = "design_host"))
@Composable
fun OutlineScreen() {
    Text("Migrated Screen: navStrings.outline")
}

// Original ID: adjust
@Az(rail = RailItem(id = "adjust", text = "navStrings.adjust", parent = "design_host"))
@Composable
fun AdjustScreen() {
    Text("Migrated Screen: navStrings.adjust")
}

// Original ID: balance
@Az(rail = RailItem(id = "balance", text = "navStrings.balance", parent = "design_host"))
@Composable
fun BalanceScreen() {
    Text("Migrated Screen: navStrings.balance")
}

// Original ID: blending
@Az(rail = RailItem(id = "blending", text = "navStrings.build", parent = "design_host"))
@Composable
fun BlendingScreen() {
    Text("Migrated Screen: navStrings.build")
}

// Original ID: save_project
@Az(rail = RailItem(id = "save_project", text = "navStrings.save", parent = "project_host"))
@Composable
fun SaveProjectScreen() {
    Text("Migrated Screen: navStrings.save")
}

// Original ID: load_project
@Az(rail = RailItem(id = "load_project", text = "navStrings.load", parent = "project_host"))
@Composable
fun LoadProjectScreen() {
    Text("Migrated Screen: navStrings.load")
}

// Original ID: export_project
@Az(rail = RailItem(id = "export_project", text = "navStrings.export", parent = "project_host"))
@Composable
fun ExportProjectScreen() {
    Text("Migrated Screen: navStrings.export")
}

// Original ID: settings_sub
@Az(rail = RailItem(id = "settings_sub", text = "navStrings.settings", parent = "project_host"))
@Composable
fun SettingsSubScreen() {
    Text("Migrated Screen: navStrings.settings")
}
