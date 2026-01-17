# Data Layer Documentation

This document describes how GraffitiXR handles data persistence, state management, and serialization.

## **1. Core Data Models**

### **`UiState` (Immutable)**
-   **Location:** `app/src/main/java/com/hereliesaz/graffitixr/UiState.kt`
-   **Purpose:** The single source of truth for the application's UI. It holds:
    -   Global state (`editorMode`, `isCapturingTarget`).
    -   **Layers:** A list of `OverlayLayer` objects managed by the user.
    -   **AR State:** Tracking status, anchors, and mapping quality.
    -   **Editor State:** Paths (`List<List<Offset>>`) and Refinement logic.
-   **Usage:** Managed by `MainViewModel` via a `StateFlow`. UI components observe this stream.

### **`OverlayLayer` (Serializable)**
-   **Location:** `app/src/main/java/com/hereliesaz/graffitixr/data/OverlayLayer.kt`
-   **Purpose:** Represents a single visual layer (image + properties).
-   **Fields:**
    -   `uri`: Image source.
    -   `transforms`: Scale, Rotation (X,Y,Z), Offset.
    -   `adjustments`: Opacity, Brightness, Contrast, Saturation, Color Balance.
    -   `blendMode`: Composition mode (Multiply, Overlay, etc.).

### **`ProjectData` (Serializable)**
-   **Location:** `app/src/main/java/com/hereliesaz/graffitixr/data/ProjectData.kt`
-   **Purpose:** The DTO (Data Transfer Object) used for saving and loading projects. It mirrors the persistent parts of `UiState`.
-   **Fields:**
    -   `layers`: List of `OverlayLayer`.
    -   `targetImageUris`: List of URIs for the AR target images.
    -   `fingerprint`: The OpenCV feature data.
    -   `refinementPaths`: Vector paths for the target mask.
    -   `gpsData`, `sensorData`: Contextual metadata.

### **`Fingerprint`**
-   **Location:** `app/src/main/java/com/hereliesaz/graffitixr/data/Fingerprint.kt`
-   **Purpose:** Stores the OpenCV ORB features required to identify an AR target.
-   **Structure:**
    -   `keypoints`: List of `org.opencv.core.KeyPoint` (serialized via `KeyPointSerializer`).
    -   `descriptorsData`: ByteArray containing raw descriptor data.
    -   `descriptorsRows`, `descriptorsCols`, `descriptorsType`: Metadata to reconstruct the `Mat`.

## **2. Serialization Strategy**

The app uses `kotlinx.serialization` with custom serializers for complex/native types.

### **Custom Serializers (`Serializers.kt`)**
-   **`KeyPointSerializer`:** Converts OpenCV `KeyPoint` <-> JSON object (`x`, `y`, `size`, `angle`, etc.).
-   **`UriSerializer`:** Persists `Uri` as string path.
-   **`OffsetSerializer`:** Converts Compose `Offset` <-> JSON object.
-   **`BlendModeSerializer`:** Converts Compose `BlendMode` <-> String name.

## **3. Project Management**

### **`ProjectManager`**
-   **Location:** `app/src/main/java/com/hereliesaz/graffitixr/utils/ProjectManager.kt`
-   **Function:** Handles the I/O operations for project files (`.json` or `.zip`).
-   **Logic:**
    -   Converts `UiState` -> `ProjectData` (handling type mapping like `List<Offset>` -> `List<Pair>`).
    -   Saves images to local project directory.
    -   Serializes JSON manifest.

### **File Storage**
-   **Cache:** Temporary images (camera captures, processed results) are stored in `context.cacheDir`.
-   **Persistence:** Saved projects are stored in `context.filesDir/projects/{projectId}/`.
-   **Export:** Users can export projects to a `.zip` file containing the JSON data and all referenced image assets.

# Data Layer & Persistence

## The `.gxr` File Format
GraffitiXR projects are saved as compressed ZIP archives with the extension `.gxr`.

### Structure
```text
project.gxr
├── meta.json          // Metadata (GPS, Heading, Image Transforms)
├── model.map          // Binary Gaussian Splat data
├── target.fingerprint // OpenCV ORB descriptors (for re-localization)
└── reference.png      // The artist's source image

model.map Specification
A binary dump of the std::vector<SplatGaussian>.
{
"version": 1,
"created_at": 1715420000,
"gps": {
"lat": 29.9511,
"lng": -90.0715
},
"overlay": {
"scale": 1.5,
"opacity": 0.8,
"rotation": 45.0
}
}

Byte Offset,Type,Description
0,char[4],"Magic Header ""GXR1"""
4,int32,Splat Count (N)
8,struct,"Splat[0] (Pos {x,y,z}, Color {r,g,b,a})"
...,...,Splat[N]