$ErrorActionPreference = 'Stop'
$envFile = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw 'Run python deploy/init-dev.py first.' }
$secretLine = [IO.File]::ReadAllLines($envFile) | Where-Object { $_.StartsWith('BOOTSTRAP_PASSWORD=') } | Select-Object -First 1
if (-not $secretLine) { throw 'BOOTSTRAP_PASSWORD missing.' }
$password = $secretLine.Substring('BOOTSTRAP_PASSWORD='.Length)
$login = $null
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    try {
        $registry = Invoke-RestMethod -Uri 'http://localhost:8761/eureka/apps' -Headers @{Accept='application/json'} -TimeoutSec 5
        $names = @($registry.applications.application | ForEach-Object { $_.name })
        if ($names -contains 'COMMUNITYFIX-SERVICE' -and $names -contains 'COMMUNITYFIX-GATEWAY') {
            $login = Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/api/auth/login' -ContentType 'application/json' -Body (@{username='admin';password=$password} | ConvertTo-Json -Compress) -TimeoutSec 5
            if ($login.code -eq 200 -and $login.data.token) { break }
        }
    } catch {}
    Start-Sleep -Seconds 3
}
if ($login.code -ne 200 -or -not $login.data.token) { throw 'Gateway login failed.' }
$headers = @{Authorization='Bearer ' + $login.data.token}
$me = Invoke-RestMethod -Uri 'http://localhost:8081/api/auth/me' -Headers $headers -TimeoutSec 10
$users = Invoke-RestMethod -Uri 'http://localhost:8081/api/system/users?size=1' -Headers $headers -TimeoutSec 10
if ($me.code -ne 200 -or $users.code -ne 200) { throw 'Gateway authentication or user route failed.' }
try {
    Invoke-WebRequest -Uri 'http://localhost:8081/communityfix-service/api/auth/me' -UseBasicParsing -TimeoutSec 10 | Out-Null
    throw 'Unexpected discovery route is accessible.'
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 404) { throw }
}
Write-Output 'PASS: Eureka registration, Gateway load-balanced API, JWT and user route, hidden discovery path.'
