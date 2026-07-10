# GraffitiXR — Licensing (Plan of Record)

Authoritative, path-by-path license layout. Decisions distilled from `docs/RESEARCH.md` Part 2. This
licenses the architecture ahead of building the extension system, on purpose — so the module and license
boundaries are correct before code exists.

> **Not legal advice.** The mechanics here are standard, but if a licensing or acquisition conversation
> ever turns on this, have an IP lawyer read `LICENSE`, this file, and `docs/licenses/MIT.txt`.

---

## Summary

| Layer | License | Rationale |
|---|---|---|
| App, `core:*`, the AR / SLAM / **teleological engine**, and engine-touching built-ins | **PolyForm Noncommercial 1.0.0** | The product and the moat. Competitors can't ship it; you keep all commercial rights. |
| `:extension-api` / SDK / plugin loader *(not built yet)* | **MIT** | The contract extensions build against, and that other apps can adopt. |
| Asset importers — ABR, `.brushset`, `.cube` *(not built yet)* | **MIT** | Interop layer; open is pure upside. |
| Commodity reference tools that do **not** touch the native engine (brush, eraser, blur, curves, color-balance) | **MIT** | Seed the ecosystem; double as extension templates. |
| Bundled third parties (OpenCV, ML Kit, …) | Their own upstream licenses | Not ours to relicense. |

Primary terms: `/LICENSE` (PolyForm). MIT text: `/docs/licenses/MIT.txt`. AGPL-3.0 (retained, not applied):
`/docs/licenses/AGPL-3.0.txt`.

## Precedence (most specific wins)

per-file SPDX header  →  module `LICENSE`  →  this `LICENSING.md`  →  root `/LICENSE`

## PolyForm Noncommercial — the protected set

The engine is **not** contained in one module; the protective boundary must cover the whole set:

- `core/nativebridge/**` — the native C++ engine (`MobileGS`, `SuperPointDetector`, `StereoProcessor`,
  `ImageWarper`, `DistortionHead`, `GraffitiJNI`, …), the JNI bridge, and the Kotlin engine side
  (`SlamManager`, depth providers). Carries its own `core/nativebridge/LICENSE`.
- `feature/ar/anchor/**` — `MetricFingerprintBuilder`, `MetricMarks`, `PlaneMarks`, `PoseFusion`
  (fingerprint construction + pose solving — core teleological logic).
- `feature/ar/eval/**` — `DriftCostProbe`, `EvalMetrics`, `EvalSampleLog` (painting-progress / drift
  measurement — the "teleological" half).
- `core/common/.../model/Fingerprint.kt`, `WallFeatureMap.kt`, and the fingerprint serializers
  (`FingerprintSerializer`, `KeyPointSerializer`, `MatSerializer`, `RefinementPathSerializer`).
- The method docs: `TELEOLOGICAL_SLAM.md`, `RELOC_MAP_DESIGN.md`, `SELF_GROWING_FINGERPRINT.md`,
  `NATIVE_ENGINE.md`, `DISTORTION_HEAD.md`, `SLAM_SETUP.md`.
- Everything else in the app not explicitly MIT below.

**Borderline glue left under PolyForm by default** (pull into a stricter posture only if you want maximal
lockdown): `feature/ar/rendering/ArRenderer.kt`, `feature/ar/ArViewModel.kt`,
`feature/ar/TargetCreationFlow.kt`. These *use* the engine but don't contain the novel method.

## MIT — the open extension layer *(declared ahead of implementation)*

When these are built, they carry MIT (add an SPDX header per file):

~~~
// SPDX-License-Identifier: MIT
~~~

- `:extension-api` — the extension API / SDK / plugin loader. Its own module, so the license line is a real
  module line.
- The asset importers (ABR / `.brushset` / `.cube`).
- The commodity tools refactored as reference extensions — **only** those operating purely on
  pixels/layers/canvas. Anything reaching into the native engine (e.g. GPU Liquify → `SlamManager`) stays a
  PolyForm **privileged built-in**, not MIT.

## The hard boundary

**The MIT extension surface exposes only editor extension points — operations on layers, bitmaps, and the
canvas. It must never surface the relocalization / teleological engine.** Plugins extend the editor, not the
tracking core. A competitor adopting the MIT API/format is expected and fine (standard-setting, the VS Code
outcome); they still never touch the engine.

## Notes that change what this means

- **Source license ≠ app-usage grant.** PolyForm on the *source* blocks competitors from lifting code. The
  *compiled app* is separately made **free for anyone to use, including paid commissions**, under its own
  app-usage terms — so a working muralist is never barred by the source's non-commercial clause.
- **The author is never restricted.** PolyForm/MIT bind others; you keep full rights. A commercial Pro tier
  and future dual-licensing or acquisition are unaffected.
- **Not "open source."** PolyForm is source-available, not OSI-approved, and the app as a whole isn't open
  source. Describe it accurately; drop "open source" from public copy.
- **Contributions need a CLA.** Outside contributions are owned by their authors under whatever license they
  land in; to keep dual-licensing/acquisition open, require a CLA or don't accept outside contributions to
  parts you might commercialize.
- **Third-party compliance is separate.** Bundled deps keep their upstream licenses; note a **GPL-2.0**
  component (`ittnotify`) inside the vendored OpenCV — a compliance item to check regardless of this plan.

## Status

- Applied now: PolyForm on the app + engine (`/LICENSE`, `core/nativebridge/LICENSE`).
- Declared, not yet applied: MIT on `:extension-api`, the importers, and the designated commodity tools —
  SPDX headers go on when that code is written.
- On ice: AGPL-3.0 (`docs/licenses/AGPL-3.0.txt`), retained in case copyleft is ever wanted for an isolated
  part. Not currently used — AGPL is incompatible with a sellable/cross-app extension ecosystem.
