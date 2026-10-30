"""The full local gate must include the same actual Maven probes as CI."""

import contextlib
import io
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check as repository_check


class RepositoryEntrypointTests(unittest.TestCase):
    def invoke(self, arguments, failure=None):
        commands = []

        def run(command, cwd):
            commands.append((command, cwd))
            failed = (
                failure is True and any('check_backend_quality.py' in str(arg) for arg in command)
            ) or (failure == 'frontend-tests' and command[-1] == 'test')
            return SimpleNamespace(returncode=19 if failed else 0)

        with patch.object(sys, 'argv', ['check.py', *arguments]), \
                patch.object(repository_check.subprocess, 'run', side_effect=run), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            result = repository_check.main()
        return result, commands

    def test_default_preserves_existing_lightweight_checks_without_running_tools(self):
        result, commands = self.invoke([])
        self.assertEqual(0, result)
        self.assertEqual(len(repository_check.CHECKS), len(commands))
        self.assertFalse(any('check_backend_quality.py' in str(command) for command, _ in commands))

    def test_all_runs_actual_probes_after_verify_and_before_frontend(self):
        result, commands = self.invoke(['--all', '--maven-cache-seed', '/cache/with spaces'])
        self.assertEqual(0, result)
        probe_indexes = [index for index, (command, _) in enumerate(commands)
                         if any('check_backend_quality.py' in str(arg) for arg in command)]
        self.assertEqual(1, len(probe_indexes))
        index = probe_indexes[0]
        command, cwd = commands[index]
        self.assertEqual(sys.executable, command[0])
        self.assertIn('--seed-repository', command)
        self.assertEqual('/cache/with spaces', command[command.index('--seed-repository') + 1])
        self.assertEqual(repository_check.ROOT, cwd)
        self.assertIn('verify', commands[index - 1][0])
        self.assertIn('check', commands[index + 1][0])
        self.assertEqual(repository_check.ROOT / 'ptmcp-ui-react', commands[index + 1][1])

    def test_actual_probe_failure_stops_the_full_gate_and_does_not_run_frontend(self):
        result, commands = self.invoke(['--all'], failure=True)
        self.assertEqual(19, result)
        self.assertTrue(any('check_backend_quality.py' in str(arg) for arg in commands[-1][0]))
        self.assertFalse(any(cwd == repository_check.ROOT / 'ptmcp-ui-react' for _, cwd in commands))

    def test_all_includes_frontend_tests_after_frontend_checks(self):
        result, commands = self.invoke(['--all'])
        self.assertEqual(0, result)
        frontend = [command for command, cwd in commands if cwd == repository_check.ROOT / 'ptmcp-ui-react']
        self.assertEqual(['check', 'test'], [command[-1] for command in frontend])

    def test_frontend_test_failure_fails_the_full_gate(self):
        result, commands = self.invoke(['--all'], failure='frontend-tests')
        self.assertEqual(19, result)
        self.assertEqual('test', commands[-1][0][-1])

    def test_cache_seed_is_not_silently_ignored_without_all(self):
        with self.assertRaises(SystemExit) as error:
            self.invoke(['--maven-cache-seed', '/cache'])
        self.assertEqual(2, error.exception.code)


if __name__ == '__main__':
    unittest.main()
