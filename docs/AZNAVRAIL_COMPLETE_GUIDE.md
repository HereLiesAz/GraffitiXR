# AzNavRail Complete Guide (Sample App Edition)

This guide documents the complete configuration and usage of the AzNavRail library as demonstrated in the official **Sample App**. It serves as the definitive reference for setting up layouts, configuring the rail, and implementing all supported components.

---

## 1. Top-Level Setup: Host Activity Layout

Every AzNavRail implementation **must** start with `AzHostActivityLayout`. This container manages safe zones, device rotation (0°, 90°, 270°), and z-ordering.

**Sample App Implementation:**
```kotlin
AzHostActivityLayout(
    navController = navController,
    modifier = Modifier.fillMaxSize(),
    currentDestination = currentDestination?.destination?.route,
    isLandscape = isLandscape, // derived from LocalConfiguration
    initiallyExpanded = false
) {
    // 1. Configure the Rail here (DSL)
    // 2. Define Background layers here (DSL)
    // 3. Define Onscreen content here (DSL)
}
```


**React (React Native / react-native-web) Equivalent:**
While Android uses `AzHostActivityLayout` and a DSL to manage positioning and Safe Zones automatically, React projects explicitly construct their layout and pass properties and arrays of objects. The React version enforces the same visual rules via standard flex layouts.

```tsx
import { AzNavRail, AzNavItem, AzNavRailSettings } from '@HereLiesAz/aznavrail-react';
import { View } from 'react-native';

const settings: AzNavRailSettings = {
    dockingSide: AzDockingSide.LEFT,
    packRailButtons: false,
    usePhysicalDocking: false,
    defaultShape: AzButtonShape.RECTANGLE,
    activeColor: '#6200EE',
    translucentBackground: 'rgba(0,0,0,0.5)',
    enableRailDragging: true,
    isLoading: false,
    helpList: { "home": "Home screen" },
    infoScreen: false,
    onDismissInfoScreen: () => {},
};

const items: AzNavItem[] = [
    // Define items array here
];

export default function AppLayout() {
    return (
        <View style={{ flex: 1, flexDirection: 'row' }}>
            <AzNavRail
                appName="My App"
                items={items}
                expanded={false}
                settings={settings}
                onToggleExpand={() => {}}
            />
            {/* Background and Onscreen Content */}
        </View>
    );
}
```

---

## 2. Rail Configuration (DSL)

Inside the `AzHostActivityLayout` content block, you configure the rail using three primary functions: `azConfig`, `azTheme`, and `azAdvanced`.

### A. General Configuration (`azConfig`)
Controls layout behavior and docking logic.

```kotlin
azConfig(
    packButtons = packRailButtons,       // Boolean: Pack items tightly vs spaced
    dockingSide = AzDockingSide.LEFT,    // Enum: LEFT or RIGHT
    noMenu = noMenu,                     // Boolean: Disable the side drawer entirely
    usePhysicalDocking = usePhysicalDocking // Boolean: Anchor to physical hardware edge vs visual left
)
```


### B. Theming (`azTheme`)
Controls visual style defaults.

```kotlin
azTheme(
    defaultShape = AzButtonShape.RECTANGLE, // Default shape for all items
    activeColor = MaterialTheme.colorScheme.primary, // Color for active state
    translucentBackground = Color.Black.copy(alpha = 0.5f) // Set the background color for menus/overlays!
)
```

**React Implementation:**
```tsx
const settings: AzNavRailSettings = {
    defaultShape: AzButtonShape.RECTANGLE,
    activeColor: '#6200EE',
    translucentBackground: 'rgba(0,0,0,0.5)',
};
// Pass this object to the settings prop on AzNavRail
```



### C. Advanced Features (`azAdvanced`)
Enables complex behaviors like drag-and-drop and help overlays.

```kotlin
azAdvanced(
    isLoading = isLoading,               // Boolean: Show global loading overlay
    enableRailDragging = true,           // Boolean: Enable FAB Mode (detach rail)
    helpEnabled = showHelp,              // Boolean: Show Help Overlay
    helpList = mapOf("home" to "Home screen"), // Map<String, Any>: Extra help texts
    onDismissHelp = { showHelp = false },
    onInteraction = { itemId, item ->    // Called on every item interaction
        Log.d("Rail", "Interacted: $itemId (${item.text})")
    }
)
```

`onInteraction` fires whenever any rail item is interacted with — click, toggle, cycler advance, nested rail open, or reloc drag. It receives the item's `id` and the full `AzNavItem`, enabling analytics integration without per-item callbacks.

**React Implementation:**
```tsx
const settings: AzNavRailSettings = {
    isLoading: isLoading,
    enableRailDragging: true,
    infoScreen: showHelp,
    helpList: { "home": "Home screen" },
    onDismissInfoScreen: () => setShowHelp(false),
};
// Pass this object to the settings prop on AzNavRail
// onInteraction is passed as a prop on AzNavRail:
// <AzNavRail onInteraction={(action, details, item) => console.log(action, item)} ...>
```


> **Note on Help Overlay:**
> The `HelpOverlay` displays a short, truncated entry for each item to conserve space. Tapping a help card expands it to reveal the full description and any extra text provided in `helpList`. Furthermore, `helpList` can be supplied dynamically to `AzNestedRail` components for distinct, localized help data.

---

## 3. Navigation Items (DSL)

Items are added sequentially. The order in the DSL determines the order in the rail/menu.

### Standard Items
*   **Menu Item:** Only appears in the expanded drawer.
*   **Help Rail Item:** Dedicated trigger for the Help overlay.
*   **Rail Item:** Appears in the rail (and drawer).
*   **Content Types:** Supports Text, resource IDs (Icons), and `Color`.

```kotlin
// Menu-only item
azMenuItem(
    id = "home",
    text = "Home",
    route = "home",
    info = "Navigate to the Home screen",
    onClick = { /* log click */ }
)

// Multi-line text support
azMenuItem(id = "multi-line", text = "This is a\nmulti-line item", route = "multi-line")

// Help trigger rail item
azHelpRailItem(id = "help-trigger", text = "Get Help")

// Help trigger as a sub-item
azHelpSubItem(id = "help-sub-trigger", hostId = "rail-host", text = "Get Help Here")

// Rail item with Color content
azRailItem(id = "color-item", text = "Color", content = Color.Red)

// Rail item with Icon Resource
azRailItem(id = "icon-item", text = "Icon", content = android.R.drawable.ic_menu_agenda)

// Rail item with specific shape override
azRailItem(id = "none-shape", text = "No Shape", shape = AzButtonShape.NONE)

// Rail item with Custom Composable Content Size
azRailItem(id = "wide-composable", text = "Wide", content = AzComposableContent {
    Box(Modifier.width(120.dp).background(Color.Blue))
}) // Will not clip to rail width!

// Disabled item
azRailItem(id = "profile", text = "Profile", disabled = true, route = "profile")

// Rail item with custom @Composable content via AzComposableContent
azRailItem(
    id = "size_item",
    text = "Size",
    content = AzComposableContent { isEnabled ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isEnabled) {
                    if (isEnabled) {
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            // Drag logic
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(Color.White, CircleShape)
            )
        }
    }
)
```

**React Implementation:**
```tsx
// Tutorials are mapped through helpList in React
const settings: AzNavRailSettings = {
    infoScreen: true,
    helpList: {
        "item-1": "Help text for item 1"
    }
};
```

### Toggles
Binary switches for state (e.g., Online/Offline, Dark Mode).

```kotlin
// Rail Toggle
azRailToggle(
    id = "pack-rail",
    isChecked = packRailButtons,
    toggleOnText = "Packed",
    toggleOffText = "Unpacked",
    route = "pack-rail",
    onClick = { packRailButtons = !packRailButtons }
)

// Menu Toggle
azMenuToggle(
    id = "dark-mode",
    isChecked = isDarkMode,
    toggleOnText = "Dark Mode",
    toggleOffText = "Light Mode",
    onClick = { isDarkMode = !isDarkMode }
)
```

### Cyclers
Multi-state buttons that cycle through a list of options.

```kotlin
// Rail Cycler (with disabled specific option)
azRailCycler(
    id = "rail-cycler",
    options = listOf("A", "B", "C", "D"),
    selectedOption = "A",
    disabledOptions = listOf("C"),
    onClick = { /* cycle logic */ }
)

// Menu Cycler
azMenuCycler(
    id = "menu-cycler",
    options = listOf("X", "Y", "Z"),
    selectedOption = "X",
    onClick = { /* cycle logic */ }
)
```

### Dividers
Visual separators.
```kotlin
azDivider()
```

---

## 4. Hierarchical Navigation (Hosts)

Hosts are accordion-style items that expand to reveal sub-items.

```kotlin
// Menu Host
azMenuHostItem(id = "menu-host", text = "Menu Host")
// Sub-items must reference the hostId
azMenuSubItem(id = "menu-sub-1", hostId = "menu-host", text = "Menu Sub 1")
azMenuSubToggle(id = "sub-toggle", hostId = "menu-host", isChecked = true, toggleOnText = "On", toggleOffText = "Off")

// Rail Host
azRailHostItem(id = "rail-host", text = "Rail Host")
azRailSubItem(id = "rail-sub-1", hostId = "rail-host", text = "Rail Sub 1")
azHelpSubItem(id = "help-sub-item", hostId = "rail-host", text = "Help Sub")
azRailSubCycler(id = "sub-cycler", hostId = "rail-host", options = listOf("A", "B"), selectedOption = "A")
```

### Nested hosts (sub-items that are also hosts)

A sub-item can itself be a host with its own sub-items via `azRailSubHostItem` /
`azMenuSubHostItem`. Hosts nest to **any depth**: opening a sub-host reveals its children
inline while its sibling sub-items stay visible (accordion behavior at every level).

Children are matched to their host by `hostId` (a reference, not by position), so a sub-host's
children are unambiguous even when they sit among other sub-items.

```kotlin
azRailHostItem(id = "rail-host", text = "Rail Host")
azRailSubItem(id = "rail-sub-1", hostId = "rail-host", text = "Rail Sub 1")

// "rail-subhost" is a child of "rail-host" AND a host for its own children.
azRailSubHostItem(id = "rail-subhost", hostId = "rail-host", text = "Rail Sub Host")
azRailSubItem(id = "nested-a", hostId = "rail-subhost", text = "Nested A")
azRailSubItem(id = "nested-b", hostId = "rail-subhost", text = "Nested B")
```

> The parent host referenced by `hostId` must be declared **before** the sub-host, and a
> sub-host may not reference itself.

---

## 5. Drag & Drop (Relocatable Items)

Items that can be reordered by the user.
**Requirement:** Minimum of 2 items with the same `hostId`.

```kotlin
azRailRelocItem(
    id = "reloc-1",
    hostId = "rail-host", // Cluster ID
    text = "Reloc Item 1",
    forceHiddenMenuOpen = false, // Programmatic control for hidden context menu
    onHiddenMenuDismiss = { /* Menu was closed! */ },
    onRelocate = { from, to, newOrder -> /* handle reorder */ }
) {
    // Hidden Context Menu (Tap to open)
    listItem(text = "Action 1", onClick = { })
}
```

---

## 6. Nested Rails (Popups)

Secondary rails that open in a popup overlay. Do NOT assign a route to the parent item.

**Dynamic Bumping Effect:** When a vertical nested rail is opened, the main navigation rail will dynamically decrease its width (shrinking to the button width) to simulate the nested rail bumping it out of the way. Closing the nested rail restores the main rail to its original width.

```kotlin
// Vertical Nested Rail
azNestedRail(
    id = "nested-rail",
    text = "Vertical Nested",
    alignment = AzNestedRailAlignment.VERTICAL,
    keepNestedRailOpen = true // Remains open until parent is tapped again
) {
    azRailItem(id = "nested-1", text = "Nested Item 1", route = "nested-1")
}

// Horizontal Nested Rail
azNestedRail(
    id = "nested-horizontal",
    text = "Horizontal Nested",
    alignment = AzNestedRailAlignment.HORIZONTAL
) {
    azRailItem(id = "nested-h-1", text = "H-Item 1")
}
```

---

## 7. Layout Layers (Background & Onscreen)

AzNavRail allows defining content layers relative to the rail.

### Background Layers
Content placed *behind* the rail.

```kotlin
background(weight = 0) {
    // Full screen background (e.g. Map)
    Box(Modifier.fillMaxSize().background(Color(0xFFEEEEEE)))
}

background(weight = 10) {
    // Layer with padding
    Box(...)
}
```

### Onscreen Content
The main UI content, automatically padded to respect safe zones and rail width.

**Usage:**
~~~kotlin
// Basic Usage
azRailRelocItem(
    id = "1",
    hostId = "favs",
    text = "Favorite A",
    onRelocate = { from, to, newOrder -> }
) {
    // Define Hidden Context Menu (Fallback)
    listItem("Delete") { }
}

// As a Nested Rail Parent
azRailRelocItem(
    id = "tools_reloc",
    hostId = "toolbar",
    text = "Drag Me",
    nestedRailAlignment = AzNestedRailAlignment.HORIZONTAL, // Customize direction
    keepNestedRailOpen = true, // Remains open until parent is tapped again
    nestedContent = {
        // This content appears in the popup when the item is clicked (not dragged)
        azRailItem("hammer", "Hammer")
        azRailItem("wrench", "Wrench")
    }
) {
    // Hidden Menu (optional if nestedContent is provided)
    listItem("Remove Tool") { }
}
~~~

---

## 8. Standalone Components

These components are used within your screens (e.g., inside `AzNavHost`), not inside the rail configuration.

### AzTextBox
Advanced text input with history support.

*   **Uncontrolled (History):** `historyContext` persists values.
    ```kotlin
    AzTextBox(hint = "Search", historyContext = "search_history", onSubmit = {})
    ```
*   **Controlled:** Manually manage state via `value` and `onValueChange`.
    ```kotlin
    AzTextBox(value = text, onValueChange = { text = it }, hint = "Controlled")
    ```
*   **No Outline:** `outlined = false`
*   **Disabled:** `enabled = false`

### AzForm
Groups AzTextBoxes for validation and traversal.

```kotlin
AzForm(
    formName = "loginForm",
    onSubmit = { formData -> /* Map<String, String> */ }
) {
    entry(entryName = "username", hint = "Username", initialValue = "AzRailFan") // Pre-filled!
    entry(entryName = "password", hint = "Password", secret = true) // Password mask
    entry(entryName = "bio", hint = "Biography", multiline = true)  // Multi-line
}
```

### AzRoller
Slot-machine style selector.

```kotlin
AzRoller(
    options = listOf("Cherry", "Bell", "Bar"),
    selectedOption = "Cherry",
    onOptionSelected = { it -> }
)
```

### AzButton / AzToggle / AzCycler
Standalone versions of rail components for general UI use.

```kotlin
AzButton(text = "Button", onClick = {}, shape = AzButtonShape.SQUARE)
AzToggle(isChecked = true, onToggle = {}, toggleOnText = "On", toggleOffText = "Off")
AzCycler(options = listOf("1", "2"), selectedOption = "1", onCycle = {})
```


## 9. Status-Driven Guidance Framework

The guidance framework replaces the old scripted scene/card tutorial with a **reactive, status-driven graph**. Instead of authoring linear walkthroughs, you describe your app's userflow as a flowchart of **statuses** (named reactive boolean nodes) connected by **edges** (transitions that carry instruction text), and declare **goals** (target statuses) to guide the user toward. The engine finds the shortest path from a currently-true status to each active goal, shows the next step's instruction, auto-advances the moment a target becomes true (there is no "Next" button), reroutes live as the user wanders, and renders every active goal's instruction as a callout. The instruction overlay is mounted automatically by `AzHostActivityLayout` — you never mount it yourself.

### 9.1 Concepts

**Status** — a named node identified by a string id, true when its predicate is true. Sources:
- Developer predicates via `azStatus(id) { ... }`.
- Built-in `az.*` ids the engine auto-publishes (below).

**Edge** — a directed transition `from → to` carrying instruction text. When the engine routes through an edge it shows that text; when `to` becomes true it auto-advances. An edge with `to = null` is a **passive** ambient hint (shown while `from` holds, with no next target).

**Goal** — a target status to reach, declared with `azGoal(...)`. Inert until activated — imperatively via the controller, or self-activating via `autoStartWhen`. Multiple goals can be active at once; their instructions are de-duplicated and each rendered as a callout.

**Built-in statuses** (auto-published):
- `az.app.ready` (always true; root node)
- `az.rail.expanded` / `az.rail.collapsed` / `az.rail.floating`
- `az.host.<id>.expanded`
- `az.screen.<route>`
- `az.item.<id>.active`
- `az.nestedRail.<id>.open`
- `az.help.open`
- `az.onscreen.<id>.visible`

The engine also auto-generates edges for rail affordances (open the menu, tap a host → expanded, open a nested rail, tap a routed item → `az.screen.<route>`).

### 9.2 Android DSL

These are members of the rail scope, so they're called inside the `AzHostActivityLayout { ... }` content lambda alongside `azRailItem`, `azAdvanced`, etc.

```kotlin
fun azStatus(id: String, predicate: () -> Boolean)

fun azEdge(
    from: String,
    to: String? = null,
    text: String = "",
    title: String? = null,
    highlightItemId: String? = null,
    highlightTargetId: String? = null,
    highlightSelector: (() -> String?)? = null,
    steps: List<AzInstructionStep>? = null,
)

fun azGoal(id: String, target: String, label: String? = null, autoStartWhen: String? = null)
```

`autoStartWhen` is a **status id** (not a lambda): the goal self-activates whenever that status is true and the goal hasn't already completed.

`AzHostActivityLayout(...)` **returns** an `AzGuidanceController`. Inside composables it is also available as `LocalAzGuidanceController.current`.

> **Note:** `rememberAzGuidanceController()` remembers a *fresh* controller. To drive the rendered overlay, read the host-provided instance from `LocalAzGuidanceController.current` (or the value returned by `AzHostActivityLayout`) — not a `remember`ed one.

```kotlin
val guidance = AzHostActivityLayout(navController = nav, currentDestination = route) {
    azRailHostItem(id = "features", text = "Features")
    azRailSubItem(id = "feature-a", hostId = "features", text = "Feature A", route = "feature-a")

    azStatus("profileComplete") { viewModel.profile.isComplete }

    azEdge(
        from = "az.screen.feature-a",
        to = "profileComplete",
        text = "Fill in your name and email to finish setup.",
        title = "Complete your profile",
        highlightItemId = "feature-a",
    )

    azGoal(id = "onboarding", target = "profileComplete", label = "Finish onboarding", autoStartWhen = "az.app.ready")
    azGoal(id = "find-features", target = "az.host.features.expanded", label = "Discover Features")
}

guidance.activate("find-features")
guidance.deactivate("find-features")
guidance.markReached("profileComplete")
val done: Boolean = guidance.isCompleted("onboarding")
```

`AzGuidanceController` members: `enabled`, `activeGoals`, `completedGoals`, `current`, `currentInstructions`, `currentFlow` (a `StateFlow`), `enable()`, `disable()`, `activate(id)`, `deactivate(id)`, `markReached(id)`, `isCompleted(id)`, and `advance()` / `next()` / `back()` for manual stepping.

**Persistence:** completed goals are stored in `SharedPreferences` file `az_tutorial_prefs`, key `az_navrail_completed_goals`.

### 9.3 Paged steps, dynamic highlights & arbitrary targets

A single edge can carry an ordered list of `AzInstructionStep`s, so one logical step walks the spotlight across several controls:

```kotlin
AzInstructionStep(
    text: String,
    title: String? = null,
    highlightItemId: String? = null,      // a rail item id, or the AZ_ITEM_ACTIVE token
    highlightTargetId: String? = null,    // an arbitrary on-screen target (see azGuidanceTarget)
    side: AzCalloutSide? = null,          // Auto | Above | Below | Start | End
    highlightSelector: (() -> String?)? = null, // resolve a rail item id at render time
    advanceWhen: String? = null,          // status id; auto-advances when true (else "tap to continue")
)
```

- **Dynamic item highlight:** pass `highlightItemId = AZ_ITEM_ACTIVE` to point at whichever rail item is currently active, or `highlightSelector = { viewModel.activeId }` to resolve a runtime id (e.g. a per-row item created after the graph was declared) each frame. Returning null degrades to text-only.
- **Arbitrary on-screen targets:** register a spotlight on any composable region with `azGuidanceTarget(id) { AzGuideShape? }`, then reference it via `highlightTargetId`. `AzGuideShape` is `Circle`, `Rect`, or `Path` (built from `AzPathCmd`), recomputed every frame; return null to degrade to text-only.

### 9.4 Suppression & custom rendering

```kotlin
// Hide callouts while the predicate is true; re-show after a settle delay when it flips back.
azSuppressGuide(settleMs = 700L) { gestureInProgress }

// Supply your own callout body; the library still draws the dim + spotlight.
azGuideRenderer { snapshot, bounds -> /* your composable */ }
```

### 9.5 Engine behaviour

- **Pathfinding:** BFS finds the shortest path from a true status to each active goal's target; the first edge on that path is shown.
- **Auto-advance:** no Next button — advancement is driven entirely by status truth; a goal completes the instant its target becomes true.
- **Live re-routing:** the route recomputes when the user's state changes.
- **De-duplication:** an instruction shared across goals is shown once.
- **Observation timing:** Compose/snapshot state is observed instantly; non-reactive sources are polled (~300 ms).

### 9.6 Model types

| Type | Shape |
|---|---|
| `AzGuideHighlight` | `None` · `FullScreen` · `Item(id)` · `Area(rect)` · `Target(id)` · `Dynamic(selector)` · `ActiveItem` |
| `AzInstructionStep` | `(text, title?, highlightItemId?, highlightTargetId?, side?, highlightSelector?, advanceWhen?)` |
| `AzInstruction` | `(text, title?, highlight, side, media?)` |
| `AzCalloutSide` | `Auto` / `Above` / `Below` / `Start` / `End` |
| `AzGuideShape` | `Circle(cx, cy, radius, padding)` · `Rect(left, top, width, height, cornerRadius, padding)` · `Path(commands, padding)` |
| `AzEdge` | `(from, to?, instruction, steps)` |
| `AzGoal` | `(id, target, label?, autoStartWhen?)` |

### 9.7 React / Web

The TypeScript port mirrors the Android API with `<AzStatus>`, `<AzEdge>`, and `<AzGoal>` components and a `useAzGuidanceController()` hook exposing the same surface (`enabled`, `activeGoals`, `completedGoals`, `enable`, `disable`, `activate`, `deactivate`, `markReached`, `isCompleted`). Completed goals persist to `localStorage` / `AsyncStorage` under `az_navrail_completed_goals`. Additional exports: `AzGuidanceProvider`, `AzInstructionOverlay`, `useActiveStatuses`, `computeBuiltinStatuses`, `nextHop`, `routeInstructions`, `computeAutoEdges`.

### 9.8 Migration from the scripted framework

| Old (scripted) | New (status-driven) |
|---|---|
| `AzTutorial` / `scene(...)` / `card(...)` | `azStatus` / `azEdge` / `azGoal` + the reactive engine |
| `AzTutorialController.startTutorial(id, …)` | `AzGuidanceController.activate(goalId)` |
| `AzTutorialController.markTutorialRead(id)` | `AzGuidanceController.markReached(goalId)` |
| `azAdvanced(tutorials = …)` | **removed** — declare statuses/edges/goals in the host lambda |
| Help-overlay "Start Tutorial" launcher | **removed** — guidance is developer-activated (and self-activating via `autoStartWhen`) |
| `AzHighlight` / `AzAdvanceCondition` (Button / TapTarget / TapAnywhere / Event) | subsumed by the status graph + auto-advancing engine; per-step `advanceWhen` |
| Scene branching, checklist & media cards, `AzTutorialOverlay` | removed; the overlay is auto-mounted |
| Persistence key `az_navrail_read_tutorials` | `az_navrail_completed_goals` |

---

## 10. Bottom Sheets

AzNavRail ships a four-detent bottom-sheet shell ported from [LogKitty](https://github.com/HereLiesAz/LogKitty). It is offered in two flavors that share state, theming, and gesture handling, so consumers get identical visual behavior whether the sheet lives inside a normal Activity or floats over the screen from a foreground Service.

### 10.1 The Detent Model

| Detent | Default height | Purpose |
| :--- | :--- | :--- |
| `HIDDEN` | 14dp swipe strip | Sheet is collapsed; the strip is a touch-target for a drag-up gesture but otherwise lets the underlying UI receive touches. |
| `PEEK` | 56dp ticker | Single-line preview of the sheet content. |
| `HALF` | 50% of parent | Half-screen view with a dim scrim above. |
| `FULL` | 90% of parent | Near-full-screen view with the same scrim. |

The fractions and the absolute heights are tunable via `AzSheetConfig`.

### 10.2 In-tree usage

Inside `AzHostActivityLayout` use the `azBottomSheet` DSL. The sheet draws above the rail, the menu, and the `onscreen` content area with `zIndex(2f)`, spans the full screen width edge-to-edge, and extends all the way to the bottom of the screen (no automatic `windowInsetsPadding`) so the HIDDEN-detent strip — 28dp tall by default, with a dimmed drag-handle — is reachable from the system-navigation-bar area. A tap on the strip steps up to PEEK alongside the swipe-up gesture. It is *not* a background. If your sheet body needs to clear the system nav bar visually, pad inside your `content` lambda or use `AzBottomSheetInsetAware` directly outside the DSL.

```kotlin
val sheetController = rememberAzSheetController(initial = AzSheetDetent.PEEK)

AzHostActivityLayout(navController = nav, currentDestination = currentRoute) {
    azConfig(dockingSide = AzDockingSide.LEFT)
    azMenuItem(id = "home", text = "Home", route = "home", onClick = { /* … */ })
    onscreen { AzNavHost(startDestination = "home") { /* … */ } }

    azBottomSheet(controller = sheetController) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Hello sheet")
            Button(onClick = { sheetController.stepUp() }) { Text("Expand") }
        }
    }
}
```

### 10.3 Controller and state

`AzSheetController` carries two channels: a Compose `mutableStateOf`-backed `var` for in-tree consumers, and a `StateFlow` for the system-overlay flavor's window-resize coroutine. Mutate `detent` and `isEnabled` from the main thread; both channels stay in sync.

```kotlin
sheetController.stepUp()                          // HIDDEN → PEEK → HALF → FULL
sheetController.stepDown()                        // reverse
sheetController.snapTo(AzSheetDetent.FULL)         // direct jump
sheetController.isEnabled = false                  // forces HIDDEN, blocks step calls
```

### 10.4 Gestures

- **Swipe up** on the sheet card or hidden strip accumulates per-frame delta and calls `stepUp()` exactly once when `config.dragThresholdDp` is crossed.
- **Swipe down** calls `snapTo(HIDDEN)`, dismissing the sheet entirely in one gesture rather than stepping down one detent at a time.
- **Scrim tap** in `HALF` / `FULL` calls `stepDown()` (dim overlay visible).
- **Transparent tap overlay** at `PEEK` — a non-dimmed, full-screen tap catcher that calls `stepDown()`, transitioning to HIDDEN. Makes the dismiss gesture discoverable for users who tap rather than swipe.
- System **back press** calls `stepDown()` while the sheet is non-HIDDEN when `config.collapseOnBack = true`.
- **Horizontal swipe** is opt-in via `config.horizontalSwipeEnabled` and the `onSwipeLeft` / `onSwipeRight` callbacks — LogKitty uses these for tab navigation.

### 10.5 Theming

`AzSheetConfig.backgroundColor` defaults to `MaterialTheme.colorScheme.surface` blended with `backgroundAlpha`. Override both for custom looks; LogKitty wires its user-configurable color + opacity directly through.

### 10.6 System-overlay flavor

For Services that float a sheet over the active foreground app, use `AzBottomSheetWindowHost`. The library ships no `Service` and no permissions; the consumer's Service supplies the lifecycle/savedState owners and declares `SYSTEM_ALERT_WINDOW` itself.

```kotlin
class MyOverlayService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {
    private lateinit var sheetHost: AzBottomSheetWindowHost
    private val controller = AzSheetController(initial = AzSheetDetent.HIDDEN)

    override fun onCreate() {
        super.onCreate()
        sheetHost = AzBottomSheetWindowHost(
            context = this,
            controller = controller,
            config = AzSheetConfig(
                backgroundColor = userBg,
                backgroundAlpha = userAlpha,
            ),
            lifecycleOwner = this,
            viewModelStoreOwner = this,
            savedStateRegistryOwner = this,
            navBarHeightPx = resources.getDimensionPixelSize(
                resources.getIdentifier("navigation_bar_height", "dimen", "android")
            ),
        ) { MyContent(controller) }
        sheetHost.attach()
    }

    override fun onDestroy() {
        sheetHost.detach()
        super.onDestroy()
    }
}
```

Call `sheetHost.attachNavBarDecor()` from an accessibility service's `onServiceConnected` to add the secondary `TYPE_ACCESSIBILITY_OVERLAY` window that tints the system nav bar to match the sheet color.

The in-tree flavor animates between detent heights with `animateDpAsState`; the system-overlay flavor hard-jumps via `WindowManager.updateViewLayout`, matching LogKitty's existing look frame-for-frame.

### 10.7 LogKitty migration

LogKitty currently maintains its own `SheetController`, `LogBottomSheet`, and nav-bar-decoration code inside `LogKittyOverlayService`. To replace them with AzNavRail's shell:

1. Add the `aznavrail` dependency.
2. Replace `SheetController` and `LogBottomSheet` with `AzSheetController` and `AzBottomSheetWindowHost` (see snippet above).
3. Pass LogKitty's existing tabs / log-list composable as the `content` slot.
4. Delete `LogBottomSheet.kt`, `SheetController.kt`, and the inline nav-bar decoration block.

Visual behavior — detent heights, drag feel, scrim, animation timing, and nav-bar color sync — is preserved frame-for-frame because `AzBottomSheetWindowHost` ports the same `WindowManager` flag set, the same accumulated-delta gesture, and the same nav-bar decoration window verbatim.
