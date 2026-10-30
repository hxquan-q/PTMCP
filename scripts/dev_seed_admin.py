#!/usr/bin/env python3
"""Create local-only PTMCP administrator seed SQL and credentials.

The generated password is written only to the credentials file (mode 0600);
it is never printed to stdout or embedded in SQL. The SQL contains only the
BCrypt hash. This script is for the disposable local development database.
"""

from __future__ import annotations

import argparse
import datetime as dt
import re
import secrets
import sys
from pathlib import Path

PERMISSION_PATTERN = re.compile(r"ptmcp:[a-z0-9-]+(?::[a-z0-9-]+)+")
SKIP_PERMISSIONS = {"ptmcp:session-expired"}


def collect_permissions(root: Path) -> list[str]:
    """Collect every ptmcp:* code the console can require.

    The React UI and frontend docs list the codes the console uses; the admin
    module's @AdminAction values are the server-side source of truth. Scanning
    both keeps an existing dev database in step when either side adds a code.
    """
    codes: set[str] = set()
    for base in (
        root / "ptmcp-ui-react" / "src",
        root / "ptmcp-parent" / "ptmcp-admin" / "src" / "main" / "java",
        root / "docs" / "frontend",
    ):
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if not path.is_file() or path.suffix not in {
                ".ts",
                ".vue",
                ".mjs",
                ".md",
                ".json",
                ".java",
            }:
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            codes.update(PERMISSION_PATTERN.findall(text))
    codes.difference_update(SKIP_PERMISSIONS)
    if len(codes) < 10:
        raise SystemExit(
            "Could not find enough ptmcp:* permission codes; run from the repository root."
        )
    return sorted(codes)


def bcrypt_hash(password: str) -> str:
    try:
        import crypt

        hashed = crypt.crypt(password, crypt.mksalt(crypt.METHOD_BLOWFISH))
    except (AttributeError, OSError, ValueError):
        hashed = None
    if hashed and hashed.startswith(("$2a$", "$2b$", "$2y$")):
        return hashed
    try:
        import bcrypt
    except ImportError as error:
        raise SystemExit(
            "BCrypt hashing is unavailable. Install python3-bcrypt or apache2-utils."
        ) from error
    return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt(rounds=12)).decode("ascii")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--credentials", type=Path)
    parser.add_argument(
        "--permissions-only",
        action="store_true",
        help="Emit only the platform_admin permission refresh; no credential or credentials file is touched.",
    )
    args = parser.parse_args()
    if not args.permissions_only and args.credentials is None:
        parser.error("--credentials is required unless --permissions-only is given")

    root = args.root.resolve()
    password = secrets.token_urlsafe(18)
    password_hash = bcrypt_hash(password)
    permissions = collect_permissions(root)
    created = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")

    permission_rows = "\n".join(
        "INSERT IGNORE INTO sys_role_permission "
        f"(role_id, permission_code, create_time, update_time) "
        f"VALUES (1, '{code}', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));"
        for code in permissions
    )

    if args.permissions_only:
        refresh_sql = (
            "-- Generated local development permission refresh. Do not commit.\n"
            "INSERT INTO sys_role\n"
            "    (role_id, role_key, role_name, status, del_flag, version, create_time, update_time)\n"
            "VALUES\n"
            "    (1, 'platform_admin', 'Platform Admin', '0', '0', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))\n"
            "ON DUPLICATE KEY UPDATE\n"
            "    role_name = VALUES(role_name),\n"
            "    status = '0',\n"
            "    del_flag = '0',\n"
            "    update_time = UTC_TIMESTAMP(6);\n\n"
            f"{permission_rows}\n"
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(refresh_sql, encoding="utf-8")
        args.output.chmod(0o600)
        print(f"Permission refresh written: {args.output} ({len(permissions)} codes)")
        return 0

    sql = f"""-- Generated local development seed. Do not commit.
INSERT INTO sys_dept
    (dept_id, parent_id, ancestors, dept_name, status, del_flag, version, create_time, update_time)
VALUES
    (1, 0, '0', 'PTMCP Dev', '0', '0', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    dept_name = VALUES(dept_name),
    status = '0',
    del_flag = '0',
    update_time = UTC_TIMESTAMP(6);

INSERT INTO sys_role
    (role_id, role_key, role_name, status, del_flag, version, create_time, update_time)
VALUES
    (1, 'platform_admin', 'Platform Admin', '0', '0', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    role_name = VALUES(role_name),
    status = '0',
    del_flag = '0',
    update_time = UTC_TIMESTAMP(6);

{permission_rows}

INSERT IGNORE INTO sys_user_profile
    (user_id, dept_id, display_name, password_hash, status, version, create_time, update_time)
VALUES
    (1, 1, 'PTMCP Dev Admin', '{password_hash}', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

INSERT IGNORE INTO sys_user_login_name
    (user_id, normalized_name, create_time, update_time)
VALUES
    (1, 'admin', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

INSERT IGNORE INTO sys_user_role
    (user_id, role_id, create_time, update_time)
VALUES
    (1, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
"""

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(sql, encoding="utf-8")
    args.output.chmod(0o600)

    credentials = f"""PTMCP local development administrator
URL: http://127.0.0.1:25175 (PTMCP_UI_PUBLIC=true: http://127.0.0.1:25173)
Username: admin
Password: {password}
Created: {created}

This password is only for the disposable local development database.
Change it after the first login if the environment is shared.
"""
    args.credentials.parent.mkdir(parents=True, exist_ok=True)
    args.credentials.write_text(credentials, encoding="utf-8")
    args.credentials.chmod(0o600)

    print(f"Local administrator seed written. Credentials: {args.credentials}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
