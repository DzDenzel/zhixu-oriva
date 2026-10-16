[CmdletBinding()]
param([switch]$Build, [switch]$ShowSetup)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Assert-OrivaDocker

if (!(Test-Path -LiteralPath $script:EnvFile)) {
    function New-OrivaSecret {
        $bytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        return -join ($bytes | ForEach-Object { $_.ToString('x2') })
    }
    $configuration = @(
        'COMPOSE_PROJECT_NAME=oriva',
        'ORIVA_IMAGE=ghcr.io/dzdenzel/zhixu-oriva-backend:2.1.0',
        'ORIVA_BIND_ADDRESS=0.0.0.0', 'ORIVA_PORT=8080', 'ORIVA_TIMEZONE=UTC',
        "MYSQL_ROOT_PASSWORD=$(New-OrivaSecret)", "PLANLOCK_DB_PASSWORD=$(New-OrivaSecret)",
        "PLANLOCK_REDIS_PASSWORD=$(New-OrivaSecret)", "PLANLOCK_JWT_SECRET=$(New-OrivaSecret)",
        "PLANLOCK_SETUP_TOKEN=$(New-OrivaSecret)", 'PLANLOCK_PUBLIC_PLUGINS_CATALOG_URL=', 'ORIVA_DOMAIN='
    ) -join "`n"
    Write-PrivateText $script:EnvFile ($configuration+"`n")
}
$values = Get-OrivaEnv
if (!$ShowSetup) {
    if ($Build) {
        if (!(Test-Path -LiteralPath (Join-Path $PSScriptRoot '../backend/Dockerfile'))) { throw 'Local build requires the complete source archive. See README.' }
        Invoke-OrivaCompose build --pull backend
    } else { Invoke-OrivaCompose pull }
    if ($Build) { Invoke-OrivaCompose up -d --wait --wait-timeout 240 --no-build }
    else { Invoke-OrivaCompose up -d --wait --wait-timeout 240 }
}
$health = Get-OrivaHealth
if ($health.initialized) { Write-Output 'Your backend is ready. Sign in with your existing owner account; initialization remains closed.' }
else {
    $url = if ($values['ORIVA_DOMAIN']) { 'https://'+$values['ORIVA_DOMAIN']+'/setup' } else { 'http://localhost:'+$values['ORIVA_PORT']+'/setup' }
    Write-Output "Open $url"
    $addresses = @(Get-OrivaPrivateIPv4)
    if ($addresses.Count -gt 0) {
        foreach ($address in $addresses) {
            $suffix = if ($address.Recommended) { ' (recommended)' } else { '' }
            Write-Output ('LAN candidate: http://'+$address.IPAddress+':'+$values['ORIVA_PORT']+'/setup'+$suffix)
        }
    } else {
        Write-Output 'No private IPv4 address detected; run ipconfig and choose an address your phone can reach.'
    }
    Write-Output ('One-time initialization password: '+$values['PLANLOCK_SETUP_TOKEN'])
    Write-Output 'On the setup page, confirm an address your phone can reach (LAN IP or HTTPS domain).'
}
