"""Report committed and working-tree paths without modifying Git state."""

import argparse
import json
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]


def git(repo: Path, *args: str) -> bytes:
    return subprocess.run(["git", "-C", str(repo), *args], check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def names(data: bytes) -> list[str]:
    return sorted(part.decode("utf-8") for part in data.split(b"\0") if part)


def report(repo: Path, base: str | None) -> dict:
    head = git(repo, "rev-parse", "--verify", "HEAD^{commit}").decode().strip()
    merge_base = None
    committed = None
    if base is not None:
        resolved = git(repo, "rev-parse", "--verify", "--end-of-options", f"{base}^{{commit}}").decode().strip()
        merge_base = git(repo, "merge-base", resolved, head).decode().strip()
        committed = names(git(repo, "diff", "--name-only", "-z", "--no-renames", merge_base, head, "--"))
    return {
        "version": 1,
        "head": head,
        "base": resolved if base is not None else None,
        "merge_base": merge_base,
        "committed": committed,
        "staged": names(git(repo, "diff", "--cached", "--name-only", "-z", "--no-renames", "--")),
        "unstaged": names(git(repo, "diff", "--name-only", "-z", "--no-renames", "--")),
        "untracked": names(git(repo, "ls-files", "--others", "--exclude-standard", "-z")),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", help="Explicit verified base ref; omit to inspect only working-tree layers")
    parser.add_argument("--repo", type=Path, default=ROOT, help="Repository to inspect")
    args = parser.parse_args()
    try:
        result = report(args.repo, args.base)
    except (OSError, UnicodeError, subprocess.CalledProcessError) as exc:
        print(f"change scope unavailable: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
