# Data Layer Documentation

This document describes how GraffitiXR handles data persistence, state management, and serialization.

## **1. Core Data Models**

### **`UiState` (Immutable)**
-   **Location:** `app/src/main/java/com/hereliesaz/graffitixr/UiState.kt`
-   **Purpose:** The single source of truth for the application's UI. It holds everything from current slider values (`opacity`, `contrast`) to complex objects like `capturedTargetImages` and `refinementPaths`.
-   **Usage:** Managed by `MainViewModel` via a `StateFlow`. UI components observe this stream.

### **`ProjectData` (Serializable)**
-   **Location:** `app/src/main/java/com/hereliesaz/graffitixr/data/ProjectData.kt`
-   **Purpose:** The DTO (Data Transfer Object) used for saving and loading projects. It mirrors the persistent parts of `UiState`.
-   **Fields:**
    -   `targetImageUris`: List of URIs for the AR target images.
    -   `fingerprint`: The OpenCV feature data (see below).
    -   `overlayImageUri`: URI for the user's art.
    -   `refinementPaths`: Vector paths for the target mask.
    -   `gpsData`, `sensorData`: Contextual metadata.

### **`Fingerprint`**
-   **Location:** `app/src/main/java/com/hereliesaz/graffitixr/data/Fingerprint.kt`
-   **Purpose:** Stores the OpenCV ORB features required to identify an AR target.
-   **Structure:**
    -   `keyPoints`: List of `org.opencv.core.KeyPoint`.
    -   `descriptors`: `org.opencv.core.Mat` (serialized as rows/cols/type/data).

## **2. Serialization Strategy**

The app uses `kotlinx.serialization` with custom serializers for complex/native types.

### **Custom Serializers (`Serializers.kt`)**
-   **`KeyPointSerializer`:** Converts OpenCV `KeyPoint` <-> JSON object (`x`, `y`, `size`, `angle`, etc.).
-   **`MatSerializer`:** Converts OpenCV `Mat` <-> JSON object. Since `Mat` holds native memory, the data is extracted to a Base64 string or byte array for storage and reconstructed upon loading.
-   **`BlendModeSerializer`:** specific serializer for Android `BlendMode`.

## **3. Project Management**

### **`ProjectManager`**
-   **Location:** `app/src/main/java/com/hereliesaz/graffitixr/utils/ProjectManager.kt`
-   **Function:** Handles the I/O operations for project files (`.json` or `.zip`).
-   **Auto-Save:** The `MainViewModel` triggers an auto-save every 30 seconds to the "autosave" project slot.

### **File Storage**
-   **Cache:** Temporary images (camera captures, processed results) are stored in `context.cacheDir`.
-   **Persistence:** Saved projects are stored in the app's internal storage directory.
-   **Export:** Users can export projects to a `.zip` file containing the JSON data and all referenced image assets.
