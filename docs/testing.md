# Testing Strategy & Guidelines

## **1. Automated Testing**

### **Unit Tests (`app/src/test/`)**
-   **Focus:** Logic classes like `MainViewModel`, `ProgressCalculator`, `Utils`.
-   **Framework:** JUnit 4, Mockito, Kotlin Coroutines Test.
-   **Running:** `./gradlew testDebugUnitTest`
-   **Key Test Class:** `MainViewModelTest` covers state transitions, calculations, and flow logic.

### **UI Tests (`app/src/androidTest/`)**
-   **Focus:** Compose UI interactions.
-   **Status:** Currently limited. Focus is on Unit Tests.

## **2. Manual Verification**

### **AR Mode**
-   **Requirement:** A physical Android device supporting ARCore.
-   **Emulator:** The standard Android Emulator supports a "Virtual Scene" for ARCore, which is sufficient for basic logic checks but not for performance or camera quality tuning.

### **Build Verification**
-   **Command:** `./gradlew assembleDebug`
-   **Mandatory:** Must pass before every commit.

### **Linting**
-   **Command:** `./gradlew lintDebug`
-   **Policy:** Treat warnings as errors where possible. Fix deprecations immediately.

## **3. Test-Driven Development (TDD)**
-   When fixing a bug (e.g., calculation error), write a failing test case in `MainViewModelTest` first.
-   Fix the bug.
-   Verify the test passes.
