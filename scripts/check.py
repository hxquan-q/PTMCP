"""Repository checks; --all also builds the backend and frontend frameworks."""

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
    ("-m", "unittest", "discover", "-s", "scripts/tests", "-v"),
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--all', dest='all_projects', action='store_true', help='Also run Maven verify and pnpm check; select the development environment first.')
    args = parser.parse_args()
    for arguments in CHECKS:
        print(f"CHECK: {' '.join(arguments)}", flush=True)
        result = subprocess.run([sys.executable, "-B", *arguments], cwd=ROOT)
        if result.returncode:
            return result.returncode
    if args.all_projects:
        commands = (
            (['mvnw.cmd', '-B', '-ntp', 'verify'] if os.name == 'nt' else ['sh', './mvnw', '-B', '-ntp', 'verify'], ROOT / 'ptmcp-parent'),
            (['pnpm.cmd', 'check'] if os.name == 'nt' else ['pnpm', 'check'], ROOT / 'ptmcp-ui'),
        )
        for command, directory in commands:
            if os.name == 'nt':
                command = [os.environ.get('COMSPEC', 'cmd.exe'), '/d', '/c', *command]
            print(f"CHECK: {directory.name}: {' '.join(command)}", flush=True)
            result = subprocess.run(command, cwd=directory)
            if result.returncode:
                return result.returncode
        print('PASS: repository and framework builds. Business implementation and verification remain separate.')
    else:
        print('PASS: repository documentation/workflow/configuration checks; use --all for actual framework builds.')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
