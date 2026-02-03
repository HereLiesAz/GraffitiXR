# Ralph Scratchpad - Fixing the Broken Build

## Analysis of Build Errors
The build failed with many unresolved references in `feature:dashboard`, `feature:editor`, and `feature:ar`.

### 1. Feature:Dashboard
- `OnboardingManager.kt`: Missing `SharedPreferences` and its methods. Likely missing `android.content.Context` or `android.content.SharedPreferences` imports.
- `ProjectLibraryScreen.kt`: `ProjectData` unresolved. It's currently in `com.hereliesaz.graffitixr.common.model`.
- `SaveProjectDialog.kt`: `AzTextBox` unresolved. This is from the `AzNavRail` library.
- `SettingsScreen.kt`: `AzLoad` unresolved.

### 2. Feature:Editor
- `BackgroundRemover.kt`: `await` unresolved. Likely missing `kotlinx.coroutines.tasks.await` for GMS tasks if it's using ML Kit.
- `MockupScreen.kt`: `UiState` unresolved.

### 3. Feature:AR
- `ArRenderer.kt`: `BackgroundRenderer` and `PointCloudRenderer` unresolved. They are in the same package `com.hereliesaz.graffitixr.feature.ar.rendering`, but maybe the imports are messed up or they weren't moved.
- `ArView.kt`: `onPlanesDetected`, `onFrameCaptured` etc. are missing from the `ArView` composable signature.
- `MappingScreen.kt`: `slam` and `slamManager` unresolved.

## Plan
1. Fix `feature:dashboard` errors.
2. Fix `feature:editor` errors.
3. Fix `feature:ar` errors.

I'll start with `feature:dashboard` as it seems to have some basic dependency/import issues.
