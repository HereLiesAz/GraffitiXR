#!/usr/bin/env python3
"""Validate the merged release manifest remains installable on non-ARCore hardware."""

from __future__ import annotations

import argparse
import glob
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"


def find_manifest(variant: str) -> Path:
    patterns = (
        f"app/build/intermediates/merged_manifest/{variant}/**/AndroidManifest.xml",
        f"app/build/intermediates/packaged_manifests/{variant}/**/AndroidManifest.xml",
    )
    candidates: list[str] = []
    for pattern in patterns:
        candidates.extend(glob.glob(pattern, recursive=True))
    if not candidates:
        raise SystemExit(f"No merged/packaged {variant} AndroidManifest.xml found.")
    return max((Path(p) for p in candidates), key=lambda p: p.stat().st_mtime)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", choices=("debug", "release"), required=True)
    args = parser.parse_args()

    manifest = find_manifest(args.variant)
    root = ET.parse(manifest).getroot()
    errors: list[str] = []

    features = {}
    for feature in root.findall("uses-feature"):
        name = feature.get(ANDROID + "name")
        if name:
            features[name] = feature.get(ANDROID + "required", "true")

    if features.get("android.hardware.camera.ar") != "false":
        errors.append("android.hardware.camera.ar must remain required=false.")

    unexpected_required = sorted(
        name
        for name, required in features.items()
        if required != "false" and name != "android.hardware.camera"
    )
    if unexpected_required:
        errors.append(
            "Unexpected required hardware feature(s): " + ", ".join(unexpected_required)
        )

    application = root.find("application")
    arcore_value = None
    if application is not None:
        for meta in application.findall("meta-data"):
            if meta.get(ANDROID + "name") == "com.google.ar.core":
                arcore_value = meta.get(ANDROID + "value")
                break
    if arcore_value != "optional":
        errors.append("com.google.ar.core meta-data must remain value=optional.")

    if errors:
        print(f"SphereSLAM manifest check FAILED: {manifest}", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        raise SystemExit(1)

    print(
        f"SphereSLAM manifest OK: {manifest}; camera.ar optional, ARCore optional, "
        "no unexpected required hardware features."
    )


if __name__ == "__main__":
    main()
