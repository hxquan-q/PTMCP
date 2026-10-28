"""Regression scenarios for commit message checks; fixtures never touch the user's Git tree."""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_commits import MAX_HEADER, check_message, revisions


def placeholder(word: str) -> str:
    """Build a scrubber placeholder without writing template syntax in the source."""
    return "{" * 2 + word + "}" * 2


class CommitMessageTests(unittest.TestCase):
    def test_accepts_conventional_messages(self):
        for message in (
            "fix(admin): 原子隔离数据源目标变更并关联服务端审计请求",
            "feat(api)!: 调整REST响应契约",
            "docs(datasource): 记录参考方案与746项验证及升级边界",
            "chore(evidence): 补充真实模式走查截图",
        ):
            with self.subTest(message=message):
                self.assertEqual(check_message(message), [])

    def test_rejects_historical_violating_messages(self):
        cases = {
            "verify(real): 操作日志真实数据一致性；772持平": "unsupported type",
            "session: 目标转入blocked": "unsupported type",
            "UI 小批：部门表单委派作用域提示": "not 'type(scope): subject'",
        }
        for message, expected in cases.items():
            with self.subTest(message=message):
                errors = check_message(message)
                self.assertTrue(errors)
                self.assertTrue(any(expected in error for error in errors), errors)

    def test_rejects_redaction_placeholder(self):
        words = (
            "PA" + "SSWORD_" + "abc123",
            "API" + "_KEY_" + "def456",
            "SEC" + "RET_" + "ghi789",
        )
        for word in words:
            message = f"fix(auth): 恢复 sys_user.{placeholder(word)}_hash 读取"
            with self.subTest(word=word):
                self.assertTrue(check_message(message))

    def test_rejects_credential_markers(self):
        token = "gh" + "p_" + "A" * 30
        self.assertTrue(check_message(f"fix: rotate {token}"))
        key = "AK" + "IA" + "B" * 16
        self.assertTrue(check_message(f"chore: rotate {key}"))

    def test_rejects_overlong_header(self):
        message = "docs(architecture): " + "很长的标题" * 20
        errors = check_message(message)
        self.assertTrue(any(str(MAX_HEADER) in error for error in errors), errors)

    def test_rejects_empty_message(self):
        self.assertEqual(check_message("   \n"), ["empty commit message"])


class RevisionRangeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.email", "test@example.com")
        self.git("config", "user.name", "Test")

    def git(self, *args):
        return subprocess.run(
            ["git", *args], cwd=self.root, capture_output=True, text=True, check=True
        ).stdout

    def commit(self, message):
        (self.root / "file.txt").write_text(message, encoding="utf-8")
        self.git("add", "file.txt")
        self.git("commit", "-q", "-m", message)

    def test_range_only_covers_the_listed_commits(self):
        self.commit("feat: 初始提交")
        self.commit("verify(x): 违规提交")
        self.assertEqual(len(revisions("HEAD~1..HEAD", self.root)), 1)

    def test_single_revision_checks_only_that_commit(self):
        self.commit("feat: 初始提交")
        self.commit("fix: 后续提交")
        self.assertEqual(len(revisions("HEAD", self.root)), 1)

    def test_zero_base_falls_back_to_head(self):
        self.commit("feat: 初始提交")
        self.assertEqual(len(revisions(f"{'0' * 40}..HEAD", self.root)), 1)


if __name__ == "__main__":
    unittest.main()
