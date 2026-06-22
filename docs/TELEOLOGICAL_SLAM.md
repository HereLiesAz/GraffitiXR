# Teleological SLAM (Painting-Progress Correction)

Conventional relocalization treats the wall as a fixed target: capture a
fingerprint once, then match against it forever. That assumption breaks for a
muralist, because **the wall changes as you paint it** — the very marks the
fingerprint relies on get covered by the artwork, so matching gets *worse* the
further along you are.

GraffitiXR turns that around. Because the app already knows what the finished
piece is supposed to look like (the overlay you loaded), it treats the goal
state as additional information — hence *teleological* (goal-directed). The
further along the painting is, the more real-world corroboration the engine
has, and the more tightly the overlay locks to the wall.

## Mechanism

The work happens entirely in the native engine (`core/nativebridge`), layered
on top of the OpenCV relocalizer:

1. **Baseline fingerprint.** When the artist registers the wall, the engine
   stores ORB/feature descriptors of the clean surface (the relocalization
   fingerprint used by `relocThreadFunc` for snap-back).
2. **Progress measurement (`MobileGS::tryUpdateFingerprint`).** On a clean
   camera frame, the engine measures how much of the registered artwork base is
   now corroborated by real wall content, writing the result to
   `mPaintingProgress`. This stage is read-only with respect to the reloc
   fingerprint.
3. **Confidence weighting.** As `mPaintingProgress` rises, the corroborated
   marks contribute more to the pose solution, so global drift correction
   becomes more aggressive — the overlay "snaps" more tightly the more of the
   mural exists on the wall.

This is the inverse of the failure mode other tracing apps hit, where accuracy
degrades as the original reference marks disappear under paint.

## Relationship to the rest of the engine

- **Relocalization** (snap-back after tracking loss / screen-off) is the
  `relocThreadFunc` background thread — see [NATIVE_ENGINE.md](NATIVE_ENGINE.md)
  and [SLAM_SETUP.md](SLAM_SETUP.md).
- **Pose tracking** itself is provided by ARCore; the native engine layers
  relocalization, the persistent voxel map, and this teleological correction on
  top of ARCore's poses.
- Pose smoothing/fusion lives in the Kotlin `PoseFusion` layer, not in C++.

---
*Documentation updated on 2026-06-22 during the SLAM right-size and documentation-accuracy pass.*
