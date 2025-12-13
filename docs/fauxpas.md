# Common Faux Pas & Forbidden Actions

A list of mistakes to avoid ensuring the stability and integrity of the project.

## **1. Critical Technical Errors**

### **Removing OpenCV**
-   **The Error:** Removing the OpenCV dependency or the `Fingerprint` serialization logic.
-   **Why it's bad:** OpenCV is **required** for the AR persistence feature (`Fingerprint`). Without it, projects cannot be saved or loaded correctly.
-   **Correction:** Keep `libs.opencv` in `build.gradle.kts` and maintain the `Fingerprint` class.

### **Breaking the Build**
-   **The Error:** Committing code that does not compile.
-   **Why it's bad:** It blocks the CI pipeline and prevents the user (and other agents) from testing.
-   **Correction:** Always run `./gradlew assembleDebug` before committing.

### **Ignoring `google-services.json`**
-   **The Error:** Deleting `app/google-services.json.template` or failing to understand why the build fails on CI.
-   **Why it's bad:** The CI pipeline relies on this template to inject secrets.
-   **Correction:** Respect the template file.

## **2. Process Violations**

### **Ignoring `AGENTS.md`**
-   **The Error:** Proceeding with a plan that contradicts the instructions in `AGENTS.md`.
-   **Why it's bad:** `AGENTS.md` contains the project's specific "laws of physics".
-   **Correction:** Read `AGENTS.md` first.

### **Destructive Resets**
-   **The Error:** Deleting the entire source tree to "start over".
-   **Why it's bad:** It destroys history and the user's trust.
-   **Correction:** Refactor, don't delete. Fix forward.

### **Editing Build Artifacts**
-   **The Error:** modifying files in `app/build/` directly.
-   **Why it's bad:** These changes are overwritten on the next build.
-   **Correction:** Edit the source files in `app/src/`.
