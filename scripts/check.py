"""Repository checks; --all also verifies projects and actual backend quality probes."""

import argparse
import os
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
CHECKS = (
    ("docs/evidence/validate_docs.py",),
    ("scripts/verify_agent_workflow.py",),
    ("scripts/verify_quality_config.py",),
    ("scripts/verify_commits.py",),
    ("-m", "unittest", "discover", "-s", "scripts/tests", "-v"),
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--all', dest='all_projects', action='store_true', help='Also run Maven verify, isolated quality probes and pnpm check; select the development environment first.')
    parser.add_argument('--maven-cache-seed', help='Optional read-only cache seed for isolated Maven probes; requires --all.')
    args = parser.parse_args()
    if args.maven_cache_seed and not args.all_projects:
        parser.error('--maven-cache-seed requires --all')
    for arguments in CHECKS:
        print(f"CHECK: {' '.join(arguments)}", flush=True)
        result = subprocess.run([sys.executable, "-B", *arguments], cwd=ROOT)
        if result.returncode:
            return result.returncode
    if args.all_projects:
        # 探针仓库一次性空缓存，首次 validate 需全量拉取插件；默认 180s 在冷环境必然超时
        # （两次实测：冷镜像下载 >180s，断言未动）。放宽的是门禁工具等待，不是检查本身。
        probes = [sys.executable, '-B', str(ROOT / 'scripts/check_backend_quality.py'), '--timeout', '900']
        if args.maven_cache_seed:
            probes.extend(['--seed-repository', args.maven_cache_seed])
        commands = (
            (['mvnw.cmd', '-B', '-ntp', 'verify'] if os.name == 'nt' else ['sh', './mvnw', '-B', '-ntp', 'verify'], ROOT / 'ptmcp-parent'),
            (probes, ROOT),
            (['pnpm.cmd', 'check'] if os.name == 'nt' else ['pnpm', 'check'], ROOT / 'ptmcp-ui'),
        )
        for command, directory in commands:
            if os.name == 'nt' and command[0].lower().endswith('.cmd'):
                command = [os.environ.get('COMSPEC', 'cmd.exe'), '/d', '/c', *command]
            print(f"CHECK: {directory.name}: {' '.join(command)}", flush=True)
            result = subprocess.run(command, cwd=directory)
            if result.returncode:
                return result.returncode
        print('PASS: repository checks, backend verification/quality probes and frontend checks. Business implementation and verification remain separate.')
    else:
        print('PASS: repository documentation/workflow/configuration checks; use --all for actual framework builds.')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
