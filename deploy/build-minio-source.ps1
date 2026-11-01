param([string]$DnsServer = '1.1.1.1')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $taskRoot '.local/acceptance/minio-source'
$output = Join-Path $taskRoot '.local/acceptance/minio-build'
$revision = '0d7408fc9969caf07de6a8c3a84f9fbb10a6739e'
if (-not (Test-Path -LiteralPath $source)) {
    & git clone --depth 1 --branch RELEASE.2025-04-22T22-12-26Z https://github.com/minio/minio.git $source
    if ($LASTEXITCODE) { throw 'Official source download failed.' }
}
$actual = & git -C $source rev-parse HEAD
if ($LASTEXITCODE -or $actual -ne $revision) { throw 'MinIO revision does not match verified release.' }
$changes = & git -C $source status --porcelain
if ($LASTEXITCODE -or $changes) { throw 'MinIO source is modified; build refused.' }
New-Item -ItemType Directory -Force $output | Out-Null
$arguments = @('run','--rm')
if ($DnsServer) {
    $parsedDns = $null
    if (-not [Net.IPAddress]::TryParse($DnsServer,[ref]$parsedDns)) { throw 'Invalid DNS address.' }
    $arguments += @('--dns',$DnsServer)
}
$arguments += @('--mount',"type=bind,source=$source,target=/src,readonly",'--mount',"type=bind,source=$output,target=/out",'-w','/src','-e','CGO_ENABLED=0','golang:1.24.2-bookworm','go','build','-buildvcs=false','-trimpath','-o','/out/minio','.')
& docker @arguments
if ($LASTEXITCODE) { throw 'MinIO source compilation failed.' }
Copy-Item -LiteralPath (Join-Path $source 'LICENSE') -Destination (Join-Path $output 'LICENSE')
& docker build -t communityfix-minio:source-20250422 -f (Join-Path $PSScriptRoot 'acceptance/MinioBinary.Dockerfile') $output
if ($LASTEXITCODE) { throw 'MinIO image build failed.' }
@{revision=$revision;source='https://github.com/minio/minio';binarySha256=(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $output 'minio')).Hash;image='communityfix-minio:source-20250422'} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $output 'source-evidence.json')
