#!/usr/bin/env python3
"""Static build-contract checks for the embedded SphereSLAM/artoolkitX path."""

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


expected_abi_expr = 'abiFilters += listOf("arm64-v8a", "armeabi-v7a")'
for path in ("app/build.gradle.kts", "core/nativebridge/build.gradle.kts"):
    if expected_abi_expr not in read(path):
        fail(f"{path} must build/package both supported ARM ABIs.")

cmake = read("core/nativebridge/src/main/cpp/CMakeLists.txt")


def source_list(name: str) -> list[str]:
    match = re.search(rf"set\({name}\s+(.*?)\n\s*\)", cmake, re.S)
    if not match:
        fail(f"Missing CMake source list {name}.")
        return []
    entries = []
    for raw in match.group(1).splitlines():
        value = raw.strip()
        if value and not value.startswith("#"):
            entries.append(value)
    return entries


all_sources: list[tuple[str, str]] = []
for group in ("ARX_KPM_SRC", "ARX_AR_SRC", "ARX_SUPPORT_SRC"):
    for source in source_list(group):
        all_sources.append((group, source))

seen: dict[str, str] = {}
for group, source in all_sources:
    if source in seen:
        fail(f"Duplicate artoolkitX source {source} appears in {seen[source]} and {group}.")
    else:
        seen[source] = group

for required in (
    "${ARX_ROOT}/ARUtil/file_utils.c",
    "${ARX_ROOT}/ARUtil/uuid/uuid_sha1.c",
    "${ARX_ROOT}/ARUtil/ioapi.c",
    "${ARX_ROOT}/ARUtil/unzip.c",
    "${ARX_ROOT}/ARUtil/zip.c",
):
    if required not in seen:
        fail(f"Required artoolkitX support source is missing: {required}")

graffiti_lib = re.search(r"add_library\(graffitixr\s+SHARED\s+(.*?)\n\)", cmake, re.S)
if not graffiti_lib:
    fail("Could not locate graffitixr native source list.")
else:
    if graffiti_lib.group(1).count("KpmBridge.cpp") != 1:
        fail("KpmBridge.cpp must appear exactly once in the graffitixr shared library.")

if cmake.count("target_link_libraries(graffitixr arx_kpm)") != 1:
    fail("graffitixr must link the embedded arx_kpm target exactly once.")

native_rules = read("core/nativebridge/consumer-rules.pro")
if "-keep class com.hereliesaz.graffitixr.nativebridge.KpmBridge { *; }" not in native_rules:
    fail("KpmBridge needs an explicit consumer keep rule for static-name JNI entry points.")
if "native <methods>;" not in native_rules:
    fail("Native bridge consumer rules must keep native method names.")

sphere_gradle = read("sphereslam/build.gradle.kts")
sphere_rules = read("sphereslam/consumer-rules.pro")
if 'consumerProguardFiles("consumer-rules.pro")' not in sphere_gradle:
    fail(":sphereslam must publish its consumer rules.")
if "-keep class com.hereliesaz.sphereslam.** { *; }" not in sphere_rules:
    fail(":sphereslam public API keep rule is missing.")

if errors:
    print("SphereSLAM build contract FAILED:", file=sys.stderr)
    for error in errors:
        print(f"  - {error}", file=sys.stderr)
    raise SystemExit(1)

print(
    "SphereSLAM build contract OK: ARM ABIs match, native source lists are unique, "
    "required artoolkitX support sources are present, and R8/JNI keep rules are wired."
)
