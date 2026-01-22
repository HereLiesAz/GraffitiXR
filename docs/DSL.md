# AzNavRail DSL Configuration

GraffitiXR configures the rail in `MainScreen.kt` using the DSL.

## Example Structure
## IMPORTANT: AzNavRail MUST be used within an AzHostActivityLayout container. The library enforces strict layout rules (safe zones, padding, z-ordering) and will throw a runtime error if AzNavRail is instantiated directly without a host wrapper (except when running as a system overlay service).

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.AzNavHost
import com.hereliesaz.aznavrail.AzTextBox
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.aznavrail.model.AzHeaderIconShape

@Composable
fun SampleScreen() {
    val navController = rememberNavController()
    // currentDestination and isLandscape are automatically derived by AzHostActivityLayout
    // but can be overridden if needed.

    var isOnline by remember { mutableStateOf(true) }
    var isDarkMode by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val railCycleOptions = remember { listOf("A", "B", "C", "D") }
    var railSelectedOption by remember { mutableStateOf(railCycleOptions.first()) }
    val menuCycleOptions = remember { listOf("X", "Y", "Z") }
    var menuSelectedOption by remember { mutableStateOf(menuCycleOptions.first()) }

    AzHostActivityLayout(navController = navController) {
        azSettings(
            // displayAppNameInHeader = true, // Set to true to display the app name instead of the icon
            packRailButtons = false,
            isLoading = isLoading,
            defaultShape = AzButtonShape.RECTANGLE, // Set a default shape for all rail items
            enableRailDragging = true, // Enable the draggable rail feature
            headerIconShape = AzHeaderIconShape.ROUNDED, // Set the header icon shape to ROUNDED
            activeColor = MaterialTheme.colorScheme.tertiary, // Optional: Secondary color for the selected item
            vibrate = true, // Optional: Enable haptic feedback for gestures
            dockingSide = AzDockingSide.LEFT, // Optional: AzDockingSide.LEFT (default) or AzDockingSide.RIGHT
            noMenu = false // Optional: If true, all items are displayed on the rail and the menu is disabled
        )

        // A standard menu item - only appears in the expanded menu
        azMenuItem(id = "home", text = "Home", route = "home")

        // A menu item with multi-line text
        azMenuItem(id = "multi-line", text = "This is a\nmulti-line item", route = "multi-line")

        // A rail item with the default shape (RECTANGLE)
        azRailItem(id = "favorites", text = "Favorites", route = "favorites")

        // A disabled rail item that overrides the default shape
        azRailItem(
            id = "profile",
            text = "Profile",
            shape = AzButtonShape.CIRCLE,
            disabled = true,
            route = "profile"
        )

        azDivider()

        // A rail toggle item with the SQUARE shape
        azRailToggle(
            id = "online",
            isChecked = isOnline,
            toggleOnText = "Online",
            toggleOffText = "Offline",
            shape = AzButtonShape.SQUARE,
            route = "online",
            onClick = { isOnline = !isOnline }
        )

        // A menu toggle item
        azMenuToggle(
            id = "dark-mode",
            isChecked = isDarkMode,
            toggleOnText = "Dark Mode",
            toggleOffText = "Light Mode",
            route = "dark-mode",
            onClick = { isDarkMode = !isDarkMode }
        )

        azDivider()

        // A rail cycler with a disabled option
        azRailCycler(
            id = "rail-cycler",
            options = railCycleOptions,
            selectedOption = railSelectedOption,
            disabledOptions = listOf("C"),
            route = "rail-cycler",
            onClick = {
                val currentIndex = railCycleOptions.indexOf(railSelectedOption)
                val nextIndex = (currentIndex + 1) % railCycleOptions.size
                railSelectedOption = railCycleOptions[nextIndex]
            }
        )

        // A menu cycler
        azMenuCycler(
            id = "menu-cycler",
            options = menuCycleOptions,
            selectedOption = menuSelectedOption,
            route = "menu-cycler",
            onClick = {
                val currentIndex = menuCycleOptions.indexOf(menuSelectedOption)
                val nextIndex = (currentIndex + 1) % menuCycleOptions.size
                menuSelectedOption = menuCycleOptions[nextIndex]
            }
        )


        // A button to demonstrate the loading state
        azRailItem(id = "loading", text = "Load", route = "loading", onClick = { isLoading = !isLoading })

        azDivider()

        azMenuHostItem(id = "menu-host", text = "Menu Host", route = "menu-host")
        azMenuSubItem(id = "menu-sub-1", hostId = "menu-host", text = "Menu Sub 1", route = "menu-sub-1")
        azMenuSubItem(id = "menu-sub-2", hostId = "menu-host", text = "Menu Sub 2", route = "menu-sub-2")

        azRailHostItem(id = "rail-host", text = "Rail Host", route = "rail-host")
        azRailSubItem(id = "rail-sub-1", hostId = "rail-host", text = "Rail Sub 1", route = "rail-sub-1")
        azMenuSubItem(id = "rail-sub-2", hostId = "rail-host", text = "Menu Sub 2", route = "rail-sub-2")

        azMenuSubToggle(
            id = "sub-toggle",
            hostId = "menu-host",
            isChecked = isDarkMode,
            toggleOnText = "Sub Toggle On",
            toggleOffText = "Sub Toggle Off",
            route = "sub-toggle",
            onClick = { isDarkMode = !isDarkMode }
        )

        azRailSubCycler(
            id = "sub-cycler",
            hostId = "rail-host",
            options = menuCycleOptions,
            selectedOption = menuSelectedOption,
            route = "sub-cycler",
            onClick = {
                val currentIndex = menuCycleOptions.indexOf(menuSelectedOption)
                val nextIndex = (currentIndex + 1) % menuCycleOptions.size
                menuSelectedOption = menuCycleOptions[nextIndex]
            }
        )

        // Your app's main content goes here, wrapped in 'onscreen' to enforce layout rules.
        onscreen(alignment = Alignment.Center) {
            Column(modifier = Modifier.padding(16.dp)) {
                AzTextBox(
                    modifier = Modifier.padding(bottom = 16.dp),
                    hint = "Enter text...",
                    onSubmit = { text ->
                        // Log.d(TAG, "Submitted text: $text")
                    },
                    submitButtonContent = {
                        Text("Go")
                    }
                )

                AzNavHost(startDestination = "home") {
                    composable("home") { Text("Home Screen") }
                    composable("multi-line") { Text("Multi-line Screen") }
                    composable("favorites") { Text("Favorites Screen") }
                    composable("profile") { Text("Profile Screen") }
                    composable("online") { Text("Online Screen") }
                    composable("dark-mode") { Text("Dark Mode Screen") }
                    composable("rail-cycler") { Text("Rail Cycler Screen") }
                    composable("menu-cycler") { Text("Menu Cycler Screen") }
                    composable("loading") { Text("Loading Screen") }
                    composable("menu-host") { Text("Menu Host Screen") }
                    composable("menu-sub-1") { Text("Menu Sub 1 Screen") }
                    composable("menu-sub-2") { Text("Menu Sub 2 Screen") }
                    composable("rail-host") { Text("Rail Host Screen") }
                    composable("rail-sub-1") { Text("Rail Sub 1 Screen") }
                    composable("rail-sub-2") { Text("Rail Sub 2 Screen") }
                    composable("sub-toggle") { Text("Sub Toggle Screen") }
                    composable("sub-cycler") { Text("Sub Cycler Screen") }
                }
            }
        }
    }
}

```

## AzHostActivityLayout Layout Rules
AzHostActivityLayout enforces a "Strict Mode" layout system:

Rail Avoidance: No content in the onscreen block will overlap the rail. Padding is automatically applied based on the docking side.
Vertical Safe Zones: Content is restricted from the top 20% and bottom 10% of the screen.
Automatic Flipping: Alignments passed to onscreen (e.g., TopStart) are automatically mirrored if the rail is docked to the right.
Backgrounds: Use the background(weight) DSL to place full-screen content behind the UI (e.g., maps, camera feeds). Backgrounds ignore safe zones.

## Custom Components
We extend the Rail with Content {} blocks to render the Vertical Slider directly inside the flyout. This is crucial for opacity control without leaving the context of the rail.
