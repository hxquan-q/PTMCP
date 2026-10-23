"""Run actual Maven quality positive/negative probes in disposable project copies.

Select Java 21 first (Windows: dot-source scripts/Enter-Dev.ps1). No formatter
apply/fix is used. Optional cache seeds are copied, never modified or hardlinked.
Exit 0 means every mandatory probe passed; setup, timeout and unrelated failures
exit 1, including unavailable dependencies. No database or UI is involved.
"""

import argparse
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
IGNORED = {'.git', '.local', 'target', 'node_modules', 'dist', '__pycache__'}
INFRASTRUCTURE_ERRORS = (
    'could not resolve', 'could not transfer artifact', 'non-resolvable parent pom',
    'pluginresolutionexception', 'dependencyresolutionexception',
    'connection timed out', 'connection refused', 'unknown host',
)
GOOD_JAVA = '''package local.ptmcp.quality;

import java.util.List;

public final class QualityProbe {
    public static List<String> copy(List<String> values) {
        return List.copyOf(values);
    }
}
'''
STAR_JAVA = GOOD_JAVA.replace('import java.util.List;', 'import java.util.*;')
BAD_FORMAT_JAVA = GOOD_JAVA.replace('    public static List<String> copy(List<String> values) {',
                                    'public static List<String> copy( List<String> values ){')


class ProbeFailure(RuntimeError):
    """A required probe did not produce trustworthy evidence."""


def assert_result(name: str, code: int, output: str, markers: tuple[str, ...] = ()) -> None:
    """Require both process outcome and gate-specific diagnostics, never just grep."""
    if not markers:
        valid = code == 0 and 'BUILD SUCCESS' in output
    else:
        lowered = output.lower()
        valid = (code != 0 and 'BUILD FAILURE' in output and 'BUILD SUCCESS' not in output
                 and all(marker in output for marker in markers)
                 and not any(error in lowered for error in INFRASTRUCTURE_ERRORS))
    if not valid:
        expected = 'successful Maven build' if not markers else f'rejection by {markers}'
        raise ProbeFailure(f'{name}: exit {code}; expected {expected}. See Maven output above.')
    print(f'PASS: {name} (Maven exit {code})', flush=True)


def ignore_copy(directory: str, names: list[str]) -> list[str]:
    return [name for name in names
            if name in IGNORED or (Path(directory) / name).is_symlink()]


def copy_project(source: Path, destination: Path) -> None:
    """Copy real POMs, Wrapper, sources and quality config, not a replacement POM."""
    for relative in ('ptmcp-parent', 'config/quality'):
        path = source / relative
        if path.is_symlink():
            raise ProbeFailure(f'Cannot copy symlink project root: {relative}')
        shutil.copytree(path, destination / relative, ignore=ignore_copy)
    # Spotless defaults to native CRLF on Windows without Git attributes. Preserve
    # the real LF policy in a NEW disposable repository; never copy original .git.
    attributes = source / '.gitattributes'
    if not attributes.is_file() or attributes.is_symlink():
        raise ProbeFailure('Missing actual root .gitattributes')
    shutil.copy2(attributes, destination / '.gitattributes')
    subprocess.run(['git', 'init', '--quiet', '--template=', str(destination)], check=True,
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)


def maven_command(project: Path, repository: Path, arguments: list[str],
                  windows: bool | None = None) -> list[str]:
    windows = os.name == 'nt' if windows is None else windows
    wrapper = ([os.environ.get('COMSPEC', 'cmd.exe'), '/d', '/c', r'.\mvnw.cmd']
               if windows else ['sh', './mvnw'])
    return [*wrapper, '-B', '-ntp', f'-Dmaven.repo.local={repository}', *arguments]


def stop_process(process: subprocess.Popen) -> None:
    """Kill the Wrapper's entire child tree on timeout, not only cmd/sh."""
    if os.name == 'nt':
        subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
    else:
        os.killpg(process.pid, signal.SIGKILL)


def run_maven(project: Path, repository: Path, environment: dict[str, str],
              arguments: list[str], timeout: int) -> tuple[int, str]:
    command = maven_command(project, repository, arguments)
    print(f'MAVEN [{project.parent.name}]: {" ".join(arguments)}', flush=True)
    # File output avoids pipe deadlocks while waiting with a timeout.
    with tempfile.TemporaryFile() as output:
        process = subprocess.Popen(command, cwd=project, env=environment, stdout=output,
                                   stderr=subprocess.STDOUT, start_new_session=os.name != 'nt')
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired as error:
            try:
                stop_process(process)
            except (OSError, subprocess.SubprocessError) as cleanup_error:
                print(f'WARNING: process-tree cleanup failed: {cleanup_error}', flush=True)
            finally:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=15)
            output.seek(0)
            print(output.read().decode('utf-8', errors='replace'), flush=True)
            raise ProbeFailure(f'Maven timeout after {timeout}s: {arguments}') from error
        output.seek(0)
        text = output.read().decode('utf-8', errors='replace')
    print(text, flush=True)
    return code, text


def inject_java(project: Path, source_set: str, content: str) -> None:
    path = project / f'ptmcp-common/src/{source_set}/java/local/ptmcp/quality/QualityProbe.java'
    if path.exists():
        raise ProbeFailure(f'Fixture would overwrite an existing source: {path}')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8', newline='\n')


def inject_spring(project: Path) -> None:
    path = project / 'ptmcp-common/pom.xml'
    # Mutate only the copied module. Version remains governed by the actual BOM.
    namespace = 'http://maven.apache.org/POM/4.0.0'
    ET.register_namespace('', namespace)
    tree = ET.parse(path)
    dependencies = tree.getroot().find(f'{{{namespace}}}dependencies')
    if dependencies is None:
        dependencies = ET.SubElement(tree.getroot(), f'{{{namespace}}}dependencies')
    dependency = ET.SubElement(dependencies, f'{{{namespace}}}dependency')
    for name, value in (('groupId', 'org.springframework'), ('artifactId', 'spring-core')):
        ET.SubElement(dependency, f'{{{namespace}}}{name}').text = value
    tree.write(path, encoding='utf-8', xml_declaration=True)


def probes(source: Path, workspace: Path, timeout: int, seed_repository: Path | None) -> None:
    repository = workspace / 'repository'
    if seed_repository is not None:
        if not seed_repository.is_dir() or seed_repository.is_symlink():
            raise ProbeFailure('--seed-repository must be an existing non-symlink directory')
        shutil.copytree(seed_repository, repository, ignore=ignore_copy)
    else:
        repository.mkdir()
    environment = os.environ.copy()
    # Do not inherit -D skip flags, alternate repo arguments or user settings.
    for key in ('MAVEN_OPTS', 'MAVEN_ARGS', 'MVNW_USERNAME', 'MVNW_PASSWORD', 'MVNW_REPOURL'):
        environment.pop(key, None)
    wrapper_home = workspace / 'maven-user'
    cached_wrapper = Path(os.environ.get('MAVEN_USER_HOME', str(Path.home() / '.m2'))) / 'wrapper'
    if cached_wrapper.is_dir() and not cached_wrapper.is_symlink():
        shutil.copytree(cached_wrapper, wrapper_home / 'wrapper', ignore=ignore_copy)
    environment['MAVEN_USER_HOME'] = str(wrapper_home)
    settings = workspace / 'settings.xml'
    settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>', encoding='utf-8')

    def fresh(name: str) -> Path:
        destination = workspace / name
        copy_project(source, destination)
        return destination / 'ptmcp-parent'

    def run(project: Path, arguments: list[str]) -> tuple[int, str]:
        return run_maven(project, repository, environment,
                         ['-s', str(settings), '-gs', str(settings), *arguments], timeout)

    positive = fresh('positive')
    code, version = run(positive, ['--version'])
    if code or not re.search(r'Java version: 21(?:[.\s,]|$)', version):
        raise ProbeFailure('Wrapper must actually use Java 21; select the project environment first')
    assert_result('actual parent and all child modules validate', *run(positive, ['validate']))
    # This control proves the helper source and its location are valid before mutation.
    inject_java(positive, 'main', GOOD_JAVA)
    inject_java(positive, 'test', GOOD_JAVA)
    assert_result('formatted main/test fixture control', *run(positive, ['validate']))

    for source_set in ('main', 'test'):
        project = fresh(f'format-{source_set}')
        inject_java(project, source_set, BAD_FORMAT_JAVA)
        assert_result(f'Spotless rejects {source_set} formatting', *run(project, ['validate']),
                      ('spotless-maven-plugin:3.1.0:check', 'format violations', 'QualityProbe.java'))

    project = fresh('star-import')
    inject_java(project, 'main', STAR_JAVA)
    assert_result('wildcard fixture is already formatted',
                  *run(project, ['-pl', 'ptmcp-common', '-am', 'spotless:check']))
    assert_result('Checkstyle rejects formatted wildcard import', *run(project, ['validate']),
                  ('maven-checkstyle-plugin:3.6.0:check', 'AvoidStarImport', 'QualityProbe.java'))

    project = fresh('common-spring')
    inject_spring(project)
    assert_result('Enforcer rejects common Spring dependency', *run(project, ['validate']),
                  ('maven-enforcer-plugin:3.6.2:enforce', '(module-boundary)',
                   'BannedDependencies', 'org.springframework:spring-core', 'banned via the exclude/include list'))
    print('PASS: actual Maven quality probes; parser uniqueness/Sa-Token negative fixtures '
          'and business verification are not covered.', flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--timeout', type=int, default=180,
                        help='Timeout in seconds per Wrapper invocation (default: 180)')
    parser.add_argument('--seed-repository', type=Path,
                        help='Optional read-only cache seed; copied into the disposable repository')
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error('--timeout must be positive')
    try:
        with tempfile.TemporaryDirectory(prefix='ptmcp-quality-') as directory:
            probes(ROOT, Path(directory), args.timeout, args.seed_repository)
    except (ProbeFailure, OSError, subprocess.SubprocessError, ET.ParseError) as error:
        print(f'FAIL: {error}', flush=True)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
