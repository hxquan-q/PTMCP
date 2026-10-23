"""Error code allocation is a public contract: collisions and drift must fail."""

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_quality_config import validate_registry


class ErrorCodeRegistryTests(unittest.TestCase):
    def setUp(self):
        self.registry = {
            'successCode': 200,
            'modules': {'common': [100000, 109999], 'audit': [170000, 179999]},
            'errors': [{'key': 'INVALID_ARGUMENT', 'module': 'common', 'code': 100001}],
        }

    def test_valid_allocation_passes(self):
        self.assertEqual([], validate_registry(self.registry))

    def test_two_symbols_cannot_share_a_number(self):
        self.registry['errors'].append({'key': 'OTHER_ERROR', 'module': 'common', 'code': 100001})
        self.assertTrue(validate_registry(self.registry))

    def test_one_symbol_cannot_have_two_numbers(self):
        self.registry['errors'].append({'key': 'INVALID_ARGUMENT', 'module': 'common', 'code': 100002})
        self.assertTrue(validate_registry(self.registry))

    def test_module_cannot_allocate_another_modules_number(self):
        self.registry['errors'][0]['code'] = 170001
        self.assertTrue(validate_registry(self.registry))

    def test_overlapping_module_blocks_are_rejected(self):
        self.registry['modules']['audit'] = [109999, 170000]
        self.assertTrue(validate_registry(self.registry))

    def test_unknown_modules_are_rejected(self):
        self.registry['errors'][0]['module'] = 'unknown'
        self.assertTrue(validate_registry(self.registry))

    def test_upstream_success_code_cannot_be_redefined(self):
        self.registry['successCode'] = 0
        self.assertTrue(validate_registry(self.registry))

    def test_malformed_range_fails_with_a_diagnostic(self):
        self.registry['modules']['common'] = ['100000', 109999]
        self.assertTrue(validate_registry(self.registry))


if __name__ == '__main__':
    unittest.main()
