# AzNavRail DSL Configuration

GraffitiXR configures the rail in `MainScreen.kt` using the DSL.

## Example Structure

```kotlin
AzNavRail(
    header = {
        HeaderIcon(painter = painterResource(id = R.drawable.ic_spray_can))
    }
) {
    // 1. SCAN MODE
    RailItem(
        id = "scan",
        icon = Icons.Default.Radar,
        label = "Scan"
    ) {
        // Flyout Menu
        Action(id = "reset", label = "Reset Map", onClick = { viewModel.resetSlam() })
        Toggle(id = "debug", label = "Show Debug", isChecked = uiState.debugMode)
    }

    // 2. PROJECT MODE
    RailItem(
        id = "project",
        icon = Icons.Default.Image,
        label = "Project"
    ) {
        // The file picker trigger
        Action(id = "pick", label = "Load Image", onClick = { launcher.launch() })
        
        // Opacity Slider (Custom Composable inside Rail)
        Content {
            VerticalSlider(
                value = uiState.opacity,
                onValueChange = { viewModel.setOpacity(it) }
            )
        }
    }
}
```

## Custom Components
We extend the Rail with Content {} blocks to render the Vertical Slider directly inside the flyout. This is crucial for opacity control without leaving the context of the rail.