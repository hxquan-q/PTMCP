# 生成 shared\config\app.env 与本地 OAuth 签名私钥（Windows）。
#
#   powershell -File init-env.ps1 -BaseUrl http://<主机>:<端口> [-Force]
#
# 需要 openssl（Git for Windows / Win64 OpenSSL 均可）生成未加密 PKCS#8 私钥。
param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [switch]$Force,
    [string]$DeployRoot = ''
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Ptmcp.Layout.ps1')

$layout = Resolve-PtmcpLayout -ScriptDir $PSScriptRoot -DeployRoot $DeployRoot
Import-Module $layout.Module -Force
if ($BaseUrl -notmatch '^https?://') { throw "基址必须以 http:// 或 https:// 开头：$BaseUrl" }
$BaseUrl = $BaseUrl.TrimEnd('/')

$config = $layout.Config
if ((Test-Path -LiteralPath $config) -and -not $Force) {
    throw "$config 已存在。备份后重试，或加 -Force 重新生成（会轮换全部密钥）"
}

$openssl = Get-Command openssl -ErrorAction SilentlyContinue
if ($null -eq $openssl) { throw '需要 openssl 生成 OAuth 签名私钥；请安装 Git for Windows 或 Win64 OpenSSL 后重试' }

foreach ($d in @('shared\config\secrets', 'shared\data\wal', 'run', 'logs', 'temp')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $layout.Root $d) | Out-Null
}

function New-PtmcpSecret {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

$credentialKey = New-PtmcpSecret
$auditKey = New-PtmcpSecret
$walKey = New-PtmcpSecret
$keyFile = Join-Path $layout.Root 'shared\config\secrets\oauth-signing.pem'
$pem = Invoke-PtmcpProcess -FilePath $openssl.Source -Arguments @(
    'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', $keyFile)
if ($pem.ExitCode -ne 0) { throw "生成 OAuth 签名私钥失败：$($pem.Error)" }

$cookieSecure = 'false'
if ($BaseUrl -match '^https://') { $cookieSecure = 'true' }

$tpl = Join-Path $layout.Release 'scripts\app.env.example'
if (-not (Test-Path -LiteralPath $tpl)) { throw "缺少模板：$tpl" }
$text = Get-Content -LiteralPath $tpl -Raw
$text = $text.Replace('@PUBLIC_BASE_URL@', $BaseUrl)
$text = $text.Replace('@COOKIE_SECURE@', $cookieSecure)
$text = $text.Replace('@CREDENTIAL_KEY@', $credentialKey)
$text = $text.Replace('@AUDIT_KEY@', $auditKey)
$text = $text.Replace('@WAL_KEY@', $walKey)

$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($config, $text, $utf8)

Write-Host "已生成："
Write-Host "  $config"
Write-Host "  $keyFile"
Write-Host ''
Write-Host "接下来：编辑 $config 填写元库连接（PTMCP_METADATA_*），再运行 0-install.cmd。"
