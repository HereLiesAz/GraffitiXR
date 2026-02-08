# Execution Plan

## 1. Refactoring & Cleanup
The goal is to finalize the modularization of the app.

- [ ] **Move `TargetCreationFlow` to `:feature:ar`**
    - `TargetCreationFlow.kt` is currently in `:app`. It relies on AR logic and should live in the AR module.
    - Requires moving the file and updating imports.
- [ ] **Clean up `:app` Module**
    - Investigate why `MainScreen.kt` does not seem to use `AzNavRail` despite documentation claims.
    - Check if `MainScreen` logic can be split into `feature:dashboard` or if it should remain as the global app state holder.

## 2. High Priority Features
- [ ] **AzNavRail Haptics**
    - Add haptic feedback to `AzNavRail` usage.
    - **Found Usage:** `MappingScreen.kt` uses `AzHostActivityLayout` and `azRailItem`.
    - **Missing Usage:** `MainScreen.kt` does *not* appear to use `AzNavRail`. Investigate discrepancy.
    - **Implementation:** Wrap `onClick` handlers in `MappingScreen` (and others found) with `LocalHapticFeedback.current.performHapticFeedback`.

## 3. Medium Priority Features
- [ ] **"Ghost" Toggle (Point Cloud Visibility)**
    - Check `MainScreen.kt`'s `arViewModel.togglePointCloud()`.
    - If this covers the requirement, mark as done or improve.
- [ ] **Fingerprint Aging**
    - Implement logic to expire old ORB descriptors in `MobileGS` (native).
    - Add "Force Rescan" button in `MappingScreen` or `ArView`.

## 4. Documentation
- [ ] Update `README.md` with new features if implemented.
