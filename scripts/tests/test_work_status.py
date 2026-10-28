"""Regression scenarios for the derived work-item status view."""

from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from work_status import classify, collect


def task(item_id, status, acceptance, verification, updated="2026-10-28"):
    return f"""# {item_id}：测试项

ID: {item_id}
Status: {status}
Owner: current-agent
Acceptance: {acceptance}
Verification: {verification}
Updated: {updated}

## 目标与边界
边界。
## 实施与验证
验证。
## 结果与证据
证据。
## 交接
交接。
"""


class WorkStatusTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def write(self, item_id, **kwargs):
        (self.directory / f"{item_id}.md").write_text(
            task(item_id, **kwargs), encoding="utf-8"
        )

    def test_groups_split_engineering_acceptance_and_blockers(self):
        self.write("T01", status="in_progress", acceptance="pending", verification="NOT_RUN")
        self.write("T02", status="verifying", acceptance="pending", verification="PASS")
        self.write("T03", status="blocked", acceptance="pending", verification="NOT_RUN")
        self.write("T04", status="done", acceptance="not-required", verification="PASS")
        groups = classify(collect(self.directory))
        self.assertEqual([item["id"] for item in groups["engineering"]], ["T01"])
        self.assertEqual([item["id"] for item in groups["awaiting"]], ["T02"])
        self.assertEqual([item["id"] for item in groups["blocked"]], ["T03"])
        self.assertEqual(groups["closable"], [])
        self.assertEqual(groups["contradictory"], [])

    def test_passed_slice_without_human_acceptance_is_reported_closable(self):
        self.write("T05", status="verifying", acceptance="not-required", verification="PASS")
        groups = classify(collect(self.directory))
        self.assertEqual([item["id"] for item in groups["closable"]], ["T05"])

    def test_done_with_pending_acceptance_is_contradictory(self):
        self.write("T06", status="done", acceptance="pending", verification="PASS")
        groups = classify(collect(self.directory))
        self.assertEqual([item["id"] for item in groups["contradictory"]], ["T06"])


if __name__ == "__main__":
    unittest.main()
