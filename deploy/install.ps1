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
        'ORIVA_IMAGE=ghcr.io/dzdenzel/zhixu-oriva-backend:2.1.3',
        'ORIVA_BIND_ADDRESS=0.0.0.0', 'ORIVA_PORT=8080', 'ORIVA_TIMEZONE=UTC',
        "MYSQL_ROOT_PASSWORD=$(New-OrivaSecret)", "PLANLOCK_DB_PASSWORD=$(New-OrivaSecret)",
        "PLANLOCK_REDIS_PASSWORD=$(New-OrivaSecret)", "PLANLOCK_JWT_SECRET=$(New-OrivaSecret)",
        "PLANLOCK_SETUP_TOKEN=$(New-OrivaSecret)", 'PLANLOCK_PUBLIC_PLUGINS_CATALOG_URL=', 'ORIVA_DOMAIN='
    ) -join "`n"
    Write-PrivateText $script:EnvFile ($configuration+"`n")
}
function Test-OrivaCachedBaseImages([string]$DockerfilePath) {
    $stages = @{}
    $images = @()
    foreach ($rawLine in [IO.File]::ReadAllLines($DockerfilePath)) {
        $line = $rawLine.Trim()
        if ($line -notmatch '^(?i)FROM\s+(.+)$') { continue }
        $tokens = @($Matches[1] -split '\s+')
        $idx = 0
        while ($idx -lt $tokens.Count -and $tokens[$idx].StartsWith('--')) { $idx++ }
        if ($idx -ge $tokens.Count) { continue }
        $img = $tokens[$idx]
        if ($img -and !$img.StartsWith('-') -and $img.ToLowerInvariant() -ne 'scratch' -and !$stages.ContainsKey($img.ToLowerInvariant())) {
            $images += $img
        }
        if (($idx + 2) -lt $tokens.Count -and $tokens[$idx + 1].ToLowerInvariant() -eq 'as') {
            $stages[$tokens[$idx + 2].ToLowerInvariant()] = $true
        }
    }
    if ($images.Count -eq 0) { return $false }
    foreach ($image in $images) {
        $savedPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & docker image inspect $image *> $null
            if ($LASTEXITCODE -ne 0) { return $false }
        } catch {
            return $false
        } finally {
            $ErrorActionPreference = $savedPreference
        }
    }
    return $true
}
$values = Get-OrivaEnv
if (!$ShowSetup) {
    if ($Build) {
        $dockerfile = Join-Path $PSScriptRoot '../backend/Dockerfile'
        if (!(Test-Path -LiteralPath $dockerfile)) { throw 'Local build requires the complete source archive. See README.' }
        $buildArgs = @()
        foreach ($k in @('HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'no_proxy')) {
            $val = [Environment]::GetEnvironmentVariable($k)
            if ([string]::IsNullOrEmpty($val) -or $val -match '[\s\p{Cc}]') { continue }
            $buildArgs += @('--build-arg', "$k=$val")
        }
        $mirrorVal = [Environment]::GetEnvironmentVariable('ORIVA_MAVEN_MIRROR')
        if (![string]::IsNullOrEmpty($mirrorVal)) {
            $mirrorValid = $false
            if ($mirrorVal -notmatch '[\s\p{Cc}]' -and $mirrorVal -cmatch '\Ahttps://([A-Za-z0-9._-]+)(?::([0-9]+))?(?:/[A-Za-z0-9._:/-]*)?\z') {
                $mirrorHost = $Matches[1]
                $mirrorPort = if ($Matches.ContainsKey(2)) { $Matches[2] } else { $null }
                if ($mirrorHost -notmatch '\A\.|\.\z|\.\.') {
                    if ([string]::IsNullOrEmpty($mirrorPort)) {
                        $mirrorValid = $true
                    } else {
                        $portNum = 0
                        if ([int]::TryParse($mirrorPort, [ref]$portNum) -and $portNum -ge 1 -and $portNum -le 65535) {
                            $mirrorValid = $true
                        }
                    }
                }
            }
            if ($mirrorValid) {
                $buildArgs += @('--build-arg', "MAVEN_MIRROR_URL=$mirrorVal")
            } else {
                Write-Warning 'Ignoring invalid ORIVA_MAVEN_MIRROR (must be an https:// URL without whitespace, credentials, query/fragment, or XML/shell special characters).'
            }
        }
        try {
            Invoke-OrivaCompose build --pull @buildArgs backend
        } catch {
            if (Test-OrivaCachedBaseImages $dockerfile) {
                Write-Warning 'compose build --pull failed, but required base images are cached locally; retrying build without --pull.'
                Invoke-OrivaCompose build @buildArgs backend
            } else {
                throw
            }
        }
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
