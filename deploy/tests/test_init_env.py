"""Execute Linux generators using synthetic configurations; no real secrets are retained."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class InitEnvironmentTests(unittest.TestCase):
    def fixture(self, directory, kind):
        if kind == 'compose':
            shutil.copytree(ROOT / 'deploy', directory / 'deploy', ignore=shutil.ignore_patterns(
                'secrets', '.env', 'dist', '__pycache__'))
            return directory / 'deploy/init-env.sh', directory / 'deploy/.env'
        scripts = directory / 'releases/test/scripts'
        shutil.copytree(ROOT / 'deploy/native/linux/scripts', scripts)
        shutil.copy(ROOT / 'deploy/base-url.sh', scripts)
        return scripts / 'init-env.sh', directory / 'shared/config/app.env'

    def test_invalid_bases_are_rejected_before_writing_secrets(self):
        for kind in ('compose', 'native'):
            for base in ('http://192.168.1.10:8080', 'http://example.com', 'http://127.0.0.2:8080',
                         'http://localhost:0', 'http://localhost:65536', 'https://example.com/path',
                         'https://user:pass@example.com', 'https://example.com?x=1',
                         'https://example.com#fragment', 'https://[::::]:443', 'https://bad_host',
                         'https://host-', 'https://example.123', 'https://1.2.3'):
                with self.subTest(kind=kind, base=base), tempfile.TemporaryDirectory() as name:
                    script, config = self.fixture(Path(name), kind)
                    result = subprocess.run(['bash', str(script), base], capture_output=True, text=True)
                    self.assertNotEqual(0, result.returncode, result.stdout)
                    self.assertFalse(config.exists())
                    self.assertEqual([], list(Path(name).rglob('*.pem')))

    def test_valid_bases_derive_direct_http_ports(self):
        for kind in ('compose', 'native'):
            for base, port in (('http://localhost:19090', '19090'), ('http://127.0.0.1', '80'),
                               ('http://[::1]:19091/', '19091'), ('https://example.com:8443', '8080'),
                               ('https://123', '8080'), ('https://example.com.', '8080'),
                               ('https://[2001:db8::1]:8443', '8080')):
                with self.subTest(kind=kind, base=base), tempfile.TemporaryDirectory() as name:
                    script, config = self.fixture(Path(name), kind)
                    result = subprocess.run(['bash', str(script), base], capture_output=True, text=True)
                    self.assertEqual(0, result.returncode, result.stderr)
                    content = config.read_text(encoding='utf-8')
                    self.assertTrue(f'PTMCP_HTTP_PORT={port}\n' in content, 'Generated HTTP listener port differs')
                    self.assertTrue(base.rstrip('/') in content, 'Generated base URL differs')
                    self.assertEqual(0o600, config.stat().st_mode & 0o777)

    def test_empty_port_policy_is_distinct_from_unset(self):
        lib = ROOT / 'deploy/native/linux/scripts/lib.sh'
        script = '''source "$1"
PTMCP_PUBLIC_BASE_URL=http://localhost:8080
PTMCP_METADATA_JDBC_URL=jdbc:synthetic
PTMCP_METADATA_DB_USERNAME=synthetic
PTMCP_METADATA_DB_PASSWORD=synthetic
PTMCP_CREDENTIAL_KEY=synthetic
PTMCP_AUDIT_KEY=synthetic
PTMCP_GATEWAY_WAL_KEY=synthetic
DEPLOY_ROOT=/tmp/synthetic
RELEASE=/tmp/synthetic/releases/test
ptmcp_export_app_env
printf '%s|%s' "$PTMCP_ADMIN_DATASOURCE_ALLOWEDPORTS" "$PTMCP_GATEWAY_ALLOWEDPORTS"
'''
        for value, expected in ((None, '3306,5432|3306,5432'), ('', '|'), ('15432', '15432|15432')):
            env = dict(os.environ)
            env.pop('PTMCP_BUSINESS_ALLOWED_PORTS', None)
            if value is not None:
                env['PTMCP_BUSINESS_ALLOWED_PORTS'] = value
            result = subprocess.run(['bash', '-c', script, 'test', str(lib)], env=env,
                                    capture_output=True, text=True, check=True)
            self.assertEqual(expected, result.stdout)


if __name__ == '__main__':
    unittest.main()
