# Research: Target Evolution Perfection

## Objective
Effortless target creation via segmentation. Perfect Teleological SLAM.

## Target Creation
*   **Current:** MLKit Segmentation -> OpenCV FloodFill.
*   **Improvement:**
    *   "Effortless" = Automate the "refinement".
    *   If MLKit confidence is high, use it directly.
    *   Snap corners to the mask bounds automatically (using `extractCorners` which uses `approxPolyDP`).
    *   *UX:* Allow user to just "tap" the object, expanding the mask intelligently (Segment Anything Model is too heavy, but MLKit is good).

## Teleological SLAM
*   **Current:** `solvePnP` mentioned in docs.
*   **Status:** Implemented in `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/computervision/TeleologicalTracker.kt`.
*   **Action:** Verify the loop in field tests.

## Action Plan
1.  [x] Locate `TeleologicalLoop` code. (Implemented as `TeleologicalTracker.kt`)
2.  Optimize `TargetEvolutionEngine` parameters (epsilon for poly approx).
