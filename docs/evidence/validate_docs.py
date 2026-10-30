"""Read-only checks for the PTMCP design documentation, using stdlib only."""

from __future__ import annotations

import json
import re
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
SCOPES = (
    "product", "architecture", "backend", "frontend", "test", "ops",
    "standards", "evidence",
)
REQUIRED = (
    "README.md", "总体方案.md", "product/PRD.md", "product/user_flow.md",
    "product/faq.md", "architecture/architecture.md", "architecture/infra.md",
    "backend/db-design.md", "backend/api-spec.md", "backend/sql-guard.md",
    "backend/authentication.md", "backend/authorization.md", "backend/datasource.md",
    "backend/audit.md", "frontend/dev-guide.md", "frontend/ui-link.md",
    "frontend/page-spec.md", "frontend/wireframes.md", "test/test-plan.md",
    "test/test-case.md", "test/test-report.md", "ops/deploy.md",
    "ops/operation-manual.md", "ops/user-manual.md", "standards/code-style.md",
    "standards/git-rule.md", "evidence/README.md", "evidence/m0-gate-register.md",
    "evidence/documentation-validation.md", "backend/mcp-tools.json",
    "standards/agent-workflow.md", "standards/decision-notes.md",
    "reference/agent-development-research.md",
    "standards/quality-tooling.md", "test/asvs-checklist.md",
)
LINK = re.compile(r"\[[^\]\n]*\]\((<[^>]+>|[^)\n]+)\)")
FENCE = re.compile(r"^\s*(`{3,}|~{3,})([^\s]*)\s*$")


def path_label(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def validate_adr_paths(paths: list[Path]) -> list[str]:
    """Keep the initial design decisions while allowing additional ADRs."""
    ids = [int(path.name[:4]) for path in paths]
    errors = []
    missing = set(range(1, 10)) - set(ids)
    if missing:
        errors.append(f"missing baseline ADR IDs: {sorted(missing)}")
    duplicates = sorted({number for number in ids if ids.count(number) > 1})
    if duplicates:
        errors.append(f"duplicate ADR IDs: {duplicates}")
    return errors


def main() -> int:
    errors: list[str] = []
    markdown = {
        DOCS / "README.md", DOCS / "总体方案.md", ROOT / "README.md",
        ROOT / "AGENTS.md", ROOT / "CLAUDE.md",
        ROOT / ".github" / "pull_request_template.md",
        DOCS / "reference" / "agent-development-research.md",
    }
    for scope in SCOPES:
        markdown.update((DOCS / scope).rglob("*.md"))
    markdown.update((ROOT / "tasks").rglob("*.md"))
    for project in ('ptmcp-parent', 'ptmcp-ui-react'):
        markdown.update((ROOT / project).glob('*.md'))
    for lifecycle in ("proposed", "implemented", "rejected"):
        markdown.update((ROOT / ".agents" / "notes" / lifecycle).rglob("*.md"))
    for relative in REQUIRED:
        if not (DOCS / relative).is_file():
            errors.append(f"missing required file: docs/{relative}")
    errors.extend(validate_adr_paths(list(
        (DOCS / "architecture" / "adr").glob("[0-9][0-9][0-9][0-9]-*.md")
    )))

    json_blocks = 0
    mermaid_blocks = 0
    local_links = 0
    for path in sorted(markdown):
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8-sig")
        if not text.startswith("# "):
            errors.append(f"missing title: {path_label(path)}")
        if "\ufffd" in text:
            errors.append(f"replacement character found: {path_label(path)}")
        active: tuple[str, int, str, int] | None = None
        block: list[str] = []
        prose: list[str] = []
        for number, line in enumerate(text.splitlines(), start=1):
            match = FENCE.match(line)
            if match and active is None:
                marker, language = match.groups()
                active = (marker[0], len(marker), language.lower(), number)
                block = []
                continue
            if active:
                if match and match.group(1)[0] == active[0] and len(match.group(1)) >= active[1] and not match.group(2):
                    if active[2] == "json":
                        json_blocks += 1
                        try:
                            json.loads("\n".join(block))
                        except json.JSONDecodeError as exc:
                            errors.append(f"invalid JSON: {path_label(path)}:{active[3]}: {exc}")
                    if active[2] == "mermaid":
                        mermaid_blocks += 1
                        if not any(row.strip() for row in block):
                            errors.append(f"empty Mermaid: {path_label(path)}:{active[3]}")
                    active = None
                else:
                    block.append(line)
                continue
            prose.append(line)
        if active:
            errors.append(f"unclosed code fence: {path_label(path)}:{active[3]}")
        for match in LINK.finditer("\n".join(prose)):
            target = match.group(1).strip().strip("<>")
            if re.match(r"^(?:https?|mailto|codex|data):", target, re.IGNORECASE) or target.startswith("#"):
                continue
            target = target.split(' "', 1)[0].split("#", 1)[0]
            target = re.sub(r":\d+$", "", unquote(target))
            if not target:
                continue
            destination = Path(target) if re.match(r"^[A-Za-z]:[/\\]", target) else path.parent / target
            local_links += 1
            if not destination.exists():
                errors.append(f"broken local link: {path_label(path)} -> {target}")

    tool_path = DOCS / "backend" / "mcp-tools.json"
    tools_count = 0
    if tool_path.exists():
        try:
            raw = json.loads(tool_path.read_text(encoding="utf-8-sig"))
            tools = raw["tools"] if isinstance(raw, dict) else raw
            expected = {"list_databases_and_tables", "get_table_schema", "execute_readonly_sql"}
            names = {tool["name"] for tool in tools}
            tools_count = len(tools)
            if names != expected or tools_count != 3:
                errors.append("MCP tools must be exactly the three documented tools")
            for tool in tools:
                schema = tool["inputSchema"]
                if schema.get("additionalProperties") is not False:
                    errors.append(f"MCP schema must reject extra fields: {tool['name']}")
                if "datasource_id" not in schema.get("required", []):
                    errors.append(f"MCP schema requires datasource_id: {tool['name']}")
        except (ValueError, KeyError, TypeError) as exc:
            errors.append(f"invalid MCP contract: {exc}")

    prd_path = DOCS / "product" / "PRD.md"
    test_path = DOCS / "test" / "test-case.md"
    fr_ids = set(re.findall(r"FR-(\d{2})", prd_path.read_text(encoding="utf-8-sig"))) if prd_path.exists() else set()
    if fr_ids != {f"{number:02d}" for number in range(1, 21)}:
        errors.append(f"expected FR-01 through FR-20, found: {sorted(fr_ids)}")
    case_ids = set(re.findall(r"TC-[A-Z]+-\d{3}", test_path.read_text(encoding="utf-8-sig"))) if test_path.exists() else set()
    result = {
        "status": "PASS" if not errors else "FAIL",
        "markdown_files": len(markdown),
        "local_links_checked": local_links,
        "json_fenced_blocks": json_blocks,
        "mermaid_blocks_counted_not_rendered": mermaid_blocks,
        "mcp_tools": tools_count,
        "product_requirement_ids": len(fr_ids),
        "test_case_ids": len(case_ids),
        "errors": errors,
        "limits": [
            "Historical input documents are not rewritten or linted.",
            "Mermaid diagrams are counted, not parsed or visually rendered.",
            "External URLs are not checked by this script.",
            "This is documentation verification, not a software test run.",
        ],
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
