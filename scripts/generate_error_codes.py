"""Generate the pure Java error enum from the sole public allocation registry."""

import argparse
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA_PATH = Path('ptmcp-parent/ptmcp-common/src/main/java/local/ptmcp/common/error/ErrorCode.java')


def render_java(registry: dict) -> str:
    """Render a registry already accepted by validate_registry, without changing it."""
    constants = ',\n'.join(f"    {entry['key']}({entry['code']})" for entry in registry['errors'])
    return f'''package local.ptmcp.common.error;

import javax.annotation.processing.Generated;

/** Generated from config/quality/rest-error-codes.json; do not edit. */
@Generated("scripts/generate_error_codes.py")
public enum ErrorCode {{
{constants};

    private final int restCode;

    ErrorCode(int restCode) {{
        this.restCode = restCode;
    }}

    public int restCode() {{
        return restCode;
    }}
}}
'''


def main(argv: list[str] | None = None) -> int:
    from verify_quality_config import validate_registry

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Reject missing or stale output without writing files.')
    args = parser.parse_args(argv)
    target = ROOT / JAVA_PATH
    try:
        registry = json.loads((ROOT / 'config/quality/rest-error-codes.json').read_text(encoding='utf-8'))
        errors = validate_registry(registry)
        if errors:
            print('FAIL: ' + '; '.join(errors))
            return 1
        expected = render_java(registry)
        if args.check:
            if not target.is_file() or target.read_text(encoding='utf-8-sig') != expected:
                print(f'FAIL: {JAVA_PATH.as_posix()} is missing or stale; run python scripts/generate_error_codes.py')
                return 1
            print('PASS: generated error enum matches the allocation registry.')
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(expected, encoding='utf-8', newline='\n')
            print(f'GENERATED: {JAVA_PATH.as_posix()}')
    except (OSError, ValueError) as exc:
        print(f'FAIL: {exc}')
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
