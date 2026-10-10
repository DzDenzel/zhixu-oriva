param(
    [Parameter(Mandatory=$true)][string]$Maven,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$EvidenceDirectory,
    [string]$MavenRepository = 'C:/Users/Administrator/.m2/repository',
    [string]$MavenSettings = 'C:/Users/Administrator/.m2/settings.xml',
    [string]$Test = 'DatabaseMigrationTest',
    [string]$LogName = 'f37-green.log',
    [switch]$Verify
)
$ErrorActionPreference = 'Stop'
if (-not $env:F37_DB_USER -or -not $env:F37_DB_PASSWORD) { throw 'Supply F37_DB_USER/F37_DB_PASSWORD in the process environment; credentials are never logged.' }
$backend = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$evidence = [System.IO.Path]::GetFullPath($EvidenceDirectory)
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$previousJava = $env:JAVA_HOME
$previousEnabled = $env:F37_ENABLED
$previousEvidence = $env:F37_EVIDENCE_DIR
$previousMavenOptions = $env:MAVEN_OPTS
try {
    $env:JAVA_HOME = $JavaHome
    $env:F37_ENABLED = 'true'
    $env:F37_EVIDENCE_DIR = $evidence
    $env:MAVEN_OPTS = "$previousMavenOptions -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Duser.language=en -Duser.country=US"
    $arguments = @('-B','-ntp','-s',$MavenSettings,"-Dmaven.repo.local=$MavenRepository",'-f',(Join-Path $backend 'pom.xml'),'-pl','planlock-app','-am')
    if ($Verify) { $arguments += @('clean','verify') }
    else { $arguments += @('test',"-Dtest=$Test",'-Dsurefire.failIfNoSpecifiedTests=false') }
    & $Maven @arguments 2>&1 | Tee-Object -FilePath (Join-Path $evidence $LogName)
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "Maven exited $code; see $LogName" }
} finally {
    $env:JAVA_HOME = $previousJava
    $env:F37_ENABLED = $previousEnabled
    $env:F37_EVIDENCE_DIR = $previousEvidence
    $env:MAVEN_OPTS = $previousMavenOptions
}
