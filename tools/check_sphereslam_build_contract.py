#!/usr/bin/env python3
"""Static build-contract checks for the published SphereSLAM/KPM dependency.

GraffitiXR no longer compiles the KPM tracker itself: the planar KPM engine (artoolkitX) is built
and published by the separate SphereSLAM library, and consumed here as a Maven artifact. This guard
protects the two invariants that keep that consumption sound at the build level:

1. The AR feature declares the published SphereSLAM artifact at a pinned, reproducible version (never
   a mutable -SNAPSHOT), so a release always resolves the same native tracker.
2. The app and the local native module still package both supported ARM ABIs, so the dependency's
   per-ABI `libsphereslam.so` has a matching slot in every APK split (a missing ABI would ship an AR
   build that cannot load the tracker on that hardware).

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

# 2. Both supported ARM ABIs stay built/packaged so every APK split has a libsphereslam.so slot.
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
    "SphereSLAM build contract OK: feature/ar consumes the published KPM artifact at a pinned "
    "version and both ARM ABIs remain packaged for its per-ABI native library."
)
