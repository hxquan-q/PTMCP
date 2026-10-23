"""Probe verdicts must distinguish a real gate rejection from infrastructure failure."""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_backend_quality import (
    ProbeFailure, assert_result, copy_project, ignore_copy, inject_java, inject_spring,
    maven_command, run_maven,
)


SPOTLESS = ('spotless-maven-plugin:3.1.0:check', 'format violations', 'QualityProbe.java')


class BackendQualityTests(unittest.TestCase):
    def test_positive_requires_successful_exit_and_build_success(self):
        assert_result('positive', 0, '[INFO] BUILD SUCCESS')
        for code, output in ((1, '[INFO] BUILD SUCCESS'), (0, 'connection failed')):
            with self.subTest(code=code, output=output), self.assertRaises(ProbeFailure):
                assert_result('positive', code, output)

    def test_negative_requires_failure_and_every_gate_marker(self):
        output = '[ERROR] BUILD FAILURE ' + ' '.join(SPOTLESS)
        assert_result('format', 1, output, SPOTLESS)
        for marker in SPOTLESS:
            with self.subTest(marker=marker), self.assertRaises(ProbeFailure):
                assert_result('format', 1, output.replace(marker, ''), SPOTLESS)
        with self.assertRaises(ProbeFailure):
            assert_result('format', 0, output, SPOTLESS)

    def test_enforcer_requires_actual_banned_dependency_diagnostic(self):
        markers = ('maven-enforcer-plugin:3.6.2:enforce', '(module-boundary)',
                   'BannedDependencies', 'org.springframework:spring-core',
                   'banned via the exclude/include list')
        output = ('BUILD FAILURE maven-enforcer-plugin:3.6.2:enforce (module-boundary) '
                  'BannedDependencies failed org.springframework:spring-core:jar:7.0.10 '
                  '<--- banned via the exclude/include list')
        assert_result('boundary', 1, output, markers)
        with self.assertRaises(ProbeFailure):
            assert_result('boundary', 1, output.replace('BannedDependencies', 'DependencyConvergence'), markers)

    def test_network_and_resolution_errors_never_count_as_gate_failure(self):
        for error in ('Could not resolve dependencies', 'Could not transfer artifact',
                      'PluginResolutionException', 'Non-resolvable parent POM',
                      'Connection timed out', 'BUILD SUCCESS'):
            with self.subTest(error=error), self.assertRaises(ProbeFailure):
                assert_result('format', 1, 'BUILD FAILURE ' + ' '.join(SPOTLESS) + error, SPOTLESS)

    def test_windows_and_unix_use_project_wrapper_and_private_repository(self):
        for windows in (False, True):
            command = maven_command(Path('/probe/ptmcp-parent'), Path('/probe/repository'), ['validate'], windows)
            self.assertIn(f'-Dmaven.repo.local={Path("/probe/repository")}', command)
            self.assertIn('validate', command)
            self.assertIn('mvnw.cmd' if windows else './mvnw', ' '.join(command))
            self.assertEqual('cmd.exe' if windows else 'sh', Path(command[0]).name)

    def test_copy_uses_actual_project_without_outputs_or_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source'
            (source / 'ptmcp-parent' / '.mvn').mkdir(parents=True)
            (source / 'config' / 'quality').mkdir(parents=True)
            (source / '.gitattributes').write_text('* text=auto eol=lf\n', encoding='utf-8')
            for relative in ('ptmcp-parent/pom.xml', 'ptmcp-parent/mvnw',
                             'ptmcp-parent/.mvn/wrapper.properties', 'config/quality/checkstyle.xml',
                             'ptmcp-parent/common/src/main/Actual.java',
                             'ptmcp-parent/common/target/Dirty.java', 'ptmcp-parent/node_modules/dirty.js',
                             'ptmcp-parent/.git/config'):
                path = source / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(relative, encoding='utf-8')
            copy_project(source, root / 'copy')
            self.assertEqual('ptmcp-parent/pom.xml', (root / 'copy/ptmcp-parent/pom.xml').read_text())
            self.assertTrue((root / 'copy/ptmcp-parent/common/src/main/Actual.java').is_file())
            self.assertFalse((root / 'copy/ptmcp-parent/common/target').exists())
            self.assertFalse((root / 'copy/ptmcp-parent/node_modules').exists())
            self.assertFalse((root / 'copy/ptmcp-parent/.git').exists())
            self.assertEqual('* text=auto eol=lf\n', (root / 'copy/.gitattributes').read_text())
            self.assertTrue((root / 'copy/.git').is_dir())

    def test_copy_ignores_symbolic_links_even_when_named_as_source(self):
        with patch('check_backend_quality.Path.is_symlink', return_value=True):
            self.assertEqual(['Linked.java'], ignore_copy('/fixture', ['Linked.java']))

    def test_java_fixture_cannot_overwrite_existing_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inject_java(root, 'main', 'original')
            with self.assertRaisesRegex(ProbeFailure, 'overwrite'):
                inject_java(root, 'main', 'replacement')
            path = root / 'ptmcp-common/src/main/java/local/ptmcp/quality/QualityProbe.java'
            self.assertEqual('original', path.read_text())

    def test_dependency_mutation_changes_only_the_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = root / 'original/ptmcp-common/pom.xml'
            original.parent.mkdir(parents=True)
            original.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'
                                '<artifactId>ptmcp-common</artifactId></project>', encoding='utf-8')
            before = original.read_bytes()
            copy = root / 'copy/ptmcp-common/pom.xml'
            copy.parent.mkdir(parents=True)
            copy.write_bytes(before)
            inject_spring(root / 'copy')
            self.assertEqual(before, original.read_bytes())
            self.assertIn('spring-core', copy.read_text())

    def test_maven_timeout_is_failure_not_negative_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch('check_backend_quality.subprocess.Popen') as popen:
                process = popen.return_value
                process.poll.return_value = None
                process.wait.side_effect = [subprocess.TimeoutExpired('mvnw', 1), 1]
                process.pid = 123
                with patch('check_backend_quality.stop_process') as stop:
                    with self.assertRaisesRegex(ProbeFailure, 'timeout'):
                        run_maven(Path(directory), Path(directory) / 'repo', {}, ['validate'], 1)
                    stop.assert_called_once()

    def test_timeout_tree_cleanup_error_does_not_wait_forever(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch('check_backend_quality.subprocess.Popen') as popen:
                process = popen.return_value
                process.poll.return_value = None
                process.wait.side_effect = [subprocess.TimeoutExpired('mvnw', 1), 1]
                with patch('check_backend_quality.stop_process', side_effect=OSError('tree gone')):
                    with self.assertRaisesRegex(ProbeFailure, 'timeout'):
                        run_maven(Path(directory), Path(directory) / 'repo', {}, ['validate'], 1)
                process.kill.assert_called_once()


if __name__ == '__main__':
    unittest.main()
