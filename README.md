# AzNavRail

[![](https://jitpack.io/v/HereLiesAz/AzNavRail.svg)](https://jitpack.io/#HereLiesAz/AzNavRail)

A contemptably stubborn if not dictatorially restrictive navigation rail/menu--I call it a renu. Or maybe a mail. No, a navigrenuail--for Jetpack Compose with a streamlined, DSL-style API.

This "navigrenuail" provides a vertical navigation rail that expands to a full menu drawer. It is designed to be "batteries-included," providing common behaviors and features out-of-the-box to ensure a consistent look and feel across applications.

## New High-Inference KSP System

AzNavRail now includes a powerful **High-Inference System** that generates your entire navigation graph and configuration from minimal code.

* **Zero Boilerplate**: No `onCreate`, no `setContent`, no manual graph building.
* **Zero-Talk Inference**: Function names become IDs and Titles automatically.
* **Compile-Time Validation**: Ensures your navigation targets are valid `@Composable`s.

[**Read the Full High-Inference Guide Here**](docs/AZ_HIGH_INFERENCE.md)

### Quick Example

~~~kotlin
@Az(app = App(dock = AzDockingSide.LEFT))
class MainActivity : AzActivity() {
    override val graph = AzGraph // Generated automatically
}

@Az // Inferred -> ID="home", Text="Home"
@Composable fun Home() { Text("Home") }
~~~

## Features

- **Responsive Layout**: Automatically adjusts to orientation changes.
- **Scrollable**: Both rail and menu are scrollable.
- **DSL API**: Simple, declarative API.
- **Multi-line Items**: Supports multi-line text.
- **Stateless**: Hoist and manage state yourself.
- **Shapes**: `CIRCLE`, `SQUARE`, `RECTANGLE`, or `NONE`. `RECTANGLE`/`NONE` auto-size width (fixed 36dp height).
- **Smart Collapse**: Items collapse the rail after interaction.
- **Delayed Cycler**: Built-in delay prevents accidental triggers.
- **Custom Colors**: Apply custom colors to buttons.
- **Dividers**: Add menu dividers.
- **Automatic Header**: Displays app icon or name.
- **Layout**: Pack buttons or preserve spacing.
- **Disabled State**: Disable items or options.
- **Loading State**: Built-in loading animation.
- **Standalone Components**: `AzButton`, `AzToggle`, `AzCycler`, `AzDivider`, `AzRoller`.
- **Navigation**: seamless Jetpack Navigation integration.
- **Hierarchy**: Nested menus with host and sub-items (Inline expansion).
- **Nested Rails**: Support for nested navigation structures (`azNestedRail`) with both Vertical (anchored column) and Horizontal (scrollable row) alignments (Popup overlay).
- **Draggable (FAB Mode)**: Detach and move the rail.
-   **Reorderable Items**: `AzRailRelocItem` allows user drag-and-drop reordering within clusters.
    -   **Drag**: Long press (with vibration feedback) to start dragging.
    -   **Hidden Menu**: Tap to focus/select. If already focused, tap again to open the hidden menu.
- **System Overlay**: System-wide overlay support with automatic resizing and activity launching.
- **Auto-sizing Text**: Text fits without wrapping (unless explicit newline).
- **Full-Fill Content**: Rail items with images or colors now completely fill the button shape (crop/bleed).
- **Toggles/Cyclers**: Simple state management.
- **Gestures**: Swipe/tap to expand, collapse, or undock.
- **`AzTextBox`**: Modern text box with autocomplete and submit button.
- **`AzRoller`**: A dropdown menu that works like a roller or slot machine, cycling through options infinitely.
- **Info Screen**: Interactive help mode for onboarding with visual guides and coordinate display.
- **Left/Right Docking**: Position the rail on the left or right side of the screen.
- **Physical Docking**: Experimental mode (`usePhysicalDocking`) to anchor the rail to the physical side of the device, adapting to rotation.
- **No Menu Mode**: Treat all items as rail items, removing the side drawer.
- **AzHostActivityLayout**: A layout container that enforces strict safe zones and automatic alignment rules.
- **AzNavHost**: A wrapper around `androidx.navigation.compose.NavHost` for seamless integration.
- **Smart Transitions**: `AzNavHost` automatically configures directional transitions (slide in/out) based on the docking side (e.g., standard LTR or mirrored for Right dock).
- **Dynamic Content**: Rail buttons can now display solid colors, numbers, or images (via Coil) directly.

## AzNavRail for Android (Jetpack Compose)

### Setup

To use this library, add JitPack to your `settings.gradle.kts`:

~~~kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
~~~

Add the **KSP plugin** and dependencies to your app's `build.gradle.kts`. You must match the KSP version to your Kotlin version.

~~~kotlin
plugins {
    id("com.google.devtools.ksp") version "2.0.0-1.0.21" // Update based on your Kotlin version
}

dependencies {
    // The Core Library
    implementation("com.github.HereLiesAz.AzNavRail:aznavrail:VERSION")
    
    // The Annotation Processor (Required for @Az High-Inference)
    implementation("com.github.HereLiesAz.AzNavRail:aznavrail-annotation:VERSION")
    ksp("com.github.HereLiesAz.AzNavRail:aznavrail-processor:VERSION")
}
~~~

### Usage: The Az Protocol

You write **zero** navigation setup code.

#### 1. The Constitution (App Config)
Extend `AzActivity` and annotate it to define the global rules (theme, docking side).

~~~kotlin
@Az(app = App(dock = AzDockingSide.RIGHT, theme = AzTheme.GlitchNoir))
class MainActivity : AzActivity() {
    // Point to the object generated by KSP. That's it.
    override val graph = AzGraph 
}
~~~

#### 2. The Stations (Screens)
Annotate any `@Composable` function to register it as a destination. The ID and Label are inferred from the function name.

~~~kotlin
// Inferred -> ID: "home", Label: "Home", Icon: Default Circle
@Az(rail = RailItem(home = true))
@Composable
fun Home() {
    Text("Strict Mode Active.")
}

// Inferred -> ID: "profile_settings", Label: "Profile Settings"
@Az(rail = RailItem(icon = R.drawable.ic_user)) 
@Composable
fun ProfileSettings() {
    Text("User Data")
}
~~~

#### 3. The Hierarchy (Nested Rails)
Define a **Host** using a property, then link children to it using `parent`.

~~~kotlin
// A "Host" expands to show children. It has no screen of its own.
@Az(railHost = RailHost(icon = R.drawable.ic_settings))
val SystemSettings = null 

// Linked via parent ID "system_settings" (inferred from property above)
@Az(nested = NestedRail(parent = "system_settings"))
@Composable
fun Wifi() { 
    Text("Network Config") 
}
~~~

### 🔨 Manual Configuration (Legacy)
If you refuse to use the KSP processor, you must manually implement the `AzHostActivityLayout` and manage the `AzNavHost` yourself. This is error-prone and discouraged.
> [Read the Manual DSL Guide](docs/AZNAVRAIL_COMPLETE_GUIDE.md)

---

## ⚛️ React Native

The React Native port provides the same "Strict Mode" layout wrappers.

**Installation:**
~~~bash
npm install aznavrail-react-native
~~~

**Usage:**
~~~tsx
import { AzNavRail, AzRailItem } from 'aznavrail-react-native';

export default function App() {
  return (
    <AzNavRail dockingSide="left">
      <AzRailItem id="home" text="Home" onPress={() => navigate('Home')} />
      <AzRailItem id="settings" text="Settings" onPress={() => navigate('Settings')} />
    </AzNavRail>
  );
}
~~~
*Note: The "Secret Screens" feature relies on native Android intents and is not available in React Native.*

---

## 🌐 Web

The Web port brings the rail interface to React applications.

**Installation:**
~~~bash
npm install aznavrail-web
~~~

**Usage:**
See the [Web Documentation](aznavrail-web/README.md) for CSS integration and layout specifics.

---

## ⚠️ The Rules (Strict Mode)

This library is opinionated.
1.  **Safe Zones:** Your UI is strictly forbidden from the top 20% and bottom 10% of the screen.
2.  **Context:** You must use `AzNavigator` (Android) to move between screens. Hardcoded route strings are blocked by the KSP validator.
3.  **The Tax:** Every function annotated with `@Az` must also be `@Composable`. If you forget, the build fails.

## Documentation

The library includes a comprehensive **Complete Guide** (`AZNAVRAIL_COMPLETE_GUIDE.md`) containing:
* Full Getting Started instructions.
* Complete API and DSL references.
* Layout rules and best practices.
* Complete Sample App source code.

### Auto-Generate Documentation

To automatically extract this guide into your project's `docs/` folder whenever you build, add the following task to your app's `build.gradle.kts`:

~~~kotlin
// In app/build.gradle.kts

tasks.register("updateAzNavDocs") {
    group = "documentation"
    description = "Extracts AzNavRail documentation from the dependency."

    doLast {
        // Find the AzNavRail AAR in the runtime classpath
        val artifact = configurations.getByName("debugRuntimeClasspath").files
            .find { it.name.contains("AzNavRail") && it.extension == "aar" }

        if (artifact != null) {
            copy {
                from(zipTree(artifact))
                include("assets/AZNAVRAIL_COMPLETE_GUIDE.md")
                into(layout.projectDirectory.dir("docs"))
                // Remove the 'assets/' prefix from the output file
                eachFile {
                    path = name
                }
                includeEmptyDirs = false
            }
            println("AzNavRail documentation updated: docs/AZNAVRAIL_COMPLETE_GUIDE.md")
        } else {
            println("AzNavRail AAR not found. Make sure the dependency is added.")
        }
    }
}

// Optional: Run this task automatically before every build
// tasks.named("preBuild") { dependsOn("updateAzNavDocs") }
~~~

Once added, run `./gradlew updateAzNavDocs` (or just build your app if you uncommented the last line) to generate the documentation.

## License

Copyright 2024 The AzNavRail Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
