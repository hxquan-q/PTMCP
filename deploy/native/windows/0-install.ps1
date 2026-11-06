# PTMCP · Windows 首次安装（解压发布包后运行一次，可重复执行续跑）。
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File 0-install.ps1 http://<主机>:<端口>
#   ... -SkipMigrate
#   ... -NoStart
#
# 步骤：
#   [1/5] 选择 releases\ 下的最新版本并记录到 shared\config\release.txt
#   [2/5] 生成共享配置与本地密钥（已存在则保留）
#   [3/5] 预检：Java 21 / nginx / 端口 / 元库 / 配置
#   [4/5] Flyway 迁移元库
#   [5/5] 启动 admin → authz → gateway → nginx，并等待健康
param(
    [string]$BaseUrl = '',
    [switch]$SkipMigrate,
    [switch]$NoStart,
    [string]$DeployRoot = ''
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Ptmcp.Layout.ps1')

$layout = Resolve-PtmcpLayout -ScriptDir $PSScriptRoot -DeployRoot $DeployRoot
Import-Module $layout.Module -Force

function Fail([string]$Message) {
    Write-Host ''
    Write-Host "[失败] $Message" -ForegroundColor Red
    Write-Host '       修复后重新运行 0-install.cmd 即可续跑，已完成的步骤不会重来。'
    exit 1
}

Write-Host '============================================================'
Write-Host '  PTMCP · Windows 首次安装'
Write-Host "  部署根目录: $($layout.Root)"
Write-Host '============================================================'
Write-Host ''

Write-Host "[1/5] 选中版本 $($layout.Version)，写入 release.txt ..."
$pointer = Join-Path $layout.Root 'shared\config\release.txt'
New-Item -ItemType Directory -Force -Path (Split-Path $pointer -Parent) | Out-Null
Set-Content -LiteralPath $pointer -Value $layout.Version -Encoding ASCII

Write-Host ''
Write-Host '[2/5] 共享配置 shared\config\app.env ...'
if (Test-Path -LiteralPath $layout.Config) {
    Write-Host "  已存在，保留：$($layout.Config)"
} else {
    if ($BaseUrl -eq '') { Fail '首次安装需要对外基址，例如：0-install.cmd http://192.168.1.10:8080' }
    & (Join-Path $PSScriptRoot 'init-env.ps1') -BaseUrl $BaseUrl -DeployRoot $layout.Root
    Fail '请先编辑上面的 app.env：填写 PTMCP_METADATA_* 元库连接，再重新运行 0-install.cmd。'
}

Write-Host ''
Write-Host '[3/5] 预检 ...'
try {
    & (Join-Path $PSScriptRoot 'check-prereq.ps1') -DeployRoot $layout.Root
} catch {
    Fail "预检执行失败：$($_.Exception.Message)"
}
if ($LASTEXITCODE -ne 0) { Fail '预检未通过' }

if ($SkipMigrate) {
    Write-Host ''
    Write-Host '[4/5] 已按参数跳过元库迁移'
} else {
    Write-Host ''
    Write-Host '[4/5] 迁移元库 ...'
    try {
        & (Join-Path $PSScriptRoot 'migrate.ps1') -DeployRoot $layout.Root
    } catch {
        Fail "元库迁移失败：$($_.Exception.Message)"
    }
    if ($LASTEXITCODE -ne 0) { Fail '元库迁移失败' }
}

if ($NoStart) {
    Write-Host ''
    Write-Host '[5/5] 已按参数跳过启动；需要时运行 1-start.cmd'
    exit 0
}

Write-Host ''
Write-Host '[5/5] 启动服务 ...'
try {
    & (Join-Path $PSScriptRoot 'platform.ps1') -Action Start -DeployRoot $layout.Root
} catch {
    Fail "启动失败：$($_.Exception.Message)；日志在 $(Join-Path $layout.Root 'logs')"
}
if ($LASTEXITCODE -ne 0) { Fail "启动失败；日志在 $(Join-Path $layout.Root 'logs')" }

$cfg = Read-PtmcpConfig (Join-Path $layout.Root 'shared\config\app.env')
$base = Get-PtmcpConfigValue $cfg 'PTMCP_PUBLIC_BASE_URL' 'http://<主机>:<端口>'
Write-Host ''
Write-Host '============================================================'
Write-Host '  安装完成'
Write-Host "  访问地址：$base"
Write-Host "  状态：    3-status.cmd"
Write-Host "  日志：    $($layout.Root)\logs"
Write-Host '  注意：当前版本尚未播种管理员账号，登录流程在后续切片提供。'
Write-Host '============================================================'
