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
3. GraffitiXR uses only the supported SphereSLAM public surface: no direct nativebridge/internal
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


# 1. The AR feature consumes the published SphereSLAM modules through the version catalog.
catalog = read("gradle/libs.versions.toml")
ar_gradle = read("feature/ar/build.gradle.kts")

version_match = re.search(r'^sphereSlam\s*=\s*"([^"]+)"', catalog, re.MULTILINE)
if not version_match:
    fail("gradle/libs.versions.toml must define the SphereSLAM version as sphereSlam.")
    sphere_slam_version = "unknown"
else:
    sphere_slam_version = version_match.group(1).strip()
    if (
        sphere_slam_version.endswith("-SNAPSHOT")
        or sphere_slam_version.lower() in {"main-snapshot", "master-snapshot"}
    ):
        fail(
            "SphereSLAM must resolve to a released version, not a moving snapshot: "
            f"{sphere_slam_version!r}."
        )

catalog_aliases = {
    "sphereslam-core": ("sphereslam", "api(libs.sphereslam.core)"),
    "sphereslam-overlay": ("overlay", "implementation(libs.sphereslam.overlay)"),
    "sphereslam-reloc": ("reloc", "api(libs.sphereslam.reloc)"),
    # MiDaS DepthEstimator (and its bundled ONNX assets) — exposed by the public standalone overlay.
    "sphereslam-models": ("models", "api(libs.sphereslam.models)"),
}
for alias, (artifact, gradle_use) in catalog_aliases.items():
    alias_pattern = re.compile(
        rf'^{re.escape(alias)}\s*=\s*\{{[^\n]*'
        rf'group\s*=\s*"com\.github\.HereLiesAz\.SphereSLAM"[^\n]*'
        rf'name\s*=\s*"{re.escape(artifact)}"[^\n]*'
        rf'version\.ref\s*=\s*"sphereSlam"[^\n]*\}}',
        re.MULTILINE,
    )
    if not alias_pattern.search(catalog):
        fail(
            f"gradle/libs.versions.toml must define {alias} for SphereSLAM artifact "
            f"{artifact!r} using version.ref = \"sphereSlam\"."
        )
    if gradle_use not in ar_gradle:
        fail(f"feature/ar must consume SphereSLAM {artifact!r} through {gradle_use}.")

# 2. SphereSLAM API migration stays on supported/public seams.
feature_ar_gradle = read("feature/ar/build.gradle.kts")
required_opt_in = "-opt-in=com.hereliesaz.sphereslam.reloc.ExperimentalSphereSlamRelocApi"
if required_opt_in not in feature_ar_gradle:
    fail("feature/ar must explicitly opt into SphereSLAM's experimental :reloc API.")

required_models_opt_in = "-opt-in=com.hereliesaz.sphereslam.models.ExperimentalSphereSlamModelsApi"
if required_models_opt_in not in feature_ar_gradle:
    fail("feature/ar must explicitly opt into SphereSLAM's experimental :models API.")

# The app's own MiDaS copy is gone; the model ships in the :models AAR. Native code still loads
# superpoint.onnx / zerodce.onnx / distortion_head.onnx from core/nativebridge assets, so only the
# MiDaS graph (Kotlin-only consumer) may be absent there.
for path in (
    "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/depth/DepthEstimator.kt",
    "core/nativebridge/src/main/assets/midas_v21_small_256_int8.onnx",
):
    if (ROOT / path).exists():
        fail(f"{path} was reintroduced; MiDaS depth comes from SphereSLAM :models.")

reloc_dep = "api(libs.sphereslam.reloc)"
if reloc_dep not in feature_ar_gradle:
    fail(
        "SphereSLAM :reloc must remain an api dependency because GraffitiXR's public "
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
    "SphereSlam.smokeTest(": "internal SphereSlam smoke-test hook",
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

# The hybrid gates/solve are SphereSLAM's sidecar.HybridKpmCorrection, which reads the public
# Observation.cameraFromPage3x4 itself. GraffitiXR hands it the tracker's published observation
# (never a hand-built one; tests use Observation.forTesting) and must not keep a private copy.
ar_renderer = read(
    "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt"
)
if "com.hereliesaz.sphereslam.sidecar.HybridKpmCorrection.solve(" not in ar_renderer:
    fail("ArRenderer no longer gates KPM observations through SphereSLAM sidecar.HybridKpmCorrection.solve.")
if "observation = hybridObservation" not in ar_renderer:
    fail("ArRenderer no longer passes the tracker's published Observation to HybridKpmCorrection.solve.")
for moved in (
    "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/anchor/HybridKpmCorrection.kt",
    "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/anchor/HybridPoseHistory.kt",
    "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/anchor/HybridPageFrame.kt",
    "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/HybridMetricKpmReference.kt",
):
    if (ROOT / moved).exists():
        fail(f"{moved} was reintroduced; use SphereSLAM's com.hereliesaz.sphereslam.sidecar package.")

# The fail-closed runtime capability probe is the library's SphereSlam.isOperational() (native
# entry points linked AND a calibrated KPM session creatable/ready/closable). GraffitiXR must gate
# standalone availability on it rather than on the weaker isAvailable() alone, and must not
# reintroduce a private copy of the probe.
ar_view_model = read(
    "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt"
)
if "SphereSlam.isOperational()" not in ar_view_model:
    fail("ArViewModel no longer resolves SphereSLAM availability through SphereSlam.isOperational().")
if (ROOT / "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/pose/SphereSlamRuntimeProbe.kt").exists():
    fail("SphereSlamRuntimeProbe was reintroduced; use the library's SphereSlam.isOperational().")

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
    f"SphereSLAM build contract OK: catalog version {sphere_slam_version}; GraffitiXR stays on "
    "the supported public API seams, and both ARM ABIs remain packaged for the native library."
)
