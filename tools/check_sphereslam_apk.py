#!/usr/bin/env python3
"""Verify that a built APK actually contains the embedded SphereSLAM/KPM runtime."""

from __future__ import annotations

import argparse
import glob
import sys
import zipfile
from pathlib import Path

EXPECTED_ABIS = ("arm64-v8a", "armeabi-v7a")
JNI_SYMBOLS = (
    b"Java_com_hereliesaz_graffitixr_nativebridge_KpmBridge_nativeKpmAvailable",
    b"Java_com_hereliesaz_graffitixr_nativebridge_KpmBridge_nativeCreateCalibratedSession",
    b"Java_com_hereliesaz_graffitixr_nativebridge_KpmBridge_nativeAddPlanarPage",
    b"Java_com_hereliesaz_graffitixr_nativebridge_KpmBridge_nativeMatchPlanar",
    b"Java_com_hereliesaz_graffitixr_nativebridge_KpmBridge_nativeDestroySession",
)
DEX_MARKERS = (
    b"Lcom/hereliesaz/graffitixr/nativebridge/KpmBridge;",
    b"Lcom/hereliesaz/sphereslam/SphereSlamStandaloneSession;",
)


def find_apk(variant: str) -> Path:
    candidates = sorted(
        glob.glob(f"app/build/outputs/apk/{variant}/**/*.apk", recursive=True)
    )
    if not candidates:
        raise SystemExit(f"No {variant} APK found under app/build/outputs/apk/{variant}")
    return Path(candidates[0])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", choices=("debug", "release"), required=True)
    args = parser.parse_args()

    apk = find_apk(args.variant)
    errors: list[str] = []

    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        for abi in EXPECTED_ABIS:
            lib_name = f"lib/{abi}/libgraffitixr.so"
            if lib_name not in names:
                errors.append(f"{apk}: missing {lib_name}")
                continue
            payload = archive.read(lib_name)
            for symbol in JNI_SYMBOLS:
                if symbol not in payload:
                    errors.append(
                        f"{apk}: {lib_name} missing exported KPM JNI symbol "
                        f"{symbol.decode('ascii')}"
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

    if errors:
        print("SphereSLAM APK packaging check FAILED:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        raise SystemExit(1)

    print(
        f"SphereSLAM APK packaging OK ({args.variant}): {apk}; "
        f"ABIs={','.join(EXPECTED_ABIS)}, KPM JNI symbols and preserved classes present."
    )


if __name__ == "__main__":
    main()
