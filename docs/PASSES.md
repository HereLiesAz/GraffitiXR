# Passes

A **pass** is one independent way of deciding that part of the design has been painted. Each pass
looks for a different set of things in the **same raw camera frame** and votes, per design cell, on
whether it sees paint. Passes all run, all the time, and their votes meet in one place.

## Rules

- **The live frame is never filtered, smoothed or rewritten.** It goes in exactly as the camera
  delivers it. What differs between passes is what they look for, derived from the design (never
  from the wall).
- Anything a matcher measures in the frame (a detector's keypoints, gradients inside a shape
  comparison) is internal to that comparison. No filtered copy of the live frame is stored, saved
  or handed to another pass.
- Smoothing the wall's texture out of the live frame is explicitly out.
- A pass can fail; passes should fail differently. A dissenting pass never vetoes another's evidence
  (`passes::combineVotes`: any pass seeing paint confirms the cell for that tick).

## Where it lives

`core/nativebridge/src/main/cpp/include/PassEvidence.h` defines the verdicts (`kNoData`, `kAbsent`,
`kPresent`), the stable pass ids, the vote combiner and per-pass `Stats`. `MobileGS::updatePaintGrid`
collects each pass's verdict per opaque design cell and combines them; the consecutive-ticks rule
(`kGridConfirm`) that turns a confirmation into a painted cell stays there.
`MobileGS::getPassStats(pass, out5)` returns `{ticks, observed, present, lastObserved, lastPresent}`.

## Today's passes

| id | name | looks for |
|---|---|---|
| 0 | `features` | design features confirmed on the wall: descriptors from the design composite matched near their predicted position (`tryUpdateFingerprint`, gated by the lock's pose) |
| 1 | `colour` | a cell that has left the bare wall and matches the paint its design colour was painted with. Hue-free: purple in the design may be yellow on the wall. The bare wall comes from the capture photo, or the first AR sighting |

## Planned

1. **Design filter bank** (offscreen, design only): sketch, mosaic, posterize at 2 and 4 levels,
   cartoon, blob silhouette, invert, channel-weighted luminance; later watercolor, Kuwahara, halftone,
   stencil layers of 2 to 5 tones. A filter decides WHERE to look; descriptors come from the
   unfiltered design at those locations and are matched against the raw frame's own features.
2. **Wall exclusion list**: features known to be bare wall do not count as paint, and the same list
   supplies a null baseline.
3. **Baseline margin check**: a pass counts only when its true alignment beats displaced alignments
   by a clear margin.
4. **Change against the bare-wall photo** (optional): the stored clean-wall photo, registered to the
   capture, compared with the live frame through the pose. The painted mask's shapes are matched
   against the design's patch shapes, whatever the colour.
   - A switch in **Settings** (not the diagnostics overlay), off by default: it is never turned on
     automatically.
   - It cannot run without the clean-wall photo, whatever the switch says.
   - The target-creation instructions ALWAYS carry the clean-wall step, switch or no switch: if the
     surface has not been marked (gridded) yet, take a photo of the bare wall first (one tap), then
     mark it; if the marks already exist, just continue by tapping them.
5. Per-pass diagnostics in the overlay.

Passes judge progress and how much to trust a lock. They do not localize until one has earned trust
against real locks.
