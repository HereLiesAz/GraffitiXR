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
| 0 | `features` | design features confirmed on the wall: descriptors from the design composite matched near their predicted position (`tryUpdateDoodle`, gated by the lock's pose) |
| 1 | `colour` | a cell that has left the bare wall and matches the paint its design colour was painted with. Hue-free: purple in the design may be yellow on the wall. The bare wall comes from the capture photo, or the first AR sighting |

| 2 to 10 | `sketch`, `mosaic`, `posterize2`, `posterize4`, `cartoon`, `blobs`, `invert`, `warm-luma`, `stencil3` | design features at places the matching design filter points to (below) |
| 11 | `change` | cells that changed since the clean-wall photo, in the shape of the design (optional; see Pass B below) |

### Filter passes

The `stencilN` filters are the app's old stencil tool (`StencilProcessor`, removed with the design
surface in 49710f90e), ported: median-blur the value channel, K-means it into N tones, close each
layer 5x5 as a cut sheet would be. N is 3 here: black, grey, white (the stencil tool the app offers uses the same three).

`DesignFilters.h` holds the bank. Each filter turns the design (offscreen, once, when it is
registered) into another picture of itself, and `locations` picks up to 300 corner-like places on it.
The filtered picture is then thrown away: descriptors are computed on the UNFILTERED, normalised
design at those places (SuperPoint `describe`, or ORB with the orientation ORB would give), so they
match against the raw live frame's own features. A filter decides where to look, nothing else.

**Built cheapest first, off the calling thread.** Making the targets takes time, so design
registration returns at once and a worker builds them in `designfilters::kBuildOrder`: invert,
posterize 2, posterize 4, warm-luma, mosaic, sketch, cartoon, blobs, stencil3 (measured: the first
ones cost a millisecond or two, the stencil about 70 ms). Each filter's targets are installed the
moment they are ready, and a pass whose targets are not there yet has no data. Filters run on a copy
at most 800 px on the long side. With SuperPoint, ONE forward pass over the design serves every
filter (`describeField` once, `sampleField` per filter). A newer design, or a clear, makes a running
build stand down before it installs anything.

Each filter's places form a target set (`MobileGS::TargetSet`) that corroborates like the artwork's:
the same pose-gated match (`gatedMatchSet`), consecutive confirmations, reset when the design moves
or is replaced. A cell's vote is "present" when at least half of that filter's places inside it are
confirmed, "absent" when some are not, and no data when it has none. Filter targets never become
reloc marks: they judge progress, and Align uses them too. `MobileGS::autoFitDesign` matches the capture
photo against the artwork's features, every installed target set and the inverted design, and keeps
the set whose similarity fit has the most inliers.

## Guards on every set

- **Wall exclusion list**: the wall as captured (the bare doodle, before any paint or self-grow)
  is projected into the live frame through the lock's pose. A design feature's live match is not
  counted when a bare-wall feature near it describes that live keypoint at least as well as the
  design does: that is the wall's own texture. The row stays observed, so its confirmation count
  resets.
- **Baseline margin**: the same set is matched again at four displaced placements of the design
  (shifted by at least 30% of the half-extent and three search radii). The true placement counts
  only when its matches exceed twice the displaced average. Chance-level agreement drops that
  tick's hits for the set (and the primary artwork's self-grow candidates from it).

## Pass B: change against the clean wall (`change`, id 11)

Optional. It compares the live wall with the **clean-wall photo**: the bare wall photographed before
anything was marked or painted. It asks which cells of the wall have changed, and whether the changed
region has the shape of the design. Colour plays no part in the second question.

- **The photo**: the round shutter on the Doodle grid screen (`ArViewModel.requestCleanWallPhoto`), saved to the
  project as `cleanWallUri`, and registered to the capture photo by feature matching and a RANSAC
  homography (`MobileGS::setCleanWallImage`; status none, pending, registered or failed). A photo
  given before the capture photo waits for it.
- **The instructions always carry the step**, whatever the switch says: the DOODLE GRID card says
  to photograph the bare wall with the shutter if the grid isn't painted yet, otherwise press
  Cancel. Cancel only puts the card and shutter away; tapping the marks works throughout.
- **The switch** is in **Settings** (not the diagnostics overlay), **off by default**, and nothing
  but the user turns it on (`SettingsRepository.changePassEnabled`). Even on, the pass judges
  nothing without a registered clean-wall photo.
- **Where it can run**: it needs the capture photo and a doodle whose frame is the capture
  camera's (an ARCore target made by the metric/plane path, or a project reloaded with its capture
  photo). A standalone SphereSLAM target, or an ARCore target made by a path that never installs the
  capture photo, has neither, so the pass judges nothing there; the clean-wall photo is still kept.
  A photo that cannot be registered (too few features, too different a view) is reported at once so
  it can be retaken. The part of the capture photo the registered clean-wall photo does not reach is
  no-data, never a repeat of its edge colour.
- **Shape rule** (`ShapeMatch.h`, unit-tested): a connected region of changed cells is paint when it
  lies inside the design (at most a quarter of its cells outside) and the design patches it touches
  are complete (nearly all seen cells changed), untouched, or few: at most two partial, or less than
  40% of the region in partial ones. A shadow or a hand cuts across many patches and spills outside
  the design, so it is not paint. Whatever colour the paint is.

## The CV view

`AR ▸ CV view` (`mode.ar.cv`) draws everything the passes are looking for over the design, so the
artist can see it. `MobileGS::renderCvPreview` composites it in the design's own pixel space: each
pass's targets as dots in that pass's colour (`passes::passRgb`), a ring while the wall has not yet
answered and a filled dot once it has, and the design cells already judged painted tinted green. A
second overlay quad in `ArRenderer`, with the design's placement and warp, textures it; a legend
(name, colour, found / looking for) sits at the top right. It is re-composited about once a second
while on. Passes whose targets are still being built simply have no dots yet.

## Planned

1. **More filters**: watercolor, Kuwahara, halftone, Voronoi, colour
   shifts. (The first eight are in; see above.)
2. Per-pass diagnostics in the overlay.

Passes judge progress and how much to trust a lock. They do not localize until one has earned trust
against real locks.
