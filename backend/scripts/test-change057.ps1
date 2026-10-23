param([switch]$Verify, [string]$Test='ExecutionHistoryContractTest,DatabaseMigrationTest', [string]$LogName='red.log')
$ErrorActionPreference='Stop'
$backend=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$env:JAVA_HOME='C:/Program Files/Java/jdk-21'
$env:MAVEN_OPTS='-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Duser.language=en -Duser.country=US'
$env:F37_ENABLED='true'
$env:F37_DB_HOST='127.0.0.1'
$env:F37_DB_PORT='13316'
$env:F37_DB_USER='root'
# Dedicated workspace-only MySQL instance; this is never a deployment credential.
$env:F37_DB_PASSWORD='change057-isolated-only'
$env:F37_EVIDENCE_DIR=Join-Path $backend 'test-evidence/change057/migrations'
$env:PLANLOCK_REDIS_PASSWORD='isolated-test-unused'
$env:PLANLOCK_JWT_SECRET='change057-isolated-test-jwt-secret-0123456789abcdef'
Get-Process java -ErrorAction SilentlyContinue | Select-Object Id,Path
$args=@('-B','-ntp','-o','-s','C:/Users/Administrator/.m2/settings.xml','-f',(Join-Path $backend 'pom.xml'))
if($Verify){$args+=@('clean','verify')}
else{$args+=@('test',"-Dtest=$Test",'-Dsurefire.failIfNoSpecifiedTests=false')}
& 'G:/Maven/apache-maven-3.9.10/bin/mvn.cmd' @args 2>&1 | Tee-Object -FilePath (Join-Path $backend "test-evidence/change057/$LogName")
exit $LASTEXITCODE
