param(
    [string]$SourceFont = "$PSScriptRoot/../../work/zhixu-visual-20261003/design-studies/MaShanZheng-Regular.ttf",
    [string]$SourceLicense = "$PSScriptRoot/../../work/zhixu-visual-20261003/design-studies/mashanzheng-OFL.txt",
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$expectedFontHash = '6D2546BB189C732A8CA29AF9E22457B152387D158AA459E4AC2CE1E51788B7FB'
$expectedLicenseHash = 'D7BDB1CEE215B689E23C2F95672A6084C790542170648267A55114103D756A08'
if ((Get-FileHash -LiteralPath $SourceFont -Algorithm SHA256).Hash -ne $expectedFontHash) {
    throw 'Source font differs from the reviewed Google Fonts Ma Shan Zheng original.'
}
if ((Get-FileHash -LiteralPath $SourceLicense -Algorithm SHA256).Hash -ne $expectedLicenseHash) {
    throw 'Source license differs from the reviewed SIL OFL 1.1.'
}
$fontDirectory = "$PSScriptRoot/../app/src/main/res/font"
$licenseDirectory = "$PSScriptRoot/../app/src/main/assets/licenses"
New-Item -ItemType Directory -Force -Path $fontDirectory, $licenseDirectory | Out-Null
& $Python -m fontTools.subset $SourceFont '--text=知所想，序所行 ' "--output-file=$fontDirectory/zhixu_motto.ttf" '--name-IDs=*' '--name-languages=*' '--name-legacy'
if ($LASTEXITCODE -ne 0) { throw "Font subsetting failed: $LASTEXITCODE" }
Copy-Item -LiteralPath $SourceLicense -Destination "$licenseDirectory/zhixu_motto_OFL.txt" -Force
Get-Item -LiteralPath "$fontDirectory/zhixu_motto.ttf" | Select-Object FullName, Length
Get-FileHash -LiteralPath "$fontDirectory/zhixu_motto.ttf" -Algorithm SHA256
