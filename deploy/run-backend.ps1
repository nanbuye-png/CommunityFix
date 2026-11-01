param([switch]$Test,[switch]$Package,[switch]$Cloud,[switch]$Minio)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw 'Run python deploy/init-dev.py first.' }
foreach ($line in [IO.File]::ReadAllLines($envFile)) {
    if ($line -match '^([A-Z_]+)=(.*)$') { Set-Item -LiteralPath "Env:$($Matches[1])" -Value $Matches[2] }
}
$env:DB_URL = 'jdbc:mysql://localhost:3307/communityfix?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8'
$env:DB_USER = 'communityfix'
$env:DEMO_ENABLED = 'true'
if (-not (Test-Path Env:DEMO_SYNC_PASSWORDS)) { $env:DEMO_SYNC_PASSWORDS = 'true' }
$env:STORAGE_MODE = if ($Minio) { 'minio' } else { 'local' }
$env:STORAGE_DIRECTORY = Join-Path $projectRoot '.local/uploads'
$env:API_DOCS_ENABLED = 'true'
$env:EUREKA_ENABLED = if ($Cloud) { 'true' } else { 'false' }
$repo = '-Dmaven.repo.local=' + (Join-Path $projectRoot '.local/m2')
Push-Location (Join-Path $projectRoot 'backend')
try {
    if ($Test) { $env:CF_INTEGRATION='true'; & mvn.cmd -B $repo test }
    elseif ($Package) { & mvn.cmd -B $repo package }
    else { & mvn.cmd -B $repo spring-boot:run }
    exit $LASTEXITCODE
} finally { Pop-Location }
