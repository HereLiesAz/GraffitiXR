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
*   **Missing:** I need to find where `solvePnP` is actually called. `MobileGS.cpp` doesn't show it in the snippet I read. It might be in a separate class or not implemented yet.
*   **Action:** Find the `TeleologicalLoop` implementation code.

## Action Plan
1.  Locate `TeleologicalLoop` code.
2.  Optimize `TargetEvolutionEngine` parameters (epsilon for poly approx).
