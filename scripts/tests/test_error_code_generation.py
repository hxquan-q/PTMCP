"""Compiled error contracts must come from the one public allocation registry."""

from copy import deepcopy
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import generate_error_codes
from generate_error_codes import JAVA_PATH, render_java
from verify_quality_config import validate_generated_errors


class ErrorCodeGenerationTests(unittest.TestCase):
    def setUp(self):
        self.registry = {
            'successCode': 200,
            'modules': {'common': [100000, 109999]},
            'errors': [{'key': 'INVALID_ARGUMENT', 'module': 'common', 'code': 100001}],
        }

    def test_render_preserves_symbols_and_numbers_without_framework_dependencies(self):
        rendered = render_java(self.registry)
        self.assertIn('package local.ptmcp.common.error;', rendered)
        self.assertIn('INVALID_ARGUMENT(100001);', rendered)
        self.assertIn('public int restCode()', rendered)
        for forbidden in ('org.springframework', 'org.dromara', 'cn.dev33'):
            self.assertNotIn(forbidden, rendered)

    def test_generation_is_deterministic_and_does_not_mutate_the_registry(self):
        before = deepcopy(self.registry)
        self.assertEqual(render_java(self.registry), render_java(deepcopy(self.registry)))
        self.assertEqual(before, self.registry)

    def test_missing_generated_file_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertTrue(validate_generated_errors(Path(directory), self.registry))

    def test_drift_is_rejected_even_when_generated_java_is_syntactically_valid(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / JAVA_PATH
            output.parent.mkdir(parents=True)
            output.write_text(render_java(self.registry), encoding='utf-8')
            self.assertEqual([], validate_generated_errors(root, self.registry))
            output.write_text(render_java(self.registry).replace('100001', '100002'), encoding='utf-8')
            self.assertTrue(validate_generated_errors(root, self.registry))

    def test_check_mode_never_rewrites_a_stale_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            registry_path = root / 'config/quality/rest-error-codes.json'
            registry_path.parent.mkdir(parents=True)
            registry_path.write_text(json.dumps(self.registry), encoding='utf-8')
            output = root / JAVA_PATH
            output.parent.mkdir(parents=True)
            output.write_text('stale output\n', encoding='utf-8')
            with patch.object(generate_error_codes, 'ROOT', root), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, generate_error_codes.main(['--check']))
            self.assertEqual('stale output\n', output.read_text(encoding='utf-8'))

    def test_invalid_registry_is_not_used_to_overwrite_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.registry['errors'][0]['key'] = 'NOT_A_JAVA_CONSTANT);'
            registry_path = root / 'config/quality/rest-error-codes.json'
            registry_path.parent.mkdir(parents=True)
            registry_path.write_text(json.dumps(self.registry), encoding='utf-8')
            output = root / JAVA_PATH
            output.parent.mkdir(parents=True)
            output.write_text('keep existing output\n', encoding='utf-8')
            with patch.object(generate_error_codes, 'ROOT', root), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, generate_error_codes.main([]))
            self.assertEqual('keep existing output\n', output.read_text(encoding='utf-8'))

    def test_every_existing_api_error_omission_gets_a_stable_allocation(self):
        root = Path(__file__).resolve().parents[2]
        registry = json.loads((root / 'config/quality/rest-error-codes.json').read_text(encoding='utf-8'))
        allocated = {entry['key'] for entry in registry['errors']}
        self.assertTrue({
            'AUTHENTICATION_FAILED',
            'SELF_PRIVILEGE_ESCALATION',
            'CONNECTION_BUDGET_EXCEEDED',
        } <= allocated)


if __name__ == '__main__':
    unittest.main()
