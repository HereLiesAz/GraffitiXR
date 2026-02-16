# AzNavRail Complete Guide

Welcome to the comprehensive guide for **AzNavRail**. This document details the **High-Inference System**, which is the only supported method for configuring the rail architecture.

**Dictatorial Design:** You do not build the graph. You do not configure the layout. You annotate your intentions, and the Compiler enforces the strict rules of the AzNavRail system.

---

## Table of Contents

1.  [Getting Started](#getting-started)
2.  [The Az Protocol (Architecture)](#the-az-protocol-architecture)
3.  [Strict Layout Rules](#strict-layout-rules)
4.  [Annotation Reference (@Az)](#annotation-reference-az)
5.  [Component Reference](#component-reference)
6.  [Sample Application Source Code](#sample-application-source-code)

---

## Getting Started

### 1. Installation

Add JitPack to your project's `settings.gradle.kts`:

~~~kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
~~~

Add the **KSP Plugin** and dependencies to your app's `build.gradle.kts`.
**Crucial:** The KSP version must match your Kotlin version.

~~~kotlin
plugins {
    id("com.google.devtools.ksp") version "2.0.0-1.0.21" // Verify version matches Kotlin
}

dependencies {
    // The Core Library
    implementation("com.github.HereLiesAz.AzNavRail:aznavrail:VERSION")

    // The Annotation Processor (The Brains)
    implementation("com.github.HereLiesAz.AzNavRail:aznavrail-annotation:VERSION")
    ksp("com.github.HereLiesAz.AzNavRail:aznavrail-processor:VERSION")
}
~~~

### 2. The Constitution (App Setup)

You do not write `onCreate`. You do not write `setContent`. You extend `AzActivity` and point it to the generated graph.

~~~kotlin
// 1. Define Global Rules (Theme, Docking)
@Az(app = App(
    dock = AzDockingSide.LEFT,
    theme = AzTheme.GlitchNoir
))
class MainActivity : AzActivity() {
    // 2. Link the Generated Graph
    override val graph = AzGraph
}
~~~

### 3. The Stations (Screens)

You do not manually add items to a rail. You annotate `@Composable` functions.

~~~kotlin
// Inferred ID: "home", Title: "Home"
@Az(rail = RailItem(home = true))
@Composable
fun Home() {
    Text("Strict Mode Active")
}

// Inferred ID: "profile", Title: "Profile"
@Az(rail = RailItem(icon = R.drawable.ic_user))
@Composable
fun Profile() {
    Text("User Settings")
}
~~~

---

## The Az Protocol (Architecture)

The KSP Processor enforces the following architecture:

1.  **Zero-Talk Inference**: If you do not provide an `id` or `text` in the annotation, it is derived from the function name (e.g., `fun WiFiSettings` -> ID: `wifi_settings`, Text: "WiFi Settings").
2.  **The Tax**: Every function annotated with `@Az` **MUST** also be annotated with `@Composable`. If you forget, the build fails.
3.  **Hierarchy**: To create sub-menus, you define a **Host** (using a property) and link children to it using `parent`.

~~~kotlin
// Define a Host (Expands in Rail)
@Az(railHost = RailHost(icon = R.drawable.ic_settings))
val System = null // Placeholder property

// Link a Child to the Host
@Az(rail = RailItem(parent = "system"))
@Composable
fun Wifi() { ... }
~~~

---

## Strict Layout Rules

The generated `AzGraph` automatically wraps your content in `AzHostActivityLayout`. This enforces:

1.  **Safe Zones**: Your UI content is strictly forbidden from the **Top 20%** and **Bottom 10%** of the screen.
2.  **Rail Avoidance**: Content is automatically padded to avoid the rail.
3.  **Smart Transitions**:
    * **Left Dock**: Screens slide in from Right.
    * **Right Dock**: Screens slide in from Left.

### Backgrounds (Bypassing Safe Zones)
To place content (like Maps) behind the safe zones, use the `background` scope in the layout (requires manual overrides or overlay components).

---

## Annotation Reference (@Az)

The `@Az` annotation is the single point of entry. It contains contexts for different component types.

### `app = App(...)`
Used on `MainActivity`.
* `dock`: `AzDockingSide.LEFT` or `RIGHT`.
* `theme`: `AzTheme` preset (e.g., `GlitchNoir`).
* `secure`: `Boolean`. If true, enforces strict safe zone clipping.

### `rail = RailItem(...)`
Defines a standard navigation screen.
* `id`: Unique String ID. (Default: Function Name).
* `text`: Display label. (Default: Function Name).
* `icon`: Drawable Resource ID.
* `parent`: ID of the Host item (if this is a sub-item).
* `home`: `Boolean`. Set to `true` for the start destination.

### `railHost = RailHost(...)`
Defines a parent item that expands to show children.
* `id`: Unique String ID.
* `text`: Display label.
* `icon`: Drawable Resource ID.

### `nested = NestedRail(...)`
Defines a generic "Nested Rail" popup menu.
* `parent`: ID of the item that opens this popup.
* `align`: `AzNestedRailAlignment.VERTICAL` or `HORIZONTAL`.

### `toggle = Toggle(...)`
Defines a state toggle button (swaps text ON/OFF).
* `onText`: Text to display when True.
* `offText`: Text to display when False.
* `slot`: `AzSlot.RAIL` or `AzSlot.MENU`.

---

## Component Reference

While the navigation structure is generated, you use these components within your screens.

### `AzTextBox`
A versatile text input with autocomplete, multiline, and "Secret" modes.

~~~kotlin
AzTextBox(
    hint = "Password",
    secret = true, // Masks input
    onSubmit = { text -> ... }
)
~~~

### `AzRoller`
A slot-machine style dropdown with split-click interaction (Left: Type, Right: Scroll).

~~~kotlin
AzRoller(
    options = listOf("A", "B", "C"),
    selectedOption = "A",
    onOptionSelected = { ... }
)
~~~

### `AzButton`
A circular, text-only button that adheres to the Glitch-Noir aesthetic.

---

## Sample Application Source Code

This is the **High-Inference** reference implementation.

### `MainActivity.kt`

~~~kotlin
package com.example.sampleapp

import com.hereliesaz.aznavrail.annotation.*
import com.hereliesaz.aznavrail.AzActivity

// 1. Configure the App
@Az(app = App(dock = AzDockingSide.RIGHT, theme = AzTheme.GlitchNoir))
class MainActivity : AzActivity() {
    // 2. Ignite the Engine
    override val graph = AzGraph
}
~~~

### `Screens.kt`

~~~kotlin
package com.example.sampleapp

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.hereliesaz.aznavrail.annotation.*

// 1. Home Screen
@Az(rail = RailItem(home = true))
@Composable
fun Home() {
    Text("Welcome to the Dictatorship.")
}

// 2. Defining a Host (System Settings)
@Az(railHost = RailHost)
val System = null

// 3. Sub-Items (Linked to System)
@Az(rail = RailItem(parent = "system"))
@Composable
fun Wifi() {
    Text("Searching for networks...")
}

@Az(rail = RailItem(parent = "system"))
@Composable
fun Bluetooth() {
    Text("Pairing devices...")
}

// 4. A Menu Toggle (Dark Mode)
@Az(toggle = Toggle(
    id = "dark_mode",
    onText = "LIGHTS OFF",
    offText = "LIGHTS ON",
    slot = AzSlot.MENU
))
@Composable
fun DarkModeHandler() {
    // Logic to handle theme switching
}
~~~
