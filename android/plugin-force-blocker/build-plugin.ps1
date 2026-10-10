param([switch]$SkipRepositoryCopy)
$ErrorActionPreference = 'Stop'
if (-not $env:ORIVA_PLUGIN_STOREPASS) {
    throw 'Set ORIVA_PLUGIN_STOREPASS to the password of your authorized plugin signing keystore.'
}

$moduleDir = $PSScriptRoot
$androidRoot = Split-Path -Parent $moduleDir
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'G:\SDK' }
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-21' }
$workDir = Join-Path $moduleDir 'build\plugin-package'
$outputDir = Join-Path $moduleDir 'build\outputs\plugin'
$pluginAar = Join-Path $moduleDir 'build\outputs\aar\plugin-force-blocker-release.aar'
$apiAar = Join-Path $androidRoot 'core-plugin\build\outputs\aar\core-plugin-release.aar'
$officialKeystore = Join-Path $androidRoot '.plugin-signing\planlock-plugin-dev.p12'
$thirdPartyKeystore = Join-Path $androidRoot '.plugin-signing\planlock-thirdparty-dev.p12'
$output = Join-Path $outputDir 'force-blocker-1.0.0.plp'
$thirdPartyOutput = Join-Path $outputDir 'community-force-blocker-1.0.0.plp'
$repositoryOutputDir = Join-Path (Split-Path -Parent $androidRoot) 'backend\plugins\official'
$thirdPartyRepositoryOutputDir = Join-Path (Split-Path -Parent $androidRoot) 'backend\plugins\third-party'

foreach ($keystore in $officialKeystore, $thirdPartyKeystore) {
    if (-not (Test-Path -LiteralPath $keystore)) {
        throw "Missing plugin signing keystore: $keystore"
    }
}

if (Test-Path -LiteralPath $workDir) {
    $resolvedWorkDir = [System.IO.Path]::GetFullPath($workDir)
    $resolvedBuildDir = [System.IO.Path]::GetFullPath((Join-Path $moduleDir 'build'))
    foreach ($candidateDir in @($moduleDir, $resolvedBuildDir, $resolvedWorkDir)) {
        if ((Get-Item -LiteralPath $candidateDir).Attributes.HasFlag([System.IO.FileAttributes]::ReparsePoint)) {
            throw "Refusing plugin staging cleanup through reparse directory: $candidateDir"
        }
    }
    if ((Split-Path -Parent $resolvedWorkDir) -ne $resolvedBuildDir -or
        (Get-Item -LiteralPath $resolvedWorkDir).Attributes.HasFlag([System.IO.FileAttributes]::ReparsePoint)) {
        throw "Refusing to delete unverified plugin staging path: $resolvedWorkDir"
    }
    Remove-Item -LiteralPath $workDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path (Join-Path $workDir 'plugin-aar'), (Join-Path $workDir 'api-aar'), (Join-Path $workDir 'dex'), $outputDir | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($pluginAar, (Join-Path $workDir 'plugin-aar'))
[System.IO.Compression.ZipFile]::ExtractToDirectory($apiAar, (Join-Path $workDir 'api-aar'))

$d8 = Join-Path $sdkRoot 'build-tools\36.0.0\d8.bat'
$androidJar = Join-Path $sdkRoot 'platforms\android-36\android.jar'
& $d8 --release --min-api 24 --lib $androidJar --classpath (Join-Path $workDir 'api-aar\classes.jar') --output (Join-Path $workDir 'dex') (Join-Path $workDir 'plugin-aar\classes.jar')
if ($LASTEXITCODE -ne 0) { throw "d8 failed with exit code $LASTEXITCODE" }

function New-SignedPluginPackage {
    param(
        [string]$Name,
        [string]$Manifest,
        [string]$Destination,
        [string]$Keystore,
        [string]$Alias
    )
    $staging = Join-Path $workDir "staging-$Name"
    $unsigned = Join-Path $workDir "$Name-unsigned.jar"
    New-Item -ItemType Directory -Force -Path $staging | Out-Null
    Copy-Item -LiteralPath (Join-Path $workDir 'dex\classes.dex') -Destination (Join-Path $staging 'classes.dex')
    Copy-Item -LiteralPath $Manifest -Destination (Join-Path $staging 'plugin.json')
    Push-Location $staging
    try {
        & (Join-Path $javaRoot 'bin\jar.exe') --create --file $unsigned classes.dex plugin.json
        if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
    Copy-Item -LiteralPath $unsigned -Destination $Destination -Force
    & (Join-Path $javaRoot 'bin\jarsigner.exe') '-J-Duser.language=en' '-J-Duser.country=US' -keystore $Keystore -storetype PKCS12 -storepass:env ORIVA_PLUGIN_STOREPASS -keypass:env ORIVA_PLUGIN_STOREPASS -sigalg SHA256withRSA -digestalg SHA-256 $Destination $Alias
    if ($LASTEXITCODE -ne 0) { throw "jarsigner failed with exit code $LASTEXITCODE" }
}

New-SignedPluginPackage -Name 'force-blocker' -Manifest (Join-Path $moduleDir 'plugin.json') -Destination $output -Keystore $officialKeystore -Alias 'planlock-plugin-dev'
New-SignedPluginPackage -Name 'community-force-blocker' -Manifest (Join-Path $moduleDir 'plugin-third-party.json') -Destination $thirdPartyOutput -Keystore $thirdPartyKeystore -Alias 'planlock-thirdparty-dev'

function Get-Sha256Hex {
    param([string]$Path)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $stream = [System.IO.File]::OpenRead($Path)
        try {
            return ([System.BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '')
        } finally {
            $stream.Dispose()
        }
    } finally {
        $sha.Dispose()
    }
}
$hash = Get-Sha256Hex $output
$thirdPartyHash = Get-Sha256Hex $thirdPartyOutput
$length = (Get-Item -LiteralPath $output).Length
$thirdPartyLength = (Get-Item -LiteralPath $thirdPartyOutput).Length
if (-not $SkipRepositoryCopy) {
    New-Item -ItemType Directory -Force -Path $repositoryOutputDir | Out-Null
    New-Item -ItemType Directory -Force -Path $thirdPartyRepositoryOutputDir | Out-Null
    Copy-Item -LiteralPath $output -Destination (Join-Path $repositoryOutputDir 'force-blocker-1.0.0.plp') -Force
    Copy-Item -LiteralPath $thirdPartyOutput -Destination (Join-Path $thirdPartyRepositoryOutputDir 'community-force-blocker-1.0.0.plp') -Force
}
Write-Output "PLUGIN_PATH=$output"
Write-Output "PLUGIN_REPOSITORY_COPY_SKIPPED=$SkipRepositoryCopy"
Write-Output "PLUGIN_SIZE=$length"
Write-Output "PLUGIN_SHA256=$hash"
Write-Output "THIRD_PARTY_PLUGIN_PATH=$thirdPartyOutput"
Write-Output "THIRD_PARTY_PLUGIN_SIZE=$thirdPartyLength"
Write-Output "THIRD_PARTY_PLUGIN_SHA256=$thirdPartyHash"
