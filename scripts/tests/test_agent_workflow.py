"""Regression scenarios for workflow gates; fixtures never touch the user's Git tree."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_agent_workflow import validate_workflow


TASK = """# T01-a：测试任务

ID: T01-a
Status: in_progress
Owner: current-agent
Acceptance: pending
Verification: NOT_RUN
Updated: 2026-10-23

## 目标与边界
保持现有公共合同。
## 实施与验证
执行隔离验证。
## 结果与证据
未执行，等待实现。
## 交接
从当前切片继续。
"""
NOTE = """# Agent Note: 流程取舍

Status: implemented

## Problem
上下文丢失。
## Decision
使用任务合同。
## Alternatives considered
只用聊天：成本低，但不能跨会话可靠恢复。
## Consequences
增加维护工作，换来可查证的交接。
## Verification
结构校验通过；语义仍需评审。
"""


class WorkflowGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write("AGENTS.md", "# Rules\n")
        self.write("CLAUDE.md", "# Bridge\n")
        self.task = self.write("tasks/work-items/T01-a.md", TASK)
        self.note = self.write(
            ".agents/notes/implemented/process/2026-10-23-workflow.md", NOTE
        )
        self.git("init", "-q")
        self.commit()

    def write(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def git(self, *args):
        return subprocess.run(
            ["git", "-c", "core.hooksPath=", "-C", str(self.root), *args], check=True,
            capture_output=True, text=True, encoding="utf-8",
            env={key: value for key, value in os.environ.items() if not key.startswith("GIT_")},
        ).stdout

    def commit(self):
        self.git("add", "--", ".")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "-qm", "fixture")

    def errors(self, base="HEAD"):
        return validate_workflow(self.root, archive_base=base)

    def test_valid_active_workflow_passes(self):
        self.assertEqual([], self.errors())

    def test_completed_task_cannot_await_acceptance(self):
        self.task.write_text(TASK.replace("in_progress", "done").replace(
            "Verification: NOT_RUN", "Verification: PASS"), encoding="utf-8")
        self.assertTrue(any("Acceptance" in e for e in self.errors()))

    def test_completed_task_requires_passed_verification(self):
        self.task.write_text(TASK.replace("in_progress", "done").replace(
            "Acceptance: pending", "Acceptance: not-required"), encoding="utf-8")
        self.assertTrue(any("Verification" in e for e in self.errors()))

    def test_internal_completed_task_passes_without_claiming_human_acceptance(self):
        self.task.write_text(TASK.replace("in_progress", "done").replace(
            "Acceptance: pending", "Acceptance: not-required").replace(
            "Verification: NOT_RUN", "Verification: PASS"), encoding="utf-8")
        self.assertEqual([], self.errors())

    def test_duplicate_status_is_ambiguous_and_rejected(self):
        self.task.write_text(TASK + "\nStatus: done\n", encoding="utf-8")
        self.assertTrue(any("Status" in e for e in self.errors()))

    def test_empty_task_section_is_rejected(self):
        self.task.write_text(TASK.replace("保持现有公共合同。", ""), encoding="utf-8")
        self.assertTrue(any("目标与边界" in e for e in self.errors()))

    def test_note_path_and_status_must_agree(self):
        self.note.write_text(NOTE.replace("Status: implemented", "Status: proposed"),
                             encoding="utf-8")
        self.assertTrue(any("status" in e for e in self.errors()))

    def test_implemented_note_cannot_retain_a_proposal(self):
        self.note.write_text(NOTE + "\n## Proposal\n待落地。\n", encoding="utf-8")
        self.assertTrue(any("proposal" in e for e in self.errors()))

    def test_note_required_sections_must_follow_lifecycle_order(self):
        self.note.write_text(NOTE.replace("## Problem\n上下文丢失。\n", "").replace(
            "## Alternatives considered", "## Problem\n上下文丢失。\n## Alternatives considered"),
            encoding="utf-8")
        self.assertTrue(any("order" in e for e in self.errors()))

    def test_note_duplicate_section_is_rejected(self):
        self.note.write_text(NOTE + "\n## Decision\n另一套决定。\n", encoding="utf-8")
        self.assertTrue(any("duplicate" in e for e in self.errors()))

    def test_proposed_note_uses_proposal_section_order(self):
        self.note.unlink()
        proposed = ("# Agent Note: 流程取舍\n\nStatus: proposed\n\n"
                    "## Proposal\n建议。\n## Problem\n问题。\n"
                    "## Alternatives considered\n无。\n## Acceptance criteria\n验证。\n"
                    "## Risks\n风险。\n")
        self.write(".agents/notes/proposed/process/2026-10-23-workflow.md", proposed)
        self.assertTrue(any("order" in e for e in self.errors()))

    def test_note_free_sections_and_fenced_examples_are_allowed(self):
        self.note.write_text(NOTE.replace("## Alternatives considered", (
            "## 背景\n额外说明。\n```md\n## Decision\n示例。\n```\n"
            "## Alternatives considered")), encoding="utf-8")
        self.assertEqual([], self.errors())

    def test_invalid_calendar_date_is_rejected(self):
        self.note.rename(self.note.with_name("2026-02-30-workflow.md"))
        self.assertTrue(any("date" in e for e in self.errors()))

    def test_rejected_note_requires_a_reason(self):
        self.note.unlink()
        self.write(".agents/notes/rejected/process/2026-10-23-workflow.md",
                   NOTE.replace("Status: implemented", "Status: rejected"))
        self.assertTrue(any("reason" in e for e in self.errors()))

    def test_note_categories_are_bounded(self):
        self.note.unlink()
        self.write(".agents/notes/implemented/random/2026-10-23-workflow.md", NOTE)
        self.assertTrue(any("category" in e for e in self.errors()))

    def archive(self):
        self.note.unlink()
        path = self.write(
            ".agents/notes/archived/process/2026-10-23-workflow.md",
            NOTE.replace("Status: implemented", "Status: archived\nArchived: 2026-10-23"),
        )
        self.commit()
        return path

    def test_archive_changes_are_detected_even_if_they_are_committed(self):
        archived = self.archive()
        base = self.git("rev-parse", "HEAD").strip()
        archived.write_text(archived.read_text(encoding="utf-8") + "\nChanged\n",
                            encoding="utf-8")
        self.commit()
        self.assertTrue(any("frozen" in e for e in self.errors(base)))

    def test_archive_deletion_is_detected_even_when_the_directory_is_empty(self):
        self.archive().unlink()
        self.assertTrue(any("frozen" in e for e in self.errors()))

    def test_missing_archive_baseline_fails_closed(self):
        self.assertTrue(any("baseline" in e for e in self.errors("missing-ref")))

    def test_root_rule_budget_is_in_bytes(self):
        self.write("AGENTS.md", "# Rules\n" + "规" * 3000)
        self.assertTrue(any("8192" in e for e in self.errors()))


if __name__ == "__main__":
    unittest.main()
