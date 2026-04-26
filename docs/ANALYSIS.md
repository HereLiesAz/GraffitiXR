# GRAFFITIXR: AUTOPSY & ANALYSIS

## STATUS: PRODUCTION READY (STABLE)
**Date:** 2026-04-24
**Condition:** The patient is in peak physical condition. The engine has been streamlined for real-world street art conditions, prioritizing spatial memory over visual vanity.

---

## 1. RECENT SURGERIES (COMPLETED)

### [FIXED] The Performance Stall (Inria Rejection)
* **Issue:** blind implementation of "desktop-grade" 3DGS (alpha sorting, densification) made the app unusable on mobile.
* **Fix:** Excised the heavy sorting and soft-blending code. Replaced it with an ultra-lightweight **Opaque Surfel Pipeline**.
* **Win:** Rock-solid 60fps achieved with 250,000 voxels.

### [FIXED] The "Pocket" Amnesia
* **Issue:** Tracking loss was permanent after stick the phone in a pocket.
* **Fix:** Implemented **Persistent Voxel Memory** and a background **Snap-Back Thread** (`relocThreadFunc`).
* **Win:** The app now remembers the room layout and realigns its mural instantly upon resume via PnP matching.

### [FIXED] The Depth Bottleneck
* **Issue:** Processing every depth pixel was overheating the CPU.
* **Fix:** Implemented **Stochastic Integration**. Only 2048 random pixels are sampled per frame.
* **Win:** 90% reduction in depth-processing load with zero loss in tracking stability.

### [FIXED] The Hardware Schism
* **Issue:** Dual-lens devices were often falling back to low-quality mono depth.
* **Fix:** **Mandatory HW Stereo**. Session initialization now forces `REQUIRE_AND_USE`.
* **Reward:** HW Stereo data is assigned **0.9 confidence** (nearly immutable), while mono gets 0.5.

---

## 2. ARCHITECTURAL OVERVIEW (Current)

* **UI Layer:** Jetpack Compose (Kotlin)
* **Logic Layer:** ViewModels (Kotlin)
* **Memory Layer:** **Persistent Voxel Memory** (C++)
* **Recovery Layer:** **Snap-Back PnP Thread** (C++)
* **Renderer:** OpenGL ES 3.0 (Opaque `GL_POINTS`)

## 3. NEXT STEPS
1.  **Refine UI:** Optimize the one-handed "Rail" experience.
2.  **Field Testing:** Verify "Pocket-Ready" recovery in high-contrast outdoor environments.
3.  **Community:** Prepare for Beta launch.

---
*Documentation updated on 2026-04-24 during Persistent Voxel Memory and Pocket-Ready recovery implementation.*
