param(
    [ValidateSet('start', 'stop', 'status')][string]$Action = 'status',
    [string]$EnvironmentRoot = 'D:\config\ptmcp'
)

$ErrorActionPreference = 'Stop'
$manifest = Get-Content -LiteralPath (Join-Path $EnvironmentRoot 'environment.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.project -ne 'Datachat-mcp' -or $manifest.distribution -ne 'PTMCP-Dev') {
    throw 'Unexpected development environment ownership.'
}
$runtimePath = Join-Path $EnvironmentRoot 'runtime'
New-Item -ItemType Directory -Force -Path $runtimePath | Out-Null
$keeperPath = Join-Path $runtimePath 'wsl-keeper.json'
$keeper = $null
if (Test-Path -LiteralPath $keeperPath) {
    $record = Get-Content -LiteralPath $keeperPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $candidate = Get-Process -Id $record.processId -ErrorAction SilentlyContinue
    if ($candidate -and $candidate.ProcessName -eq 'wsl' -and
        $candidate.StartTime.ToUniversalTime().Ticks -eq ([datetime]$record.startedAt).ToUniversalTime().Ticks) {
        $command = (Get-CimInstance Win32_Process -Filter "ProcessId = $($candidate.Id)").CommandLine
        if ($command -match 'PTMCP-Dev.*--exec sleep infinity') { $keeper = $candidate }
    }
}
if ($Action -eq 'start' -and -not $keeper) {
    $keeper = Start-Process -FilePath "$env:SystemRoot\System32\wsl.exe" -WindowStyle Hidden -PassThru -ArgumentList @(
        '-d', 'PTMCP-Dev', '-u', 'root', '--exec', 'sleep', 'infinity'
    ) -RedirectStandardOutput (Join-Path $EnvironmentRoot 'logs\wsl-keeper.out.log') `
      -RedirectStandardError (Join-Path $EnvironmentRoot 'logs\wsl-keeper.err.log')
    @{ processId = $keeper.Id; startedAt = $keeper.StartTime.ToUniversalTime().ToString('O') } |
        ConvertTo-Json | Set-Content -LiteralPath $keeperPath -Encoding UTF8
}
$arguments = @('-d', 'PTMCP-Dev', '-u', 'root', '--exec', 'docker', 'compose',
    '--project-directory', '/opt/ptmcp', '-f', '/opt/ptmcp/compose.yaml')
switch ($Action) {
    'start' { $arguments += @('up', '-d', '--wait', '--wait-timeout', '120') }
    'stop' { $arguments += @('stop') }
    'status' { $arguments += @('ps') }
}
& wsl.exe @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Development services operation failed (exit $LASTEXITCODE)."
}
if ($Action -eq 'stop' -and $keeper) {
    Stop-Process -Id $keeper.Id
    Remove-Item -LiteralPath $keeperPath
}
