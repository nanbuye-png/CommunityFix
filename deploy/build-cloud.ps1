$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw 'Run python deploy/init-dev.py first.' }
foreach ($line in [IO.File]::ReadAllLines($envFile)) {
    if ($line -match '^([A-Z_]+)=(.*)$') { Set-Item -LiteralPath "Env:$($Matches[1])" -Value $Matches[2] }
}
$env:EUREKA_ENABLED = 'false'
$repo = '-Dmaven.repo.local=' + (Join-Path $projectRoot '.local/m2')
Push-Location $projectRoot
try {
    & mvn.cmd -B -U $repo package
    exit $LASTEXITCODE
} finally { Pop-Location }
