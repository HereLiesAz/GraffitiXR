#!/usr/bin/env python3
"""Static invariants for GraffitiXR's dual ARCore/SphereSLAM architecture.

This is intentionally a narrow source-level guard, not a Kotlin parser. It protects three product
invariants that are easy to accidentally regress during UI/renderer refactors:

1. AR mode stays reachable even when ARCore is unavailable.
2. The standalone CameraX + SphereSLAM branch never constructs or imports an ARCore Session.
3. Hybrid SphereSLAM remains observation-only in ArRenderer until an explicit fusion component is
   introduced; it must not write directly into the primary ARCore view/projection matrices.

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
branch_end = main_screen.find("} else {", branch_start + 1) if branch_start >= 0 else -1
if branch_start < 0 or branch_end < 0:
    fail("Could not locate MainScreen's non-ARCore standalone branch.")
else:
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

if FAILURES:
    print("SphereSLAM architecture invariant check FAILED:", file=sys.stderr)
    for item in FAILURES:
        print(f"  - {item}", file=sys.stderr)
    raise SystemExit(1)

print(
    "SphereSLAM architecture invariant check OK: AR mode remains reachable, standalone is "
    "ARCore-independent, and hybrid KPM is observation-only."
)
