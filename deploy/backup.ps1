[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Assert-OrivaDocker
$null = Get-OrivaEnv
$null = Get-OrivaHealth
$destination = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $destination) { throw 'Choose a new backup directory; existing backups are never overwritten.' }
New-Item -ItemType Directory -Path $destination | Out-Null
Protect-OrivaPath $destination $true
$remote = '/tmp/oriva-backup-'+[guid]::NewGuid().ToString('N')+'.sql'
try {
    $dump = 'umask 077; MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump --user=root --single-transaction --routines --triggers --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 planlock > '+$remote
    Invoke-OrivaCompose exec -T mysql sh -c $dump
    Invoke-OrivaCompose cp ('mysql:'+$remote) (Join-Path $destination 'database.sql')
    Protect-OrivaPath (Join-Path $destination 'database.sql')
    Write-PrivateText (Join-Path $destination '.env') ([IO.File]::ReadAllText($script:EnvFile))
    $sums = foreach ($name in @('.env','database.sql')) {
        (Get-OrivaSha256 (Join-Path $destination $name))+'  '+$name
    }
    Write-PrivateText (Join-Path $destination 'SHA256SUMS') (($sums -join "`n")+"`n")
    Write-Output "Backup complete: $destination. It contains credentials and personal data; store it privately."
} finally {
    Invoke-OrivaCompose exec -T mysql rm -f $remote
}
