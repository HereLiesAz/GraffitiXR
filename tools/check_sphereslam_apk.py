#!/usr/bin/env python3
"""Verify that a built APK actually contains the real (non-stub) SphereSLAM/KPM runtime.

Post-decouple the KPM tracker ships in the published SphereSLAM artifact as `libsphereslam.so`
(from its :core:nativebridge module), merged into the APK alongside the Kotlin API classes. This
guard fails the release if:

 - `libsphereslam.so` is missing for either supported ABI, or
 - it is present but the KPM JNI entry points are absent, or
 - it is the artoolkitX-less *stub* (built when the submodule is not checked out), which exports the
   same symbols but whose `nativeKpmAvailable` always returns false — i.e. non-functional AR.

The stub is caught two ways: its self-identifying marker string must be absent, and a real
artoolkitX KPM internal symbol must be present. The dex check confirms R8 preserved the JNI bridge class
under its exact name (kept by :core:nativebridge's consumer ProGuard rules). Since SphereSLAM 0.24.0
the library no longer ships a blanket `-keep com.hereliesaz.sphereslam.**`, so the public API classes
the app calls are retained by reachability but may be renamed; for those the check accepts either the
original dex descriptor or an R8 mapping entry proving the class survived shrinking.
"""

from __future__ import annotations

import argparse
import glob
import sys
import zipfile
from pathlib import Path

EXPECTED_ABIS = ("arm64-v8a", "armeabi-v7a")
NATIVE_LIB = "libsphereslam.so"
JNI_SYMBOLS = (
    b"Java_com_hereliesaz_sphereslam_nativebridge_KpmBridge_nativeKpmAvailable",
    b"Java_com_hereliesaz_sphereslam_nativebridge_KpmBridge_nativeCreateCalibratedSession",
    b"Java_com_hereliesaz_sphereslam_nativebridge_KpmBridge_nativeAddPlanarPage",
    b"Java_com_hereliesaz_sphereslam_nativebridge_KpmBridge_nativeMatchPlanar",
    b"Java_com_hereliesaz_sphereslam_nativebridge_KpmBridge_nativeDestroySession",
)
# The stub libsphereslam.so self-identifies with this message (see SphereSLAM KpmBridge.cpp's
# !HAVE_ARX_KPM branch). A real build links artoolkitX and exports its KPM C API instead.
STUB_MARKER = b"built without HAVE_ARX_KPM"
REAL_KPM_SYMBOL = b"kpmCreateHandle"
# JNI contract: must survive R8 under its exact name.
DEX_MARKERS = (b"Lcom/hereliesaz/sphereslam/nativebridge/KpmBridge;",)
# Public API the app calls: must survive R8, but may be obfuscated (no blanket keep since 0.24.0).
RETAINED_API_CLASSES = ("com.hereliesaz.sphereslam.SphereSlamTracker",)


def find_apk(variant: str) -> Path:
    candidates = sorted(
        glob.glob(f"app/build/outputs/apk/{variant}/**/*.apk", recursive=True)
    )
    if not candidates:
        raise SystemExit(f"No {variant} APK found under app/build/outputs/apk/{variant}")
    return Path(candidates[0])


def find_mapping(variant: str) -> str | None:
    candidates = sorted(glob.glob(f"app/build/outputs/mapping/{variant}/**/mapping.txt", recursive=True))
    if not candidates:
        return None
    return Path(candidates[0]).read_text(encoding="utf-8", errors="replace")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", choices=("debug", "release"), required=True)
    args = parser.parse_args()

    apk = find_apk(args.variant)
    errors: list[str] = []

    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        for abi in EXPECTED_ABIS:
            lib_name = f"lib/{abi}/{NATIVE_LIB}"
            if lib_name not in names:
                errors.append(f"{apk}: missing {lib_name} (SphereSLAM KPM artifact not packaged)")
                continue
            payload = archive.read(lib_name)
            for symbol in JNI_SYMBOLS:
                if symbol not in payload:
                    errors.append(
                        f"{apk}: {lib_name} missing exported KPM JNI symbol "
                        f"{symbol.decode('ascii')}"
                    )
            if STUB_MARKER in payload:
                errors.append(
                    f"{apk}: {lib_name} is the artoolkitX-less KPM stub "
                    "(tracking disabled); the published SphereSLAM build did not compile KPM."
                )
            if REAL_KPM_SYMBOL not in payload:
                errors.append(
                    f"{apk}: {lib_name} lacks artoolkitX KPM internals "
                    f"({REAL_KPM_SYMBOL.decode('ascii')}); not a real tracker build."
                )

        dex_names = sorted(
            name for name in names if name.startswith("classes") and name.endswith(".dex")
        )
        if not dex_names:
            errors.append(f"{apk}: no classes*.dex found")
        else:
            dex_payload = b"".join(archive.read(name) for name in dex_names)
            for marker in DEX_MARKERS:
                if marker not in dex_payload:
                    errors.append(
                        f"{apk}: R8/D8 output missing preserved class descriptor "
                        f"{marker.decode('ascii')}"
                    )
            mapping = find_mapping(args.variant)
            for class_name in RETAINED_API_CLASSES:
                descriptor = f"L{class_name.replace('.', '/')};".encode("ascii")
                if descriptor in dex_payload:
                    continue
                if mapping is not None and f"{class_name} -> " in mapping:
                    continue
                errors.append(
                    f"{apk}: {class_name} absent from dex and not in the R8 mapping "
                    f"({'none found' if mapping is None else 'stripped'})"
                )

    if errors:
        print("SphereSLAM APK packaging check FAILED:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        raise SystemExit(1)

    print(
        f"SphereSLAM APK packaging OK ({args.variant}): {apk}; "
        f"ABIs={','.join(EXPECTED_ABIS)}, real (non-stub) KPM native, JNI symbols and "
        "preserved API classes present."
    )


if __name__ == "__main__":
    main()
