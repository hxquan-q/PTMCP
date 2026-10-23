"""Validate checked-in tool templates and REST error allocations without running tools."""

from pathlib import Path
import json
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]


def validate_registry(registry: dict) -> list[str]:
    if not isinstance(registry, dict):
        return ['error registry must be an object']
    errors = []
    if registry.get('successCode') != 200:
        errors.append('R<T> successCode must remain 200')
    modules = registry.get('modules')
    entries = registry.get('errors')
    if not isinstance(modules, dict) or not modules or not isinstance(entries, list) or not entries:
        return errors + ['modules and errors must be non-empty objects/list']
    ranges = {}
    for name, bounds in modules.items():
        if (not isinstance(bounds, list) or len(bounds) != 2
                or not all(type(value) is int for value in bounds)
                or not 100000 <= bounds[0] <= bounds[1] <= 999999):
            errors.append(f'invalid six-digit range: {name}')
            continue
        for other, (start, end) in ranges.items():
            if max(start, bounds[0]) <= min(end, bounds[1]):
                errors.append(f'overlapping module ranges: {other}, {name}')
        ranges[name] = bounds
    keys, codes = set(), set()
    for entry in entries:
        if not isinstance(entry, dict):
            errors.append('error entry must be an object')
            continue
        key, module, code = (entry.get(name) for name in ('key', 'module', 'code'))
        if not isinstance(key, str) or not re.fullmatch(r'[A-Z][A-Z0-9_]*', key):
            errors.append('invalid symbolic error key')
            continue
        if key in keys:
            errors.append(f'duplicate symbolic key: {key}')
        keys.add(key)
        if not isinstance(module, str) or module not in ranges:
            errors.append(f'unknown or invalid module: {key}')
        elif type(code) is not int or not ranges[module][0] <= code <= ranges[module][1]:
            errors.append(f'error outside module range: {key}')
        if type(code) is not int:
            errors.append(f'integer code required: {key}')
        elif code in codes:
            errors.append(f'duplicate numeric code: {code}')
        else:
            codes.add(code)
    return errors


def validate_generated_errors(root: Path, registry: dict) -> list[str]:
    """Fail closed on missing/drifted compiled contracts; never regenerate in a gate."""
    from generate_error_codes import JAVA_PATH, render_java

    try:
        if (root / JAVA_PATH).read_text(encoding='utf-8-sig') == render_java(registry):
            return []
    except (OSError, UnicodeError):
        pass
    return ['generated ErrorCode.java is missing or stale; run python scripts/generate_error_codes.py']


def main() -> int:
    directory = ROOT / 'config/quality'
    errors = []
    try:
        registry = json.loads((directory / 'rest-error-codes.json').read_text(encoding='utf-8'))
        errors.extend(validate_registry(registry))
        if not errors:
            errors.extend(validate_generated_errors(ROOT, registry))
        for name, expected in (('checkstyle.xml', 'module'), ('java-plugins.xml', 'plugins')):
            if ET.parse(directory / name).getroot().tag != expected:
                errors.append(f'invalid XML root: {name}')
        for name in ('frontend/package.fragment.json', 'frontend/.prettierrc.json'):
            json.loads((directory / name).read_text(encoding='utf-8'))
    except (OSError, ValueError, ET.ParseError) as exc:
        errors.append(str(exc))
    print(json.dumps({
        'status': 'FAIL' if errors else 'PASS', 'errors': errors,
        'limits': 'Parses templates, validates error allocation and generated enum consistency; does not run Maven, ESLint, Prettier or ASVS verification.',
    }, ensure_ascii=False, indent=2))
    return int(bool(errors))


if __name__ == '__main__':
    raise SystemExit(main())
