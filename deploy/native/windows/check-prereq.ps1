# 安装前预检（Windows）：配置完整性、Java 21、nginx、端口、元库可达性、发布包文件。
#
#   powershell -File check-prereq.ps1 [-SkipMysql]
#
# 退出码：0 = 全部通过；1 = 至少一项失败。
param(
    [switch]$SkipMysql,
    [string]$DeployRoot = ''
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Ptmcp.Layout.ps1')

$layout = Resolve-PtmcpLayout -ScriptDir $PSScriptRoot -DeployRoot $DeployRoot
Import-Module $layout.Module -Force
$ctx = New-PtmcpContext -DeployRoot $layout.Root -Release $layout.Release
$cfg = $ctx.Config

$script:PassCount = 0
$script:FailCount = 0
function Pass([string]$Name, [string]$Detail) { Write-Host "  [PASS] $Name - $Detail"; $script:PassCount++ }
function FailCheck([string]$Name, [string]$Detail) { Write-Host "  [FAIL] $Name - $Detail" -ForegroundColor Red; $script:FailCount++ }
function Warn([string]$Name, [string]$Detail) { Write-Host "  [WARN] $Name - $Detail" -ForegroundColor Yellow }

Write-Host '== PTMCP 部署预检 =='
Write-Host "部署根：$($ctx.DeployRoot)"
Write-Host "版本目录：$($ctx.Release)"
Write-Host "配置文件：$($ctx.ConfigPath)"
Write-Host ''

if (-not (Test-Path -LiteralPath $ctx.ConfigPath)) {
    FailCheck 'config' "配置文件不存在：$($ctx.ConfigPath)（先运行 0-install.cmd）"
    Write-Host ''
    Write-Host "结果：$script:PassCount 通过，$script:FailCount 失败"
    exit 1
}
Pass 'config' '配置文件存在'

$missing = @()
foreach ($key in @('PTMCP_PUBLIC_BASE_URL', 'PTMCP_METADATA_JDBC_URL', 'PTMCP_METADATA_DB_USERNAME',
        'PTMCP_METADATA_DB_PASSWORD', 'PTMCP_CREDENTIAL_KEY', 'PTMCP_AUDIT_KEY', 'PTMCP_GATEWAY_WAL_KEY')) {
    $v = Get-PtmcpConfigValue $cfg $key ''
    if ($v -eq '' -or $v.Contains('CHANGE_ME')) { $missing += $key }
}
if ($missing.Count -eq 0) { Pass 'config.values' '必填项已填写' }
else { FailCheck 'config.values' "未填写或仍是 CHANGE_ME：$($missing -join ', ')" }

if ((Get-PtmcpConfigValue $cfg 'PTMCP_PUBLIC_BASE_URL' '') -match '^https?://') {
    Pass 'config.base_url' (Get-PtmcpConfigValue $cfg 'PTMCP_PUBLIC_BASE_URL' '')
} else {
    FailCheck 'config.base_url' 'PTMCP_PUBLIC_BASE_URL 必须以 http:// 或 https:// 开头'
}
if ((Get-PtmcpConfigValue $cfg 'PTMCP_METADATA_JDBC_URL' '') -match '^jdbc:mysql://') {
    Pass 'config.jdbc' '元库使用 MySQL JDBC URL'
} else {
    FailCheck 'config.jdbc' 'PTMCP_METADATA_JDBC_URL 必须是 jdbc:mysql://...'
}

$publicPort = [int](Get-PtmcpConfigValue $cfg 'PTMCP_HTTP_PORT' '8080')
$clash = @()
foreach ($svc in @('admin', 'authz', 'gateway')) {
    if ((Get-PtmcpServicePort $ctx $svc) -eq $publicPort) { $clash += $svc }
}
if ($clash.Count -eq 0) { Pass 'config.ports' "对外端口 $publicPort 与内部端口不冲突" }
else { FailCheck 'config.ports' "对外端口 $publicPort 与 $($clash -join '/') 冲突" }

try {
    $java = Get-PtmcpJava $ctx
    $javaResult = Invoke-PtmcpProcess -FilePath $java -Arguments @('-version')
    $verText = ($javaResult.Error + "`n" + $javaResult.Output).Trim()
    $ver = ($verText -split "`r?`n")[0]
    if ($ver -match 'version "(\d+)' -and [int]$Matches[1] -ge 21) { Pass 'java' $ver }
    else { FailCheck 'java' "需要 Java 21+，当前：$ver" }
} catch {
    FailCheck 'java' $_.Exception.Message
}

try {
    $nginx = Get-PtmcpNginxExe $ctx
    Pass 'nginx' $nginx
} catch {
    FailCheck 'nginx' $_.Exception.Message
}

foreach ($svc in @('admin', 'authz', 'gateway')) {
    $jar = Join-Path $ctx.Release "backend\$svc.jar"
    if (Test-Path -LiteralPath $jar) { Pass "jar.$svc" $jar } else { FailCheck "jar.$svc" "缺少 $jar" }
}
if (Test-Path -LiteralPath (Join-Path $ctx.Release 'frontend\index.html')) {
    Pass 'frontend' (Join-Path $ctx.Release 'frontend')
} else {
    FailCheck 'frontend' "缺少 SPA 入口：$(Join-Path $ctx.Release 'frontend\index.html')"
}
if (Test-Path -LiteralPath (Join-Path $ctx.Release 'backend\oauth-pages\oauth\login.html')) {
    Pass 'oauth-pages' (Join-Path $ctx.Release 'backend\oauth-pages')
} else {
    FailCheck 'oauth-pages' "缺少 OAuth 页面：$(Join-Path $ctx.Release 'backend\oauth-pages\oauth\login.html')"
}
if (Test-Path -LiteralPath (Join-Path $ctx.Release 'nginx\nginx.conf.template')) {
    Pass 'nginx.conf' '模板存在'
} else {
    FailCheck 'nginx.conf' "缺少 $(Join-Path $ctx.Release 'nginx\nginx.conf.template')"
}

$keyFile = Get-PtmcpConfigValue $cfg 'PTMCP_OAUTH_SIGNING_KEY_FILE' (Join-Path $ctx.DeployRoot 'shared\config\secrets\oauth-signing.pem')
if ((Test-Path -LiteralPath $keyFile) -and ((Get-Content -LiteralPath $keyFile -TotalCount 1) -match 'BEGIN PRIVATE KEY')) {
    Pass 'oauth.key' "PKCS#8 私钥：$keyFile"
} else {
    FailCheck 'oauth.key' "OAuth 签名私钥缺失或不是未加密 PKCS#8：$keyFile"
}

foreach ($svc in @('admin', 'authz', 'gateway')) {
    $port = Get-PtmcpServicePort $ctx $svc
    if (Test-PtmcpServiceRunning $ctx $svc) { Pass "port.$svc" "$port 由本包 $svc 占用" }
    elseif (Test-PtmcpPortInUse $port) { FailCheck "port.$svc" "端口 $port 已被其他进程占用" }
    else { Pass "port.$svc" "$port 空闲" }
}
if (Test-PtmcpNginxRunning $ctx) { Pass 'port.nginx' "$publicPort 由本包 nginx 占用" }
elseif (Test-PtmcpPortInUse $publicPort) { FailCheck 'port.nginx' "对外端口 $publicPort 已被其他进程占用" }
else { Pass 'port.nginx' "$publicPort 空闲" }

if ($SkipMysql) {
    Warn 'mysql' '已按参数跳过元库检查'
} else {
    $url = (Get-PtmcpConfigValue $cfg 'PTMCP_METADATA_JDBC_URL' '') -replace '^jdbc:mysql://', ''
    $hostPort = ($url -split '/')[0]
    $dbName = (($url -split '/', 2)[1] -split '\?')[0]
    $dbHost = $hostPort
    $mport = 3306
    if ($hostPort -match '^(.*):(\d+)$') { $dbHost = $Matches[1]; $mport = [int]$Matches[2] }
    $client = New-Object Net.Sockets.TcpClient
    $reachable = $false
    try {
        $async = $client.BeginConnect($dbHost, $mport, $null, $null)
        if ($async.AsyncWaitHandle.WaitOne(3000)) { $client.EndConnect($async); $reachable = $true }
    } catch { } finally { $client.Close() }
    if ($reachable) {
        Pass 'mysql.tcp' "$dbHost`:$mport 可连接"
        $mysql = Get-Command mysql -ErrorAction SilentlyContinue
        if ($null -ne $mysql) {
            $oldPwd = $env:MYSQL_PWD
            $env:MYSQL_PWD = Get-PtmcpConfigValue $cfg 'PTMCP_METADATA_DB_PASSWORD' ''
            try {
                $user = Get-PtmcpConfigValue $cfg 'PTMCP_METADATA_DB_USERNAME' ''
                $mysqlResult = Invoke-PtmcpProcess -FilePath $mysql.Source -Arguments @(
                    '--connect-timeout=5', '-h', $dbHost, '-P', "$mport", '-u', $user, '-N', '-B', '-e', 'SELECT 1')
                if ($mysqlResult.ExitCode -eq 0) { Pass 'mysql.auth' "账号 $user 可登录" }
                else { FailCheck 'mysql.auth' "账号 $user 无法登录 $dbHost`:$mport" }
            } finally {
                if ($null -eq $oldPwd) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue } else { $env:MYSQL_PWD = $oldPwd }
            }
        } else {
            Warn 'mysql.auth' '未安装 mysql 客户端，跳过账号检查'
        }
    } else {
        FailCheck 'mysql.tcp' "无法连接元库 $dbHost`:$mport（先启动 MySQL 或改 app.env）"
    }
}

Write-Host ''
Write-Host "结果：$script:PassCount 通过，$script:FailCount 失败"
if ($script:FailCount -gt 0) { exit 1 }
exit 0
