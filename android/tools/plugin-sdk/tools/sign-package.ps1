param(
    [string]$Package = (Join-Path $PSScriptRoot '..\dist\plugin-focus-advice-0.1.0.unsigned.pljs'),
    [string]$Destination = (Join-Path $PSScriptRoot '..\dist\plugin-focus-advice-0.1.0.pljs'),
    [string]$Keystore = (Join-Path $PSScriptRoot '..\..\..\.plugin-signing\planlock-plugin-dev.p12'),
    [string]$Alias = 'planlock-plugin-dev',
    [string]$JavaRoot = 'C:\Program Files\Java\jdk-21'
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrEmpty($env:ORIVA_PLUGIN_STOREPASS)) { throw 'Set ORIVA_PLUGIN_STOREPASS to the authorized signing password; it is never printed.' }
foreach ($path in @($Package, $Keystore)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required input is missing: $path" }
}
if ([System.IO.Path]::GetFullPath($Package) -eq [System.IO.Path]::GetFullPath($Destination)) { throw 'Output must differ from unsigned input.' }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
Copy-Item -LiteralPath $Package -Destination $Destination -Force
& (Join-Path $JavaRoot 'bin\jarsigner.exe') '-J-Duser.language=en' '-J-Duser.country=US' `
    -keystore $Keystore -storetype PKCS12 -storepass:env ORIVA_PLUGIN_STOREPASS -keypass:env ORIVA_PLUGIN_STOREPASS `
    -sigalg SHA256withRSA -digestalg SHA-256 $Destination $Alias
if ($LASTEXITCODE -ne 0) { throw "jarsigner failed: $LASTEXITCODE" }
& (Join-Path $JavaRoot 'bin\jarsigner.exe') '-J-Duser.language=en' '-J-Duser.country=US' -verify -verbose -certs $Destination
if ($LASTEXITCODE -ne 0) { throw "jarsigner verification failed: $LASTEXITCODE" }
$file = Get-Item -LiteralPath $Destination
[ordered]@{ path = $file.FullName; bytes = $file.Length; sha256 = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash;
    keystore = [System.IO.Path]::GetFullPath($Keystore); alias = $Alias } | ConvertTo-Json
