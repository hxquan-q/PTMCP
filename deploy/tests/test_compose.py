"""Isolated Compose/MySQL privilege and secret-volume tests. Requires Docker Compose v2."""

import json
from http.cookiejar import CookieJar
import os
import re
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import unittest
import uuid
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, ProxyHandler, Request, build_opener


ROOT = Path(__file__).resolve().parents[2]


class ComposeDeploymentTests(unittest.TestCase):
    def test_bootstrap_migration_runtime_privileges_and_signing_key(self):
        with tempfile.TemporaryDirectory(prefix='ptmcp-compose-') as name:
            directory = Path(name)
            deploy = directory / 'deploy'
            shutil.copytree(ROOT / 'deploy', deploy, ignore=shutil.ignore_patterns(
                'secrets', '.env', 'dist', 'tests', '__pycache__'))
            migration = Path('ptmcp-parent/ptmcp-persistence/src/main/resources/db/ptmcp-migration')
            shutil.copytree(ROOT / migration, directory / migration)
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0))
                port = sock.getsockname()[1]
            base = f'http://localhost:{port}'
            generated = subprocess.run(['bash', str(deploy / 'init-env.sh'), base],
                                       capture_output=True, text=True)
            self.assertEqual(0, generated.returncode, 'Synthetic environment generation failed')
            command = ['docker', 'compose', '-p', 'ptmcp-review-' + uuid.uuid4().hex[:12],
                       '--project-directory', str(deploy), '--env-file', str(deploy / '.env')]

            def compose(*args, **kwargs):
                return subprocess.run([*command, *args], capture_output=True, text=True, **kwargs)

            def sql(user, password, statement):
                return compose('exec', '-T', '-e', f'MYSQL_PWD={password}', 'mysql', 'mysql',
                               f'-u{user}', '-N', '-B', 'ptmcp', input=statement)

            def denied_sql(role, password, statement):
                result = sql(f'ptmcp_{role}', password, statement)
                self.assertTrue(re.search(r'ERROR (1142|1143)\b', result.stderr) is not None,
                                f'{role} must fail at the privilege boundary: {result.stderr}')

            try:
                config_result = compose('config', '--format', 'json')
                self.assertEqual(0, config_result.returncode, config_result.stderr)
                config = json.loads(config_result.stdout)
                services = config['services']
                users = {services['migrate']['environment']['FLYWAY_USER'],
                         services['admin']['environment']['PTMCP_ADMIN_DATASOURCE_USERNAME'],
                         services['authz']['environment']['PTMCP_AUTHZ_DB_USERNAME'],
                         services['gateway']['environment']['PTMCP_GATEWAY_METADATAUSERNAME']}
                self.assertEqual(4, len(users), 'Migration and application accounts must be distinct')
                for service in ('admin', 'gateway'):
                    self.assertFalse(any(v['source'] == 'authz-key' for v in services[service]['volumes'])
                                     if services[service].get('volumes') else False)
                started = compose('up', '-d', '--wait', '--wait-timeout', '120', 'mysql', timeout=150)
                if started.returncode:
                    logs = compose('logs', '--no-color', 'mysql')
                    self.fail(f'Isolated MySQL startup failed: {started.stderr}\n{logs.stdout}')
                migrated = compose('run', '--rm', 'migrate', timeout=150)
                self.assertEqual(0, migrated.returncode, migrated.stdout + migrated.stderr)
                configured = compose('run', '--rm', '--no-deps', 'configure-db', timeout=90)
                self.assertEqual(0, configured.returncode, configured.stdout + configured.stderr)
                runtime = {
                    'admin': ('PTMCP_ADMIN_DATASOURCE_PASSWORD', 'SELECT id FROM datasource; UPDATE datasource SET name=name WHERE 1=0;'),
                    'authz': ('PTMCP_AUTHZ_DB_PASSWORD', 'SELECT id FROM oauth2_registered_client; UPDATE oauth_refresh_token SET status=status WHERE 1=0;'),
                    'gateway': ('PTMCP_GATEWAY_METADATAPASSWORD', 'SELECT user_id FROM sys_user_security; UPDATE audit_log SET terminal_hash=terminal_hash WHERE 1=0;'),
                }
                root_password = (deploy / 'secrets/mysql_root.txt').read_text().strip()
                self.assertEqual(0, sql('root', root_password, 'CREATE TABLE deployment_probe (id INT);').returncode)
                for role, (password_key, allowed) in runtime.items():
                    password = services[role]['environment'][password_key]
                    self.assertTrue(password == (deploy / f'secrets/mysql_{role}.txt').read_text().strip(),
                                    'Runtime password and initialization secret differ')
                    result = sql(f'ptmcp_{role}', password, allowed)
                    if result.returncode:
                        users = sql('root', root_password, 'SELECT user,host FROM mysql.user;')
                        logs = compose('logs', '--no-color', 'mysql')
                        self.fail(f'{role} required runtime operation failed: {result.stderr}\n{users.stdout}\n{logs.stdout}')
                    for ddl in ('CREATE TABLE forbidden_probe (id INT);',
                                'ALTER TABLE deployment_probe ADD COLUMN forbidden INT;',
                                'DROP TABLE deployment_probe;'):
                        denied_sql(role, password, ddl)
                for role, denied in (
                        ('authz', 'UPDATE datasource SET name=name WHERE 1=0;'),
                        ('gateway', 'UPDATE sys_user_security SET auth_version=auth_version WHERE 1=0;'),
                        ('admin', 'UPDATE audit_log SET terminal_hash=terminal_hash WHERE 1=0;'),
                        ('admin', 'UPDATE oauth2_authorization SET authorization_code_value=NULL WHERE 1=0;')):
                    denied_sql(role, services[role]['environment'][runtime[role][0]], denied)
                # 密码表由宿主接入；重跑权限步骤后 authz 可读、不可改，gateway 仍不可读。
                self.assertEqual(0, sql('root', root_password, 'CREATE TABLE sys_user (user_id BIGINT, password VARCHAR(100));').returncode)
                configured = compose('run', '--rm', '--no-deps', 'configure-db', timeout=90)
                self.assertEqual(0, configured.returncode, configured.stdout + configured.stderr)
                self.assertEqual(0, sql('ptmcp_authz', services['authz']['environment']['PTMCP_AUTHZ_DB_PASSWORD'], 'SELECT password FROM sys_user;').returncode)
                denied_sql('authz', services['authz']['environment']['PTMCP_AUTHZ_DB_PASSWORD'], 'UPDATE sys_user SET password=NULL WHERE 1=0;')
                denied_sql('gateway', services['gateway']['environment']['PTMCP_GATEWAY_METADATAPASSWORD'], 'SELECT password FROM sys_user;')
                signing_volume = config['volumes']['authz-key']['name']
                image = services['prepare-secrets']['image']
                for user, expected in (('10001:10001', 0), ('10002:10002', 1)):
                    result = subprocess.run(['docker', 'run', '--rm', '--read-only', '--cap-drop', 'ALL',
                                             '--user', user, '-v', f'{signing_volume}:/signing:ro',
                                             '--entrypoint', 'bash', image, '-c',
                                             'test -r /signing/oauth-signing.pem && head -c 1 /signing/oauth-signing.pem >/dev/null'],
                                            capture_output=True, text=True)
                    self.assertEqual(expected, result.returncode, 'Signing-key reader boundary failed')
                self.assertEqual(0o600, (deploy / 'secrets/oauth-signing.pem').stat().st_mode & 0o777)
                # 显式空值在 Compose 展开后仍为空，不能重新放行默认端口。
                with (deploy / '.env').open('a') as file:
                    file.write("\nPTMCP_BUSINESS_ALLOWED_PORTS=''\n")
                empty = json.loads(compose('config', '--format', 'json').stdout)['services']
                self.assertEqual('', empty['admin']['environment']['PTMCP_ADMIN_DATASOURCE_ALLOWEDPORTS'])
                self.assertEqual('', empty['gateway']['environment']['PTMCP_GATEWAY_ALLOWEDPORTS'])
                if os.environ.get('PTMCP_DEPLOY_TEST_IMAGES') == '1':
                    # Docker Compose v2.24.4+；只发布回环，使用已构建的当前源码镜像。
                    override = 'services:\n'
                    for role in ('admin', 'authz', 'gateway', 'ui'):
                        override += f'  {role}:\n    image: ptmcp-review-{role}:local\n'
                        if role == 'ui':
                            override += f'    ports: !override\n      - "127.0.0.1:{port}:8080"\n'
                    (deploy / 'compose.override.yaml').write_text(override, encoding='utf-8')
                    started = compose('up', '-d', '--no-build', '--wait', '--wait-timeout', '180', 'ui', timeout=210)
                    self.assertEqual(0, started.returncode, started.stderr)
                    client = build_opener(ProxyHandler({}), HTTPCookieProcessor(CookieJar()))
                    with client.open(Request(base + '/backend/admin/api/v1/session/csrf',
                                             headers={'Origin': base}), timeout=10) as response:
                        csrf = json.load(response)['csrfToken']
                    try:
                        client.open(Request(base + '/backend/admin/api/v1/session/csrf',
                                            headers={'Origin': 'http://attacker.example'}), timeout=10)
                        self.fail('Foreign Origin unexpectedly obtained CSRF token')
                    except HTTPError as error:
                        self.assertEqual(403, error.code)
                    request = Request(base + '/backend/admin/api/v1/session/login',
                                      data=json.dumps({'username': 'synthetic-missing', 'password': 'synthetic'}).encode(),
                                      headers={'Origin': base, 'Content-Type': 'application/json', 'X-CSRF-Token': csrf})
                    try:
                        client.open(request, timeout=10)
                        self.fail('Unknown account unexpectedly logged in')
                    except HTTPError as error:
                        self.assertEqual(401, error.code, 'Admin login failed before credential validation')
                    with client.open(base + '/login', timeout=10) as response:
                        self.assertTrue(b'/oauth/' in response.read(), 'OAuth React login page missing')
                    with client.open(base + '/oauth2/jwks', timeout=10) as response:
                        self.assertTrue(json.load(response)['keys'], 'Signing key did not load')
                    with client.open(base + '/oauth2/ui/login', timeout=10) as response:
                        context = json.load(response)
                    form = urlencode({'username': 'synthetic-missing', 'password': 'synthetic', '_csrf': context['csrfToken']}).encode()
                    with client.open(base + '/login', data=form, timeout=10) as response:
                        self.assertTrue('/login' in response.url, 'OAuth login failure was not routed to authz')
                    with client.open(base + '/oauth2/ui/login', timeout=10) as response:
                        context = json.load(response)
                    with client.open(base + '/logout', data=urlencode({'_csrf': context['csrfToken']}).encode(), timeout=10) as response:
                        self.assertTrue('/login' in response.url, 'OAuth logout was not routed to authz')
                    print('PASS: four current images healthy; same-Origin CSRF accepted, foreign Origin denied; OAuth login/logout/JWKS reachable')
            finally:
                removed = compose('down', '-v', '--remove-orphans', timeout=60)
                self.assertEqual(0, removed.returncode, 'Isolated Compose cleanup failed')


if __name__ == '__main__':
    unittest.main()
