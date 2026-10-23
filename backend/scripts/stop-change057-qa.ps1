param([switch]$KeepDatabaseServer)
$ErrorActionPreference='Stop'
$backend=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidence=Join-Path $backend 'test-evidence/change057'
$qa=Get-Content -Raw -Encoding UTF8 (Join-Path $evidence 'qa-context.json')|ConvertFrom-Json
if($qa.schema -notmatch '^planlock_f101_qa_[0-9a-f]{32}$'){throw 'Unsafe QA schema; refusing cleanup'}
$mysqlId=[int](Get-Content -Encoding UTF8 (Join-Path $evidence 'mysql.pid'))
$mysql=Get-CimInstance Win32_Process -Filter "ProcessId = $mysqlId" -ErrorAction SilentlyContinue
$expectedData=Join-Path $evidence 'mysql-data'
if(-not $mysql -or $mysql.Name -ne 'mysqld.exe' -or $mysql.CommandLine -notlike "*--datadir=$expectedData*" -or $mysql.CommandLine -notlike '*--port=13316*'){throw 'Dedicated MySQL identity mismatch; refusing cleanup'}
$process=Get-CimInstance Win32_Process -Filter "ProcessId = $([int]$qa.pid)" -ErrorAction SilentlyContinue
if($process){
    $backendVersion=([xml](Get-Content -LiteralPath (Join-Path $backend 'pom.xml') -Raw -Encoding UTF8)).project.version
    $expectedJar=Join-Path $backend "planlock-app/target/planlock-app-$backendVersion.jar"
    if($process.Name -ne 'java.exe' -or $process.CommandLine -notlike "*$expectedJar*" -or $process.CommandLine -notlike "*--server.port=$($qa.port)*"){throw 'QA PID identity changed; refusing stop'}
    Stop-Process -Id $qa.pid
}
$env:MYSQL_PWD='change057-isolated-only'
try {
    & 'G:/MySQL/bin/mysql.exe' --no-defaults -h 127.0.0.1 -P 13316 -u root -e "DROP DATABASE IF EXISTS $($qa.schema)"
    if($LASTEXITCODE -ne 0){throw 'QA schema cleanup failed'}
    if(-not $KeepDatabaseServer){
        & 'G:/MySQL/bin/mysqladmin.exe' --no-defaults -h 127.0.0.1 -P 13316 -u root shutdown
        if($LASTEXITCODE -ne 0){throw 'Dedicated MySQL shutdown failed'}
    }
} finally {Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue}
$redisId=[int](Get-Content -Encoding UTF8 (Join-Path $evidence 'redis.pid'))
$redis=Get-CimInstance Win32_Process -Filter "ProcessId = $redisId" -ErrorAction SilentlyContinue
if($redis){
    if($redis.Name -ne 'redis-server.exe' -or $redis.CommandLine -notlike '*change057-qa-redis.conf*' -or $redis.CommandLine -notlike '*--port 16316*'){throw 'Dedicated Redis identity mismatch; refusing shutdown'}
    $env:REDISCLI_AUTH='isolated-test-unused'
    try {& 'G:/Redis/Redis-8.0.3-Windows-x64-msys2-with-Service/redis-cli.exe' -h 127.0.0.1 -p 16316 shutdown nosave}
    finally {Remove-Item Env:REDISCLI_AUTH -ErrorAction SilentlyContinue}
}
Write-Output "Stopped only the recorded QA backend/Redis, dropped exact schema $($qa.schema), and handled the dedicated MySQL per flag."
