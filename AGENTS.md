# AGENT INSTRUCTIONS for GraffitiXR

**CRITICAL PROTOCOL: You MUST achieve a PERFECT code review and a passing build BEFORE committing. The Native C++ layer is fragile; treat it with extreme caution.**

This document defines the technical reality of the GraffitiXR project.

---

## **1. Architecture Overview**

GraffitiXR is a **Local-Only Hybrid Application**.
It relies on a custom Shared Library (`graffiti-lib`) for high-performance mapping.

**HARD RULE:** No Cloud APIs. No Firebase Database. No Google Cloud Anchors. All data is local.

### **The Stack**
1.  **UI Layer (Kotlin/Compose):**
    * **`MainActivity.kt`**: Single Activity.
    * **`MainScreen.kt`**: Uses **`AzNavRail`** for all navigation.
    * **`MainViewModel.kt`**: Manages `UiState`.
2.  **Logic Layer (Kotlin):**
    * **`SlamManager.kt`**: Bridges ARCore frames to the Native Engine.
    * **`ProjectManager.kt`**: Handles zipping/unzipping `.gxr` project containers.
3.  **Native Core (C++17):**
    * **`MobileGS.cpp`**: The **Confidence Engine**. Handles point cloud accumulation and rendering.

---

## **2. Critical Systems**

### **A. MobileGS (Confidence Mapping)**
* **Location:** `app/src/main/cpp/MobileGS.cpp`
* **The Logic:**
    * **Input:** Depth Map + Pose.
    * **Process:** Unproject points -> Hash to Voxel ID.
    * **Duplication:** If Voxel ID exists, **INCREMENT CONFIDENCE**. Do not overwrite.
    * **Render:** Only draw splats where `confidence > CONFIDENCE_THRESHOLD`.
* **Purpose:** This mechanism naturally filters out moving objects (which don't accumulate confidence) and reinforces the static wall structure.

### **B. Persistence (The `.gxr` File)**
* **Format:** ZIP Archive.
* **Contents:**
    1.  `model.map`: Binary dump of High-Confidence Splats.
    2.  `target.fingerprint`: Serialized OpenCV Keypoints/Descriptors (ORB).
    3.  `meta.json`: Image edits, GPS, Compass heading.
* **Re-Localization:**
    * When loading a project, `FingerprintManager` scans the camera feed for the saved descriptors.
    * Once a match is found (Homography found), the `model.map` is aligned to the world frame.

### **C. AzNavRail Integration**
* **Mandate:** The UI is strictly governed by `AzNavRail`.
* **Prohibited:** No standard Android navigation bars.
* **Pattern:** Use `RailItem` for primary modes (Scan, Trace, Mockup).

---

## **3. Development Rules**

1.  **Privacy First:** Do not add libraries that transmit user data.
2.  **Native Safety:** Thread safety in `MobileGS` is manual (`std::mutex`). The Sorter thread runs asynchronous to the Render thread.
3.  **No Snippets:** Provide **FULL FILES** only.

## **4. Known Issues**
* **Confidence Drift:** Rapid movement can cause "double walls" if the confidence threshold is too low.
* **Memory Pressure:** The Voxel Map can grow large. `MobileGS` must implement a culling routine to prune low-confidence points when RAM is tight.