"""Check agent workflow file contracts, not the truth or quality of their claims."""

from __future__ import annotations

import argparse
from datetime import date
import json
import os
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]
LIFECYCLES = {"proposed", "implemented", "rejected", "archived"}
CATEGORIES = {"feature", "bug-fix", "simplification", "architecture", "process", "testing"}
TASK_STATES = {"draft", "ready", "in_progress", "verifying", "done", "blocked"}
PROPOSAL_HEADINGS = {"Proposal", "Plan", "Migration plan", "Acceptance criteria",
                     "提案", "计划", "迁移计划", "验收标准"}


def prose_only(text: str) -> str:
    """Ignore fenced examples when checking metadata and section contracts."""
    lines = []
    fence = None
    for line in text.splitlines():
        match = re.match(r"^\s*(`{3,}|~{3,})(.*)$", line)
        if fence:
            if match and match[1][0] == fence[0] and len(match[1]) >= len(fence) and not match[2].strip():
                fence = None
        elif match:
            fence = match[1]
        else:
            lines.append(line)
    return "\n".join(lines)


def sections(text: str) -> dict[str, str]:
    return dict(re.findall(r"^## ([^\n]+)\n(.*?)(?=^## |\Z)", text, re.M | re.S))


def field(text: str, key: str) -> str:
    matches = re.findall(rf"^{re.escape(key)}: (.+)$", text, re.M)
    return matches[0].strip() if len(matches) == 1 else ""


def valid_date(value: str) -> bool:
    try:
        return date.fromisoformat(value).isoformat() == value
    except ValueError:
        return False


def require_sections(text: str, required: set[str], label: str) -> list[str]:
    found = sections(text)
    return [f"{label}: missing or empty section: {heading}"
            for heading in sorted(required) if not found.get(heading, "").strip()]


def check_task(path: Path, root: Path) -> list[str]:
    label = path.relative_to(root).as_posix()
    text = prose_only(path.read_text(encoding="utf-8-sig"))
    errors = []
    values = {key: field(text, key) for key in (
        "ID", "Status", "Owner", "Acceptance", "Verification", "Updated"
    )}
    for key, value in values.items():
        if not value:
            errors.append(f"{label}: requires exactly one non-empty {key}")
    if values["ID"] != path.stem or not text.startswith(f"# {path.stem}："):
        errors.append(f"{label}: ID, filename and title must agree")
    if values["Status"] not in TASK_STATES:
        errors.append(f"{label}: invalid Status")
    if values["Acceptance"] not in {"pending", "accepted", "not-required"}:
        errors.append(f"{label}: invalid Acceptance")
    if values["Verification"] not in {"PASS", "FAIL", "NOT_RUN", "BLOCKED"}:
        errors.append(f"{label}: invalid Verification")
    if not valid_date(values["Updated"]):
        errors.append(f"{label}: invalid Updated date")
    if values["Status"] == "done":
        if values["Acceptance"] == "pending":
            errors.append(f"{label}: done requires resolved Acceptance")
        if values["Verification"] != "PASS":
            errors.append(f"{label}: done requires Verification: PASS")
    errors.extend(require_sections(text, {"目标与边界", "实施与验证", "结果与证据", "交接"}, label))
    return errors


def check_note(path: Path, root: Path) -> list[str]:
    relative = path.relative_to(root / ".agents/notes")
    label = path.relative_to(root).as_posix()
    errors = []
    if len(relative.parts) != 3:
        return [f"{label}: expected lifecycle/category/date-topic.md"]
    lifecycle, category, filename = relative.parts
    if lifecycle not in LIFECYCLES or category not in CATEGORIES:
        errors.append(f"{label}: invalid lifecycle or category")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}-[a-z0-9]+(?:-[a-z0-9]+)*\.md", filename) or not valid_date(filename[:10]):
        errors.append(f"{label}: invalid date-topic filename")
    raw = path.read_text(encoding="utf-8-sig")
    lines = raw.splitlines()
    if (len(lines) < 3 or not lines[0].startswith("# Agent Note: ")
            or not lines[0][len("# Agent Note: "):].strip()
            or lines[1] != "" or not lines[2].startswith("Status: ")):
        errors.append(f"{label}: invalid note header")
    text = prose_only(raw)
    status = field(text, "Status")
    expected = status.split(" — ", 1)[0]
    if expected != lifecycle:
        errors.append(f"{label}: path and status disagree")
    if lifecycle == "rejected" and (" — " not in status or not status.split(" — ", 1)[1].strip()):
        errors.append(f"{label}: rejected requires a reason")
    required = {"Problem", "Alternatives considered"}
    if lifecycle in {"implemented", "archived"}:
        required.update({"Decision", "Consequences", "Verification"})
        if PROPOSAL_HEADINGS.intersection(sections(text)):
            errors.append(f"{label}: implemented/archived contains proposal headings")
    else:
        required.update({"Proposal", "Acceptance criteria", "Risks"})
    if lifecycle == "archived" and not valid_date(field(text, "Archived")):
        errors.append(f"{label}: archived requires Archived date")
    errors.extend(require_sections(text, required, label))
    headings = re.findall(r"^## ([^\n]+)$", text, re.M)
    if headings and headings[0] != "Problem":
        errors.append(f"{label}: first section must be Problem")
    if len(headings) != len(set(headings)):
        errors.append(f"{label}: duplicate note section")
    ordered = (["Problem", "Decision", "Alternatives considered", "Consequences", "Verification"]
               if lifecycle in {"implemented", "archived"} else
               ["Problem", "Proposal", "Alternatives considered", "Acceptance criteria", "Risks"])
    present = [heading for heading in headings if heading in ordered]
    if len(present) == len(ordered) and present != ordered:
        errors.append(f"{label}: required note sections are out of order")
    return errors


def archive_errors(root: Path, base: str) -> list[str]:
    """Compare frozen files to an existing commit, including committed changes."""
    def git(*args: str) -> bytes:
        return subprocess.run(
            ["git", "-C", str(root), *args], check=True, capture_output=True,
        ).stdout

    try:
        # A new branch push has an all-zero 'before'; no earlier tree exists.
        if base and set(base) == {"0"}:
            base = "HEAD"
        commit = git("rev-parse", "--verify", "--end-of-options", f"{base}^{{commit}}").decode().strip()
        tracked = git("ls-tree", "-rz", "--name-only", commit, "--", ".agents/notes/archived")
        errors = []
        for entry in tracked.split(b"\0"):
            if not entry:
                continue
            relative = entry.decode("utf-8")
            path = root / relative
            original = git("show", f"{commit}:{relative}")
            if not path.is_file() or path.read_bytes().replace(b"\r\n", b"\n") != original.replace(b"\r\n", b"\n"):
                errors.append(f"{relative}: frozen archive changed or deleted since {commit[:12]}")
        return errors
    except (OSError, subprocess.CalledProcessError, UnicodeError):
        return [f"archive baseline unavailable: {base}; Git and an existing commit are required"]


def check_asset_contract(root: Path) -> list[str]:
    """Check explicit entry routes and metadata for Git-tracked skills only."""
    errors = []
    for filename, target in (("CLAUDE.md", "AGENTS.md"),
                             ("AGENTS.md", "DESIGN.md"),
                             ("AGENTS.md", "docs/standards/agent-assets.md")):
        path = root / filename
        if path.is_file() and not re.search(r"\]\(" + re.escape(target) + r"\)", path.read_text(encoding="utf-8-sig")):
            errors.append(f"{filename}: missing route to {target}")
    for filename in ("DESIGN.md", "docs/standards/agent-assets.md"):
        path = root / filename
        if not path.is_file() or not path.read_text(encoding="utf-8-sig").strip():
            errors.append(f"{filename}: missing or empty required asset")
    try:
        tracked = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--",
                                  ".agents/skills/*/SKILL.md"], check=True,
                                 capture_output=True).stdout
        for entry in tracked.split(b"\0"):
            if not entry:
                continue
            path = root / entry.decode("utf-8")
            if not path.is_file():
                errors.append(f"{path.relative_to(root).as_posix()}: tracked skill missing")
                continue
            text = path.read_text(encoding="utf-8-sig")
            match = re.match(r"\A---\s*\n(.*?)\n---(?:\n|$)", text, re.S)
            metadata = match.group(1) if match else ""
            if field(metadata, "name") != path.parent.name or not field(metadata, "description"):
                errors.append(f"{path.relative_to(root).as_posix()}: invalid skill metadata")
    except (OSError, UnicodeError, subprocess.CalledProcessError):
        errors.append("skill metadata unavailable: Git is required")
    return errors


def validate_workflow(root: Path, archive_base: str = "HEAD") -> list[str]:
    errors = []
    for name, limit in (("AGENTS.md", 8192), ("CLAUDE.md", 1024)):
        path = root / name
        if not path.is_file():
            errors.append(f"missing workflow entry: {name}")
        elif path.stat().st_size > limit:
            errors.append(f"{name}: exceeds {limit} byte budget; move detail behind a task pointer")
    for path in sorted((root / "tasks/work-items").glob("*.md")):
        errors.extend(check_task(path, root))
    for path in sorted((root / ".agents/notes").rglob("*.md")):
        errors.extend(check_note(path, root))
    errors.extend(check_asset_contract(root))
    errors.extend(archive_errors(root, archive_base))
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive-base", default=os.environ.get("AGENT_ARCHIVE_BASE", "HEAD"))
    args = parser.parse_args()
    errors = validate_workflow(ROOT, args.archive_base)
    print(json.dumps({"status": "FAIL" if errors else "PASS", "errors": errors,
                      "limits": "Structural checks do not verify evidence truth, approval or agent behavior."},
                     ensure_ascii=False, indent=2))
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
