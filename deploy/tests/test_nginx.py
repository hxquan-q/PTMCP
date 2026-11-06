"""Real nginx route checks. Run on Linux with Docker: python3 deploy/tests/test_nginx.py."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from urllib.error import URLError
from urllib.request import ProxyHandler, Request, build_opener


ROOT = Path(__file__).resolve().parents[2]
IMAGE = 'nginx@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10'
urlopen = build_opener(ProxyHandler({})).open


class Upstream(BaseHTTPRequestHandler):
    def do_GET(self):
        payload = json.dumps({'role': self.server.role, 'path': self.path,
                              'host': self.headers['Host'], 'method': self.command}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(payload)

    do_POST = do_GET

    def log_message(self, *_):
        pass


class NginxDeploymentTests(unittest.TestCase):
    def test_all_templates_route_login_logout_and_preserve_authority(self):
        for kind in ('docker', 'linux', 'windows'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(prefix='ptmcp nginx ') as name:
                directory = Path(name)
                directory.chmod(0o755)
                for child in ('run', 'logs', 'temp', 'frontend', 'nginx'):
                    (directory / child).mkdir()
                (directory / 'frontend/index.html').write_text('SPA', encoding='utf-8')
                (directory / 'nginx/mime.types').write_bytes(
                    (ROOT / 'deploy/native/linux/nginx/mime.types').read_bytes())
                servers = []
                container = None
                try:
                    ports = {}
                    for role in ('admin', 'authz', 'gateway'):
                        server = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
                        server.role = role
                        servers.append(server)
                        ports[role] = server.server_port
                        threading.Thread(target=server.serve_forever, daemon=True).start()
                    with socket.socket() as sock:
                        sock.bind(('127.0.0.1', 0))
                        port = sock.getsockname()[1]
                    if kind == 'docker':
                        text = (ROOT / 'deploy/docker/nginx.conf').read_text(encoding='utf-8')
                        text = text.replace('listen 8080;', f'listen 127.0.0.1:{port};')
                        text = text.replace('/usr/share/nginx/html', f'"{directory}/frontend"')
                        for role, original in (('admin', 8080), ('authz', 8081), ('gateway', 8082)):
                            text = text.replace(f'{role}:{original}', f'127.0.0.1:{ports[role]}')
                    else:
                        text = (ROOT / f'deploy/native/{kind}/nginx/nginx.conf.template').read_text(encoding='utf-8')
                        mapping = {'HTTP_PORT': f'127.0.0.1:{port}', **{f'{r.upper()}_PORT': str(p) for r, p in ports.items()},
                                   **{f'{r.upper()}_DIR': str(directory / p) for r, p in (
                                       ('run', 'run'), ('log', 'logs'), ('temp', 'temp'),
                                       ('frontend', 'frontend'), ('nginx', 'nginx'))}}
                        for key, value in mapping.items():
                            text = text.replace(f'@{key}@', value)
                    config = directory / 'run/nginx.conf'
                    text = text.replace('worker_processes auto;', 'worker_processes 1;')
                    config.write_text(text, encoding='utf-8')
                    result = subprocess.run(['docker', 'run', '-d', '--network', 'host',
                                             '-v', f'{directory}:{directory}', '--entrypoint', 'nginx', IMAGE,
                                             '-c', str(config), '-g', 'daemon off;'],
                                            capture_output=True, text=True, check=True)
                    container = result.stdout.strip()
                    for attempt in range(40):
                        try:
                            with urlopen(f'http://127.0.0.1:{port}/healthz', timeout=1):
                                break
                        except (URLError, OSError):
                            if attempt == 39:
                                logs = subprocess.run(['docker', 'logs', container], capture_output=True, text=True)
                                state = subprocess.run(['docker', 'inspect', '--format', '{{json .State}}', container], capture_output=True, text=True)
                                self.fail(f'nginx failed at {port}: {logs.stdout}{logs.stderr} {state.stdout}')
                            time.sleep(0.1)
                    for path, role, upstream in (
                            ('/login?continue=1', 'authz', '/login?continue=1'),
                            ('/logout', 'authz', '/logout'),
                            ('/oauth2/authorize?client_id=test', 'authz', '/oauth2/authorize?client_id=test'),
                            ('/backend/admin/api/v1/session', 'admin', '/api/v1/session')):
                        for method in ('GET', 'POST'):
                            request = Request(f'http://127.0.0.1:{port}{path}', method=method,
                                              headers={'Host': f'localhost:{port}', 'Origin': f'http://localhost:{port}'})
                            with urlopen(request, timeout=3) as response:
                                raw = response.read().decode()
                            self.assertEqual({'role': role, 'path': upstream, 'host': f'localhost:{port}',
                                              'method': method}, json.loads(raw), raw)
                finally:
                    if container:
                        subprocess.run(['docker', 'rm', '-f', container], capture_output=True, check=True)
                    for server in servers:
                        server.shutdown()
                        server.server_close()


if __name__ == '__main__':
    unittest.main()
