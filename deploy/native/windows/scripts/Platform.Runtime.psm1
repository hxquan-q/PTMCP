# PTMCP Windows 运行时模块：布局推导、配置解析、进程归属校验、启停与健康等待。
#
# 布局（与 Linux 侧一致）：
#   <deploy-root>/
#     releases/<version>/{scripts,nginx,backend,frontend,migration}
#     shared/config/app.env
#     run/  logs/  temp/
#
# 归属模型：run\<service>.json 记录 PID + CreationDate + CommandLine，
# 停止前逐项复核；任何一项不符即拒绝操作，绝不按映像名杀进程。

$script:PtmcpServices = @('admin', 'authz', 'gateway')

# 原生命令（java/nginx/openssl/mysql）把正常信息写到 stderr；在
# $ErrorActionPreference='Stop' 下用 2>&1 会把 stderr 变成 NativeCommandError。
# 这类调用统一走进程 API 分别读取 stdout/stderr，避免错误流语义干扰。
function Invoke-PtmcpProcess {
    param(
        [string]$FilePath,
        [string[]]$Arguments = @()
    )
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FilePath
    $quoted = foreach ($a in $Arguments) {
        if ($a -match '\s') { '"' + $a + '"' } else { $a }
    }
    $psi.Arguments = ($quoted -join ' ')
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    try {
        # 两路并发消费，避免 openssl 进度输出填满 stderr 后与 stdout 等待互锁。
        $stdout = $proc.StandardOutput.ReadToEndAsync()
        $stderr = $proc.StandardError.ReadToEndAsync()
        $proc.WaitForExit()
        [pscustomobject]@{
            ExitCode = $proc.ExitCode
            Output   = $stdout.GetAwaiter().GetResult()
            Error    = $stderr.GetAwaiter().GetResult()
        }
    } finally {
        $proc.Dispose()
    }
}

function Get-PtmcpConfigValue {
    param($Config, [string]$Key, [string]$Default = '')
    if ($null -ne $Config -and $Config.ContainsKey($Key) -and "$($Config[$Key])" -ne '') {
        return "$($Config[$Key])"
    }
    return $Default
}

function Get-PtmcpBaseSettings {
    param([string]$BaseUrl)
    $invalid = '基址须为无路径/凭证/query/fragment 的 HTTPS URL，或 localhost、127.0.0.1、[::1] 的 HTTP URL；端口为 1..65535'
    $match = [regex]::Match($BaseUrl, '^(https?)://(\[[0-9a-fA-F:.]+\]|[a-zA-Z0-9.-]+)(:([0-9]{1,5}))?/?$')
    if (-not $match.Success) { throw $invalid }
    $scheme = $match.Groups[1].Value
    $urlHost = $match.Groups[2].Value
    $uri = $null
    if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$uri) -or
        [Uri]::CheckHostName($urlHost.Trim('[', ']')) -eq [UriHostNameType]::Unknown) { throw $invalid }
    if (-not $urlHost.StartsWith('[')) {
        if ($urlHost.Contains('.') -and $urlHost -match '^[0-9.]+$') {
            $octets = $urlHost.Split('.')
            if ($octets.Count -ne 4) { throw $invalid }
            foreach ($octet in $octets) {
                if ($octet -notmatch '^[0-9]{1,3}$' -or [int]$octet -gt 255) { throw $invalid }
            }
        } else {
            $labels = $urlHost.TrimEnd('.').Split('.')
            foreach ($label in $labels) {
                if ($label -notmatch '^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$') { throw $invalid }
            }
            if ($labels.Count -gt 1 -and $labels[-1] -notmatch '^[a-zA-Z]') { throw $invalid }
        }
    }
    $port = 80
    if ($match.Groups[4].Success) {
        $port = [int]$match.Groups[4].Value
        if ($port -lt 1 -or $port -gt 65535) { throw $invalid }
    }
    if ($scheme -eq 'http') {
        if ($urlHost -notin @('localhost', '127.0.0.1', '[::1]')) { throw $invalid }
    } else {
        # HTTPS 由外部 TLS 入口转到内部 HTTP，不把 443 当作明文监听端口。
        $port = 8080
    }
    [pscustomobject]@{ BaseUrl = $BaseUrl.TrimEnd('/'); HttpPort = $port }
}

function Set-PtmcpSecretAcl {
    param([string]$Path)
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $system = New-Object Security.Principal.SecurityIdentifier('S-1-5-18')
    # 基于现有描述符仅修改 DACL；全新描述符在 PowerShell 5.1 会尝试写 SACL 并要求额外特权。
    $acl = Get-Acl -LiteralPath $Path
    if (Test-Path -LiteralPath $Path -PathType Container) {
        $inherit = [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
    } else {
        $inherit = [Security.AccessControl.InheritanceFlags]::None
    }
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($rule in @($acl.Access)) { $acl.RemoveAccessRuleSpecific($rule) }
    foreach ($sid in @($identity, $system)) {
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
            $sid, 'FullControl', $inherit, 'None', 'Allow')))
    }
    if (Test-Path -LiteralPath $Path -PathType Container) {
        [IO.Directory]::SetAccessControl($Path, $acl)
    } else {
        [IO.File]::SetAccessControl($Path, $acl)
    }
}

function Read-PtmcpConfig {
    param([string]$Path)
    $cfg = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $cfg }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        $i = $t.IndexOf('=')
        if ($i -lt 1) { continue }
        $k = $t.Substring(0, $i).Trim()
        $v = $t.Substring($i + 1).Trim()
        if ($v.Length -ge 2) {
            $f = $v.Substring(0, 1); $l = $v.Substring($v.Length - 1, 1)
            if (($f -eq "'" -and $l -eq "'") -or ($f -eq '"' -and $l -eq '"')) {
                $v = $v.Substring(1, $v.Length - 2)
            }
        }
        $cfg[$k] = $v
    }
    return $cfg
}

function Resolve-PtmcpRelease {
    param([string]$Root)
    $pointer = Join-Path $Root 'shared\config\release.txt'
    if (Test-Path -LiteralPath $pointer) {
        $v = (Get-Content -LiteralPath $pointer -Raw).Trim()
        $candidate = Join-Path $Root "releases\$v"
        if ($v -and (Test-Path -LiteralPath $candidate)) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    $releases = Join-Path $Root 'releases'
    if (-not (Test-Path -LiteralPath $releases)) { throw "找不到版本目录：$releases" }
    $latest = Get-ChildItem -LiteralPath $releases -Directory | Sort-Object Name -Descending | Select-Object -First 1
    if ($null -eq $latest) { throw "$releases 里没有版本目录" }
    return $latest.FullName
}

function New-PtmcpContext {
    param([string]$DeployRoot, [string]$Release)
    $root = (Resolve-Path -LiteralPath $DeployRoot).Path
    if (-not $Release) { $Release = Resolve-PtmcpRelease $root }
    $cfgPath = Join-Path $root 'shared\config\app.env'
    [pscustomobject]@{
        DeployRoot = $root
        Release    = $Release
        ConfigPath = $cfgPath
        Config     = (Read-PtmcpConfig $cfgPath)
    }
}

function Get-PtmcpServicePort {
    param($Context, [string]$Service)
    switch ($Service) {
        'admin'   { $key = 'PTMCP_ADMIN_PORT';   $def = '18080' }
        'authz'   { $key = 'PTMCP_AUTHZ_PORT';   $def = '18081' }
        'gateway' { $key = 'PTMCP_GATEWAY_PORT'; $def = '18082' }
        default   { throw "未知服务：$Service" }
    }
    return [int](Get-PtmcpConfigValue $Context.Config $key $def)
}

function Get-PtmcpRecordPath {
    param($Context, [string]$Name)
    return (Join-Path $Context.DeployRoot "run\$Name.json")
}

function Get-PtmcpProcInfo {
    param([int]$ProcessId)
    $p = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction SilentlyContinue
    if ($null -eq $p) { return $null }
    $created = $p.CreationDate
    if ($created -is [datetime]) { $created = $created.ToString('o') }
    [pscustomobject]@{
        Pid           = [int]$p.ProcessId
        CreationDate  = "$created"
        ExecutablePath = $p.ExecutablePath
        CommandLine   = $p.CommandLine
    }
}

function Test-PtmcpServiceRunning {
    param($Context, [string]$Service)
    $recPath = Get-PtmcpRecordPath $Context $Service
    if (-not (Test-Path -LiteralPath $recPath)) { return $false }
    $rec = Get-Content -LiteralPath $recPath -Raw | ConvertFrom-Json
    $proc = Get-PtmcpProcInfo ([int]$rec.Pid)
    if ($null -eq $proc) { Remove-Item -LiteralPath $recPath -Force; return $false }
    if ($proc.CreationDate -ne $rec.CreationDate) { return $false }
    if (-not $proc.CommandLine -or $proc.CommandLine.IndexOf("-Dptmcp.service=$Service") -lt 0) { return $false }
    return $true
}

function Test-PtmcpPortInUse {
    param([int]$Port)
    $client = New-Object Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(500)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Get-PtmcpJava {
    param($Context)
    $java = Get-PtmcpConfigValue $Context.Config 'JAVA_EXE' 'java'
    $cmd = Get-Command $java -ErrorAction SilentlyContinue
    if ($null -eq $cmd) { throw "找不到 Java：$java（安装 JDK 21，或在 app.env 设置 JAVA_EXE）" }
    return $cmd.Source
}

function Set-PtmcpAppEnvironment {
    param($Context)
    $c = $Context.Config
    $base = Get-PtmcpConfigValue $c 'PTMCP_PUBLIC_BASE_URL'
    if ($base -eq '') { throw 'app.env 缺少 PTMCP_PUBLIC_BASE_URL' }
    $base = $base.TrimEnd('/')

    foreach ($pair in @(
            @('PTMCP_METADATA_JDBC_URL'),
            @('PTMCP_METADATA_DB_USERNAME'),
            @('PTMCP_METADATA_DB_PASSWORD'),
            @('PTMCP_CREDENTIAL_KEY'),
            @('PTMCP_AUDIT_KEY'),
            @('PTMCP_GATEWAY_WAL_KEY'))) {
        $v = Get-PtmcpConfigValue $c $pair[0]
        if ($v -eq '' -or $v.Contains('CHANGE_ME')) { throw "app.env 的 $($pair[0]) 未填写" }
    }

    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:PTMCP_ADMIN_DATASOURCE_URL = $c['PTMCP_METADATA_JDBC_URL']
    $env:PTMCP_ADMIN_DATASOURCE_USERNAME = $c['PTMCP_METADATA_DB_USERNAME']
    $env:PTMCP_ADMIN_DATASOURCE_PASSWORD = $c['PTMCP_METADATA_DB_PASSWORD']
    $env:PTMCP_ADMIN_IDENTITY_DATASOURCE_URL = $c['PTMCP_METADATA_JDBC_URL']
    $env:PTMCP_ADMIN_IDENTITY_DATASOURCE_USERNAME = $c['PTMCP_METADATA_DB_USERNAME']
    $env:PTMCP_ADMIN_IDENTITY_DATASOURCE_PASSWORD = $c['PTMCP_METADATA_DB_PASSWORD']
    $env:PTMCP_ADMIN_AUDITKEY = $c['PTMCP_AUDIT_KEY']
    $env:PTMCP_ADMIN_CREDENTIALKEY = $c['PTMCP_CREDENTIAL_KEY']
    $env:PTMCP_ADMIN_COOKIESECURE = Get-PtmcpConfigValue $c 'PTMCP_ADMIN_COOKIE_SECURE' 'false'
    $env:PTMCP_ADMIN_DATASOURCE_ALLOWEDCIDRS = Get-PtmcpConfigValue $c 'PTMCP_BUSINESS_ALLOWED_CIDRS' ''
    $ports = '3306,5432'
    if ($c.ContainsKey('PTMCP_BUSINESS_ALLOWED_PORTS')) { $ports = "$($c['PTMCP_BUSINESS_ALLOWED_PORTS'])" }
    # PowerShell 5.1 删掉空环境变量；空白经应用 trim 后仍为空集合，不能退回默认端口。
    if ($ports.Trim() -eq '') { $ports = ' ' }
    $env:PTMCP_ADMIN_DATASOURCE_ALLOWEDPORTS = $ports

    $env:PTMCP_AUTHZ_DB_URL = $c['PTMCP_METADATA_JDBC_URL']
    $env:PTMCP_AUTHZ_DB_USERNAME = $c['PTMCP_METADATA_DB_USERNAME']
    $env:PTMCP_AUTHZ_DB_PASSWORD = $c['PTMCP_METADATA_DB_PASSWORD']
    $env:PTMCP_OAUTH_ENABLED = Get-PtmcpConfigValue $c 'PTMCP_OAUTH_ENABLED' 'true'
    $env:PTMCP_OAUTH_ISSUER = Get-PtmcpConfigValue $c 'PTMCP_OAUTH_ISSUER' $base
    $env:PTMCP_OAUTH_AUDIENCE = Get-PtmcpConfigValue $c 'PTMCP_OAUTH_AUDIENCE' "$base/mcp"
    $keyFile = Get-PtmcpConfigValue $c 'PTMCP_OAUTH_SIGNING_KEY_FILE' (Join-Path $Context.DeployRoot 'shared\config\secrets\oauth-signing.pem')
    $env:PTMCP_OAUTH_SIGNING_KEY_FILE = $keyFile
    $env:PTMCP_AUTHZ_OAUTH_PAGES_DIR = Join-Path $Context.Release 'backend\oauth-pages'
    $env:PTMCP_AS_COOKIE_SECURE = Get-PtmcpConfigValue $c 'PTMCP_AS_COOKIE_SECURE' 'false'

    $env:PTMCP_GATEWAY_ENABLED = Get-PtmcpConfigValue $c 'PTMCP_GATEWAY_ENABLED' 'true'
    $env:PTMCP_GATEWAY_METADATAURL = $c['PTMCP_METADATA_JDBC_URL']
    $env:PTMCP_GATEWAY_METADATAUSERNAME = $c['PTMCP_METADATA_DB_USERNAME']
    $env:PTMCP_GATEWAY_METADATAPASSWORD = $c['PTMCP_METADATA_DB_PASSWORD']
    $env:PTMCP_GATEWAY_CREDENTIALKEY = $c['PTMCP_CREDENTIAL_KEY']
    $env:PTMCP_GATEWAY_AUDITKEY = $c['PTMCP_AUDIT_KEY']
    $env:PTMCP_GATEWAY_WALKEY = $c['PTMCP_GATEWAY_WAL_KEY']
    $env:PTMCP_GATEWAY_WALDIRECTORY = Join-Path $Context.DeployRoot 'shared\data\wal'
    $env:PTMCP_GATEWAY_ISSUER = Get-PtmcpConfigValue $c 'PTMCP_GATEWAY_ISSUER' $base
    $env:PTMCP_GATEWAY_RESOURCE = Get-PtmcpConfigValue $c 'PTMCP_GATEWAY_RESOURCE' "$base/mcp"
    $env:PTMCP_GATEWAY_RESOURCEURI = Get-PtmcpConfigValue $c 'PTMCP_GATEWAY_RESOURCEURI' "$base/mcp"
    $env:PTMCP_GATEWAY_JWKSURI = Get-PtmcpConfigValue $c 'PTMCP_GATEWAY_JWKSURI' "http://127.0.0.1:$(Get-PtmcpServicePort $Context 'authz')/oauth2/jwks"
    $env:PTMCP_GATEWAY_RESOURCEMETADATAURI = Get-PtmcpConfigValue $c 'PTMCP_GATEWAY_RESOURCEMETADATAURI' "$base/.well-known/oauth-protected-resource"
    $env:PTMCP_GATEWAY_ALLOWEDCIDRS = Get-PtmcpConfigValue $c 'PTMCP_BUSINESS_ALLOWED_CIDRS' ''
    $env:PTMCP_GATEWAY_ALLOWEDPORTS = $ports
    $env:PTMCP_MCP_URL = Get-PtmcpConfigValue $c 'PTMCP_MCP_URL' "$base/mcp"
}

function Start-PtmcpService {
    param($Context, [string]$Service)
    if (Test-PtmcpServiceRunning $Context $Service) {
        Write-Host "  $Service 已在运行（PID $((Get-Content (Get-PtmcpRecordPath $Context $Service) -Raw | ConvertFrom-Json).Pid)）"
        return
    }
    $port = Get-PtmcpServicePort $Context $Service
    if (Test-PtmcpPortInUse $port) { throw "端口 $port 已被其他进程占用，拒绝启动 $Service" }
    $jar = Join-Path $Context.Release "backend\$Service.jar"
    if (-not (Test-Path -LiteralPath $jar)) { throw "缺少 jar：$jar" }
    $java = Get-PtmcpJava $Context
    Set-PtmcpAppEnvironment $Context

    $argList = @()
    $opts = Get-PtmcpConfigValue $Context.Config 'PTMCP_JAVA_OPTS' ''
    if ($opts -ne '') { $argList += ($opts -split '\s+' | Where-Object { $_ -ne '' }) }
    $argList += @("-Dptmcp.service=$Service", '-jar', "`"$jar`"")

    $out = Join-Path $Context.DeployRoot "logs\$Service.log"
    $err = Join-Path $Context.DeployRoot "logs\$Service.err.log"
    $oldPort = $env:SERVER_PORT
    $env:SERVER_PORT = "$port"
    try {
        $proc = Start-Process -FilePath $java -ArgumentList $argList -WorkingDirectory $Context.DeployRoot `
            -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden -PassThru
    } finally {
        if ($null -eq $oldPort) { Remove-Item Env:SERVER_PORT -ErrorAction SilentlyContinue } else { $env:SERVER_PORT = $oldPort }
    }
    Start-Sleep -Seconds 1
    if ($proc.HasExited) { throw "$Service 启动即退出，见 $out / $err" }

    $info = Get-PtmcpProcInfo $proc.Id
    $info | Add-Member -NotePropertyName Service -NotePropertyValue $Service
    $info | ConvertTo-Json | Set-Content -LiteralPath (Get-PtmcpRecordPath $Context $Service) -Encoding UTF8
    Write-Host "  $Service 已启动（PID $($proc.Id)，端口 $port）"
}

function Stop-PtmcpService {
    param($Context, [string]$Service, [int]$TimeoutSeconds = 60)
    $recPath = Get-PtmcpRecordPath $Context $Service
    if (-not (Test-PtmcpServiceRunning $Context $Service)) {
        Remove-Item -LiteralPath $recPath -Force -ErrorAction SilentlyContinue
        Write-Host "  $Service 未在运行"
        return
    }
    $rec = Get-Content -LiteralPath $recPath -Raw | ConvertFrom-Json
    Stop-Process -Id ([int]$rec.Pid) -ErrorAction Stop
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($null -ne (Get-Process -Id ([int]$rec.Pid) -ErrorAction SilentlyContinue)) {
        if ((Get-Date) -ge $deadline) { throw "$Service（PID $($rec.Pid)）在 ${TimeoutSeconds}s 内未退出；保留记录，请人工检查" }
        Start-Sleep -Milliseconds 500
    }
    Remove-Item -LiteralPath $recPath -Force -ErrorAction SilentlyContinue
    Write-Host "  $Service 已停止（Windows 下不保证 JVM 关闭钩子执行）"
}

function Wait-PtmcpHealth {
    param($Context, [string]$Service, [int]$TimeoutSeconds = 240)
    $port = Get-PtmcpServicePort $Context $Service
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (-not (Test-PtmcpServiceRunning $Context $Service)) {
            throw "$Service 进程已退出，日志：$(Join-Path $Context.DeployRoot "logs\$Service.log")"
        }
        try {
            $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri "http://127.0.0.1:$port/actuator/health"
            if ($r.Content -match '"status"\s*:\s*"UP"') {
                Write-Host "  $Service 就绪（127.0.0.1:$port）"
                return
            }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw "$Service 在 ${TimeoutSeconds}s 内未就绪，日志：$(Join-Path $Context.DeployRoot "logs\$Service.log")"
}

function Get-PtmcpNginxExe {
    param($Context)
    $exe = Get-PtmcpConfigValue $Context.Config 'NGINX_EXE' 'nginx.exe'
    $cmd = Get-Command $exe -ErrorAction SilentlyContinue
    if ($null -eq $cmd) { throw "找不到 nginx：$exe（解压 nginx for Windows 后在 app.env 设置 NGINX_EXE）" }
    return $cmd.Source
}

function Write-PtmcpNginxConf {
    param($Context)
    $tpl = Join-Path $Context.Release 'nginx\nginx.conf.template'
    if (-not (Test-Path -LiteralPath $tpl)) { throw "缺少 nginx 模板：$tpl" }
    $text = Get-Content -LiteralPath $tpl -Raw
    $map = @{
        '@HTTP_PORT@'    = Get-PtmcpConfigValue $Context.Config 'PTMCP_HTTP_PORT' '8080'
        '@ADMIN_PORT@'   = "$(Get-PtmcpServicePort $Context 'admin')"
        '@AUTHZ_PORT@'   = "$(Get-PtmcpServicePort $Context 'authz')"
        '@GATEWAY_PORT@' = "$(Get-PtmcpServicePort $Context 'gateway')"
        '@FRONTEND_DIR@' = (Join-Path $Context.Release 'frontend').Replace('\', '/')
        '@NGINX_DIR@'    = (Join-Path $Context.Release 'nginx').Replace('\', '/')
        '@RUN_DIR@'      = (Join-Path $Context.DeployRoot 'run').Replace('\', '/')
        '@LOG_DIR@'      = (Join-Path $Context.DeployRoot 'logs').Replace('\', '/')
        '@TEMP_DIR@'     = (Join-Path $Context.DeployRoot 'temp').Replace('\', '/')
    }
    foreach ($k in $map.Keys) { $text = $text.Replace($k, $map[$k]) }
    $out = Join-Path $Context.DeployRoot 'run\nginx.conf'
    [System.IO.File]::WriteAllText($out, $text, (New-Object System.Text.UTF8Encoding($false)))
    return $out
}

function Get-PtmcpNginxPid {
    param($Context)
    $pidFile = Join-Path $Context.DeployRoot 'run\nginx.pid'
    if (-not (Test-Path -LiteralPath $pidFile)) { return $null }
    # nginx 创建文件后才写 PID；空文件在启动轮询中仍表示未就绪。
    $v = Get-Content -LiteralPath $pidFile -Raw
    if ([string]::IsNullOrWhiteSpace($v)) { return $null }
    $v = $v.Trim()
    if ($v -notmatch '^\d+$') { return $null }
    return [int]$v
}

function Test-PtmcpNginxRunning {
    param($Context)
    $nginxPid = Get-PtmcpNginxPid $Context
    if ($null -eq $nginxPid) { return $false }
    $meta = Join-Path $Context.DeployRoot 'run\nginx.meta'
    if (-not (Test-Path -LiteralPath $meta)) { return $false }
    $recorded = (Get-Content -LiteralPath $meta -Raw).Trim()
    $proc = Get-PtmcpProcInfo $nginxPid
    if ($null -eq $proc) { return $false }
    return ($proc.CreationDate -eq $recorded)
}

function Start-PtmcpNginx {
    param($Context)
    if (Test-PtmcpNginxRunning $Context) { Write-Host '  nginx 已在运行'; return }
    $port = [int](Get-PtmcpConfigValue $Context.Config 'PTMCP_HTTP_PORT' '8080')
    if (Test-PtmcpPortInUse $port) { throw "端口 $port 已被其他进程占用，拒绝启动 nginx" }
    $exe = Get-PtmcpNginxExe $Context
    $conf = Write-PtmcpNginxConf $Context

    $test = Invoke-PtmcpProcess -FilePath $exe -Arguments @('-t', '-c', $conf)
    if ($test.ExitCode -ne 0) {
        throw "nginx 配置校验失败：$exe -t -c $conf`n$($test.Error)$($test.Output)"
    }

    Start-Process -FilePath $exe -ArgumentList @('-c', "`"$conf`"") -WindowStyle Hidden | Out-Null
    $deadline = (Get-Date).AddSeconds(15)
    $nginxPid = $null
    while ((Get-Date) -lt $deadline) {
        $nginxPid = Get-PtmcpNginxPid $Context
        if ($null -ne $nginxPid) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($null -eq $nginxPid) { throw "nginx 启动后未生成 run\nginx.pid，见 logs\nginx-error.log" }
    $info = Get-PtmcpProcInfo $nginxPid
    if ($null -eq $info) { throw "nginx 进程不存在（PID $nginxPid）" }
    Set-Content -LiteralPath (Join-Path $Context.DeployRoot 'run\nginx.meta') -Value $info.CreationDate -Encoding ASCII
    Write-Host "  nginx 已启动（PID $nginxPid，端口 $port）"
}

function Stop-PtmcpNginx {
    param($Context, [int]$TimeoutSeconds = 60)
    if (-not (Test-PtmcpNginxRunning $Context)) {
        Remove-Item -LiteralPath (Join-Path $Context.DeployRoot 'run\nginx.pid') -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath (Join-Path $Context.DeployRoot 'run\nginx.meta') -Force -ErrorAction SilentlyContinue
        Write-Host '  nginx 未在运行'
        return
    }
    $exe = Get-PtmcpNginxExe $Context
    $conf = Join-Path $Context.DeployRoot 'run\nginx.conf'
    $nginxPid = Get-PtmcpNginxPid $Context
    Invoke-PtmcpProcess -FilePath $exe -Arguments @('-s', 'quit', '-c', $conf) | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($null -ne (Get-Process -Id $nginxPid -ErrorAction SilentlyContinue)) {
        if ((Get-Date) -ge $deadline) { throw "nginx（PID $nginxPid）在 ${TimeoutSeconds}s 内未退出" }
        Start-Sleep -Milliseconds 500
    }
    Remove-Item -LiteralPath (Join-Path $Context.DeployRoot 'run\nginx.pid') -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $Context.DeployRoot 'run\nginx.meta') -Force -ErrorAction SilentlyContinue
    Write-Host '  nginx 已停止'
}

function Initialize-PtmcpDirs {
    param($Context)
    foreach ($d in @('run', 'logs', 'temp', 'shared\config\secrets', 'shared\data\wal')) {
        New-Item -ItemType Directory -Force -Path (Join-Path $Context.DeployRoot $d) | Out-Null
    }
}

function Get-PtmcpStatus {
    param($Context)
    foreach ($svc in $script:PtmcpServices) {
        $port = Get-PtmcpServicePort $Context $svc
        if (Test-PtmcpServiceRunning $Context $svc) {
            $rec = Get-Content -LiteralPath (Get-PtmcpRecordPath $Context $svc) -Raw | ConvertFrom-Json
            '{0,-8} {1,-6} running PID {2}' -f $svc, $port, $rec.Pid
        } else {
            '{0,-8} {1,-6} stopped' -f $svc, $port
        }
    }
    $port = Get-PtmcpConfigValue $Context.Config 'PTMCP_HTTP_PORT' '8080'
    if (Test-PtmcpNginxRunning $Context) {
        '{0,-8} {1,-6} running PID {2}' -f 'nginx', $port, (Get-PtmcpNginxPid $Context)
    } else {
        '{0,-8} {1,-6} stopped' -f 'nginx', $port
    }
}

function Invoke-PtmcpAction {
    param($Context, [string]$Action, [string]$Component)
    switch ($Action.ToLowerInvariant()) {
        'start' {
            Initialize-PtmcpDirs $Context
            $timeout = [int](Get-PtmcpConfigValue $Context.Config 'PTMCP_START_TIMEOUT' '240')
            foreach ($svc in $script:PtmcpServices) {
                Start-PtmcpService $Context $svc
                Wait-PtmcpHealth $Context $svc $timeout
            }
            Start-PtmcpNginx $Context
        }
        'stop' {
            Stop-PtmcpNginx $Context
            foreach ($svc in @('gateway', 'authz', 'admin')) { Stop-PtmcpService $Context $svc }
        }
        'restart' {
            Invoke-PtmcpAction $Context 'stop'
            Invoke-PtmcpAction $Context 'start'
        }
        'status' { Get-PtmcpStatus $Context }
        default { throw "未知动作：$Action（start|stop|restart|status）" }
    }
}

Export-ModuleMember -Function *
