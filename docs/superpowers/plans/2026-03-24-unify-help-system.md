# Help System Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify the help system by moving the manual "GraffitiXR Guide" into the AzNavRail built-in help overlay using the new `helpList` feature and converting the help trigger into a sub-item.

**Architecture:** 
- Leverage `azAdvanced` with the `helpList` parameter to provide contextual help cards.
- Integrate the help trigger into the "Project" accordion as an `azHelpSubItem`.
- Use `azTheme` to apply the new `translucentBackground` for better readability.

**Tech Stack:** Kotlin, Jetpack Compose, AzNavRail (v7.86).

---

### Task 1: Update Theme and Advanced Configuration

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt`

- [ ] **Step 1: Update `azTheme` with `translucentBackground`**

In `MainActivity.kt` (~line 300), add the `translucentBackground` property to the `azTheme` block.

```kotlin
                    azTheme(
                        activeColor = Cyan,
                        defaultShape = AzButtonShape.RECTANGLE,
                        headerIconShape = AzHeaderIconShape.ROUNDED,
                        translucentBackground = Color.Black.copy(alpha = 0.5f)
                    )
```

- [ ] **Step 2: Update `azAdvanced` with `helpList`**

In `MainActivity.kt` (~line 309), update the `azAdvanced` block to include the help text from the old guide dialog.

```kotlin
                    azAdvanced(
                        helpEnabled = showHelp,
                        helpList = mapOf(
                            "help_sub" to "Select a tool from the Design menu to edit your layers. To transform (scale, rotate, move) a layer, close the layer's tools. Double tap the screen to cycle between X, Y, and Z rotation axes."
                        ),
                        onDismissHelp = { showHelp = false }
                    )
```

---

### Task 2: Refactor Rail Items and Help Trigger

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt`

- [ ] **Step 1: Convert `azHelpRailItem` to `azHelpSubItem`**

In `ConfigureRailItems` (~line 815), remove the standalone `azHelpRailItem` and insert it as an `azHelpSubItem` inside the `project_host` block. Add an `onClick` to trigger the help mode.

```kotlin
        // Inside project_host block (~line 800-815)
        azRailSubItem(id = "export", hostId = "project_host", text = navStrings.export, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.exportInfo) {
            editorViewModel.exportImage()
        }
        
        // ADD THIS:
        azHelpSubItem(id = "help_sub", hostId = "project_host", text = navStrings.help, color = navItemColor, shape = AzButtonShape.NONE) {
            showHelp = true
        }

        azRailSubItem(id = "settings", hostId = "project_host", text = navStrings.settings, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.settingsInfo) {
            showSettings = true
        }
```

- [ ] **Step 2: Remove the old `azHelpRailItem` call**

Ensure the old `azHelpRailItem` call (formerly at line 815) is deleted.

---

### Task 3: Cleanup Manual Help Dialog

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt`

- [ ] **Step 1: Remove `showHelpDialog` state**

In `MainActivity.kt` (~line 122), delete the unused state variable.

```kotlin
    // REMOVE THIS:
    // var showHelpDialog by mutableStateOf(false)
```

- [ ] **Step 2: Remove `InfoDialog` from UI**

In `MainActivity.kt` (~line 640), delete the conditional block that renders the `InfoDialog`.

```kotlin
    // REMOVE THIS:
    /*
    if (showHelpDialog) {
        InfoDialog(
            title = "GraffitiXR Guide",
            content = "...",
            onDismiss = { showHelpDialog = false }
        )
    }
    */
```

- [ ] **Step 3: Commit changes**

```bash
git add app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt
git commit -m "feat(nav): unify help system using AzNavRail helpList and sub-items"
```

---

### Task 4: Verification

- [ ] **Step 1: Verify Build**

Run: `./gradlew assembleDebug`
Expected: Build SUCCESS.

- [ ] **Step 2: Verify Help Functionality**

1. Open the "Project" accordion in the rail.
2. Tap "Help".
3. Expected: The help overlay appears with the translucent background.
4. Tap the "Help" item again (while in help mode).
5. Expected: A help card appears showing the "GraffitiXR Guide" text.
