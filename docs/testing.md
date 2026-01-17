# Testing Strategy

## 1. Unit Tests (Kotlin)
* **Location:** `app/src/test/`
* **Scope:** `MainViewModel` logic, Data Serialization (`ProjectManager`), and Utility functions.
* **Run:** `./gradlew testDebugUnitTest`

## 2. Native Tests (C++)
* **Status:** Hard. We do not currently have a C++ unit test runner (GTest) integrated.
* **Method:** "Visual Verification."
    * Enable `DEBUG_COLORS` in `MobileGS.h`.
    * Scan a corner. If the corner looks like a rainbow, the normals are wrong.

## 3. UI Tests (Compose)
* **Location:** `app/src/androidTest/`
* **Scope:** Verifying `AzNavRail` interactions.
* **Note:** ARCore cannot be easily mocked in UI tests. Mock the `ArRenderer` when writing Compose tests.

## 4. Field Testing (The "Wall Test")
Before a release, you must:
1.  Build Release APK.
2.  Go to a physical brick wall.
3.  Scan it.
4.  Project an image.
5.  Walk 5 meters away and come back.
6.  **Pass Condition:** The image is still on the wall within < 1cm of drift.