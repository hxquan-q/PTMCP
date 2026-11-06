# 解析部署根与当前版本目录；被根目录下的 ps1 入口点 source。
#   <deploy-root>/releases/<version>/scripts/Platform.Runtime.psm1
function Resolve-PtmcpLayout {
    param(
        [string]$ScriptDir = $PSScriptRoot,
        [string]$DeployRoot = ''
    )

    $scriptPath = (Resolve-Path -LiteralPath $ScriptDir).Path
    if ($DeployRoot -ne '') {
        $root = (Resolve-Path -LiteralPath $DeployRoot).Path
    } elseif ($scriptPath -match '(?i)\\releases\\[^\\]+\\scripts$') {
        $root = Split-Path (Split-Path (Split-Path $scriptPath -Parent) -Parent) -Parent
    } else {
        $root = $scriptPath
    }

    $releases = Join-Path $root 'releases'
    if (-not (Test-Path -LiteralPath $releases)) { throw "找不到版本目录：$releases（发布包未按布局组装？）" }

    $relName = ''
    $pointer = Join-Path $root 'shared\config\release.txt'
    if (Test-Path -LiteralPath $pointer) { $relName = (Get-Content -LiteralPath $pointer -Raw).Trim() }
    if ($relName -eq '' -or -not (Test-Path -LiteralPath (Join-Path $releases $relName))) {
        $latest = Get-ChildItem -LiteralPath $releases -Directory | Sort-Object Name -Descending | Select-Object -First 1
        if ($null -eq $latest) { throw "$releases 里没有版本目录" }
        $relName = $latest.Name
    }

    $release = Join-Path $releases $relName
    [pscustomobject]@{
        Root    = $root
        Release = $release
        Version = $relName
        Module  = Join-Path $release 'scripts\Platform.Runtime.psm1'
        Config  = Join-Path $root 'shared\config\app.env'
    }
}
