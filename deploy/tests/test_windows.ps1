# Windows PowerShell 5.1: powershell.exe -NoProfile -File deploy/tests/test_windows.ps1
param([string]$NginxExe = '')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('ptmcp deploy ' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
function Assert($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
try {
    $release = Join-Path $scratch 'releases\test'
    New-Item -ItemType Directory -Path (Join-Path $release 'scripts') -Force | Out-Null
    Copy-Item (Join-Path $repo 'deploy\native\windows\scripts\*') (Join-Path $release 'scripts')
    Copy-Item (Join-Path $repo 'deploy\native\windows\nginx') $release -Recurse
    Import-Module (Join-Path $release 'scripts\Platform.Runtime.psm1') -Force
    $stress = '[Console]::Error.Write((''x'' * 65536)); [Console]::Out.Write(''done'')'
    $stressEncoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($stress))
    $streams = Invoke-PtmcpProcess -FilePath (Join-Path $PSHOME 'powershell.exe') -Arguments @('-NoProfile', '-EncodedCommand', $stressEncoded)
    Assert ($streams.ExitCode -eq 0 -and $streams.Output -eq 'done' -and $streams.Error.Length -eq 65536) 'Process streams deadlocked or lost output'
    $initializer = Join-Path $repo 'deploy\native\windows\init-env.ps1'
    foreach ($base in @('http://192.168.1.10:8080', 'http://example.com', 'http://127.0.0.2',
            'https://user:pass@example.com', 'https://example.com/path', 'http://localhost:0',
            'http://localhost:65536', 'https://example.com?x=1', 'https://example.com#x',
            'https://[::::]:443', 'https://bad_host', 'https://host-', 'https://example.123', 'https://1.2.3')) {
        $failed = $false
        try { & $initializer -BaseUrl $base -DeployRoot $scratch *> $null } catch { $failed = $true }
        Assert $failed "Accepted invalid base: $base"
        Assert (-not (Test-Path (Join-Path $scratch 'shared\config\app.env'))) 'Wrote config for invalid base'
        Assert (-not (Test-Path (Join-Path $scratch 'shared\config\secrets\oauth-signing.pem'))) 'Wrote secret for invalid base'
    }
    # 模拟一个向 Users 继承读取权限的安装目录。
    $acl = Get-Acl $scratch
    $users = New-Object Security.Principal.SecurityIdentifier('S-1-5-32-545')
    $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($users, 'ReadAndExecute',
        'ContainerInherit,ObjectInherit', 'None', 'Allow')))
    Set-Acl -LiteralPath $scratch -AclObject $acl
    foreach ($case in @(@('http://localhost:19090', '19090'), @('http://127.0.0.1', '80'),
            @('http://[::1]:19091/', '19091'), @('https://example.com:8443', '8080'),
            @('https://123', '8080'), @('https://example.com.', '8080'), @('https://[2001:db8::1]:8443', '8080'))) {
        foreach ($relative in @('shared\config\app.env', 'shared\config\secrets\oauth-signing.pem')) {
            $existing = Join-Path $scratch $relative
            if (Test-Path -LiteralPath $existing) {
                $broad = Get-Acl $existing
                $broad.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($users, 'Read', 'Allow')))
                [IO.File]::SetAccessControl($existing, $broad)
            }
        }
        & $initializer -BaseUrl $case[0] -DeployRoot $scratch -Force *> $null
        $ctx = New-PtmcpContext $scratch $release
        Assert ($ctx.Config['PTMCP_HTTP_PORT'] -eq $case[1]) 'Generated listener port differs from public HTTP port'
        foreach ($relative in @('shared\config', 'shared\config\secrets', 'shared\config\app.env',
                'shared\config\secrets\oauth-signing.pem')) {
            $secretAcl = Get-Acl (Join-Path $scratch $relative)
            $permitted = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18')
            foreach ($rule in $secretAcl.Access) {
                $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
                Assert ($sid -in $permitted) "Unexpected secret ACL principal on $relative"
            }
        }
    }
    New-Item -ItemType Directory -Force -Path (Join-Path $scratch 'run') | Out-Null
    $conf = Write-PtmcpNginxConf $ctx
    $bytes = [IO.File]::ReadAllBytes($conf)
    Assert (-not ($bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191)) 'nginx UTF-8 BOM'
    Assert ((Get-Content -LiteralPath $conf -Raw).Contains('pid "')) 'Unquoted nginx path'
    $emptyPid = Join-Path $scratch 'run\nginx.pid'
    [IO.File]::WriteAllText($emptyPid, '')
    Assert ($null -eq (Get-PtmcpNginxPid $ctx)) 'Empty nginx PID must remain a pending startup state'
    Remove-Item -LiteralPath $emptyPid
    # 真实子进程验证：5.1 空环境值会被删，空策略必须仍被显式传递。
    $ctx.Config['PTMCP_METADATA_JDBC_URL'] = 'jdbc:synthetic'
    foreach ($key in @('PTMCP_METADATA_DB_USERNAME', 'PTMCP_METADATA_DB_PASSWORD',
            'PTMCP_CREDENTIAL_KEY', 'PTMCP_AUDIT_KEY', 'PTMCP_GATEWAY_WAL_KEY')) { $ctx.Config[$key] = 'synthetic' }
    $command = '[Console]::Write($env:PTMCP_ADMIN_DATASOURCE_ALLOWEDPORTS.Trim() + "|" + $env:PTMCP_GATEWAY_ALLOWEDPORTS.Trim())'
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    foreach ($case in @(@($null, '3306,5432|3306,5432'), @('', '|'), @('15432', '15432|15432'))) {
        $ctx.Config.Remove('PTMCP_BUSINESS_ALLOWED_PORTS')
        if ($null -ne $case[0]) { $ctx.Config['PTMCP_BUSINESS_ALLOWED_PORTS'] = $case[0] }
        Set-PtmcpAppEnvironment $ctx
        $child = Invoke-PtmcpProcess -FilePath (Join-Path $PSHOME 'powershell.exe') -Arguments @('-NoProfile', '-EncodedCommand', $encoded)
        Assert ($child.ExitCode -eq 0 -and $child.Output -eq $case[1]) 'Child process port policy changed'
    }
    if ($NginxExe -ne '') {
        $ctx.Config['NGINX_EXE'] = (Resolve-Path -LiteralPath $NginxExe).Path
        $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
        $listener.Start()
        $port = $listener.LocalEndpoint.Port
        $listener.Stop()
        $ctx.Config['PTMCP_HTTP_PORT'] = "$port"
        $ctx.Config['PTMCP_PUBLIC_BASE_URL'] = "http://localhost:$port"
        New-Item -ItemType Directory -Path (Join-Path $release 'frontend') -Force | Out-Null
        [IO.File]::WriteAllText((Join-Path $release 'frontend\index.html'), 'Synthetic SPA')
        Push-Location $scratch
        try {
            Start-PtmcpNginx $ctx
            Assert (Test-PtmcpNginxRunning $ctx) 'Real Windows nginx did not retain its process identity'
            $health = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 -Uri "http://127.0.0.1:$port/healthz"
            Assert ($health.StatusCode -eq 200 -and $health.Content -eq 'ok') 'Real Windows nginx health failed'
            $spa = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 -Uri "http://127.0.0.1:$port/"
            Assert ($spa.Content -eq 'Synthetic SPA') 'Real Windows nginx failed with quoted space paths'
        } finally {
            try {
                if (Test-PtmcpNginxRunning $ctx) { Stop-PtmcpNginx $ctx }
            } finally { Pop-Location }
        }
        Assert (-not (Test-PtmcpNginxRunning $ctx)) 'Real Windows nginx was not stopped'
        Write-Host 'PASS: real Windows nginx config test, start/health/SPA/stop in a space-containing directory'
    }
    Write-Host "PASS: PowerShell $($PSVersionTable.PSVersion), URL/ports, secret ACL, nginx UTF-8, child environment"
} catch {
    Write-Host "Probe failure: $($_.Exception.Message)"
    throw
} finally {
    $resolved = [IO.Path]::GetFullPath($scratch)
    Assert ($resolved.StartsWith([IO.Path]::GetTempPath()) -and (Split-Path $resolved -Leaf).StartsWith('ptmcp deploy ')) 'Unsafe scratch cleanup'
    Remove-Item -LiteralPath $resolved -Force -Recurse
}
