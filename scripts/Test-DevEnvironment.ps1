param([string]$EnvironmentRoot = 'D:\config\ptmcp')

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Enter-Dev.ps1') -EnvironmentRoot $EnvironmentRoot
$java = (& java -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $java -notmatch 'version "21\.') { throw 'JDK 21 check failed.' }
$maven = (& mvn --version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $maven -notmatch 'Java version: 21\.') { throw 'Maven must run on JDK 21.' }
$node = (& node --version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $node -notmatch '^v24\.') { throw 'Node 24 LTS check failed.' }
$pnpm = (& pnpm --version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $pnpm -ne '11.9.0') { throw 'pnpm version check failed.' }
$store = (& pnpm store path | Out-String).Trim()
$expectedStore = [IO.Path]::GetFullPath((Join-Path $EnvironmentRoot 'cache\pnpm'))
if (-not ([IO.Path]::GetFullPath($store)).StartsWith($expectedStore, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'pnpm store is outside the project environment.'
}
Write-Output "PASS: Java 21 / Maven on Java 21 / Node $node / pnpm $pnpm / D-drive cache."

$health = @(& wsl.exe -d PTMCP-Dev -u root --exec docker inspect --format '{{.State.Health.Status}}' `
    ptmcp-mysql ptmcp-postgres ptmcp-redis)
if ($LASTEXITCODE -ne 0 -or $health.Count -ne 3 -or @($health | Where-Object { $_.Trim() -ne 'healthy' }).Count) {
    throw 'Services are not healthy. Run scripts/Dev-Services.ps1 start.'
}
foreach ($port in @(13306, 15432, 16379)) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        if (-not $client.ConnectAsync('127.0.0.1', $port).Wait(3000)) { throw "Port $port is unavailable." }
    }
    finally { $client.Dispose() }
}
Write-Output 'PASS: MySQL / PostgreSQL / Redis healthy and reachable from Windows.'

@'
import os, ssl
from pathlib import Path
from urllib.request import build_opener, ProxyHandler, HTTPSHandler
cert = Path(os.environ['DOCKER_CERT_PATH'])
context = ssl.create_default_context(cafile=str(cert / 'ca.pem'))
context.load_cert_chain(certfile=str(cert / 'cert.pem'), keyfile=str(cert / 'key.pem'))
with build_opener(ProxyHandler({}), HTTPSHandler(context=context)).open('https://127.0.0.1:12376/_ping', timeout=5) as response:
    if response.read() != b'OK':
        raise RuntimeError('Docker mutual TLS health failed')
print('PASS: Windows Docker API connection verifies server and client certificates.')
'@ | python -
if ($LASTEXITCODE -ne 0) { throw 'Docker mutual TLS check failed.' }
Write-Output 'Development environment checks passed; PTMCP application acceptance is separate.'
