#!/usr/bin/env python3
"""Static build-contract checks for the published SphereSLAM/KPM dependency.

GraffitiXR no longer compiles the KPM tracker itself: the planar KPM engine (artoolkitX) is built
and published by the separate SphereSLAM library, and consumed here as a Maven artifact. This guard
protects the three invariants that keep that consumption sound at the build level:

1. The AR feature declares the published SphereSLAM artifact at a pinned, reproducible version (never
   a mutable -SNAPSHOT), so a release always resolves the same native tracker.
2. The app and the local native module still package both supported ARM ABIs, so the dependency's
   per-ABI `libsphereslam.so` has a matching slot in every APK split (a missing ABI would ship an AR
   build that cannot load the tracker on that hardware).
3. GraffitiXR uses only the supported SphereSLAM 0.23.5 surface: no direct nativebridge/internal
   imports, no hidden Observation construction, and no deprecated pose-property names.

The deeper native contract — artoolkitX source lists, JNI symbol wiring, R8 keep rules for the
public API — now lives in the SphereSLAM repository's own CI and in that artifact's consumer
ProGuard rules. The runtime proof that the real (non-stub) tracker actually reached the APK is in
check_sphereslam_apk.py.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
errors: list[str] = []


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def fail(message: str) -> None:
    errors.append(message)


# 1. The AR feature consumes the complete published SphereSLAM surface at one pinned version.
ar_gradle = read("feature/ar/build.gradle.kts")
expected_sphereslam_version = "0.23.5"
expected_modules = {"sphereslam", "overlay", "reloc"}
deps = dict(
    re.findall(
        r'com\.github\.HereLiesAz\.SphereSLAM:(sphereslam|overlay|reloc):([^"\']+)',
        ar_gradle,
    )
)
missing = sorted(expected_modules - deps.keys())
if missing:
    fail("feature/ar is missing SphereSLAM artifacts: " + ", ".join(missing))

for module, version in sorted(deps.items()):
    version = version.strip()
    if version.endswith("-SNAPSHOT") or version.lower() in {"main-snapshot", "master-snapshot"}:
        fail(f"SphereSLAM {module} dependency must pin a released version, not {version!r}.")
    if version != expected_sphereslam_version:
        fail(
            f"SphereSLAM {module} must remain aligned at {expected_sphereslam_version}; "
            f"found {version!r}."
        )

if len(set(deps.values())) > 1:
    fail(f"SphereSLAM artifacts must use one version; found {deps}.")

# 2. SphereSLAM 0.23.5 API migration stays on supported/public seams.
feature_ar_gradle = read("feature/ar/build.gradle.kts")
required_opt_in = "-opt-in=com.hereliesaz.sphereslam.reloc.ExperimentalSphereSlamRelocApi"
if required_opt_in not in feature_ar_gradle:
    fail("feature/ar must explicitly opt into SphereSLAM's experimental :reloc API.")

reloc_dep = 'api("com.github.HereLiesAz.SphereSLAM:reloc:0.23.5")'
if reloc_dep not in feature_ar_gradle:
    fail(
        "SphereSLAM :reloc must remain an api dependency at 0.23.5 because GraffitiXR's public "
        "Standalone* typealiases expand to reloc types."
    )

feature_ar_source_root = ROOT / "feature/ar/src"
kotlin_sources = "\n".join(
    p.read_text(encoding="utf-8")
    for p in feature_ar_source_root.rglob("*.kt")
)

for token, explanation in {
    "com.hereliesaz.sphereslam.nativebridge": "internal nativebridge package",
    "com.hereliesaz.sphereslam.common.InternalSphereSlamApi": "internal API opt-in marker",
    "SphereSlamTracker.Observation(": "hidden SphereSlamTracker.Observation constructor",
    "pageToCamera3x4": "deprecated raw KPM pose name",
}.items():
    if token in kotlin_sources:
        fail(f"feature/ar reaches {explanation}: found {token!r}.")

standalone_analyzer = read(
    "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/"
    "SphereSlamStandaloneTrackingAnalyzer.kt"
)
if "cameraFromCanonical" not in standalone_analyzer:
    fail("Standalone analyzer is not consuming SphereSLAM Pose.cameraFromCanonical.")

hybrid_correction = read(
    "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/anchor/HybridKpmCorrection.kt"
)
if "observation.cameraFromPage3x4" not in hybrid_correction:
    fail("Hybrid correction is not consuming SphereSLAM Observation.cameraFromPage3x4.")

# 3. Both supported ARM ABIs stay built/packaged so every APK split has a libsphereslam.so slot.
expected_abi_expr = 'abiFilters += listOf("arm64-v8a", "armeabi-v7a")'
for path in ("app/build.gradle.kts", "core/nativebridge/build.gradle.kts"):
    if expected_abi_expr not in read(path):
        fail(f"{path} must build/package both supported ARM ABIs.")

if errors:
    print("SphereSLAM build contract FAILED:", file=sys.stderr)
    for error in errors:
        print(f"  - {error}", file=sys.stderr)
    raise SystemExit(1)

print(
    "SphereSLAM build contract OK: all 0.23.5 artifacts are aligned, GraffitiXR stays on the "
    "supported public API seams, and both ARM ABIs remain packaged for the native library."
)
