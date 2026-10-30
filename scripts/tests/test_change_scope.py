"""Change scope uses isolated repositories and never changes the user's index."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "change_scope.py"


class ChangeScopeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        self.write("base.txt", "base")
        self.git("add", "--", ".")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "-qm", "base")
        self.base = self.git("rev-parse", "HEAD").strip()

    def git(self, *args):
        return subprocess.run(["git", "-c", "core.hooksPath=", "-C", str(self.root), *args],
                              check=True, capture_output=True, text=True).stdout

    def write(self, name, content):
        (self.root / name).write_text(content, encoding="utf-8")

    def run_scope(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), "--repo", str(self.root), *args],
                              capture_output=True, text=True, encoding="utf-8")

    def test_layers_and_merge_base_are_separate(self):
        self.write("committed.txt", "committed")
        self.git("add", "--", "committed.txt")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "-qm", "change")
        self.write("staged.txt", "staged")
        self.git("add", "--", "staged.txt")
        self.write("staged.txt", "staged and unstaged")
        self.write("未跟踪.txt", "new")
        before = self.git("status", "--porcelain=v1")
        result = self.run_scope("--base", self.base)
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(self.base, report["merge_base"])
        self.assertEqual(["committed.txt"], report["committed"])
        self.assertEqual(["staged.txt"], report["staged"])
        self.assertEqual(["staged.txt"], report["unstaged"])
        self.assertEqual(["未跟踪.txt"], report["untracked"])
        self.assertEqual(before, self.git("status", "--porcelain=v1"))

    def test_no_base_does_not_guess_and_invalid_base_fails(self):
        report = json.loads(self.run_scope().stdout)
        self.assertIsNone(report["committed"])
        self.assertIsNone(report["merge_base"])
        result = self.run_scope("--base", "missing-ref")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout)


if __name__ == "__main__":
    unittest.main()
