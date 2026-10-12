#!/usr/bin/env python3
"""Print the version the shared version contract assigned to a commit.

The contract bot (HereLiesAz/workflows `version-contract`) runs on each push to main and, a little
after the push, commits `chore(version): M.m.p.b [skip ci]` with `.version-state.json` recording
`source_sha` (the pushed commit) and `version`. A build of that pushed commit therefore cannot read
its version from its own `version.properties` — that still holds the previous one. This waits for
the bot's record for SHA on origin/BRANCH and prints its version.

    contract_version.py SHA [--branch main] [--timeout 900] [--interval 15]

Exit status 1 (and nothing on stdout) when no record appears within the timeout.
"""
import argparse
import json
import subprocess
import sys
import time

STATE_FILE = ".version-state.json"


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=False, capture_output=True, text=True).stdout


def recorded_version(sha: str, branch: str) -> str | None:
    # Newest first; a few later pushes may already have moved the state file past SHA's record.
    for commit in git("log", f"origin/{branch}", "-n", "100", "--format=%H", "--", STATE_FILE).split():
        try:
            state = json.loads(git("show", f"{commit}:{STATE_FILE}"))
        except ValueError:
            continue
        if isinstance(state, dict) and state.get("source_sha") == sha and state.get("version"):
            return str(state["version"])
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("sha")
    parser.add_argument("--branch", default="main")
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--interval", type=int, default=15)
    args = parser.parse_args()

    deadline = time.monotonic() + args.timeout
    while True:
        git("fetch", "--quiet", "origin", args.branch)
        version = recorded_version(args.sha, args.branch)
        if version:
            print(version)
            return 0
        if time.monotonic() >= deadline:
            print(f"::error::No version-contract record for {args.sha} on {args.branch} "
                  f"after {args.timeout}s", file=sys.stderr)
            return 1
        time.sleep(args.interval)


if __name__ == "__main__":
    sys.exit(main())
