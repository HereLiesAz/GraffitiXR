# Miscellaneous Documentation

## **1. Versioning Strategy**
The project uses a dynamic versioning system defined in `build.gradle.kts`.
-   **Format:** `Major.Minor.Patch.Build` (e.g., `1.11.0.756`)
-   **Calculation:**
    -   `Major` & `Minor`: Read from `version.properties`.
    -   `Patch`: Calculated as the number of commits since the last modification of `version.properties`.
    -   `Build`: Total number of commits in the repo.

## **2. Crash Handling**
-   **Global Handler:** `CrashHandler.kt` catches uncaught exceptions.
-   **Process:**
    1.  Catches the exception.
    2.  Launches `CrashActivity` in a separate process (`:crash`).
    3.  Displays the stack trace.
    4.  Provides a button to report the issue to GitHub, pre-filling the body and tagging the AI agent (`@gemini-cli /jules`).

## **3. Setup Scripts**
-   **`setup_ndk.sh`**: A helper script to ensure the Android NDK is correctly configured in the environment. It checks for `ANDROID_NDK_HOME` or attempts to locate it within the SDK.

## **4. Gradle Configuration**
-   **Kotlin DSL:** The build scripts use `.kts`.
-   **Configuration Cache:** The build is optimized for configuration caching. External process calls (like `git` for versioning) are wrapped in `ValueSource` to be cache-friendly.
