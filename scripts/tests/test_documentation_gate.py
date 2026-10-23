"""The documentation gate must allow architecture evolution without losing history."""

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "docs/evidence"))
from validate_docs import validate_adr_paths


class ArchitectureDecisionGateTests(unittest.TestCase):
    def setUp(self):
        self.baseline = [Path(f"{number:04d}-baseline.md") for number in range(1, 10)]

    def test_additional_architecture_decision_is_allowed(self):
        self.assertEqual([], validate_adr_paths(self.baseline + [Path("0010-next.md")]))

    def test_losing_a_baseline_decision_is_rejected(self):
        self.assertTrue(validate_adr_paths(self.baseline[1:]))

    def test_two_different_decisions_cannot_share_an_id(self):
        self.assertTrue(validate_adr_paths(self.baseline + [Path("0004-other.md")]))


if __name__ == "__main__":
    unittest.main()
