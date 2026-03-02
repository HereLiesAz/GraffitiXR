# Production Readiness Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix the AR camera rendering (currently displays a single averaged color), harden the session lifecycle against crashes on resume and mode-switch, get the test suite green, add LRU pruning to prevent native engine overflow on long scans, and wire the self-update flow to GitHub Releases.

**Architecture:** Five sequential phases, each independently verifiable. Phases 1–3 are Kotlin-only. Phase 4 is C++ only. Phase 5 adds network plumbing.

**Tech Stack:** Kotlin, ARCore, OpenGL ES 3.0, C++17 (MobileGS), JUnit 4 + MockK, DownloadManager, GitHub Releases REST API.

**GitHub repo:** `HereLiesAz/MuralOverlay`

---

## Context You Need

- `ArRenderer` lives at `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt`
- `BackgroundRenderer` lives at `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/BackgroundRenderer.kt`
- `DisplayRotationHelper` lives at `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/DisplayRotationHelper.kt`
- `ArViewport` + `MainScreen` live at `app/src/main/java/com/hereliesaz/graffitixr/MainScreen.kt`
- `ArViewModel` lives at `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt`
- `MobileGS.cpp` lives at `core/nativebridge/src/main/cpp/MobileGS.cpp`
- `DashboardViewModel` lives at `feature/dashboard/src/main/java/com/hereliesaz/graffitixr/feature/dashboard/DashboardViewModel.kt`
- Manifest: `app/src/main/AndroidManifest.xml`
- Network security config: `app/src/main/res/xml/network_security_config.xml`
- App build config: `app/build.gradle.kts`

Run tests with: `./gradlew testDebugUnitTest`
Run per-module: `./gradlew :feature:ar:testDebugUnitTest`
Build debug APK: `./gradlew assembleDebug`

---

## Phase 1: Fix AR Camera Display (Single-Color Bug)

**Root cause:** `ArRenderer.onSurfaceChanged()` only calls `glViewport`. It never calls
`session.setDisplayGeometry(rotation, width, height)`. Without this, ARCore's
`frame.transformCoordinates2d()` returns garbage UV coordinates and all 4 quad vertices
sample the camera texture at essentially one point → averaged single color.

`DisplayRotationHelper` already exists and already calls `session.setDisplayGeometry()` —
it just isn't wired into `ArRenderer`.

---

### Task 1.1: Wire DisplayRotationHelper into ArRenderer

**Files:**
- Modify: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt`

**Step 1: Read the current ArRenderer**

Open `ArRenderer.kt` and confirm the `onSurfaceChanged` method only calls `glViewport`.

**Step 2: Add DisplayRotationHelper field and wire it**

Replace `ArRenderer.kt` with this updated version:

```kotlin
class ArRenderer(
    private val context: Context,
    private val slamManager: SlamManager,
    private val onTrackingUpdated: (String, Int) -> Unit
) : GLSurfaceView.Renderer {

    var session: Session? = null
        private set
    private val backgroundRenderer = BackgroundRenderer()
    private val displayRotationHelper = DisplayRotationHelper(context)  // ADD THIS

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)

    fun attachSession(session: Session?) {
        this.session = session
        if (session != null) {
            displayRotationHelper.onResume()  // ADD THIS
        } else {
            displayRotationHelper.onPause()   // ADD THIS
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        backgroundRenderer.createOnGlThread(context)
        slamManager.ensureInitialized()
        // Move setCameraTextureName here — only needs to be called once after texture is created
        session?.setCameraTextureName(backgroundRenderer.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        displayRotationHelper.onSurfaceChanged(width, height)  // ADD THIS
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val activeSession = session ?: return

        // UPDATE DISPLAY GEOMETRY BEFORE session.update() — this is the critical call
        displayRotationHelper.updateSessionIfNeeded(activeSession)  // ADD THIS

        // setCameraTextureName removed from here (moved to onSurfaceCreated)
        val frame: Frame = activeSession.update()
        val camera = frame.camera

        backgroundRenderer.draw(frame)

        // ... rest of the method unchanged ...
    }
}
```

**Step 3: Build**

```bash
./gradlew :feature:ar:assembleDebug
```
Expected: BUILD SUCCESSFUL. No compile errors.

**Step 4: Deploy and verify**

Install on device. Open the app in AR mode. Camera feed should now render as a full image, not a single averaged color. The TRACKING chip should eventually go green.

**Step 5: Commit**

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt
git commit -m "fix(ar): Wire DisplayRotationHelper into ArRenderer to fix single-color camera display

session.setDisplayGeometry() was never called. frame.transformCoordinates2d()
returned garbage UV coords, causing all quad vertices to sample at one point.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Phase 2: Session Lifecycle Hardening

**Problems:**
1. `DisposableEffect(Unit)` in `ArViewport` — the `Unit` key means it only fires once per composition. When mode switches AR → Overlay → AR, `onDispose` pauses the session, but when AR mode is re-entered, a new `DisposableEffect` fires again calling `initArSession` and `resumeArSession` — but `attachSessionToRenderer()` is NOT called again because the `AndroidView` factory does not re-run.
2. Both `DisposableEffect` and `MainActivity.onResume()` call `resumeArSession()` — potential double-resume.
3. After `onResume`, the ARCore `Session` object may be the same instance or a new one (depending on whether it was closed). The renderer needs re-attachment either way.

---

### Task 2.1: Fix DisposableEffect key and renderer re-attachment

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/graffitixr/MainScreen.kt`
- Modify: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt`

**Step 1: Read MainScreen.kt and ArViewModel.kt**

Understand the current `DisposableEffect(Unit)` block and `resumeArSession()` implementation.

**Step 2: Add a renderer reference holder to ArViewport**

In `ArViewport`, store the renderer in a `remember`:

```kotlin
@Composable
fun ArViewport(
    // ... same params ...
) {
    // ... existing code ...
    val rendererRef = remember { mutableStateOf<ArRenderer?>(null) }  // ADD

    when (uiState.editorMode) {
        EditorMode.AR -> {
            DisposableEffect(uiState.editorMode) {  // KEY ON editorMode, not Unit
                arViewModel.initArSession(context)
                arViewModel.resumeArSession()
                rendererRef.value?.let { arViewModel.attachSessionToRenderer(it) }  // re-attach
                onDispose {
                    arViewModel.pauseArSession()
                    arViewModel.attachSessionToRenderer(null)  // detach on dispose
                }
            }
            AndroidView(
                factory = { ctx ->
                    val renderer = ArRenderer(ctx, slamManager) { state, count ->
                        arViewModel.updateTrackingState(state, count)
                    }
                    rendererRef.value = renderer  // store renderer
                    arViewModel.attachSessionToRenderer(renderer)
                    onRendererCreated(renderer)
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(3)
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        // ... rest unchanged ...
    }
}
```

**Step 3: Guard against double-resume in ArViewModel**

Find `resumeArSession()` in `ArViewModel.kt`. Add a guard:

```kotlin
fun resumeArSession() {
    val s = _arSession ?: return
    try {
        if (!s.isResumed) {  // check if already resumed — ARCore Session has no isResumed field,
                              // so catch the exception instead:
            s.resume()
        }
    } catch (e: com.google.ar.core.exceptions.CameraNotAvailableException) {
        Log.e("ArViewModel", "Camera not available on resume", e)
    } catch (e: IllegalStateException) {
        // Already resumed — ignore
        Log.w("ArViewModel", "Session already resumed", e)
    }
}
```

> Note: ARCore's `Session.resume()` throws `IllegalStateException` if called when already resumed. Catching it is the right guard.

**Step 4: Build**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

**Step 5: Verify on device**

1. Open app in AR mode → camera shows correctly
2. Press home → return → camera shows correctly (no crash)
3. Switch to Overlay mode → switch back to AR → camera shows correctly

**Step 6: Commit**

```bash
git add app/src/main/java/com/hereliesaz/graffitixr/MainScreen.kt \
        feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt
git commit -m "fix(ar): Harden session lifecycle for resume and mode-switch

- Key DisposableEffect on editorMode so it re-fires when returning to AR
- Re-attach session to renderer after resume
- Guard against double-resume in ArViewModel

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Phase 3: Get Tests Green

### Task 3.1: Run the full test suite and identify failures

**Step 1: Run all unit tests**

```bash
./gradlew testDebugUnitTest 2>&1 | tail -50
```
Expected: Either BUILD SUCCESSFUL or a list of failing tests with error messages.

**Step 2: Identify which test classes fail**

Look for lines like:
```
> Task :feature:ar:testDebugUnitTest FAILED
com.hereliesaz.graffitixr.feature.ar.computervision.TeleologicalTrackerTest > ...
```

**Step 3: Fix each failure**

For each failing test, read the error, identify the root cause (likely a mock that doesn't match the current implementation), and fix the test or the implementation depending on which is wrong.

Common patterns to fix:
- `UnsatisfiedLinkError` on `Mat` → mock it: `mockk<Mat>(relaxed = true)` (never construct `Mat()` in JVM tests)
- `RuntimeException` on `android.util.Log` → add `mockkStatic(Log::class)` in `@Before`
- `NullPointerException` on a field that's now non-null → update the mock setup

**Step 4: Verify all tests pass**

```bash
./gradlew testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL` with all tests reported as passing.

**Step 5: Commit**

```bash
git add -u
git commit -m "test: Fix failing unit tests

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Phase 4: LRU Pruning in Native Engine

**Problem:** `MAX_SPLATS` is 500k. When a long scan session fills the voxel map, the native engine has no eviction strategy. The result is undefined (likely the `splatData` vector's growth is capped and new observations are silently dropped, or it causes a realloc crash).

**Fix:** In `MobileGS.cpp`, inside the function that inserts new splats (likely `processDepthFrame` or equivalent), after inserting check if `splatData.size() >= MAX_SPLATS`. If so, find and evict the `N` lowest-confidence entries.

---

### Task 4.1: Implement LRU pruning in MobileGS.cpp

**Files:**
- Modify: `core/nativebridge/src/main/cpp/MobileGS.cpp`
- Read: `core/nativebridge/src/main/cpp/include/MobileGS.h`

**Step 1: Read MobileGS.cpp to understand the splat insertion path**

Find the function that inserts splats. Look for where `splatData` (or equivalent) grows. Note the struct layout (32 bytes/splat: x,y,z,r,g,b,a,confidence).

**Step 2: Add a pruneMap function**

Find `pruneMap` if it exists as a stub, or add it. The implementation:

```cpp
void MobileGS::pruneMap() {
    if (splatData.size() < MAX_SPLATS) return;

    // Evict the bottom 10% by confidence
    const size_t evictCount = MAX_SPLATS / 10;

    // Partial sort: put the evictCount lowest-confidence splats at the front
    std::partial_sort(splatData.begin(),
                      splatData.begin() + evictCount,
                      splatData.end(),
                      [](const Splat& a, const Splat& b) {
                          return a.confidence < b.confidence;
                      });

    // Erase them
    splatData.erase(splatData.begin(), splatData.begin() + evictCount);
}
```

Adapt field names to match the actual `Splat` struct in `MobileGS.h`.

**Step 3: Call pruneMap before inserting new splats**

In the insertion path (likely `processDepthFrame`), before or after insertion:

```cpp
// After inserting new splat(s):
if (splatData.size() >= MAX_SPLATS) {
    pruneMap();
}
```

**Step 4: Build the native library**

```bash
./gradlew :core:nativebridge:assembleDebug
```
Expected: BUILD SUCCESSFUL. No C++ compile errors.

**Step 5: Verify (manual)**

Run a long scan session (several minutes). Confirm the app does not crash or freeze. The point count should stabilize rather than growing without bound.

**Step 6: Commit**

```bash
git add core/nativebridge/src/main/cpp/MobileGS.cpp \
        core/nativebridge/src/main/cpp/include/MobileGS.h
git commit -m "feat(native): Implement LRU pruning to prevent MAX_SPLATS overflow

When splatData reaches MAX_SPLATS (500k), evict the bottom 10% by confidence
score using partial_sort. Prevents undefined behavior on long scan sessions.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Phase 5: Wire Self-Update to GitHub Releases

**GitHub repo:** `HereLiesAz/MuralOverlay`
**GitHub Releases API:** `https://api.github.com/repos/HereLiesAz/MuralOverlay/releases/latest`

**What needs to happen:**
1. Add `INTERNET` + `REQUEST_INSTALL_PACKAGES` permissions to manifest
2. Register `ApkInstallReceiver` in manifest (currently unregistered — install would silently fail)
3. Update `network_security_config.xml` to deny cleartext (HTTPS only)
4. Add `checkForUpdates()` to `DashboardViewModel`
5. Wire `onCheckForUpdates` in `MainActivity` to actually call it
6. Add `BuildConfig` field for `VERSION_NAME` (currently hardcoded as `"1.18.0"` in MainActivity)

---

### Task 5.1: Fix manifest permissions and receiver registration

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/xml/network_security_config.xml`

**Step 1: Add permissions and register receiver**

In `AndroidManifest.xml`, add inside `<manifest>`:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

Add inside `<application>`:
```xml
<receiver
    android:name="com.hereliesaz.graffitixr.feature.dashboard.ApkInstallReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="android.app.action.DOWNLOAD_COMPLETE" />
    </intent-filter>
</receiver>
```

**Step 2: Harden network security config**

Replace `network_security_config.xml` with:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

This removes cleartext traffic and removes the user-CA trust (prevents MITM via installed certs).

**Step 3: Build to confirm no breakage**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

**Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml \
        app/src/main/res/xml/network_security_config.xml
git commit -m "fix(manifest): Add INTERNET + REQUEST_INSTALL_PACKAGES, register ApkInstallReceiver, harden network security

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 5.2: Add BuildConfig.VERSION_NAME and version plumbing

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt`

**Step 1: Read app/build.gradle.kts**

Find the `defaultConfig` block. Note `versionCode = 1` and `versionName = "1.0"`.

**Step 2: Wire version from version.properties**

Add this to the top of `app/build.gradle.kts` (before the `android` block):
```kotlin
val versionProps = java.util.Properties().apply {
    load(rootProject.file("version.properties").reader())
}
val vMajor = versionProps.getProperty("versionMajor").toInt()
val vMinor = versionProps.getProperty("versionMinor").toInt()
```

Update `defaultConfig`:
```kotlin
versionCode = vMajor * 1000 + vMinor
versionName = "$vMajor.$vMinor.0"

buildConfigField("String", "GITHUB_REPO", "\"HereLiesAz/MuralOverlay\"")
```

Also add `buildConfig = true` to `buildFeatures` (may already be there — check first).

**Step 3: Update MainActivity to use BuildConfig**

Replace the hardcoded `"1.18.0"` in the `SettingsScreen` call:
```kotlin
currentVersion = BuildConfig.VERSION_NAME,
```

**Step 4: Build and verify**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

**Step 5: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt
git commit -m "fix: Wire versionName from version.properties and add GITHUB_REPO BuildConfig field

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 5.3: Implement checkForUpdates in DashboardViewModel

**Files:**
- Modify: `feature/dashboard/src/main/java/com/hereliesaz/graffitixr/feature/dashboard/DashboardViewModel.kt`
- Modify: `feature/dashboard/src/main/java/com/hereliesaz/graffitixr/feature/dashboard/DashboardUiState.kt`

**Step 1: Read DashboardViewModel.kt and DashboardUiState.kt**

Understand the current state shape and what `updateStatusMessage` is.

**Step 2: Add update check fields to DashboardUiState**

Add to `DashboardUiState`:
```kotlin
data class DashboardUiState(
    // ... existing fields ...
    val updateStatusMessage: String? = null,
    val isCheckingForUpdate: Boolean = false,
    val pendingUpdateApkUrl: String? = null,  // non-null when an update is ready to install
)
```

**Step 3: Add checkForUpdates() to DashboardViewModel**

```kotlin
fun checkForUpdates(currentVersion: String) {
    viewModelScope.launch {
        _uiState.update { it.copy(isCheckingForUpdate = true, updateStatusMessage = null) }
        try {
            val latestRelease = fetchLatestRelease()  // see below
            if (latestRelease == null) {
                _uiState.update { it.copy(isCheckingForUpdate = false, updateStatusMessage = "Could not check for updates") }
                return@launch
            }
            val latestTag = latestRelease.tagName.removePrefix("v")
            if (isNewerVersion(latestTag, currentVersion)) {
                val apkAsset = latestRelease.assets.firstOrNull { it.name.endsWith(".apk") }
                _uiState.update {
                    it.copy(
                        isCheckingForUpdate = false,
                        updateStatusMessage = "New version $latestTag available",
                        pendingUpdateApkUrl = apkAsset?.browserDownloadUrl
                    )
                }
            } else {
                _uiState.update { it.copy(isCheckingForUpdate = false, updateStatusMessage = "Up to date") }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _uiState.update { it.copy(isCheckingForUpdate = false, updateStatusMessage = "Update check failed") }
        }
    }
}

private suspend fun fetchLatestRelease(): GitHubRelease? {
    return withContext(kotlinx.coroutines.Dispatchers.IO) {
        val url = java.net.URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            if (connection.responseCode != 200) return@withContext null
            val json = connection.inputStream.bufferedReader().readText()
            parseRelease(json)
        } finally {
            connection.disconnect()
        }
    }
}

// Minimal JSON parsing without a dependency (avoids adding Retrofit/Moshi for one endpoint)
private fun parseRelease(json: String): GitHubRelease? {
    return try {
        val tagName = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
        val assetUrls = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(json)
            .map { it.groupValues[1] }
            .toList()
        val assetNames = Regex("\"name\"\\s*:\\s*\"([^\"]+\\.apk)\"")
            .findAll(json)
            .map { it.groupValues[1] }
            .toList()
        val assets = assetNames.zip(assetUrls).map { (name, url) -> GitHubAsset(name, url) }
        GitHubRelease(tagName, assets)
    } catch (e: Exception) { null }
}

private fun isNewerVersion(latest: String, current: String): Boolean {
    fun parse(v: String) = v.split(".").map { it.toIntOrNull() ?: 0 }
    val l = parse(latest); val c = parse(current)
    for (i in 0 until maxOf(l.size, c.size)) {
        val lv = l.getOrElse(i) { 0 }; val cv = c.getOrElse(i) { 0 }
        if (lv != cv) return lv > cv
    }
    return false
}

private data class GitHubRelease(val tagName: String, val assets: List<GitHubAsset>)
private data class GitHubAsset(val name: String, val browserDownloadUrl: String)
```

> Note: No Retrofit/Moshi added. `HttpURLConnection` is stdlib. YAGNI.

**Step 4: Add installUpdate() to DashboardViewModel**

```kotlin
fun installUpdate(context: android.content.Context) {
    val apkUrl = _uiState.value.pendingUpdateApkUrl ?: return
    val downloadManager = context.getSystemService(android.app.DownloadManager::class.java)
    val request = android.app.DownloadManager.Request(android.net.Uri.parse(apkUrl)).apply {
        setTitle("GraffitiXR Update")
        setDescription("Downloading update...")
        setDestinationInExternalFilesDir(context, null, "update.apk")
        setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    }
    val downloadId = downloadManager.enqueue(request)
    context.getSharedPreferences("secure_prefs", android.content.Context.MODE_PRIVATE)
        .edit().putLong("update_download_id", downloadId).apply()
}
```

**Step 5: Wire into MainActivity**

In `MainActivity.kt`, find the `SettingsScreen(...)` call and update:
```kotlin
SettingsScreen(
    currentVersion = BuildConfig.VERSION_NAME,
    updateStatus = dashboardUiState.updateStatusMessage,
    isCheckingForUpdate = dashboardUiState.isCheckingForUpdate,
    isRightHanded = editorUiState.isRightHanded,
    onHandednessChanged = { editorViewModel.toggleHandedness() },
    onCheckForUpdates = { dashboardViewModel.checkForUpdates(BuildConfig.VERSION_NAME) },
    onInstallUpdate = { dashboardViewModel.installUpdate(this@MainActivity) },
    onClose = { showSettings = false }
)
```

You'll need to collect `dashboardViewModel.uiState` — check if it's already collected in `MainActivity`. If not, add:
```kotlin
val dashboardUiState by dashboardViewModel.uiState.collectAsState()
```

**Step 6: Build**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

**Step 7: Test manually**

1. Open Settings
2. Tap "Check for Updates"
3. Verify spinner shows briefly
4. Verify "Up to date" or "New version X.Y.Z available" appears
5. If update available, tap Install — verify DownloadManager notification appears

**Step 8: Commit**

```bash
git add feature/dashboard/src/main/java/com/hereliesaz/graffitixr/feature/dashboard/DashboardViewModel.kt \
        feature/dashboard/src/main/java/com/hereliesaz/graffitixr/feature/dashboard/DashboardUiState.kt \
        app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt
git commit -m "feat(dashboard): Wire Check for Updates to GitHub Releases API

Fetches latest release from HereLiesAz/MuralOverlay, compares to BuildConfig.VERSION_NAME,
downloads APK via DownloadManager. No new dependencies added (uses HttpURLConnection).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Verification Checklist (Run After All Phases)

```bash
# All unit tests pass
./gradlew testDebugUnitTest

# Full debug build succeeds
./gradlew assembleDebug
```

**On-device checks:**
- [ ] AR mode: full camera feed renders (not a single color)
- [ ] Press home → return: camera feed still works, no crash
- [ ] Switch AR → Overlay → AR: both modes work, no crash
- [ ] Long scan (5+ min): no crash from splat overflow
- [ ] Settings → Check for Updates: shows a result (not stuck on spinner)
- [ ] TRACKING chip goes green in AR mode

---

## What Is NOT In This Plan (Intentional Defers)

- **Release signing config** — debug signing is fine for internal use
- **`isMinifyEnabled = true`** — deferred; requires ProGuard tuning for native JNI
- **Live voxel count HUD** — `nativeGetSplatCount()` JNI — nice-to-have, not blocking
- **GPS wiring to SLAM** — not needed for core use case
- **CI build-and-test pipeline** — deferred until the above is stable
