"""Derive the work-item status view; work items stay the only source of truth."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
WORK_ITEMS = ROOT / "tasks/work-items"
FIELDS = ("Status", "Acceptance", "Verification", "Updated")


def field(text: str, key: str) -> str:
    matches = re.findall(rf"^{re.escape(key)}: (.+)$", text, re.M)
    return matches[0].strip() if len(matches) == 1 else ""


def collect(directory: Path) -> list[dict[str, str]]:
    items = []
    for path in sorted(directory.glob("*.md")):
        text = path.read_text(encoding="utf-8-sig")
        title = next(
            (line[2:] for line in text.splitlines() if line.startswith("# ")),
            path.stem,
        )
        items.append(
            {"id": path.stem, "title": title, **{key: field(text, key) for key in FIELDS}}
        )
    return items


def classify(items: list[dict[str, str]]) -> dict[str, list[dict[str, str]]]:
    """Split work items into the states a reader needs, not the fields they store."""
    awaiting = [
        item for item in items
        if item["Status"] == "verifying"
        and item["Verification"] == "PASS"
        and item["Acceptance"] == "pending"
    ]
    engineering = [
        item for item in items
        if item["Status"] in {"draft", "ready", "in_progress"}
        or (item["Status"] == "verifying" and item["Verification"] != "PASS")
    ]
    blocked = [item for item in items if item["Status"] == "blocked"]
    closable = [
        item for item in items
        if item["Status"] == "verifying"
        and item["Verification"] == "PASS"
        and item["Acceptance"] != "pending"
    ]
    contradictory = [
        item for item in items
        if item["Status"] == "done"
        and (item["Acceptance"] == "pending" or item["Verification"] != "PASS")
    ]
    return {
        "awaiting": awaiting,
        "engineering": engineering,
        "blocked": blocked,
        "closable": closable,
        "contradictory": contradictory,
    }


def render(items: list[dict[str, str]], groups: dict[str, list[dict[str, str]]]) -> str:
    lines = [f"工作项状态（派生视图，不落盘）：共 {len(items)} 项", ""]

    def block(title: str, rows: list[dict[str, str]]) -> None:
        lines.append(f"{title}：{len(rows)}")
        if not rows:
            lines.append("  （无）")
        for item in sorted(rows, key=lambda row: (row["Updated"], row["id"])):
            lines.append(f"  {item['Updated']}  {item['id']:<18} {item['title']}")
        lines.append("")

    block("待人工验收（工程 PASS，Acceptance: pending）", groups["awaiting"])
    block("工程进行中", groups["engineering"])
    block("阻塞", groups["blocked"])
    block("可收尾（PASS 且无需人工验收）", groups["closable"])
    block("状态矛盾（done 但未满足规则）", groups["contradictory"])
    return "\n".join(lines).rstrip() + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--strict",
        action="store_true",
        help="存在可收尾或矛盾项时以非零退出（供 CI 使用）",
    )
    args = parser.parse_args(argv)
    items = collect(WORK_ITEMS)
    groups = classify(items)
    sys.stdout.write(render(items, groups))
    if args.strict and (groups["closable"] or groups["contradictory"]):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
