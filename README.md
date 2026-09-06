# GraffitiXR

GraffitiXR is an android app for street artists. There are plenty of apps that overlay an image on your camera view so you can virtually trace it, but when I'm painting a mural based on a sketch that I have saved on my phone, using a tripod can really ebb the flow. We're all over the damn place. Me, I put my phone in my pocket. Even the apps that use AR to keep the image steady and in one place can't deal with the abysmal darkness of the pocket.

So I'm making something better by repurposing (what those in-the-know call) the grid method. I was always thinking, "Why can't these specific doodles be saved, like a persistent anchor, so the overlay is always just plain in the right spot?"

So, now, that's what those doodles do.

I had to invent a custom **fingerprinted relocalizer** that works on Android without the help of the cloud—because graffiti is, you know, illegal. When you lock onto a wall, it captures an OpenCV feature **fingerprint** of your marks—descriptors plus a handful of triangulated 3D points. Even after tracking loss or a screen-off event, the engine matches the live camera against that fingerprint and solves the pose (PnP) to **snap back** and realign your mural to the wall in milliseconds—no cloud, and no pre-scan of the whole room.

And I followed it up with what I call a **Teleological SLAM**—since we know what the result is supposed to look like, I use OpenCV to look for your progress, meaning that the further along you are, the more tightly the overlay sticks to the wall. Without this, you'd cover those marks up with the painting itself, making the app less accurate as you go. That's exactly where other apps like this truly fail.

Both of those—snap-back and the self-extending fingerprint—currently ship as an opt-in toggle (Settings > diagnostic overlay, off by default) while I validate them on real hardware, so don't expect either out of the box just yet.

Just for shirts and goggles, I included the non-AR, image overlay functionality for image tracing, just like you get with those other apps, in case you cray like that. Or if you cray-cray, there's **Mockup mode**. Nab a picture of the wall, then I got some quick tools for a quick mockup. And if you've got nothing to prove, you just want something copied onto paper perfectly, **Trace mode** allows you to use your phone as a lightbox, keeping your screen on with the brightness turned up, locking your image into place and blocking all touches until you're finished.

And then, there's a decent suite of pertinent design tools for prepping the one image you're placing — tone, colour balance, outline extraction, subject isolation. Compositing several images into one is the companion design app's job, not this one's. I could go on, but I feel like I already have.

## Key Features
*   **Offline-First:** No cloud dependencies for anything the app does — tracking, rendering, and design work are all local. The one thing that ever leaves the device is a crash report, and that's opt-in and off by default (Settings > Crash reports); see [`docs/en/PRIVACY_POLICY.md`](docs/en/PRIVACY_POLICY.md).
*   **Fingerprint Relocalization:** a C++17 native OpenCV pipeline (ORB/SuperPoint descriptors + PnP/RANSAC) fingerprints the marks you draw on the wall and snaps the overlay back after tracking loss — fully offline, no room pre-scan.
*   **Pocket-Ready (experimental, off by default):** drift correction and a self-extending fingerprint (so snap-back can survive the original reference being painted over) exist and can be turned on from Settings > diagnostic overlay, but neither has been validated on real hardware yet — see [Teleological SLAM](docs/TELEOLOGICAL_SLAM.md).
*   **Dual-Lens Aware:** auto-selects hardware stereo depth on devices that expose it; other devices track via ARCore's monocular pose with no separate depth estimate.
*   **Co-op Mode:** encrypted, QR-paired session sharing so a collaborator can watch the host's live canvas in AR. Currently host → guest only — a guest's own edits don't sync back yet.
*   **AzNavRail UI:** thumb-driven, one-handed navigation designed for artists holding a spray can.

## Modes
*   **AR Mural:** The core precision instrument for anchoring digital concepts to physical surfaces, tracked via the fingerprint relocalizer above.
*   **Mockup Mode:** Fast tools for visualizing layers and blend modes on top of static wall photos.
*   **Trace (Lightbox):** Full-brightness surface for copying onto paper with touch-lock, rail auto-retract, and physical-volume-button exit (Up, Down, Up, Down).
*   **Overlay:** Non-AR image tracing — your reference image overlaid on the live camera (CameraX) with adjustable opacity. On the small number of devices without ARCore, this mode can instead track a shape you mark on the wall using the same OpenCV pipeline, planar-only.
*   **Design:** Placement and legibility tools for the one design image being traced — opacity/brightness/contrast/saturation/colour-balance, invert, outline extraction, and subject isolation. There is exactly one design image; compositing several images into one is the companion design app's job, not this app's.

## Licensing
GraffitiXR is **source-available, not open source.** The app, the `core:*` modules, and the AR / SLAM / teleological engine are licensed under **PolyForm Noncommercial 1.0.0** ([`/LICENSE`](LICENSE)); the declared extension API surface and asset importers are **MIT** ([`docs/licenses/MIT.txt`](docs/licenses/MIT.txt)). The **compiled app is free for anyone to use, including paid commissions** — the noncommercial term binds re-use of the *source*, not muralists doing paid work. See [`docs/LICENSING.md`](docs/LICENSING.md) for the authoritative, path-by-path layout and precedence. Bundled third parties (OpenCV, ML Kit, …) keep their own upstream licenses.

## Architecture
Strictly decoupled multi-module Clean Architecture:
*   `:app` — Navigation, camera orchestration, and Hilt dependency injection.
*   `:feature:ar` — ARCore session management, `ArRenderer`, and SLAM data processing.
*   `:feature:editor` — Multi-layer image manipulation and adjustments.
*   `:feature:dashboard` — Project library, onboarding, and settings.
*   `:core:nativebridge` — Native C++ engine (`MobileGS`), JNI bridge, and relocalization threads. OpenCV itself is a Maven Central dependency (`org.opencv:opencv`), not a vendored module.
*   `:android_collaboration_module` — peer-to-peer networking for Co-op Mode (host → guest; see Key Features above).
*   `:core:data` / `:core:domain` / `:core:common` — Unified data layer and wearable abstraction.
*   `:core:design` — Shared Compose design system (reusable controls and overlays).

## Documentation
- [Architecture Overview](docs/ARCHITECTURE.md)
- [Native Engine Details](docs/NATIVE_ENGINE.md)
- [SLAM Setup & Relocalization](docs/SLAM_SETUP.md)
- [Teleological SLAM](docs/TELEOLOGICAL_SLAM.md)
- [Performance Guide](docs/performance.md)
- [Testing Strategy](docs/testing.md)
- [Data Formats](docs/data_formats.md)
- [Contributing](docs/contributing.md)
- [Release & Google Play Delivery](docs/RELEASE.md)

---
*Documentation updated on 2026-09-04 (second pass, same day): corrected the "multi-layer graphical creation" / "Design: Multi-layer image composition" claims — the multi-layer stack was removed; there is exactly one design image, and compositing several into one is the companion design app's job now, not this app's. Earlier same-day pass: corrected feature claims against the current codebase — see the audit this pass was based on for details. Removed AI Glasses Support (Meta Ray-Ban provider deleted; the Xreal provider can never activate), stencil generation, and GPU-accelerated Liquify, none of which have implementing code; marked snap-back/self-grow as the opt-in, unvalidated toggles they currently are; corrected Co-op Mode and Dual-Lens Aware to their real (narrower) behavior; fixed the crash-report claim to describe the new opt-in consent flow. Prior update: 2026-07-12, for AzNavRail 11.0 and the PolyForm/MIT licensing layout.*
