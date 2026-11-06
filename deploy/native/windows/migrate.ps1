# 对元库执行 Flyway 迁移（Windows）。
#
# 优先本机 flyway CLI（FLYWAY_EXE，默认 PATH 上的 flyway）；否则回退 Docker
# （与 deploy/compose.yaml 同一固定镜像摘要）。两者都没有时直接失败。
param(
    [string]$DeployRoot = ''
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Ptmcp.Layout.ps1')

$layout = Resolve-PtmcpLayout -ScriptDir $PSScriptRoot -DeployRoot $DeployRoot
Import-Module $layout.Module -Force
$ctx = New-PtmcpContext -DeployRoot $layout.Root -Release $layout.Release
$cfg = $ctx.Config

$migration = Join-Path $ctx.Release 'migration'
if (-not (Test-Path -LiteralPath $migration)) { throw "缺少迁移目录：$migration" }
$jdbc = Get-PtmcpConfigValue $cfg 'PTMCP_METADATA_JDBC_URL' ''
if ($jdbc -eq '') { throw 'app.env 缺少 PTMCP_METADATA_JDBC_URL' }

Write-Host '== PTMCP 元库迁移 =='
Write-Host "JDBC：$jdbc"
Write-Host "迁移目录：$migration"

$flywayCmd = Get-PtmcpConfigValue $cfg 'FLYWAY_EXE' 'flyway'
$flyway = Get-Command $flywayCmd -ErrorAction SilentlyContinue
if ($null -ne $flyway) {
    Write-Host '使用本机 flyway CLI。'
    $env:FLYWAY_URL = $jdbc
    $env:FLYWAY_USER = Get-PtmcpConfigValue $cfg 'PTMCP_METADATA_DB_USERNAME' ''
    $env:FLYWAY_PASSWORD = Get-PtmcpConfigValue $cfg 'PTMCP_METADATA_DB_PASSWORD' ''
    $env:FLYWAY_LOCATIONS = "filesystem:$migration"
    $env:FLYWAY_CONNECT_RETRIES = '30'
    & $flyway.Source migrate
    exit $LASTEXITCODE
}

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -ne $docker) {
    Write-Host '未找到 flyway CLI，回退到 Docker（flyway/flyway:11-alpine）。'
    # 容器内访问宿主 MySQL 时用 host.docker.internal。
    $containerUrl = $jdbc -replace '//(127\.0\.0\.1|localhost)', '//host.docker.internal'
    & $docker.Source run --rm -v "${migration}:/flyway/sql:ro" `
        -e "FLYWAY_URL=$containerUrl" `
        -e "FLYWAY_USER=$(Get-PtmcpConfigValue $cfg 'PTMCP_METADATA_DB_USERNAME' '')" `
        -e "FLYWAY_PASSWORD=$(Get-PtmcpConfigValue $cfg 'PTMCP_METADATA_DB_PASSWORD' '')" `
        -e 'FLYWAY_LOCATIONS=filesystem:/flyway/sql' `
        -e 'FLYWAY_CONNECT_RETRIES=30' `
        flyway/flyway@sha256:df06535dfd6559ae5f0628c4761013dfa4ea6a30cf6e34294931d8091f55a18f `
        migrate
    exit $LASTEXITCODE
}

throw '需要 flyway CLI 或 Docker 之一来执行迁移。安装 Flyway 后在 app.env 设置 FLYWAY_EXE。'
