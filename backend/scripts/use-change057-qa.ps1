param([int]$Port=18081)
$ErrorActionPreference='Stop'
$backend=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$base="http://127.0.0.1:$Port/api/v1"
$health=Invoke-RestMethod "$base/health"
if($health.status -ne 'UP'){throw 'QA health failed'}
$authBody=@{deviceUid=('backend-use-'+[guid]::NewGuid().ToString('N'));deviceName='Backend usage review';platform='android'}|ConvertTo-Json
$auth=(Invoke-RestMethod -Method Post -Uri "$base/auth/device" -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($authBody))).data
$headers=@{Authorization="Bearer $($auth.token)"}
function Call-Qa([string]$Method,[string]$Path,$Payload){
    $params=@{Method=$Method;Uri="$base$Path";Headers=$headers;ContentType='application/json; charset=utf-8'}
    if($null -ne $Payload){$params.Body=[Text.Encoding]::UTF8.GetBytes(($Payload|ConvertTo-Json -Depth 8 -Compress))}
    $response=Invoke-RestMethod @params
    if($response.code -ne 0){throw "QA request failed: $Method $Path code=$($response.code)"}
    return $response.data
}
$plan=Call-Qa Post '/plans' @{name='工作日专注-后端使用复核';type='工作';startTime='09:00:00';endTime='10:00:00';endedOccurrenceDate='2026-10-16';statusLog='[]'}
$execution=@{planId=$plan.id;planDate='2026-10-16';status=2;startedAt='2026-10-16T09:00:00';endedAt='2026-10-16T09:30:00';clientId=('usage-execution-'+$plan.id)}
$unlock=@{planId=$plan.id;unlockAt='2026-10-16T09:30:00';method='backup_password';clientId=('usage-unlock-'+$plan.id)}
1..2|ForEach-Object{Call-Qa Post '/records/execution' $execution|Out-Null;Call-Qa Post '/records/unlock' $unlock|Out-Null}
$paused=Call-Qa Post "/plans/$($plan.id)/pause?date=2026-10-16" $null
$history=Call-Qa Get '/records/history?limit=1' $null
$snapshot=Call-Qa Get '/sync/pull' $null
$log=$paused.statusLog|ConvertFrom-Json
if($history.executions.Count -ne 1 -or $history.unlocks.Count -ne 1 -or $history.hasMore){throw 'Upload replay changed history count'}
if($paused.status -ne 0 -or $log[-1].status -ne 0 -or $snapshot.plans[0].endedOccurrenceDate -ne '2026-10-16'){throw 'Plan pause/history/snapshot mismatch'}
$result=@{result='passed';userId=$auth.userId;planId=$plan.id;executionRows=$history.executions.Count;unlockRows=$history.unlocks.Count;planStatus=$paused.status;statusLog=$log;endedOccurrenceDate=$snapshot.plans[0].endedOccurrenceDate;flow='guest device -> plan -> early execution/unlock replay -> pause -> history -> sync snapshot';note='Dedicated QA account only; no tokens retained in evidence.'}
$result|ConvertTo-Json -Depth 8|Set-Content -Encoding UTF8 (Join-Path $backend 'test-evidence/change057/qa-use-result.json')
Write-Output "QA use passed: user=$($auth.userId), plan=$($plan.id), execution=1, unlock=1, status=0, snapshot date preserved."
