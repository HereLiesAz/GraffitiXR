# AzNavRail DSL Configuration (v7.62)

GraffitiXR configures the rail in `MainActivity.kt` using the DSL split across three config functions.

## IMPORTANT: AzNavRail MUST be used within an AzHostActivityLayout container. The library enforces strict layout rules (safe zones, padding, z-ordering) and will throw a runtime error if AzNavRail is instantiated directly without a host wrapper (except when running as a system overlay service).

## Configuration Functions

The v7.62 API splits settings across three dedicated functions:

| Function | Purpose |
|---|---|
| `azTheme(...)` | Visual appearance — active color, button shapes |
| `azConfig(...)` | Layout behavior — button packing, docking side |
| `azAdvanced(...)` | Feature flags — dragging, help screen |

## GraffitiXR Rail Pattern (from `MainActivity.kt`)

```kotlin
AzHostActivityLayout(navController = navController) {

    // Visual theme
    azTheme(
        activeColor = activeHighlightColor,
        defaultShape = AzButtonShape.RECTANGLE,
        headerIconShape = AzHeaderIconShape.ROUNDED
    )

    // Layout config
    azConfig(
        packButtons = true,
        dockingSide = if (isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT
    )

    // Feature flags
    azAdvanced(helpEnabled = true)

    // Mode host with sub-items (accordion hierarchy)
    azRailHostItem(id = "mode_host", text = "Modes")
    azRailSubItem(id = "ar",      hostId = "mode_host", text = "AR",      route = "AR",      shape = AzButtonShape.NONE)
    azRailSubItem(id = "overlay", hostId = "mode_host", text = "Overlay", route = "Overlay", shape = AzButtonShape.NONE)
    azRailSubItem(id = "mockup",  hostId = "mode_host", text = "Mockup",  route = "Mockup",  shape = AzButtonShape.NONE)
    azRailSubItem(id = "trace",   hostId = "mode_host", text = "Trace",   route = "Trace",   shape = AzButtonShape.NONE)

    // Dedicated help button (v7.60+)
    azHelpRailItem(id = "help", text = "Help")

    // App content — full-screen camera/canvas behind the rail
    background(weight = 0) { ArViewport(...) }
}
```

## `azHelpRailItem(id, text)`

Added in v7.60. Places a dedicated help button directly on the rail as a permanent item. When tapped, it activates the Info Screen overlay (the same overlay triggered by `azAdvanced(helpEnabled = true)`). Use this instead of relying on the auto-generated Help menu item when you want the help button always visible on the rail.

```kotlin
azHelpRailItem(id = "help", text = "Help")
```

## AzHostActivityLayout Layout Rules
AzHostActivityLayout enforces a "Strict Mode" layout system:

Rail Avoidance: No content in the onscreen block will overlap the rail. Padding is automatically applied based on the docking side.
Vertical Safe Zones: Content is restricted from the top 20% and bottom 10% of the screen.
Automatic Flipping: Alignments passed to onscreen (e.g., TopStart) are automatically mirrored if the rail is docked to the right.
Backgrounds: Use the background(weight) DSL to place full-screen content behind the UI (e.g., maps, camera feeds). Backgrounds ignore safe zones.

## Custom Components
We extend the Rail with Content {} blocks to render the Vertical Slider directly inside the flyout. This is crucial for opacity control without leaving the context of the rail.
