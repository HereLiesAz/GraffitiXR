# Performance Guide

GraffitiXR is optimized for stable AR tracking on mobile hardware without a persistent 3D map to
maintain — there is no voxel/splat rendering loop; see [`NATIVE_ENGINE.md`](NATIVE_ENGINE.md) and
[`ARCHITECTURE.md`](ARCHITECTURE.md).

## Relocalization

The native engine's background thread (`relocThreadFunc`) matches the live camera against the wall
fingerprint and re-attempts at two cadences: ~5 Hz once locked, ~16 Hz while hunting for a lock — see
[`TELEOLOGICAL_SLAM.md`](TELEOLOGICAL_SLAM.md). This runs off the render thread; heavier one-off work
(fingerprint generation at target-capture time, SuperPoint inference) currently shares a single
engine-wide native lock with the per-frame camera/YUV feed, so a capture can visibly stall rendering
— a known cost, not yet addressed.

## Camera / Perception Throttling

Camera target frame rate is user-configurable from Settings (`CameraTargetFps`: 30, 60, device
default, device max), and the app can automatically throttle further under load — on thermal
throttling, Android power-save mode, low battery, and detected lag — each independently toggleable
from Settings. See [`FEATURE_REFERENCE.md`](FEATURE_REFERENCE.md) §"Performance & throttling" for
the current defaults and exact toggles.

## Battery & Thermal Management

* **Background offloading:** relocalization (PnP matching) and project persistence run on dedicated
  low-priority threads, off the render/UI thread.
* **Perception layers are opt-in visual overlays**, not part of the tracking pipeline itself — see
  `docs/UI_UX.md` for what each one shows.

---
*Documentation updated on 2026-09-04: removed the fictional opaque-voxel rendering loop, "mandatory
hardware stereo," and stochastic-depth-integration claims (none of that ships — see
`NATIVE_ENGINE.md`); replaced with the actual relocalization cadence and the real, user-configurable
throttle settings. Prior update: 2026-04-24, Persistent Voxel Memory and Pocket-Ready recovery
implementation — the subsystem that update described was later deleted.*
