param([string]$EnvironmentRoot = 'D:\config\ptmcp')

$ErrorActionPreference = 'Stop'
$manifestPath = Join-Path $EnvironmentRoot 'environment.json'
if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "Development environment is not installed: $manifestPath"
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.project -ne 'Datachat-mcp') {
    throw 'Environment manifest does not belong to Datachat-mcp.'
}
$javaBin = Join-Path $manifest.javaHome 'bin'
$mavenBin = Join-Path $manifest.mavenHome 'bin'
$required = @(
    (Join-Path $javaBin 'java.exe'),
    (Join-Path $mavenBin 'mvn.cmd'),
    (Join-Path $manifest.nodeHome 'node.exe'),
    (Join-Path $manifest.pnpmHome 'pnpm.cmd')
)
foreach ($path in $required) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing development tool: $path"
    }
}
$env:JAVA_HOME = $manifest.javaHome
$env:MAVEN_HOME = $manifest.mavenHome
$env:MAVEN_USER_HOME = Join-Path $EnvironmentRoot 'cache\maven-user'
$env:PNPM_HOME = $manifest.pnpmHome
$env:PTMCP_DEV_HOME = $EnvironmentRoot
$prefixes = @($javaBin, $mavenBin, $manifest.nodeHome, (Join-Path $EnvironmentRoot 'bin'), $manifest.pnpmHome)
$env:Path = (($prefixes + ($env:Path -split ';')) | Select-Object -Unique) -join ';'
if ($env:PTMCP_ACTIVE -ne $EnvironmentRoot) {
    $env:PTMCP_ORIGINAL_MAVEN_OPTS = [string]$env:MAVEN_OPTS
    $env:PTMCP_ACTIVE = $EnvironmentRoot
}
$repository = Join-Path $EnvironmentRoot 'cache\maven'
$env:MAVEN_OPTS = "$env:PTMCP_ORIGINAL_MAVEN_OPTS `"-Dmaven.repo.local=$repository`"".Trim()
$env:npm_config_cache = Join-Path $EnvironmentRoot 'cache\npm'
$env:npm_config_store_dir = Join-Path $EnvironmentRoot 'cache\pnpm'
if ($manifest.dockerHost) {
    foreach ($name in @('ca.pem', 'cert.pem', 'key.pem')) {
        if (-not (Test-Path -LiteralPath (Join-Path $manifest.dockerCertPath $name))) {
            throw "Missing Docker client TLS file: $name"
        }
    }
    $env:DOCKER_HOST = $manifest.dockerHost
    $env:DOCKER_TLS_VERIFY = '1'
    $env:DOCKER_CERT_PATH = $manifest.dockerCertPath
}
Write-Output 'PTMCP environment selected for this terminal: Java 21 / Maven / Node 24 LTS / pnpm.'
Write-Output "Local configuration: $manifestPath"
