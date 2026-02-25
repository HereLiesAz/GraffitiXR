# AzNavRail Complete Guide (v7.55)

Welcome to the definitive "Encyclopedic" guide for **AzNavRail**. This document details every single feature, its abilities, its limitations, and strict usage protocols.

---

## Table of Contents

1.  [Core Architecture](#core-architecture)
    *   [Host Activity Layout](#host-activity-layout)
    *   [Safe Zones & Layout Logic](#safe-zones--layout-logic)
2.  [Rail Configuration](#rail-configuration)
    *   [Docking & Orientation](#docking--orientation)
    *   [FAB Mode (Draggable Rail)](#fab-mode-draggable-rail)
    *   [Info Screen (Help Mode)](#info-screen-help-mode)
3.  [Navigation Structure](#navigation-structure)
    *   [Hosted vs. Nested Rails](#hosted-vs-nested-rails)
    *   [Hosted Rails (Hierarchical)](#hosted-rails-hierarchical)
    *   [Nested Rails (Popup)](#nested-rails-popup)
    *   [Standard Items](#standard-items)
    *   [Reorderable Items (Drag & Drop)](#reorderable-items-drag--drop)
4.  [Interactive Components](#interactive-components)
    *   [Toggles](#toggles)
    *   [Cyclers](#cyclers)
    *   [Rollers (Slot Machine)](#rollers-slot-machine)
5.  [Input Components](#input-components)
    *   [AzTextBox](#aztextbox)
    *   [AzForm](#azform)
6.  [Advanced Features](#advanced-features)
    *   [AzLoad (Loading)](#azload-loading)
    *   [System Overlay](#system-overlay)

---

## Core Architecture

### Host Activity Layout

**Description:** The mandatory top-level container. It manages the Z-ordering of the rail, background layers, and main content.

**Abilities:**
*   **Automatic Padding:** Calculates the width of the rail (collapsed or floating) and applies padding to your content so it never overlaps.
*   **Rotation Handling:** Adapts layout to device rotation (0°, 90°, 270°).
*   **Background Layering:** Allows placing content *behind* the rail (e.g., full-screen maps) using `background(weight)`.

**Limitations:**
*   **Strict Usage:** Must be the root composable. Throwing an error if `AzNavRail` is used outside it.
*   **Single Instance:** Designed for one rail per screen.

**Usage:**
```kotlin
AzHostActivityLayout(navController = navController) {
    background(weight = 0) { GoogleMap(...) } // Behind UI
    onscreen(Alignment.TopStart) { Text("Main UI") } // Safe UI
}
```

### Safe Zones & Layout Logic

**Description:** A strict system that reserves screen real estate for system bars and navigation elements.

**Abilities:**
*   **Top 10%:** Reserved for Header/Status Bar. No interactive content allowed here.
*   **Bottom 10%:** Reserved for Footer/Nav Bar. No interactive content allowed here.
*   **Mirrored Alignment:** If docked `RIGHT`, `Alignment.TopStart` acts visually as `TopEnd` relative to the safe area.

**Limitations:**
*   **Unavoidable:** You cannot disable safe zones. They are "Constitutionally" enforced.

---

## Rail Configuration

### Docking & Orientation

**Description:** Controls where the rail sits.

**Abilities:**
*   **`dockingSide`**: `LEFT` or `RIGHT`.
*   **`usePhysicalDocking`**: If `true`, ties the rail to the physical hardware edge. If you rotate the device, the rail rotates with it (staying on the physical "left"), rather than jumping to the new visual left.

**Usage:**
```kotlin
azConfig(
    dockingSide = AzDockingSide.LEFT,
    usePhysicalDocking = true
)
```

### FAB Mode (Draggable Rail)

**Description:** Allows the rail to detach from the edge and become a Floating Action Button (FAB).

**Abilities:**
*   **Activation:** Long-press the header icon OR swipe vertically on the rail. Triggers haptic feedback.
*   **Drag Constraints:** Can be dragged anywhere on the screen. The X-axis is constrained to the screen width, and the Y-axis is constrained to the 10-90% safe zones.
*   **Auto-Fold:** If expanded, items fold up into the FAB while dragging and unfold when dropped.
*   **Snapping:** Dragging close to the docking edge snaps it back to Rail Mode.

**Limitations:**
*   **No Menu:** The expandable drawer is disabled in FAB mode.
*   **Size Cap:** Max size is capped at 80% of screen height/width to ensure safe zone compliance.

**Usage:**
```kotlin
azAdvanced(enableRailDragging = true)
```

### Info Screen (Help Mode)

**Description:** An interactive overlay for onboarding.

**Abilities:**
*   **Visual Guides:** Draws lines connecting description cards to specific rail items.
*   **Auto-Wiring:** Automatically calculates item positions; no manual coordinates needed.
*   **Live Debugging:** Displays X/Y coordinates of items.
*   **Host Interaction:** Users can expand Host items to see help for sub-items.

**Limitations:**
*   **Modal:** Blocks interaction with standard items (except Hosts) until dismissed.

**Usage:**
```kotlin
azAdvanced(infoScreen = true)
azRailItem(..., info = "This goes to Home")
```

---

## Navigation Structure

### Hosted vs. Nested Rails

It is crucial to understand the difference between the two hierarchical systems:

| Feature | **Hosted Rails** | **Nested Rails** |
| :--- | :--- | :--- |
| **Visual Metaphor** | Accordion / Expansion | Popup / Overlay |
| **Location** | Inline within the Rail or Menu | Floating next to the anchor item |
| **Usage** | Primary navigation hierarchy (e.g., Settings categories) | Complex toolsets, deep sub-menus, or clutter reduction |
| **Interaction** | Click to Expand/Collapse | Click to Open Popup (closes on outside click) |
| **Context** | Shares the same visual container | Creates a new Z-ordered window |

### Hosted Rails (Hierarchical)

**Description:** Accordion-style hierarchy where parent items expand to reveal children inline.

**Abilities:**
*   **Exclusive Expansion:** Only one Host can be open at a time. Opening another auto-collapses the first.
*   **Location:** Hosts can be in the Rail (always visible) or Menu (drawer only).

**Usage:**
```kotlin
azRailHostItem(id = "settings", text = "Settings")
// Child items MUST reference the parent's ID
azRailSubItem(id = "wifi", hostId = "settings", text = "WiFi", route = "wifi")
azRailSubItem(id = "bt", hostId = "settings", text = "Bluetooth", route = "bluetooth")
```

### Nested Rails (Popup)

**Description:** A secondary rail that appears as a popup overlay when the parent item is clicked.

**Abilities:**
*   **Alignment:**
    *   `VERTICAL`: Drops down from the item. Good for sub-menus.
    *   `HORIZONTAL`: Slides out to the side. Good for tool palettes.
*   **Positioning:** Automatically calculated relative to the anchor item's screen position.
*   **Offset:** Horizontal rails have a configurable margin (default 8dp) to prevent overlap.
*   **Reloc Integration:** Relocatable items (`azRailRelocItem`) can also host Nested Rails.

**Limitations:**
*   **Single Level:** Nested rails cannot contain *other* nested rails (depth limit 1).

**Usage:**
```kotlin
azNestedRail(
    id = "tools",
    text = "Tools",
    alignment = AzNestedRailAlignment.HORIZONTAL
) {
    // Define the content of the popup rail here
    azRailItem("hammer", "Hammer")
    azRailItem("wrench", "Wrench")
}
```

### Standard Items

**Description:** The basic clickable unit.

**Abilities:**
*   **Dynamic Content:** Accepts `Color`, `Int` (Resource ID), or `String` (Text).
*   **Shapes:** `CIRCLE`, `SQUARE` (Fixed size), `RECTANGLE` (Auto-width), `NONE` (Text only).
*   **Classifiers:** Tags for programmatic highlighting (e.g., "shared_route").

**Limitations:**
*   **ImageVectors:** Explicitly NOT supported (use Resource ID instead) due to Coil crashes.

**Usage:**
```kotlin
azRailItem(
    id = "home",
    text = "Home",
    content = R.drawable.ic_home, // Resource ID
    shape = AzButtonShape.SQUARE
)
```

### Reorderable Items (Drag & Drop)

**Description:** Items that can be rearranged by the user.

**Abilities:**
*   **Cluster Logic:** Items can only be moved within their contiguous "cluster" (neighbors sharing the same `hostId` and type).
*   **Interaction Model:**
    *   **Tap:** Selects/Focuses the item.
    *   **Long Press + Drag:** Moves the item within its cluster (vibration confirmation on grab).
    *   **Long Press (No Drag):** Opens the Hidden Context Menu or Nested Rail.
*   **Nested Rail Support:** Can host a Nested Rail via the `nestedContent` block.
*   **Context Menu:** Supports `listItem` (actions) and `inputItem` (renaming) in the `hiddenMenu` block.

**Limitations:**
*   **Minimum 2:** You need at least 2 items to form a cluster.
*   **Overlap Threshold:** Requires 40% overlap to trigger a swap.

**Usage:**
```kotlin
azRailRelocItem(
    id = "1",
    hostId = "favs",
    text = "Favorite A",
    onRelocate = { from, to, newOrder -> },
    // Define Nested Rail (Popup) content
    nestedContent = {
        azRailItem("sub_action", "Sub Action")
    }
) {
    // Define Hidden Context Menu (Fallback)
    listItem("Delete") { }
}
```

---

## Interactive Components

### Toggles

**Description:** Binary state switch.

**Abilities:**
*   **Visuals:** Updates text/icon based on state.
*   **Feedback:** Haptic feedback on toggle.

**Usage:**
```kotlin
azRailToggle(id = "dark", isChecked = isDark, toggleOnText = "Dark", toggleOffText = "Light")
```

### Cyclers

**Description:** Multi-state button.

**Abilities:**
*   **Delay:** Built-in 1000ms delay before confirming selection to prevent accidental rapid cycling.
*   **Visuals:** Shows pending selection during delay.

**Limitations:**
*   **Validation:** Throws error if `selectedOption` is not in `options` list.

**Usage:**
```kotlin
azRailCycler(id = "mode", options = listOf("A", "B", "C"), selectedOption = "A")
```

### Rollers (Slot Machine)

**Description:** A dropdown that behaves like a physical roller.

**Abilities:**
*   **Split Interaction:** Left-click = Type/Filter. Right-click = Slot Machine Roll.
*   **Filtering:** Typing filters the list in real-time.
*   **Snapping:** Items snap into place when scrolling.

**Usage:**
```kotlin
AzRoller(options = listOf("1", "2", "3"), selectedOption = "1", onOptionSelected = {})
```

---

## Input Components

### AzTextBox

**Description:** Advanced text input.

**Abilities:**
*   **History:** Namespaced autocomplete history (LRU, max 5 items). Persists to file.
*   **Modes:** `multiline` (auto-expand) OR `secret` (password mask). Mutual exclusive.
*   **Controls:** Integrated Clear/Reveal and Submit buttons.

**Limitations:**
*   **Exclusivity:** Cannot be both `multiline` and `secret`. throws `IllegalArgumentException`.

**Usage:**
```kotlin
AzTextBox(hint = "Pass", secret = true, onSubmit = {})
```

### AzForm

**Description:** Group container for text boxes.

**Abilities:**
*   **Aggregation:** Collects values from all children into a Map.
*   **Traversal:** `Next` key moves focus. `Send` key on last item submits form.
*   **Unified Style:** Applies outline/color settings to all children.

**Usage:**
```kotlin
AzForm(formName = "login", onSubmit = { map -> }) {
    entry("user", "User")
    entry("pass", "Pass", secret = true)
}
```

---

## Advanced Features

### AzLoad (Loading)

**Description:** Global or local loading spinner.

**Abilities:**
*   **Overlay:** `azAdvanced(isLoading = true)` blocks entire UI with spinner.
*   **Standalone:** `AzLoad()` composable for local use.

### System Overlay

**Description:** Run the rail over other apps.

**Abilities:**
*   **Dynamic Resize:** Expands to screen size during drag; shrinks to wrap content when idle.
*   **Auto-Launch:** Clicking an item brings the app to foreground.

**Requirements:**
*   Must extend `AzNavRailOverlayService`.
*   Requires `SYSTEM_ALERT_WINDOW` permission.
