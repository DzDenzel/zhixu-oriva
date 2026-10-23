param([int]$Port=18081, [string]$Schema='')
$ErrorActionPreference='Stop'
$backend=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidence=Join-Path $backend 'test-evidence/change057'
$createSchema=[string]::IsNullOrEmpty($Schema)
$schema=if($createSchema){'planlock_f101_qa_'+[guid]::NewGuid().ToString('N')}else{$Schema}
if($schema -notmatch '^planlock_f101_qa_[0-9a-f]{32}$'){throw 'Unsafe QA schema name'}
$backendVersion=([xml](Get-Content -LiteralPath (Join-Path $backend 'pom.xml') -Raw -Encoding UTF8)).project.version
$jar=Join-Path $backend "planlock-app/target/planlock-app-$backendVersion.jar"
if(-not (Test-Path -LiteralPath $jar)){throw 'Run test-change057.ps1 -Verify first.'}
# This credential belongs only to the disposable workspace MySQL on port 13316.
$env:MYSQL_PWD='change057-isolated-only'
try {
    if($createSchema){
        & 'G:/MySQL/bin/mysql.exe' --no-defaults -h 127.0.0.1 -P 13316 -u root -e "CREATE DATABASE $schema CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
        if($LASTEXITCODE -ne 0){throw 'Dedicated QA schema creation failed.'}
    }
} finally {Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue}
$env:PLANLOCK_DB_USER='root'
$env:PLANLOCK_DB_PASSWORD='change057-isolated-only'
$env:PLANLOCK_REDIS_PASSWORD='isolated-test-unused'
$env:PLANLOCK_REDIS_HOST='127.0.0.1'
$env:PLANLOCK_REDIS_PORT='16316'
$env:PLANLOCK_JWT_SECRET='change057-isolated-test-jwt-secret-0123456789abcdef'
$args=@('-jar',$jar,'--server.address=127.0.0.1',"--server.port=$Port",'--planlock.deployment.enabled=false',"--spring.datasource.url=jdbc:mysql://127.0.0.1:13316/${schema}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true")
$process=Start-Process -FilePath 'C:/Program Files/Java/jdk-21/bin/java.exe' -ArgumentList $args -WorkingDirectory $backend -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evidence 'qa-stdout.log') -RedirectStandardError (Join-Path $evidence 'qa-stderr.log')
@{schema=$schema;pid=$process.Id;port=$Port;url="http://10.0.2.2:$Port/api/v1/"}|ConvertTo-Json|Set-Content -Encoding UTF8 (Join-Path $evidence 'qa-context.json')
Write-Output "QA backend starting: PID=$($process.Id); schema=$schema; emulator=http://10.0.2.2:$Port/api/v1/"
