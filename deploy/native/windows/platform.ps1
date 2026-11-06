# PTMCP 托管入口（Windows）。
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File platform.ps1 -Action Start
#   ... -Action Stop|Restart|Status
#   ... -Action Start -TimeoutSeconds 300
#
# 只管理本包自己的进程：run\<service>.json 记录并与运行中进程逐项比对
# （PID + 创建时间 + 命令行），不一致就拒绝操作。
param(
    [ValidateSet('Start', 'Stop', 'Restart', 'Status')]
    [string]$Action = 'Status',
    [int]$TimeoutSeconds = 0,
    [string]$DeployRoot = ''
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Ptmcp.Layout.ps1')

$layout = Resolve-PtmcpLayout -ScriptDir $PSScriptRoot -DeployRoot $DeployRoot
Import-Module $layout.Module -Force

$ctx = New-PtmcpContext -DeployRoot $layout.Root -Release $layout.Release
if ($TimeoutSeconds -gt 0) { $ctx.Config['PTMCP_START_TIMEOUT'] = "$TimeoutSeconds" }
Invoke-PtmcpAction -Context $ctx -Action $Action
