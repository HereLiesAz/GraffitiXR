# SYSTEM RELATIONSHIPS & USER FLOW

## 1. THE UNIVERSAL 2D PLANE (The Content Core)
**Definition:** The `UniversalPlane` is a Global Singleton State. It serves as the immutable "Truth" of the user's artwork.
**Persistence Rule:** Changes here are atomic and instant across ALL modes.

### A. Structure & Data Relationships
* **The Stack:** `List<Layer>`. Order is Z-index (0 is bottom).
* **The Layer:**
    * **`Bitmap source`**: The raw pixel data.
    * **`Matrix transform`**: Stores `Translation(x,y)`, `Scale(sx,sy)`, `Rotation(degrees)`.
        * *Constraint:* Transforms are always stored relative to the layer's center.
    * **`ColorAdjustment adjustments`**: `HSBC` values (0.0-1.0).
    * **`BlendMode blend`**: `PorterDuff.Mode` (e.g., SCREEN, MULTIPLY).
    * **`EffectState effects`**: Booleans for `isIsolated` (MLKit) and `isOutlined` (OpenCV).

### B. Implementation Logic
* **In AR Mode:** The `UniversalPlane` is rendered onto a 3D Quad.
    * *Relation:* `Quad.matrix = AnchorPose * Plane.transform`.
* **In Overlay Mode:** The `UniversalPlane` is rendered to a 2D Canvas.
    * *Relation:* `Canvas.matrix = ScreenSpace * Plane.transform`.
* **In Mockup Mode:** The `UniversalPlane` is rendered on top of the `MockupBackground`.

---

## 2. AR WORLD PERSISTENCE (The Reality Anchor)
**Definition:** The system that locks the `UniversalPlane` to physical reality.
**Components:** `MobileGS` (Engine), `SlamMap` (Spatial Data), `Fingerprint` (Relocalization Key).

### A. The Dependency Chain
1.  **The Engine (`MobileGS`):**
    * **Input:** Camera Frame (YUV) + IMU Data.
    * **Process:** Generates a sparse Point Cloud + Camera Pose.
    * **Output:** `ConfidenceMap` (Voxel Grid).
2.  **The Map (`SlamMap`):**
    * **Data:** Binary serialization of the `ConfidenceMap`.
    * **Relation:** This is the "Save File" of the physical wall.
3.  **The Key (`Fingerprint`):**
    * **Data:** A set of ORB Feature Descriptors extracted from the specific target image used to start the session.
    * **Function:** When the app restarts, `MobileGS` scans for these ORB features.
    * **Logic:** `IF (CurrentFeatures match Fingerprint) -> Trigger Relocalization -> Restore AnchorPose`.

### B. Implementation Directive
* **Saving:** When `Project.save()` is called, you MUST serialize both the `SlamMap` (via `MobileGS.saveBytes()`) and the `Fingerprint` (via `OpenCV.serialize()`).
* **Loading:** On `ArView` entry:
    1.  Load `Fingerprint` into memory.
    2.  Feed `Fingerprint` to `MobileGS`.
    3.  `MobileGS` enters `RELOCALIZATION_MODE`.
    4.  Upon match, `MobileGS` aligns the coordinate system and switches to `TRACKING_MODE`.

---

## 3. TARGET CREATION (The Grid Ritual)
**Definition:** The workflow to establish the initial `Anchor` (Coordinate 0,0,0).
**Context:** `TargetCreationOverlay.kt`.

### A. The Workflow Logic
1.  **Capture Phase:**
    * **Input:** Camera X stream.
    * **User Action:** Tap "Shutter".
    * **Data:** Captures `Bitmap tempTarget`.
    * **Relation:** `ArView` is PAUSED (Camera logic handed to `TargetCreationOverlay`).
2.  **Rectification Phase (Unwarp):**
    * **Context:** `UnwarpScreen`.
    * **User Action:** Drag 4 corners to define the plane.
    * **Logic:** `OpenCV.getPerspectiveTransform(srcPoints, dstPoints)`.
    * **Output:** `Bitmap flatTarget` (The rectified, flat texture of the wall).
3.  **Feature Extraction Phase:**
    * **Process:** Pass `flatTarget` to `OrbFeatureDetector`.
    * **Validation:** `IF (FeatureCount < 50) -> Reject "Too low texture"`.
    * **Result:** `Fingerprint` created.
4.  **Injection Phase:**
    * **Action:** `MobileGS.setAnchor(Fingerprint)`.
    * **Result:** The engine now treats this image's position as (0,0,0) in World Space.

---

## 4. THE AZNAVRAIL (The Nervous System)
**Definition:** The master controller. It manages state transitions and informs the user of their context.
**Visual Rule:** All Viewports (Camera feeds, Mockup Canvas) are logically treated as **BACKGROUNDS**. The Rail sits *above* them.

### A. Rail Item Architecture
Every icon on the rail corresponds to a specific `RailRelocItem` enum state.

| Rail Group | Item ID | Action / Logic | Implementation Link |
| :--- | :--- | :--- | :--- |
| **MODES** | `AR` | **Switch Viewport:** `ArView`.<br>**State:** `activeMode = AR`.<br>**Bg:** Live Camera + SLAM. | `MainScreen.kt` -> `NavHost` |
| | `OVERLAY` | **Switch Viewport:** `OverlayScreen`.<br>**State:** `activeMode = OVERLAY`.<br>**Bg:** Live Camera (No SLAM). | `MainScreen.kt` |
| | `MOCKUP` | **Switch Viewport:** `MockupScreen`.<br>**State:** `activeMode = MOCKUP`.<br>**Bg:** `MockupBackground` Bitmap. | `MainScreen.kt` |
| | `TRACE` | **Switch Viewport:** `TraceScreen`.<br>**State:** `activeMode = TRACE`.<br>**Bg:** White Lightbox. | `MainScreen.kt` |
| **GRID** | `CREATE` | **Trigger:** `TargetCreationOverlay`.<br>**Logic:** See Section 3. | `MainViewModel.onTargetCreate()` |
| | `SURVEY` | **Trigger:** `MappingScreen`.<br>**Logic:** Enable `MobileGS` scanning visualizer. | `MainViewModel.onSurveyor()` |
| | `REFINE` | **Tool:** Toggle Mask Brush.<br>**Context:** `TargetCreation` only.<br>**Logic:** `isMasking = !isMasking`. | `TargetCreationOverlay.kt` |
| **DESIGN** | `ADD` | **Intent:** `ActivityResult(PickVisualMedia)`.<br>**Logic:** Add Result to `UniversalPlane`. | `MainViewModel.addLayer()` |
| | `LAYERS` | **UI:** Show Reorderable List.<br>**Relation:** Direct reflection of `UniversalPlane.layers`. | `EditorUi.kt` |
| | `WALL` | **Trigger:** `ActivityResult(PickVisualMedia)`.<br>**Context:** Mockup Mode ONLY.<br>**Logic:** Sets `MockupBackground`. | `MainViewModel.setMockupWall()` |
| | `ISOLATE` | **Process:** `MLKit.Segmenter`.<br>**Target:** Active Layer.<br>**Result:** Apply Alpha Mask. | `ImageUtils.removeBackground()` |
| | `OUTLINE` | **Process:** `OpenCV.Canny`.<br>**Target:** Active Layer.<br>**Result:** Edge-detected Bitmap. | `ImageUtils.generateOutline()` |
| **PROJECT**| `SAVE` | **Process:** Serialize `UniversalPlane` + `SlamMap` -> Zip.<br>**IO:** Blocking Write (Coroutine IO). | `ProjectManager.save()` |
| | `LOAD` | **Process:** Unzip -> Hydrate `UniversalPlane` -> Load `SlamMap` to Engine. | `ProjectManager.load()` |

---

## 5. MOCKUP MODE EXCEPTION (Detailed)
**Context:** The one deviation from the Universal Plane.

### A. The Logic
* **The Problem:** Mockup mode needs a static reference image (a photo of a train, a wall, etc.) that acts as the "canvas" but isn't part of the artwork itself.
* **The Solution:** The `MockupBackground`.
* **Relation:**
    * `UniversalPlane` sits at `Z-Index: 1`.
    * `MockupBackground` sits at `Z-Index: 0`.
* **Interaction Rule:**
    * When `Rail.WALL` is active: Gestures affect `MockupBackground` (Scale/Pan the train photo).
    * When `Rail.WALL` is INACTIVE: Gestures affect `UniversalPlane` (Scale/Pan the graffiti).

---

## 6. IMPLEMENTATION DIRECTIVES (How-To)

### Identifying Camera Displays as Backgrounds
To ensure the `AzNavRail` and UI overlays render correctly over the camera feeds, you must use a `Box` layout with specific z-ordering in `MainScreen.kt`.

```kotlin
// LOGIC PATTERN FOR MainScreen.kt
Box(modifier = Modifier.fillMaxSize()) {
    // 1. THE BACKGROUND LAYER (Viewports)
    // This MUST be the first child of the Box.
    when (viewState.activeMode) {
        AppMode.AR -> ArView(renderer = ...) // Camera Feed
        AppMode.OVERLAY -> OverlayScreen(camera = ...) // Camera Feed
        AppMode.MOCKUP -> MockupScreen(background = ...) // Static Image
        AppMode.TRACE -> TraceScreen() // White Background
    }

    // 2. THE INTERACTION LAYER (Universal 2D Plane handling)
    // This handles gestures for the artwork.
    if (viewState.activeMode != AppMode.TRACE) {
        GestureHandler(
            target = UniversalPlane,
            onTransform = { matrix -> MainViewModel.updatePlane(matrix) }
        )
    }

    // 3. THE UI LAYER (AzNavRail)
    // This sits ON TOP.
    Row(modifier = Modifier.fillMaxSize()) {
        AzNavRail(
            items = viewState.railItems,
            onItemClick = { item -> MainViewModel.handleRailAction(item) }
        )
        
        // Editor Panels (appear next to rail)
        if (viewState.isEditorOpen) {
            EditorPanel(state = viewState.editorState)
        }
    }
}
```

Linking Rail Items to Logic
In MainViewModel.kt, you must implement a strict mapping:

```kotlin
// LOGIC PATTERN FOR MainViewModel.kt
fun handleRailAction(item: RailRelocItem) {
    when (item) {
        RailRelocItem.AR -> {
            // 1. Persist current Plane state
            // 2. Initialize MobileGS
            _uiState.update { it.copy(activeMode = AppMode.AR) }
        }
        RailRelocItem.ISOLATE -> {
            // 1. Get Active Layer
            val layer = _uiState.value.universalPlane.activeLayer
            // 2. Launch Coroutine
            viewModelScope.launch(Dispatchers.Default) {
                val isolated = ImageUtils.removeBackground(layer.bitmap)
                // 3. Update Plane (Thread Safe)
                updateLayerBitmap(layer.id, isolated)
            }
        }
        // ... handle all cases
    }
}
```