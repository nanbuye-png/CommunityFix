param([Parameter(Mandatory=$true)][ValidateSet('registry','backend','gateway')][string]$Service)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($Service -eq 'backend') {
    & (Join-Path $PSScriptRoot 'run-backend.ps1') -Cloud
    exit $LASTEXITCODE
}
$repo = '-Dmaven.repo.local=' + (Join-Path $projectRoot '.local/m2')
Push-Location (Join-Path $projectRoot $Service)
try {
    & mvn.cmd -B $repo spring-boot:run
    exit $LASTEXITCODE
} finally { Pop-Location }
