# AzNavRail Complete Guide

Welcome to the comprehensive guide for **AzNavRail**. This document contains everything you need to know to use the library, including setup instructions, a full API and DSL reference, layout rules, and complete sample code.

---

## Table of Contents

1.  [Getting Started](#getting-started)
2.  [AzHostActivityLayout Layout Rules](#azhostactivitylayout-layout-rules)
3.  [Smart Transitions with AzNavHost](#smart-transitions-with-aznavhost)
4.  [DSL Reference](#dsl-reference)
5.  [API Reference](#api-reference)
6.  [Sample Application Source Code](#sample-application-source-code)

---

## Getting Started

### Installation

To use AzNavRail, add JitPack to your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.HereLiesAz:AzNavRail:VERSION") // Replace VERSION with the latest release
}
```

### Basic Usage

**IMPORTANT:** `AzNavRail` **MUST** be used within an `AzHostActivityLayout` container.

```kotlin
AzHostActivityLayout(navController = navController) {
    // 1. Theme (Visuals)
    azTheme(
        activeColor = Color.Red,
        defaultShape = AzButtonShape.CIRCLE
    )

    // 2. Config (Behavior)
    azConfig(
        displayAppName = true,
        dockingSide = AzDockingSide.LEFT,
        packButtons = true
    )

    // 3. Advanced (Optional)
    azAdvanced(
        infoScreen = false 
    )

    // Define Rail Items (Visible on collapsed rail)
    azRailItem(id = "home", text = "Home", route = "home", onClick = { /* navigate */ })

    // Define Menu Items (Visible only when expanded)
    azMenuItem(id = "settings", text = "Settings", route = "settings", onClick = { /* navigate */ })

    // Define Content
    onscreen(Alignment.Center) {
        // AzNavHost automatically links to the outer AzHostActivityLayout
        AzNavHost(startDestination = "home") {
             composable("home") { Text("Home Screen") }
             // ...
        }
    }
}
```

---

## AzHostActivityLayout Layout Rules

`AzHostActivityLayout` enforces a "Strict Mode" layout system to ensure consistent UX and prevent overlap.

1.  **Rail Avoidance**: Content in the `onscreen` block is automatically padded to avoid the rail.
2.  **Safe Zones**: Content is restricted from the **Top 20%** and **Bottom 10%** of the screen.
3.  **Automatic Flipping**: Alignments passed to `onscreen` (e.g., `TopStart`) are mirrored if the rail is docked to the Right.
4.  **Backgrounds**: Use the `background(weight)` DSL to place full-screen content (e.g., maps) behind the UI. Backgrounds **ignore safe zones**.

**Example:**

```kotlin
AzHostActivityLayout(navController = navController) {
    // Full screen background
    background(weight = 0) {
        GoogleMap(...)
    }

    // Safe UI content
    onscreen(Alignment.TopEnd) {
        Text("Overlay")
    }
}
```

---

## Smart Transitions with AzNavHost

The `AzNavHost` wrapper provides seamless integration with the `AzHostActivityLayout`:

1.  **Automatic Navigation Controller**: It automatically retrieves the `navController` provided to `AzHostActivityLayout`, eliminating the need to pass it again.
2.  **Directional Transitions**: It automatically configures entry and exit animations based on the rail's docking side:
    *   **Left Dock**: New screens slide in from the **Right**; old screens slide out to the **Left** (towards the rail).
    *   **Right Dock**: New screens slide in from the **Left**; old screens slide out to the **Right** (towards the rail).

---

## DSL Reference

The DSL is used inside `AzHostActivityLayout` to configure the rail and items.

### AzHostActivityLayout Scope

-   `background(weight: Int, content: @Composable () -> Unit)`: Adds a background layer ignoring safe zones.
-   `onscreen(alignment: Alignment, content: @Composable () -> Unit)`: Adds content to the safe area.

### AzNavRail Scope

**Sectors:**

1.  **`azTheme(...)`** (Visuals):
    -   `expandedRailWidth`: Dp
    -   `collapsedRailWidth`: Dp
    -   `defaultShape`: AzButtonShape
    -   `headerIconShape`: AzHeaderIconShape
    -   `activeColor`: Color?
    -   `showFooter`: Boolean

2.  **`azConfig(...)`** (Behavior):
    -   `displayAppName`: Boolean
    -   `packButtons`: Boolean
    -   `dockingSide`: AzDockingSide (LEFT/RIGHT)
    -   `noMenu`: Boolean
    -   `vibrate`: Boolean
    -   `activeClassifiers`: Set<String>

3.  **`azAdvanced(...)`** (Special Ops):
    -   `isLoading`: Boolean
    -   `enableRailDragging`: Boolean
    -   `onUndock`: (() -> Unit)?
    -   `overlayService`: Class<out Service>?
    -   `onOverlayDrag`: ((Float, Float) -> Unit)?
    -   `onItemGloballyPositioned`: ((String, Rect) -> Unit)?
    -   `infoScreen`: Boolean
    -   `onDismissInfoScreen`: (() -> Unit)?

**Items:**
-   `azMenuItem(...)`: Item visible only in expanded menu.
-   `azRailItem(...)`: Item visible in rail and menu.
-   `azMenuToggle(...)` / `azRailToggle(...)`: Toggle buttons.
-   `azMenuCycler(...)` / `azRailCycler(...)`: Cycle through options.
-   `azDivider()`: Horizontal divider.
-   `azMenuHostItem(...)` / `azRailHostItem(...)`: Parent items for nested menus.
-   `azMenuSubItem(...)` / `azRailSubItem(...)`: Child items.
-   `azRailRelocItem(...)`: Reorderable drag-and-drop items.

**Common Parameters:**
-   `id`: Unique identifier.
-   `text`: Display label.
-   `route`: Navigation route (optional).
-   `icon`: (Implicitly handled by shapes/text in this library).
-   `disabled`: Boolean state.
-   `info`: Help text for Info Screen mode.
-   `onClick`: Lambda action.

---

## New Features

### AzLoad Animation
The `AzLoad` component provides a loading animation. It can be used as a full-screen overlay managed by AzNavRail or as a standalone component.

**Full-Screen Overlay:**
```kotlin
AzNavRail(...) {
    azAdvanced(
        isLoading = true
        // ...
    )
}
```

**Standalone Usage:**
```kotlin
Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    AzLoad()
}
```

### AzRoller
The `AzRoller` component is a versatile dropdown that behaves like a slot machine but also supports typing and filtering.

```kotlin
AzRoller(
    options = listOf("Cherry", "Bell", "Bar"),
    selectedOption = "Cherry",
    onOptionSelected = { /* handle selection */ },
    hint = "Select Item",
    enabled = true
)
```

- **Split Interaction:** Left click for typing/filtering, right click for "Slot Machine" mode.
- **Typing Support:** Automatically filters options.

### Draggable Rail (FAB Mode)
Set `enableRailDragging = true` in `azAdvanced`. Long-press the header to undock into a FAB.

### Reorderable Items
Use `azRailRelocItem` for items that can be reordered via drag-and-drop.

```kotlin
azRailRelocItem(
    id = "reloc-1",
    hostId = "host-1",
    text = "Item 1",
    onRelocate = { from, to, newOrder -> }
)
```

### System Overlay
AzNavRail can function as a system-wide overlay. See the README for `OverlayService` implementation details.

---

## API Reference

### `AzHostActivityLayout`
```kotlin
@Composable
fun AzHostActivityLayout(
    modifier: Modifier = Modifier,
    navController: NavHostController, // Mandatory
    currentDestination: String? = null,
    isLandscape: Boolean? = null,
    initiallyExpanded: Boolean = false,
    disableSwipeToOpen: Boolean = false,
    content: AzNavHostScope.() -> Unit
)
```

### `AzNavHost`
```kotlin
@Composable
fun AzNavHost(
    startDestination: String,
    modifier: Modifier = Modifier,
    // navController derived from context if omitted
    contentAlignment: Alignment = Alignment.Center,
    route: String? = null,
    // ... transition params (smart defaults)
    builder: NavGraphBuilder.() -> Unit
)
```

### `AzTextBox`
A versatile text input component.
```kotlin
@Composable
fun AzTextBox(
    modifier: Modifier = Modifier,
    value: String? = null,
    onValueChange: ((String) -> Unit)? = null,
    hint: String = "",
    outlined: Boolean = true,
    multiline: Boolean = false,
    secret: Boolean = false,
    isError: Boolean = false,
    historyContext: String? = null,
    submitButtonContent: (@Composable () -> Unit)? = null,
    onSubmit: (String) -> Unit
)
```

### `AzForm`
Groups `AzTextBox` fields.
```kotlin
@Composable
fun AzForm(
    formName: String,
    onSubmit: (Map<String, String>) -> Unit,
    content: AzFormScope.() -> Unit
)
```

### `AzButton`, `AzToggle`, `AzCycler`
Standalone versions of the rail components are available for general UI use.
