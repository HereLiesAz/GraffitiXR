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

WHAT THIS SCRIPT STILL DOES NOT COVER (found the hard way -- a glee audit caught all of this,
including two items previously listed here as known gaps and since closed: brace-initialized
members and header-inline method bodies are now handled; see the fixes below):
  1. JNI-LEVEL GLOBALS. This script only parses MobileGS.h/MobileGS.cpp -- it has no idea
     GraffitiJNI.cpp exists. `gLastColorFrame`, a plain `cv::Mat` global written by both
     nativeFeedYuvFrame and nativeFeedColorFrame, needed its own mutex (gColorFrameMutex) once
     those two entry points stopped sharing gEngineMutex's exclusive lock -- this script's "OK"
     said nothing about it, because it never looked at that file. Any new JNI-level mutable
     global needs the same manual check this script cannot do.
  2. CLASSES DECLARED ELSEWHERE. SuperPointDetector/DistortionHead/LowLightEnhancer (each in
     their own .h/.cpp under core/nativebridge/src/main/cpp/) each declare their own internal
     mMutex, which is what actually protects a live model reload (nativeLoadSuperPoint etc.)
     against the background reloc-thread worker calling into them -- gEngineMutex's exclusive
     lock on the loader entry points only protects them against JNI-entry readers, not that
     thread. This script cannot see any of it; don't read its clean output as proof those
     classes are safe.

A second glee audit, run specifically to distrust this script's own coverage claims, measured
that the two "handled" gaps below were in fact still open at the time: the header declares
roughly 30 std::atomic members, and this script's member regex saw only 2 of them before the
brace-init fix; getMapPointCount()/getWallKeypointCount() (both inline in MobileGS.h, both
touching plain members) were entirely unexamined before the header-inline-body fix; the
destructor (~MobileGS) was skipped by FUNC_START_RE before the tilde fix. All three are fixed
now (verify by re-running: the OK message reports the actual counts), but the fact that a
"docstring says it's fixed" claim was wrong before is itself the reason to verify the counts
look right after any future change to this script, not just trust its own text.

A THIRD pass found the header-inline-body fix above was itself still incomplete, plus a
second, independent bug in the guard check: the header scanner only matched a method whose
ENTIRE body was on one line, silently skipping any multi-line inline method (e.g.
evalSyncEveryN/getCorroborationConfidence/decayCorroboration -- all inline in MobileGS.h, all
multiple lines) with no warning if one of them ever touched a plain member unguarded; and
guardedness was computed once per LINE rather than by token position, so `consume(mFoo);
lock_guard<...> lock(mMutex);` (member touched BEFORE the lock) and `{ lock_guard<...>
lock(mMutex); } return mFoo;` (member touched AFTER that lock's scope closed, same line) both
read as "a lock is somewhere on this line" and were silently treated as guarded either way.
Fixed by replacing the two brace-tracking splitters with one shared split_brace_bodies() (any
body length, either file) and replacing check_function's per-line guardedness with a single
ordered token scan (braces/locks/members interleaved by actual position, not by line).

In short: a clean run means "no unguarded access to a plain MobileGS.h member (including
brace-initialized ones) in a MobileGS:: function body, whether defined in MobileGS.cpp or
inline in the header" -- not "this file's locking is correct" in any deeper sense, and
definitely not "GraffitiJNI.cpp's locking is correct" (see gap 1 above).

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
    r"^\s*(?:mutable\s+)?[A-Za-z_][\w:<>,\*\& ]*\bm([A-Z]\w*)\s*(\[[^\]]*\])?"
    r"\s*(=.*|\{[^{}]*\})?;\s*(//.*)?$"
)
ATOMIC_DECL_RE = re.compile(r"std::atomic<")
FUNC_START_RE = re.compile(r"^[\w:<>,\*\&\s]*\bMobileGS::(~?\w+)\s*\(")
# A method defined inline in the header -- unqualified (no `MobileGS::`), unlike FUNC_START_RE.
# Deliberately lenient (name + opening paren is enough, mirroring FUNC_START_RE's own leniency
# for multi-line signatures): a header line this matches that turns out to be a bare prototype
# (ends in `;` before any `{` is found) or something else entirely is harmlessly skipped by
# split_brace_bodies' own bail-on-`;` check, the same way it already tolerates FUNC_START_RE
# matching a prototype in the .cpp file.
HEADER_METHOD_START_RE = re.compile(r"^\s*[\w:<>,\*\&]+[\s\*\&]+(\w+)\s*\(")

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
    # getMutex() returns a reference to mMutex ITSELF so several GraffitiJNI.cpp call sites can
    # lock_guard it externally (grep getMutex() -- all four callers do exactly that). Requiring
    # a lock already be held to read a reference to the lock object would be circular; this is
    # the correct, intentional escape hatch that pattern needs, not an unguarded access to a
    # protected member. Found by split_header_inline_functions() once it started examining
    # header-inline bodies -- a real new finding from that fix, reviewed and allowlisted.
    "mMutex",
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


def split_brace_bodies(text: str, start_re: "re.Pattern[str]") -> list[tuple[str, int, list[str]]]:
    """Return [(function_name, start_line_no, body_lines)] for each block whose start line
    matches start_re, by brace-depth-tracking the body -- handles bodies of any length, not just
    single-line ones (that gap let split_header_inline_functions silently skip every multi-line
    inline method in MobileGS.h, e.g. evalSyncEveryN/getCorroborationConfidence/decayCorroboration,
    with no warning if one of them ever touched a plain member unguarded)."""
    lines = text.splitlines()
    functions: list[tuple[str, int, list[str]]] = []
    i = 0
    n = len(lines)
    while i < n:
        m = start_re.match(lines[i])
        if not m:
            i += 1
            continue
        name = m.group(1)
        start = i
        # Find the opening brace, which may be on a later line for multi-line signatures.
        j = i
        while j < n and "{" not in lines[j]:
            if lines[j].rstrip().endswith(";"):
                break  # this was a declaration/prototype, not a definition
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


# Tokens relevant to lock-scope tracking, in a single pass so brace/lock/member order WITHIN a
# line is respected -- the previous per-line design computed one `guarded` bool for an entire
# line after scanning it for a lock anywhere on it, so `consume(mFoo); lock_guard<...>
# lock(mMutex);` (member touched BEFORE the lock is even declared) and `{ lock_guard<...>
# lock(mMutex); } return mFoo;` (member touched AFTER that lock's scope already closed, same
# line) both read as "a lock is present on this line" and were silently treated as guarded.
TOKEN_RE = re.compile(
    r"(?P<lbrace>\{)"
    r"|(?P<rbrace>\})"
    r"|(?P<lock>std::(?:lock_guard|unique_lock|shared_lock)<[^>]*>\s+\w+\s*\(\s*m\w+\s*\))"
    r"|(?P<member>\bm[A-Z]\w*\b)"
)


def check_function(name: str, body: list[str], plain_members: set[str]) -> list[Finding]:
    findings: list[Finding] = []
    depth = 0
    # Stack of depths at which a lock_guard/unique_lock/shared_lock was declared -- any depth
    # equal to the top of this stack (we never re-enter a shallower depth without popping first,
    # since braces are processed in the same pass) means "currently inside that lock's scope".
    lock_depths: list[int] = []

    for offset, raw_line in enumerate(body):
        # Strip line comments (crude, but this codebase doesn't nest // inside strings here).
        code = raw_line.split("//", 1)[0]
        for match in TOKEN_RE.finditer(code):
            kind = match.lastgroup
            if kind == "lbrace":
                depth += 1
            elif kind == "rbrace":
                depth -= 1
                while lock_depths and lock_depths[-1] > depth:
                    lock_depths.pop()
            elif kind == "lock":
                lock_depths.append(depth)
            else:  # member
                member = match.group(0)
                if member not in plain_members:
                    continue
                if lock_depths:
                    continue
                findings.append(Finding(name, member, offset, raw_line.strip()))
    return findings


def main() -> int:
    header_text = HEADER.read_text()
    source_text = SOURCE.read_text()

    plain_members, atomic_members = parse_members(header_text)
    if not plain_members and not atomic_members:
        print("ERROR: parsed zero MobileGS members -- header regex is out of sync.", file=sys.stderr)
        return 2

    functions = split_brace_bodies(source_text, FUNC_START_RE)
    header_functions = split_brace_bodies(header_text, HEADER_METHOD_START_RE)
    if not functions:
        print("ERROR: parsed zero MobileGS:: function bodies -- source regex is out of sync.", file=sys.stderr)
        return 2
    if not header_functions:
        print("ERROR: parsed zero header-inline method bodies -- header regex is out of sync.", file=sys.stderr)
        return 2
    functions = functions + header_functions

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
