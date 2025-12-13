# Authentication & Authorization

## **1. User Authentication**
GraffitiXR is currently a client-side utility application.
-   **No Login:** There is no user account system, login screen, or cloud synchronization of user data.
-   **Local Storage:** All project data is stored locally on the device using `ProjectManager`.

## **2. External Services**

### **GitHub API (Update Check)**
The application connects to the GitHub API to check for software updates.
-   **Endpoint:** `https://api.github.com/repos/HereLiesAZ/GraffitiXR/releases`
-   **Auth:** Anonymous access (public repository). No API key is currently used for this read-only operation.
-   **Data:** Retrieves release tags and asset URLs to identify new versions.

### **Google Services (Build Time)**
-   **File:** `app/google-services.json`
-   **Purpose:** Required for Firebase/Google plugin initialization, even if explicit features aren't used.
-   **Security:** The sensitive keys are injected during the CI/CD build process via the `google-services-injection.yml` workflow to prevent committing secrets to source control.

## **3. Permissions**

The application requests the following Android permissions:
-   **`android.permission.CAMERA`**: Required for ARCore and CameraX features.
-   **`android.permission.INTERNET`**: Required for checking updates.
-   **`android.permission.ACCESS_FINE_LOCATION`**: Optional, used to tag saved projects with GPS data.
