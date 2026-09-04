#!/usr/bin/env python3
"""Static lock-discipline check for core/nativebridge/src/main/cpp/MobileGS.cpp.

This is the programmatic counterpart to the reasoning in GraffitiJNI.cpp's gEngineMutex
comment: gEngineMutex only protects the lifetime of the gSlamEngine pointer (it is not
what makes MobileGS's internal state thread-safe -- MobileGS protects its own state with
mMutex/mRelocMutex/std::atomic members). That means gEngineMutex is safe to convert from an
exclusive std::mutex to a std::shared_mutex (readers-share, only nativeInitialize/
nativeDestroy need exclusive access to the pointer itself) *only if* every plain
(non-atomic) MobileGS member is actually accessed under one of MobileGS's own mutexes
everywhere it's touched -- i.e. gEngineMutex's incidental global exclusivity is not silently
plugging a gap in MobileGS's internal locking.

This script finds every plain-member access in every MobileGS:: method body and flags any
one that happens with no MobileGS-owned lock_guard/unique_lock active in the enclosing scope.
It is a lexical/brace-depth heuristic, not a real C++ parser -- it will not catch every
possible bug, and it does not attempt to verify that *the right* mutex protects a member,
only that *some* mutex does. That is exactly the class of bug the gEngineMutex-to-
shared_mutex conversion cares about: a member touched under no engine-internal lock at all,
which today only isn't racy because gEngineMutex happens to make every JNI entry point run
one-at-a-time.

Run it after any change to MobileGS.cpp/.h, or as part of CI, to keep that invariant honest
instead of taking it on faith.

Exit code 0 = clean. Exit code 1 = at least one unguarded access found.
"""
from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
HEADER = REPO_ROOT / "core/nativebridge/src/main/cpp/include/MobileGS.h"
SOURCE = REPO_ROOT / "core/nativebridge/src/main/cpp/MobileGS.cpp"

MEMBER_DECL_RE = re.compile(
    r"^\s*(?:mutable\s+)?[A-Za-z_][\w:<>,\*\& ]*\bm([A-Z]\w*)\s*(\[[^\]]*\])?\s*(=.*)?;\s*(//.*)?$"
)
ATOMIC_DECL_RE = re.compile(r"std::atomic<")
LOCK_DECL_RE = re.compile(
    r"std::(?:lock_guard|unique_lock|shared_lock)<[^>]*>\s+\w+\s*\(\s*(m\w+)\s*\)"
)
FUNC_START_RE = re.compile(r"^[\w:<>,\*\&\s]+\bMobileGS::(\w+)\s*\(")

# Members that are legitimately touched outside any lock by design, with the reasoning
# cited below. Keep this list short and cited -- it is an explicit allowlist of
# *already-reviewed* races, not a way to silence the checker.
KNOWN_UNLOCKED_OK = {
    # Assigned exactly once, in initialize(), which only ever runs inside nativeInitialize --
    # the one JNI entry point (besides nativeDestroy) that takes gEngineMutex exclusively, so
    # no shared-lock reader can observe it mid-construction. Never reassigned afterward
    # (verified: grep for `mFeatureDetector *=` / `mMatcher *=` / `mL2Matcher *=` /
    # `mRelocThread *=` in MobileGS.cpp finds only the initialize() sites). Read-only for the
    # rest of the engine's life, so concurrent unlocked reads are fine.
    "mFeatureDetector",
    "mMatcher",
    "mL2Matcher",
    "mRelocThread",  # .joinable()/.join() in destroy(), itself gEngineMutex-exclusive.
    # std::condition_variable::notify_one() is safe to call without holding any lock by the
    # standard's own guarantee -- it's the *wait* side that needs the paired mutex, not notify.
    "mRelocCv",
    # mSuperPoint/mEnhancer/mDistortionHead are NOT write-once: nativeLoadSuperPoint/
    # nativeLoadDistortionHead/nativeLoadLowLightEnhancer can reload them at any time via their
    # .load() calls, and every other reader here (runRelocPass, tryUpdateFingerprint,
    # getSuperPointFeatures, getFingerprintKeypoints, generateFingerprint) touches them with no
    # MobileGS-internal lock. Safety depends on a cross-file invariant this script cannot see:
    # GraffitiJNI.cpp's nativeLoadSuperPoint/nativeLoadDistortionHead/nativeLoadLowLightEnhancer
    # must take gEngineMutex as std::unique_lock (not shared_lock), so a live reload excludes
    # every reader instead of racing them. That is enforced today -- see gEngineMutex's own
    # comment in GraffitiJNI.cpp -- but a future edit to those three JNI entry points could
    # silently break it without this script noticing. Listed here anyway, with this citation,
    # rather than pretending the check is complete.
    "mSuperPoint",
    "mEnhancer",
    "mDistortionHead",
}


@dataclass
class Finding:
    function: str
    member: str
    line_no: int
    line: str


def parse_members(header_text: str) -> tuple[set[str], set[str]]:
    plain: set[str] = set()
    atomic: set[str] = set()
    for line in header_text.splitlines():
        m = MEMBER_DECL_RE.match(line)
        if not m:
            continue
        name = "m" + m.group(1)
        if ATOMIC_DECL_RE.search(line):
            atomic.add(name)
        else:
            plain.add(name)
    return plain, atomic


def split_functions(source_text: str) -> list[tuple[str, int, list[str]]]:
    """Return [(function_name, start_line_no, body_lines)] for each MobileGS:: method."""
    lines = source_text.splitlines()
    functions: list[tuple[str, int, list[str]]] = []
    i = 0
    n = len(lines)
    while i < n:
        m = FUNC_START_RE.match(lines[i])
        if not m:
            i += 1
            continue
        name = m.group(1)
        start = i
        # Find the opening brace, which may be on a later line for multi-line signatures.
        j = i
        while j < n and "{" not in lines[j]:
            if lines[j].rstrip().endswith(";"):
                break  # this was a declaration, not a definition
            j += 1
        if j >= n or "{" not in lines[j]:
            i += 1
            continue
        depth = 0
        body: list[str] = []
        k = j
        started = False
        while k < n:
            line = lines[k]
            depth += line.count("{") - line.count("}")
            body.append(line)
            if "{" in line:
                started = True
            if started and depth <= 0:
                break
            k += 1
        functions.append((name, start + 1, body))
        i = k + 1
    return functions


def check_function(name: str, body: list[str], plain_members: set[str]) -> list[Finding]:
    findings: list[Finding] = []
    depth = 0
    # Stack of (depth_at_which_lock_was_declared,) -- any depth >= a locked depth is "guarded".
    lock_depths: list[int] = []
    member_pattern = re.compile(r"\bm[A-Z]\w*\b")

    for offset, raw_line in enumerate(body):
        line = raw_line
        # Strip line comments (crude, but this codebase doesn't nest // inside strings here).
        code = line.split("//", 1)[0]

        pre_depth = depth
        depth += code.count("{") - code.count("}")

        # Pop lock scopes that just closed.
        while lock_depths and lock_depths[-1] > depth:
            lock_depths.pop()

        if LOCK_DECL_RE.search(code):
            lock_depths.append(pre_depth if pre_depth > 0 else depth)

        guarded = bool(lock_depths)
        for match in member_pattern.finditer(code):
            member = match.group(0)
            if member not in plain_members:
                continue
            if guarded:
                continue
            findings.append(Finding(name, member, offset, line.strip()))
    return findings


def main() -> int:
    header_text = HEADER.read_text()
    source_text = SOURCE.read_text()

    plain_members, atomic_members = parse_members(header_text)
    if not plain_members and not atomic_members:
        print("ERROR: parsed zero MobileGS members -- header regex is out of sync.", file=sys.stderr)
        return 2

    functions = split_functions(source_text)
    if not functions:
        print("ERROR: parsed zero MobileGS:: function bodies -- source regex is out of sync.", file=sys.stderr)
        return 2

    all_findings: list[Finding] = []
    for name, _start, body in functions:
        for f in check_function(name, body, plain_members):
            if f.member in KNOWN_UNLOCKED_OK:
                continue
            all_findings.append(f)

    if not all_findings:
        print(
            f"OK: {len(functions)} MobileGS:: functions checked, "
            f"{len(plain_members)} plain members, {len(atomic_members)} atomic members -- "
            "no *newly* unguarded plain-member access found (beyond the cited, reviewed "
            f"entries in KNOWN_UNLOCKED_OK: {', '.join(sorted(KNOWN_UNLOCKED_OK))}).\n"
            "Caveat: this proves every access has SOME MobileGS-owned lock active, not that "
            "it's the RIGHT one -- it cannot detect two call sites guarded by different "
            "mutexes for the same member (that class of bug needs a human read; see "
            "scheduleRelocCheck's mViewMatrix snapshot for a real example that was fixed by "
            "hand, not caught by this script)."
        )
        return 0

    print(
        f"FOUND {len(all_findings)} unguarded plain-member access(es) "
        f"(no MobileGS-owned lock_guard/unique_lock active):\n"
    )
    for f in all_findings:
        print(f"  MobileGS::{f.function}: {f.member}")
        print(f"      {f.line}")
    print(
        "\nThese are exactly the accesses gEngineMutex's global exclusivity is currently "
        "papering over. Give each one its own lock (matching the mutex that guards the "
        "member's writer) before relying on gEngineMutex being a shared_mutex, or add it to "
        "KNOWN_UNLOCKED_OK in this script with a cited reason if it's an already-accepted "
        "approximation."
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
