#!/usr/bin/env python3
"""Static invariants for GraffitiXR's dual ARCore/SphereSLAM architecture.

This is intentionally a narrow source-level guard, not a Kotlin parser. It protects three product
invariants that are easy to accidentally regress during UI/renderer refactors:

1. AR mode stays reachable even when ARCore is unavailable.
2. The standalone CameraX + SphereSLAM branch never constructs or imports an ARCore Session.
3. Hybrid SphereSLAM remains observation-only in ArRenderer until an explicit fusion component is
   introduced; it must not write directly into the primary ARCore view/projection matrices.
4. MobileGS tracking validity is backend-neutral, and standalone self-grow/map points remain in the
   centred SphereSLAM fingerprint frame rather than passing through an ARCore-world conversion.
5. The standalone runtime never reaches ARCore-only hit-test/depth/anchor/perception APIs and exposes
   an explicit canonical-wall hit-test seam instead.

If a future, legitimate architecture change trips this check, update the check together with the
new explicit seam. Do not simply weaken/remove it.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FAILURES: list[str] = []


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def fail(message: str) -> None:
    FAILURES.append(message)


main_activity = read("app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt")
main_screen = read("app/src/main/java/com/hereliesaz/graffitixr/MainScreen.kt")
ar_renderer = read(
    "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt"
)
standalone_analyzer = read(
    "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/SphereSlamStandaloneTrackingAnalyzer.kt"
)
mobile_gs_cpp = read("core/nativebridge/src/main/cpp/MobileGS.cpp")
mobile_gs_h = read("core/nativebridge/src/main/cpp/include/MobileGS.h")
graffiti_jni = read("core/nativebridge/src/main/cpp/GraffitiJNI.cpp")
slam_manager = read(
    "core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt"
)
ar_view_model = read(
    "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt"
)
standalone_wall_hit = read(
    "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/StandaloneWallHitTest.kt"
)

# 1. AR reachability must not be gated by ARCore availability.
mode_assignment = re.search(r"val\s+showArModeEntry\s*=\s*([^\n]+)", main_activity)
if not mode_assignment:
    fail("MainActivity no longer declares showArModeEntry; review AR-mode reachability.")
else:
    expression = mode_assignment.group(1).strip()
    if expression != "true":
        fail(
            "AR mode must stay reachable on non-ARCore devices; "
            f"showArModeEntry is currently: {expression}"
        )

# 2. Standalone classes and the non-ARCore MainScreen branch may not touch ARCore Session.
standalone_paths = [
    "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/SphereSlamStandaloneOverlay.kt",
    "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/SphereSlamStandaloneTrackingAnalyzer.kt",
    "sphereslam/src/main/java/com/hereliesaz/sphereslam/SphereSlamStandaloneSession.kt",
]
for path in standalone_paths:
    text = read(path)
    if "com.google.ar.core" in text:
        fail(f"{path} imports/references ARCore; standalone must remain ARCore-independent.")
    if re.search(r"\bSession\s*\(", text):
        fail(f"{path} constructs Session(...); standalone must not construct an ARCore Session.")

branch_start = main_screen.find("} else if (!arUiState.isArCoreAvailable) {")
branch_end_marker = "\n                    } else {\n                        var glView"
branch_end = main_screen.find(branch_end_marker, branch_start + 1) if branch_start >= 0 else -1
if branch_start < 0 or branch_end < 0:
    fail("Could not locate MainScreen's non-ARCore standalone branch.")
else:
    # Do not search for the first nested "} else {" inside the branch: Compose callbacks contain
    # their own conditionals. Anchor the end at the outer ARCore branch's distinctive glView setup.
    standalone_branch = main_screen[branch_start:branch_end]
    forbidden = {
        "Session(": "constructs an ARCore Session",
        "ArRenderer(": "constructs the ARCore renderer",
        "setArMode(true": "enables the ARCore-backed runtime",
    }
    for token, reason in forbidden.items():
        if token in standalone_branch:
            fail(f"MainScreen standalone branch {reason}: found {token!r}.")
    if "SphereSlamStandaloneOverlay(" not in standalone_branch:
        fail("MainScreen non-ARCore branch no longer mounts SphereSlamStandaloneOverlay.")

# 3. Hybrid ArRenderer keeps ARCore as primary and KPM observation-only.
if "ArCorePoseSource()" not in ar_renderer:
    fail("ArRenderer no longer declares ArCorePoseSource as the hybrid primary pose source.")
if "poseSource.sample(viewMatrix, projMatrix" not in ar_renderer:
    fail("ArRenderer primary view/projection matrices no longer flow through poseSource.sample().")

allowed_tracker_calls = {"reset", "setReference", "submitFrame", "close"}
tracker_calls = set(re.findall(r"sphereSlamTracker\.(\w+)\s*\(", ar_renderer))
unexpected = sorted(tracker_calls - allowed_tracker_calls)
if unexpected:
    fail(
        "ArRenderer now consumes SphereSLAM beyond the observation-only sidecar contract "
        f"({', '.join(unexpected)}). Route observations through an explicit fusion component "
        "before changing primary renderer pose."
    )

for direct_token in ("latestObservation", "pageToCamera3x4", "SphereSlamPoseMath"):
    if direct_token in ar_renderer:
        fail(
            f"ArRenderer directly references {direct_token}; hybrid KPM correction must enter "
            "through the explicit fusion seam rather than primary matrices."
        )

# 4. MobileGS frame/tracking state must be backend-neutral and preserve standalone object space.
for path, text in {
    "MobileGS.cpp": mobile_gs_cpp,
    "MobileGS.h": mobile_gs_h,
    "GraffitiJNI.cpp": graffiti_jni,
    "SlamManager.kt": slam_manager,
    "ArRenderer.kt": ar_renderer,
    "SphereSlamStandaloneTrackingAnalyzer.kt": standalone_analyzer,
}.items():
    for legacy in ("setArCoreTrackingState", "mIsArCoreTracking", "nativeSetArCoreTrackingState"):
        if legacy in text:
            fail(f"{path} still contains legacy backend-specific tracking symbol {legacy}.")

if "setTrackingPoseValid(acceptedView != null)" not in standalone_analyzer:
    fail(
        "Standalone analyzer no longer marks MobileGS pose validity from the accepted KPM pose "
        "for the same frame."
    )
if "mWallKeypoints3D.push_back(newPts[i]);" not in mobile_gs_cpp:
    fail(
        "MobileGS self-grow no longer appends back-projected points directly in fingerprint space; "
        "review standalone centred-page frame preservation."
    )
if "mMapPoints3D.push_back(cv::Point3f(P.x, P.y, P.z));" not in mobile_gs_cpp:
    fail(
        "MobileGS feature-map growth no longer stores the fingerprint-frame intersection directly; "
        "review standalone centred-page frame preservation."
    )

# 5. Standalone equivalents must fail closed instead of reaching ARCore-only APIs.
standalone_forbidden = {
    "frame.hitTest": "ARCore Frame.hitTest",
    "acquireDepthImage": "ARCore Depth API",
    "createAnchor(": "ARCore Anchor creation",
    "PlaneRenderer": "ARCore plane renderer",
    "PointCloudRenderer": "ARCore point-cloud renderer",
    "ArDebugRenderer": "ARCore perception debug renderer",
}
for path in standalone_paths:
    text = read(path)
    for token, meaning in standalone_forbidden.items():
        if token in text:
            fail(f"{path} reaches {meaning} through token {token!r}; standalone must use an explicit equivalent.")

if "object StandaloneWallHitTest" not in standalone_wall_hit:
    fail("Standalone canonical wall hit-test seam is missing.")
for required in ("wallLocked", "coveredRegions", "Vec3(x, y, 0f)"):
    if required not in standalone_wall_hit:
        fail(f"StandaloneWallHitTest lost required fail-closed/canonical-wall contract token {required!r}.")

if 'GlassesSessionState.Fallback("Standalone wall calibration for glasses is not implemented yet")' not in ar_view_model:
    fail("Standalone wearable calibration no longer fails closed before the ARCore hit-test path.")
if (
    "probe: skipped; ARCore-only depth probe requires positively resolved ARCore capability"
    not in ar_view_model
):
    fail("ARCore-only stereo/depth probe no longer requires positively resolved ARCore capability.")
if "(!capability.isArCoreAvailabilityResolved || !capability.isArCoreAvailable)" not in ar_view_model:
    fail("setArMode no longer fails closed while ARCore availability is unresolved/unavailable.")
for required in (
    "isDepthApiSupported = false",
    "isHardwareStereoActive = false",
    "currentCenterDepth = -1f",
):
    if required not in ar_view_model:
        fail(f"Standalone tracking no longer clears stale ARCore depth state: missing {required!r}.")

if FAILURES:
    print("SphereSLAM architecture invariant check FAILED:", file=sys.stderr)
    for item in FAILURES:
        print(f"  - {item}", file=sys.stderr)
    raise SystemExit(1)

print(
    "SphereSLAM architecture invariant check OK: AR mode remains reachable, standalone is "
    "ARCore-independent, hybrid KPM is observation-only, and ARCore-only perception APIs stay "
    "behind explicit backend boundaries."
)
