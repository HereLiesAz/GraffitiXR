# Development Workflow

## **1. Branching Strategy**
-   **Main Branch:** `main`.
-   **Feature Branches:** Agents typically work directly on the current branch provided by the environment, effectively a feature branch.
-   **Commits:** Small, atomic, and descriptive.
    -   *Bad:* "Fix stuff"
    -   *Good:* "Fix rotation calculation in ArRenderer"

## **2. CI/CD Pipeline (`.github/workflows/`)**

### **`android-ci-jules.yml`**
-   **Triggers:** Push to `main`, Pull Request.
-   **Steps:**
    1.  Checkout code.
    2.  Set up JDK.
    3.  **Inject Secrets:** Uses `google-services-injection.yml` to create `google-services.json`.
    4.  **Build:** `./gradlew assembleDebug`.
    5.  **Test:** `./gradlew testDebugUnitTest`.
    6.  **Lint:** `./gradlew lintDebug`.
    7.  **Artifacts:** Uploads the APK.

### **`auto-release.yml`**
-   **Triggers:** Successful completion of the CI workflow.
-   **Action:** Creates/Updates a GitHub Release tagged `latest-debug` with the new APK.

## **3. Release Process**
-   **Versioning:** Update `version.properties` (Major/Minor).
-   **Build Number:** Automatically increments based on git commit count.
-   **Distribution:** Automated via GitHub Releases.

## **4. Local Environment Setup**
-   **SDK:** Ensure `local.properties` points to your Android SDK.
-   **Keys:** You need `app/google-services.json`. Use the template `app/google-services.json.template` and fill it with dummy data for local compilation if real keys are not available.
