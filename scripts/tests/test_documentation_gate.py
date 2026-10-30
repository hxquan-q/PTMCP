"""The documentation gate must allow architecture evolution without losing history."""

from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "docs/evidence"))
from validate_docs import validate_adr_paths, validate_required_files


class ArchitectureDecisionGateTests(unittest.TestCase):
    def setUp(self):
        self.baseline = [Path(f"{number:04d}-baseline.md") for number in range(1, 10)]

    def test_additional_architecture_decision_is_allowed(self):
        self.assertEqual([], validate_adr_paths(self.baseline + [Path("0010-next.md")]))

    def test_losing_a_baseline_decision_is_rejected(self):
        self.assertTrue(validate_adr_paths(self.baseline[1:]))

    def test_two_different_decisions_cannot_share_an_id(self):
        self.assertTrue(validate_adr_paths(self.baseline + [Path("0004-other.md")]))


class RequiredDocumentGateTests(unittest.TestCase):
    def test_missing_required_document_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(
                ["missing required file: docs/standards/api-change-template.md"],
                validate_required_files(root, ("standards/api-change-template.md",)),
            )
            (root / "standards").mkdir()
            (root / "standards/api-change-template.md").write_text("# Template\n", encoding="utf-8")
            self.assertEqual([], validate_required_files(root, ("standards/api-change-template.md",)))


if __name__ == "__main__":
    unittest.main()
