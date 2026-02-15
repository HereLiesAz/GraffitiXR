# Plan: Beta Completion

## Phase 1: Core Stability & Bug Fixes
- [ ] **Fix Camera Blocking Bug:**
    - [ ] Verify `ArView.kt` layout order (PreviewView vs GLSurfaceView).
    - [ ] Ensure `ArRenderer` clears to transparent (0,0,0,0) and does *not* draw an opaque background.
    - [ ] Verify `setZOrderMediaOverlay(true)` on `GLSurfaceView`.
- [ ] **Refine Target Evolution:**
    - [ ] Update `TargetEvolutionEngine` to use `approxPolyDP` for automatic contour simplification.
    - [ ] Implement "Snap to Target" interaction in `MaskingScreen`.

## Phase 2: New Features - Drawing Tools
- [ ] **Basic Tools (Kotlin):**
    - [ ] Implement `DrawingCanvas` with `Brush` and `Eraser` support.
    - [ ] Add `Toolbar` UI for tool selection and color picking.
- [ ] **Advanced Tools (JNI):**
    - [ ] Implement `processLiquify` in `MobileGS` (OpenCV `remap` or Shader).
    - [ ] Implement `processInpaint` in `MobileGS` (OpenCV `inpaint`).
    - [ ] Expose JNI methods in `NativeMethods.kt`.
    - [ ] Connect UI to Native calls.

## Phase 3: New Features - Export Logic
- [ ] **Export Manager:**
    - [ ] Implement `ExportManager` class.
    - [ ] Implement `Single Image Export` (Off-screen Canvas).
    - [ ] Implement `Layer Export` (Save individual bitmaps).
- [ ] **UI Integration:**
    - [ ] Add "Export" button/menu to `EditorScreen`.

## Phase 4: AR Enhancements
- [ ] **Teleological SLAM:**
    - [ ] Locate `solvePnP` call site or implement if missing.
    - [ ] Ensure `TeleologicalLoop` correctly updates `MobileGS` camera pose.
- [ ] **Passive Triangulation (Stereo):**
    - [ ] Implement `StereoDepthProvider` using Camera2 logical multi-camera API.
    - [ ] Implement `updateStereoFrame` in JNI (OpenCV `StereoSGBM`).
    - [ ] Update `ArViewModel` to select `StereoDepthProvider` if LiDAR is absent.

## Phase 5: Polish & Verify
- [ ] **Manual Testing:** Verify all features on device.
- [ ] **Unit Tests:** Add tests for new logic.
- [ ] **Documentation:** Update `README.md` and `USER_GUIDE.md`.
