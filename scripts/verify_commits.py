"""Reject non-conventional or credential-bearing commit messages in a revision range."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
TYPES = ("feat", "fix", "docs", "refactor", "test", "build", "ci", "chore", "perf", "revert")
HEADER = re.compile(
    r"^(?P<type>[a-z]+)(?:\((?P<scope>[^()\s][^()]*)\))?(?P<breaking>!)?: (?P<subject>\S.*)$"
)
MAX_HEADER = 72
ZERO_SHA = "0" * 40
CREDENTIALS = (
    ("private key block", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("GitHub token", re.compile(r"\b(?:gh[pousr]|github_pat)_[A-Za-z0-9_]{20,}\b")),
    ("GitLab token", re.compile(r"\bglpat-[A-Za-z0-9_-]{20,}\b")),
    ("AWS access key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("redaction placeholder", re.compile(r"\{\{[A-Z][A-Z0-9_]*_[A-Za-z0-9]+\}\}")),
)


def check_message(message: str) -> list[str]:
    """Return the problems in one commit message; an empty list means accepted."""
    lines = message.strip().splitlines()
    if not lines:
        return ["empty commit message"]
    header = lines[0]
    errors = []
    match = HEADER.match(header)
    if not match:
        errors.append(f"header is not 'type(scope): subject': {header!r}")
    elif match["type"] not in TYPES:
        errors.append(
            f"unsupported type {match['type']!r}; allowed: {', '.join(TYPES)}"
        )
    if len(header) > MAX_HEADER:
        errors.append(f"header is {len(header)} characters; limit is {MAX_HEADER}")
    for label, pattern in CREDENTIALS:
        if pattern.search(message):
            errors.append(f"message contains a possible {label}")
    return errors


def git(root: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(root), *args], capture_output=True, text=True
    )
    if result.returncode:
        raise SystemExit(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def revisions(spec: str, root: Path = ROOT) -> list[str]:
    """Resolve a revision or base..head range into commits, skipping merges."""
    if ".." in spec:
        base, head = spec.split("..", 1)
        if not base or not base.strip("0"):
            spec = head or "HEAD"
            args = ("rev-list", "--no-merges", "-n", "1", spec)
        else:
            args = ("rev-list", "--no-merges", f"{base}..{head}")
    else:
        args = ("rev-list", "--no-merges", "-n", "1", spec)
    return git(root, *args).split()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--range",
        dest="spec",
        default=os.environ.get("AGENT_COMMIT_RANGE", "HEAD"),
        help="Revision or base..head range; defaults to $AGENT_COMMIT_RANGE or HEAD",
    )
    args = parser.parse_args(argv)
    shas = revisions(args.spec)
    errors = []
    for sha in shas:
        for problem in check_message(git(ROOT, "log", "-1", "--format=%B", sha)):
            errors.append(f"{sha[:12]}: {problem}")
    print(
        json.dumps(
            {
                "status": "PASS" if not errors else "FAIL",
                "range": args.spec,
                "commits_checked": len(shas),
                "errors": errors,
                "limits": (
                    "Checks message shape and obvious credential markers only; "
                    "it cannot judge whether the described change is correct."
                ),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
