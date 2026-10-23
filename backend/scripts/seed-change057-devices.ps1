param([int]$Port=18081)
$ErrorActionPreference='Stop'
$backend=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$qa=Get-Content -Raw -Encoding UTF8 (Join-Path $backend 'test-evidence/change057/qa-context.json')|ConvertFrom-Json
if($qa.schema -notmatch '^planlock_f101_qa_[0-9a-f]{32}$'){throw 'Unsafe QA schema'}
$base="http://127.0.0.1:$Port/api/v1"
function Register-QaDevice([string]$Uid,[string]$Name){
    $body=@{deviceUid=$Uid;deviceName=$Name;platform='android'}|ConvertTo-Json
    $response=Invoke-RestMethod -Method Post -Uri "$base/auth/device" -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
    if($response.code -ne 0){throw 'QA device registration failed'}
    return $response.data
}
$suffix=[guid]::NewGuid().ToString('N')
$deviceA=Register-QaDevice "change057-qa-a-$suffix" 'CHANGE-057 QA A'
$deviceB=Register-QaDevice "change057-qa-b-$suffix" 'CHANGE-057 QA B'
$env:MYSQL_PWD='change057-isolated-only'
try {
    $sql="UPDATE devices SET user_id=$([long]$deviceA.userId) WHERE id=$([long]$deviceB.deviceId) AND user_id=$([long]$deviceB.userId)"
    & 'G:/MySQL/bin/mysql.exe' --no-defaults -h 127.0.0.1 -P 13316 -u root --database=$($qa.schema) -e $sql
    if($LASTEXITCODE -ne 0){throw 'QA-only device owner assignment failed'}
} finally {Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue}
$deviceB=Register-QaDevice "change057-qa-b-$suffix" 'CHANGE-057 QA B'
if($deviceA.userId -ne $deviceB.userId -or $deviceA.deviceId -eq $deviceB.deviceId){throw 'QA device ownership mismatch'}
$session=@{apiBaseUrl="http://10.0.2.2:$Port/api/v1/";userId=$deviceA.userId;deviceAId=$deviceA.deviceId;deviceBId=$deviceB.deviceId;tokenA=$deviceA.token;tokenB=$deviceB.token}
$privateFile=Join-Path $backend '../work/change057-bug101-20261016/qa-sessions-private.json'
$session|ConvertTo-Json -Depth 5|Set-Content -Encoding UTF8 $privateFile
Write-Output "QA two-device session saved privately: user=$($deviceA.userId), A=$($deviceA.deviceId), B=$($deviceB.deviceId); tokens not printed."
