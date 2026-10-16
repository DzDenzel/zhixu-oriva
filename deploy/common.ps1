$ErrorActionPreference = 'Stop'
$script:Kit = $PSScriptRoot
$script:EnvFile = Join-Path $script:Kit '.env'

function Protect-OrivaPath([string]$Path, [bool]$Directory = $false) {
    if ($env:OS -eq 'Windows_NT') {
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        $item = if ($Directory) { [IO.DirectoryInfo]::new($Path) } else { [IO.FileInfo]::new($Path) }
        $acl = if ($PSVersionTable.PSVersion.Major -ge 6) {
            [IO.FileSystemAclExtensions]::GetAccessControl($item, [Security.AccessControl.AccessControlSections]::Access)
        } else { $item.GetAccessControl([Security.AccessControl.AccessControlSections]::Access) }
        $acl.SetAccessRuleProtection($true, $false)
        foreach ($existing in @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))) { $acl.RemoveAccessRuleSpecific($existing) }
        $inheritance = if ($Directory) { [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit' } else { [Security.AccessControl.InheritanceFlags]::None }
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', $inheritance, 'None', 'Allow')
        $acl.AddAccessRule($rule)
        if ($PSVersionTable.PSVersion.Major -ge 6) { [IO.FileSystemAclExtensions]::SetAccessControl($item, $acl) }
        else { $item.SetAccessControl($acl) }
    } else {
        & chmod $(if ($Directory) { '700' } else { '600' }) -- $Path
        if ($LASTEXITCODE) { throw 'Cannot restrict private file permissions' }
    }
}

function Write-PrivateText([string]$Path, [string]$Value) {
    # CreateNew prevents a second installer from replacing existing credentials.
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        Protect-OrivaPath $Path
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Value)
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush()
    } finally { $stream.Dispose() }
}

function Get-OrivaSha256([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
    finally { $algorithm.Dispose(); $stream.Dispose() }
}

function Get-OrivaEnv {
    if (!(Test-Path -LiteralPath $script:EnvFile -PathType Leaf)) { throw 'Run install.ps1 first; .env not found.' }
    $result = @{}
    foreach ($line in [IO.File]::ReadAllLines($script:EnvFile)) {
        if (!$line -or $line.StartsWith('#')) { continue }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=([^\r\n]*)$') { throw 'Invalid .env format; do not execute it as a script.' }
        if ($result.ContainsKey($Matches[1])) { throw 'Duplicate key in .env' }
        $result[$Matches[1]] = $Matches[2]
    }
    foreach ($key in @('PLANLOCK_JWT_SECRET','PLANLOCK_DB_PASSWORD','MYSQL_ROOT_PASSWORD','PLANLOCK_REDIS_PASSWORD','PLANLOCK_SETUP_TOKEN')) {
        if ($result[$key] -notmatch '^[0-9a-f]{64,}$') { throw "Invalid or missing $key in .env; original file preserved." }
    }
    if ($result['COMPOSE_PROJECT_NAME'] -notmatch '^[a-z0-9][a-z0-9_-]{0,62}$') { throw 'Invalid compose project name.' }
    if ($result['ORIVA_PORT'] -notmatch '^\d{1,5}$' -or [int]$result['ORIVA_PORT'] -lt 1 -or [int]$result['ORIVA_PORT'] -gt 65535) { throw 'Invalid backend port.' }
    if (!$result['ORIVA_IMAGE'] -or $result['ORIVA_IMAGE'] -match '[\s$`"'']') { throw 'Invalid backend image.' }
    Protect-OrivaPath $script:EnvFile
    return $result
}

function Invoke-OrivaDocker {
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw 'Docker operation failed. No volumes have been deleted; see deploy/README.md for troubleshooting.' }
}

function Assert-OrivaDocker {
    if (!(Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Please install and start Docker Desktop / Docker Engine first.' }
    Invoke-OrivaDocker info --format '{{.ServerVersion}}' | Out-Null
    $version = (Invoke-OrivaDocker compose version --short | Out-String).Trim().TrimStart('v')
    if ($version -notmatch '^(\d+)\.(\d+)\.(\d+)') { throw 'Cannot determine Docker Compose version.' }
    if ([version]("{0}.{1}.{2}" -f $Matches[1],$Matches[2],$Matches[3]) -lt [version]'2.24.4') { throw 'Docker Compose 2.24.4 or newer is required.' }
}

function Invoke-OrivaCompose {
    $values = Get-OrivaEnv
    $files = @('--env-file', $script:EnvFile, '-f', (Join-Path $script:Kit 'docker-compose.yml'), '--project-name', $values['COMPOSE_PROJECT_NAME'])
    if ($values['ORIVA_DOMAIN']) { $files += @('-f', (Join-Path $script:Kit 'docker-compose.https.yml')) }
    $saved = @{}
    try {
        foreach ($key in $values.Keys) {
            if ($key -match '^(PLANLOCK_|ORIVA_|MYSQL_ROOT_PASSWORD$|COMPOSE_PROJECT_NAME$)') {
                $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
                [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
            }
        }
        Invoke-OrivaDocker compose @files @args
    } finally {
        foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    }
}

function Get-OrivaHealth {
    $raw = Invoke-OrivaCompose exec -T backend java -cp /opt/oriva/health Healthcheck --json
    $health = ($raw | Out-String) | ConvertFrom-Json
    if ($health.status -ne 'UP') { throw 'Backend is not ready.' }
    return $health
}
# Filter host-internal interfaces; recommendation never chooses an address for the owner.
function Get-OrivaPrivateIPv4 {
    try {
        $adapters = @(Get-NetAdapter -ErrorAction Stop)
        $routes = @(Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop |
            Where-Object { $_.NextHop -and $_.NextHop -ne '0.0.0.0' })
        @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop | ForEach-Object {
            $address = $_
            $adapter = $adapters | Where-Object { $_.InterfaceIndex -eq $address.InterfaceIndex } | Select-Object -First 1
            if ($address.IPAddress -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)' -and
                $adapter -and $adapter.HardwareInterface -and $adapter.Status -eq 'Up' -and
                ($adapter.Name+' '+$adapter.InterfaceDescription) -notmatch 'Hyper-V|WSL|VMware|VirtualBox|vEthernet|Tailscale|WireGuard|VPN') {
                [pscustomobject]@{
                    IPAddress = $address.IPAddress
                    Recommended = @($routes | Where-Object { $_.InterfaceIndex -eq $address.InterfaceIndex }).Count -gt 0
                }
            }
        } | Sort-Object @{Expression='Recommended';Descending=$true}, IPAddress -Unique)
    } catch { @() }
}
