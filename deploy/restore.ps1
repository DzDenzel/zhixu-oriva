[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$BackupDirectory,
    [Parameter(Mandatory=$true)][string]$TargetDirectory,
    [Parameter(Mandatory=$true)][string]$ProjectName,
    [ValidateRange(1,65535)][int]$Port = 8081
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
$sourceKit = $PSScriptRoot
$backup = (Resolve-Path -LiteralPath $BackupDirectory).Path
$target = [IO.Path]::GetFullPath($TargetDirectory)
if (Test-Path -LiteralPath $target) { throw 'Restore requires a fresh target directory; existing contents are never overwritten.' }
if ($ProjectName -notmatch '^[a-z0-9][a-z0-9_-]{0,62}$') { throw 'Invalid new compose project name.' }
$checksumFile = Join-Path $backup 'SHA256SUMS'
if (!(Test-Path -LiteralPath $checksumFile)) { throw 'Backup SHA256SUMS is missing; refusing unverified input.' }
$names = @()
foreach ($line in [IO.File]::ReadAllLines($checksumFile)) {
    if ($line -notmatch '^([0-9a-fA-F]{64})  (\.env|database\.sql)$') { throw 'Invalid SHA256SUMS entry.' }
    $hash = $Matches[1]; $name = $Matches[2]
    if ($names -contains $name) { throw 'Duplicate SHA256SUMS entry.' }
    if ((Get-OrivaSha256 (Join-Path $backup $name)) -ne $hash.ToLowerInvariant()) { throw 'Backup checksum mismatch; target untouched.' }
    $names += $name
}
if ($names.Count -ne 2) { throw 'SHA256SUMS must contain .env and database.sql.' }
$script:EnvFile = Join-Path $backup '.env'
$original = Get-OrivaEnv
if ($original['COMPOSE_PROJECT_NAME'] -eq $ProjectName) { throw 'Restore must use a different compose project name.' }
Assert-OrivaDocker
$existingContainers = Invoke-OrivaDocker ps -aq --filter "label=com.docker.compose.project=$ProjectName"
$existingVolumes = Invoke-OrivaDocker volume ls -q --filter "label=com.docker.compose.project=$ProjectName"
if (($existingContainers | Out-String).Trim() -or ($existingVolumes | Out-String).Trim()) { throw 'Target compose project already owns resources; refusing to overwrite data.' }
# Compose can reuse same-named volumes even when their project labels are absent.
$existingNames = @(Invoke-OrivaDocker volume ls -q)
foreach ($name in @("${ProjectName}_mysql-data", "${ProjectName}_redis-data")) {
    if ($existingNames -contains $name) { throw 'Target data volume already exists; refusing to overwrite data.' }
}
New-Item -ItemType Directory -Path $target | Out-Null
Protect-OrivaPath $target $true
foreach ($name in @('docker-compose.yml','docker-compose.https.yml','Caddyfile','common.ps1','common.sh','install.ps1','install.sh','backup.ps1','backup.sh','restore.ps1','restore.sh','README.md')) {
    Copy-Item -LiteralPath (Join-Path $sourceKit $name) -Destination (Join-Path $target $name)
}
$text = [IO.File]::ReadAllText((Join-Path $backup '.env'))
$text = [regex]::Replace($text, '(?m)^COMPOSE_PROJECT_NAME=.*$', 'COMPOSE_PROJECT_NAME='+$ProjectName)
$text = [regex]::Replace($text, '(?m)^ORIVA_PORT=.*$', 'ORIVA_PORT='+$Port)
# Restore starts without claiming the original public domain. Enable HTTPS separately after switching DNS.
$text = [regex]::Replace($text, '(?m)^ORIVA_DOMAIN=.*$', 'ORIVA_DOMAIN=')
Write-PrivateText (Join-Path $target '.env') $text
$script:Kit = $target; $script:EnvFile = Join-Path $target '.env'
$remote = '/tmp/oriva-restore-'+[guid]::NewGuid().ToString('N')+'.sql'
Invoke-OrivaCompose up -d --wait --wait-timeout 240 mysql redis
try {
    Invoke-OrivaCompose cp (Join-Path $backup 'database.sql') ('mysql:'+$remote)
    Invoke-OrivaCompose exec -T mysql sh -c ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --default-character-set=utf8mb4 planlock < '+$remote)
} finally { Invoke-OrivaCompose exec -T mysql rm -f $remote }
Invoke-OrivaCompose up -d --wait --wait-timeout 240 --no-build
$health = Get-OrivaHealth
if (!$health.initialized) { throw 'Restored database has no completed initialization; inspect the backup before using it.' }
Write-Output "Restore complete. Backend port: $Port. Use a fresh App workspace for a changed URL; see README."
